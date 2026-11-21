package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.queue.RunQueueGate;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import com.collabcode.execution.model.RunSpec;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for POST /internal/runs (T-18 Decision 7) and per-request repeated-run
 * budgets (US-64, NFR14, CR10) using FakeCodeRunner (never executing user code on host).
 *
 * Configured with small deterministic limits (max-runs-per-request=3,
 * aggregate-time-budget-ms=200, max-queue-length=1) so every budget and queue-full
 * condition runs quickly and deterministically.
 */
@SpringBootTest(properties = {
        "app.internal.token=test-token-us64",
        "app.execution.limits.max-queue-length=1",
        "app.execution.limits.queue-retry-after-seconds=5",
        "app.execution.limits.max-runs-per-request=3",
        "app.execution.limits.aggregate-time-budget-ms=200",
        "app.execution.limits.budget-retry-after-seconds=30"
})
@ActiveProfiles({"stub-permissions", "stub-runner"})
@AutoConfigureMockMvc
class InternalRunControllerTest {

    private static final String TOKEN = "test-token-us64";
    private static final String REQ_ID = "7a3e2b10-6c5d-4f8a-9e12-3456789abcde";
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

    // ------------------------------------------------------------------ Auth

    @Test
    @DisplayName("Missing or invalid X-Internal-Token returns 401 UNAUTHORIZED without running code")
    void internalRuns_withoutValidToken_returns401() throws Exception {
        reset(codeRunner, broadcastService);

        String body = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "print(input())",
                  "inputs": ["1"]
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(codeRunner, broadcastService);
    }

    // ------------------------------------------------------------------ Happy paths & no broadcast

    @Test
    @DisplayName("test_batch within budget returns 200 with contract shape and NEVER broadcasts run-result")
    void testBatch_withinBudget_returns200AndDoesNotBroadcast() throws Exception {
        reset(codeRunner, broadcastService);

        String body = """
                {
                  "request_id": "3e4f5a6b-7c8d-9e0f-1a2b-3c4d5e6f7a8b",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "print(input())",
                  "inputs": ["a", "b", "c"],
                  "expected_outputs": ["A", "B", "WRONG"]
                }
                """.formatted(ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value("3e4f5a6b-7c8d-9e0f-1a2b-3c4d5e6f7a8b"))
                .andExpect(jsonPath("$.purpose").value("test_batch"))
                .andExpect(jsonPath("$.batch_results.length()").value(3))
                .andExpect(jsonPath("$.batch_results[0].input").value("a"))
                .andExpect(jsonPath("$.batch_results[0].status").value("pass"))
                .andExpect(jsonPath("$.batch_results[0].stdout").value("A\n"))
                .andExpect(jsonPath("$.batch_results[2].status").value("wrong_output"));

        verify(codeRunner, times(3)).execute(any(RunSpec.class));
        verifyNoInteractions(broadcastService);
    }

    @Test
    @DisplayName("measurement within budget returns 200 with completed_runs and NEVER broadcasts")
    void measurement_withinBudget_returns200() throws Exception {
        reset(codeRunner, broadcastService);

        String body = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "measurement",
                  "language": "python",
                  "code": "def solve(n): pass",
                  "inputs": ["100", "200", "400"],
                  "repeats": 1
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(REQ_ID))
                .andExpect(jsonPath("$.purpose").value("measurement"))
                .andExpect(jsonPath("$.completed_runs.length()").value(3))
                .andExpect(jsonPath("$.completed_runs[0].input").value("100"))
                .andExpect(jsonPath("$.completed_runs[0].runtimes_ms.length()").value(1))
                .andExpect(jsonPath("$.completed_runs[0].output").exists());

        verify(codeRunner, times(3)).execute(any(RunSpec.class));
        verifyNoInteractions(broadcastService);
    }

    @Test
    @DisplayName("sanitizer supports C/C++ (returns sanitizer_report) and rejects Python with 422 UNSUPPORTED_LANGUAGE")
    void sanitizer_enforcesCppOnlyDR5() throws Exception {
        reset(codeRunner, broadcastService);

        String cppBody = """
                {
                  "request_id": "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d",
                  "room_id": "%s",
                  "purpose": "sanitizer",
                  "language": "cpp",
                  "code": "int main() { /* stderr */ return 1; }",
                  "sanitizers": ["address", "undefined"]
                }
                """.formatted(ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cppBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("sanitizer"))
                .andExpect(jsonPath("$.sanitizer_report.status").value("crashed"))
                .andExpect(jsonPath("$.sanitizer_report.exit_code").value(2));

        org.mockito.ArgumentCaptor<RunSpec> specCaptor = org.mockito.ArgumentCaptor.forClass(RunSpec.class);
        verify(codeRunner).execute(specCaptor.capture());
        assertThat(specCaptor.getValue().isSanitizer()).isTrue();
        assertThat(specCaptor.getValue().getSanitizers()).containsExactly("address", "undefined");

        String pyBody = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "sanitizer",
                  "language": "python",
                  "code": "print(1)"
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pyBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQ_ID))
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_LANGUAGE"));

        verifyNoInteractions(broadcastService);
    }

    // ------------------------------------------------------------------ US-64: Run cap exceeded

    @Test
    @DisplayName("US-64: runs-per-request cap exceeded returns 429 RUN_BUDGET_EXCEEDED with max_runs_per_request")
    void runCapExceeded_returns429WithContractShape() throws Exception {
        reset(codeRunner, broadcastService);

        // Configured max-runs-per-request=3; request asks for 4 inputs
        String body = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "print(input())",
                  "inputs": ["1", "2", "3", "4"]
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.request_id").value(REQ_ID))
                .andExpect(jsonPath("$.error.code").value("RUN_BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.error.reason").value("max_runs_per_request"))
                .andExpect(jsonPath("$.error.message")
                        .value("Repeated-run cap of 3 runs per request exceeded (NFR14)."))
                .andExpect(jsonPath("$.error.retry_after_seconds").value(30));

        // Exactly 3 runs ran; the 4th was refused before reaching CodeRunner
        verify(codeRunner, times(3)).execute(any(RunSpec.class));
        verifyNoInteractions(broadcastService);
    }

    // ------------------------------------------------------------------ US-64: Aggregate time budget exceeded

    @Test
    @DisplayName("US-64: aggregate time budget exceeded with slow FakeCodeRunner returns 429 RUN_BUDGET_EXCEEDED")
    void aggregateTimeBudgetExceeded_withSlowFakeRunner_returns429WithContractShape() throws Exception {
        reset(codeRunner, broadcastService);

        // Configured aggregate-time-budget-ms=200, max-runs-per-request=3.
        // Each run sleeps ~120ms; after 2 runs (~240ms >= 200ms), run 3 is refused by time budget.
        String body = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "# delay 120",
                  "inputs": ["1", "2", "3"]
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.request_id").value(REQ_ID))
                .andExpect(jsonPath("$.error.code").value("RUN_BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.error.reason").value("aggregate_time_budget"))
                .andExpect(jsonPath("$.error.message")
                        .value("Aggregate repeated-run execution budget of 200ms exceeded (NFR14)."))
                .andExpect(jsonPath("$.error.retry_after_seconds").value(30));

        // Only 2 runs ran before aggregate time budget blocked the 3rd
        verify(codeRunner, times(2)).execute(any(RunSpec.class));
        verifyNoInteractions(broadcastService);
    }

    // ------------------------------------------------------------------ US-64 + NFR4.1: Per-run 5s limit

    @Test
    @DisplayName("US-64 / NFR4.1: each individual run inside repeated-run feature obeys the 5s limit")
    void perRunTimeoutLimit_stillAppliesInsideRepeatedRuns() throws Exception {
        reset(codeRunner, broadcastService);

        String singleTimeout = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "while True: pass",
                  "inputs": ["1"]
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleTimeout))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch_results[0].status").value("timeout"))
                .andExpect(jsonPath("$.batch_results[0].exit_code").value(124))
                .andExpect(jsonPath("$.batch_results[0].execution_time_ms").value(5000.0));

        // And a second run in the same request is refused because 5000ms >= 200ms aggregate budget
        String twoTimeouts = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "while True: pass",
                  "inputs": ["1", "2"]
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(twoTimeouts))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RUN_BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.error.reason").value("aggregate_time_budget"));

        verifyNoInteractions(broadcastService);
    }

    // ------------------------------------------------------------------ US-64 + US-11: Shared Queue Gate

    @Test
    @DisplayName("US-64 / US-11: repeated run when shared queue is full returns 503 QUEUE_FULL busy message")
    void internalRun_whenSharedQueueFull_returns503BusyMessage() throws Exception {
        reset(codeRunner, broadcastService);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Occupy the single shared RunQueueGate slot (simulating an in-flight POST /v1/runs)
            Future<?> held = pool.submit(() -> gate.run("public-run", () -> {
                started.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            String body = """
                    {
                      "request_id": "%s",
                      "room_id": "%s",
                      "purpose": "test_batch",
                      "language": "python",
                      "code": "print(input())",
                      "inputs": ["1", "2"]
                    }
                    """.formatted(REQ_ID, ROOM_ID);

            mockMvc.perform(post("/internal/runs")
                            .header("X-Internal-Token", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                    .andExpect(jsonPath("$.request_id").value(REQ_ID))
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
