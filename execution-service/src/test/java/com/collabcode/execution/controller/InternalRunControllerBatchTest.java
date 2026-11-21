package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.model.BatchRunSpec;
import com.collabcode.execution.queue.RunQueueGate;
import com.collabcode.execution.runner.CodeRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criteria tests for US-71 (Run a batch of generated tests in one container - FR38.4, FR5.1, NFR14):
 * - POST /internal/runs with purpose test_batch
 * - Contract shape of batch_results (input, status, exit_code, execution_time_ms, stdout, stderr, timeout/oom flags)
 * - Individual timeout and OOM reporting for that test only, remaining tests still run
 * - Run cap and aggregate time budget enforcement returning 429 RUN_BUDGET_EXCEEDED with partial results preserved
 * - Single queue gate reservation returning 503 QUEUE_FULL busy message when saturated
 * - Never broadcasts run results to room WebSocket
 */
@SpringBootTest(properties = {
        "app.internal.token=test-token-us71",
        "app.execution.limits.max-queue-length=1",
        "app.execution.limits.queue-retry-after-seconds=5",
        "app.execution.limits.max-runs-per-request=4",
        "app.execution.limits.aggregate-time-budget-ms=8000",
        "app.execution.limits.budget-retry-after-seconds=30"
})
@ActiveProfiles({"stub-permissions", "stub-runner"})
@AutoConfigureMockMvc
class InternalRunControllerBatchTest {

    private static final String TOKEN = "test-token-us71";
    private static final String REQ_ID = "3e4f5a6b-7c8d-9e0f-1a2b-3c4d5e6f7a8b";
    private static final String ROOM_ID = "Zq8-Lw2Ef6Th0Yx4Rn7JbA";

    @Autowired private MockMvc mockMvc;
    @Autowired private RunQueueGate gate;

    @SpyBean private CodeRunner codeRunner;
    @MockBean private RoomBroadcastService broadcastService;

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("US-71: Happy path test_batch returns 200 with batch_results and never broadcasts")
    void testBatch_happyPath_returns200WithBatchResults() throws Exception {
        reset(codeRunner, broadcastService);

        String body = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "print(input().upper())",
                  "inputs": ["alpha", "beta"],
                  "expected_outputs": ["ALPHA", "WRONG"]
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(REQ_ID))
                .andExpect(jsonPath("$.purpose").value("test_batch"))
                .andExpect(jsonPath("$.batch_results.length()").value(2))
                .andExpect(jsonPath("$.batch_results[0].input").value("alpha"))
                .andExpect(jsonPath("$.batch_results[0].status").value("pass"))
                .andExpect(jsonPath("$.batch_results[0].stdout").value("ALPHA\n"))
                .andExpect(jsonPath("$.batch_results[0].exit_code").value(0))
                .andExpect(jsonPath("$.batch_results[0].timed_out").value(false))
                .andExpect(jsonPath("$.batch_results[0].oom_killed").value(false))
                .andExpect(jsonPath("$.batch_results[1].input").value("beta"))
                .andExpect(jsonPath("$.batch_results[1].status").value("wrong_output"))
                .andExpect(jsonPath("$.batch_results[1].expected_output").value("WRONG"));

        verify(codeRunner).executeBatch(any(BatchRunSpec.class), any());
        verifyNoInteractions(broadcastService);
    }

    @Test
    @DisplayName("US-71: A test that times out or is OOM-killed is reported for that test only, remaining tests still run")
    void testBatch_mixedOutcomes_reportedPerTest_remainingTestsStillRun() throws Exception {
        reset(codeRunner, broadcastService);

        String reqId = "4e5f6a7b-8c9d-0e1f-2a3b-4c5d6e7f8a9b";
        String body = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "print('ok')",
                  "inputs": ["normal", "infinite_loop", "test_oom", "ok2"]
                }
                """.formatted(reqId, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch_results.length()").value(4))
                .andExpect(jsonPath("$.batch_results[0].input").value("normal"))
                .andExpect(jsonPath("$.batch_results[0].status").value("pass"))
                .andExpect(jsonPath("$.batch_results[0].exit_code").value(0))
                .andExpect(jsonPath("$.batch_results[1].input").value("infinite_loop"))
                .andExpect(jsonPath("$.batch_results[1].status").value("timeout"))
                .andExpect(jsonPath("$.batch_results[1].exit_code").value(124))
                .andExpect(jsonPath("$.batch_results[1].timed_out").value(true))
                .andExpect(jsonPath("$.batch_results[1].oom_killed").value(false))
                .andExpect(jsonPath("$.batch_results[2].input").value("test_oom"))
                .andExpect(jsonPath("$.batch_results[2].status").value("crash"))
                .andExpect(jsonPath("$.batch_results[2].exit_code").value(137))
                .andExpect(jsonPath("$.batch_results[2].oom_killed").value(true))
                .andExpect(jsonPath("$.batch_results[2].timed_out").value(false))
                .andExpect(jsonPath("$.batch_results[3].input").value("ok2"))
                .andExpect(jsonPath("$.batch_results[3].status").value("pass"))
                .andExpect(jsonPath("$.batch_results[3].exit_code").value(0));

        verifyNoInteractions(broadcastService);
    }

    @Test
    @DisplayName("US-71 / NFR14: Run cap exceeded returns 429 RUN_BUDGET_EXCEEDED with partial results preserved")
    void testBatch_runCapExceeded_returns429WithPartialResults() throws Exception {
        reset(codeRunner, broadcastService);

        // max-runs-per-request is configured to 4; batch asks for 5 tests
        String reqId = "5e6f7a8b-9c0d-1e2f-3a4b-5c6d7e8f9a0b";
        String body = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "print(input())",
                  "inputs": ["t1", "t2", "t3", "t4", "t5"]
                }
                """.formatted(reqId, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.request_id").value(reqId))
                .andExpect(jsonPath("$.error.code").value("RUN_BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.error.reason").value("max_runs_per_request"))
                .andExpect(jsonPath("$.error.retry_after_seconds").value(30))
                .andExpect(jsonPath("$.batch_results.length()").value(4))
                .andExpect(jsonPath("$.batch_results[0].input").value("t1"))
                .andExpect(jsonPath("$.batch_results[1].input").value("t2"))
                .andExpect(jsonPath("$.batch_results[2].input").value("t3"))
                .andExpect(jsonPath("$.batch_results[3].input").value("t4"));

        verifyNoInteractions(broadcastService);
    }

    @Test
    @DisplayName("US-71 / NFR14: Aggregate time budget exceeded returns 429 with partial results preserved")
    void testBatch_aggregateTimeBudgetExceeded_returns429WithPartialResults() throws Exception {
        reset(codeRunner, broadcastService);

        // aggregate-time-budget-ms is 8000. Each infinite_loop is 5000ms.
        // After 2 timeouts (10000ms >= 8000ms), test 3 is stopped by time budget.
        String reqId = "6e7f8a9b-0c1d-2e3f-4a5b-6c7d8e9f0a1b";
        String body = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "print(1)",
                  "inputs": ["infinite_loop", "infinite_loop", "s3"]
                }
                """.formatted(reqId, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.request_id").value(reqId))
                .andExpect(jsonPath("$.error.code").value("RUN_BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.error.reason").value("aggregate_time_budget"))
                .andExpect(jsonPath("$.batch_results.length()").value(2))
                .andExpect(jsonPath("$.batch_results[0].input").value("infinite_loop"))
                .andExpect(jsonPath("$.batch_results[1].input").value("infinite_loop"));

        verifyNoInteractions(broadcastService);
    }

    @Test
    @DisplayName("US-71 / FR16: When shared queue gate is full, test_batch is refused with 503 QUEUE_FULL")
    void testBatch_whenQueueGateFull_returns503BusyMessage() throws Exception {
        reset(codeRunner, broadcastService);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            Future<?> held = pool.submit(() -> gate.run("hold-slot", () -> {
                started.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            String reqId = "7e8f9a0b-1c2d-3e4f-5a6b-7c8d9e0f1a2b";
            String body = """
                    {
                      "request_id": "%s",
                      "room_id": "%s",
                      "purpose": "test_batch",
                      "language": "python",
                      "code": "print(1)",
                      "inputs": ["1", "2"]
                    }
                    """.formatted(reqId, ROOM_ID);

            mockMvc.perform(post("/internal/runs")
                            .header("X-Internal-Token", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                    .andExpect(jsonPath("$.request_id").value(reqId))
                    .andExpect(jsonPath("$.error.code").value("QUEUE_FULL"))
                    .andExpect(jsonPath("$.error.message")
                            .value("Execution service is busy, try again in a few seconds."))
                    .andExpect(jsonPath("$.error.retry_after_seconds").value(5));

            verifyNoInteractions(codeRunner, broadcastService);

            release.countDown();
            held.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
