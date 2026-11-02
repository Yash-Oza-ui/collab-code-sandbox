package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.queue.RunQueueGate;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * US-11 (FR16): when the queue is full, POST /v1/runs is refused at once with the
 * contract's 503 QUEUE_FULL, nothing is started, and the next run works once a slot frees.
 *
 * The limit is set to 1 for this test. One slot is held directly on the gate, so no
 * Docker and no slow runner are needed. Offline, like the other tests.
 */
@SpringBootTest(properties = "app.execution.limits.max-queue-length=1")
@AutoConfigureMockMvc
class RunControllerUS11Test {

    private static final String REQUEST_ID = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10";

    @Autowired private MockMvc mockMvc;
    @Autowired private RunQueueGate gate;

    @MockBean private CodeRunner codeRunner;
    @MockBean private RoomBroadcastService broadcastService;

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final String BODY = """
            {
              "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
              "room_id":    "room-demo-01",
              "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
              "language":   "python",
              "code":       "print('hello')"
            }
            """;

    @Test
    @DisplayName("Full queue: 503 QUEUE_FULL with Retry-After, no run started; accepted again after a slot frees")
    void run_whenQueueFull_returns503ThenRecovers() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> held = pool.submit(() -> gate.run("held", () -> {
                started.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                    .andExpect(jsonPath("$.error.code").value("QUEUE_FULL"))
                    .andExpect(jsonPath("$.error.message")
                            .value("Execution service is busy, try again in a few seconds."))
                    .andExpect(jsonPath("$.error.retry_after_seconds").value(5));

            // the refused run started no container and broadcast nothing
            verifyNoInteractions(codeRunner, broadcastService);

            release.countDown();
            held.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // slot is free again -> the next run is accepted
        when(codeRunner.execute(anyString(), anyString(), anyString()))
                .thenReturn(new ExecutionResult("hello\n", "", 0, 15.0, 4.2,
                        false, false, null, false, null));
        mockMvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout").value("hello\n"));
    }
}
