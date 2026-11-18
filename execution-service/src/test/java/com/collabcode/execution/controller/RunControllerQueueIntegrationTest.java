package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunSpec;
import com.collabcode.execution.queue.RunQueueGate;
import com.collabcode.execution.runner.CodeRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * US-11 integration tests: queue-full behaviour exercised over the real HTTP
 * stack (RANDOM_PORT, TestRestTemplate) to complement the MockMvc slice in
 * RunControllerUS11Test.
 *
 * Queue limit is set to 1. One slot is held directly on the gate so no Docker
 * is needed and no slow runner is required.
 *
 * Covers (FR16, US-11 DoD):
 *  - 503 QUEUE_FULL response shape over real HTTP
 *  - Retry-After header present and non-empty
 *  - Refused run does NOT disturb the in-flight run (no extra interactions)
 *  - Repeated run requests (second, third) all count toward the same gate
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.execution.limits.max-queue-length=1"
)
@ActiveProfiles({"stub-permissions", "stub-runner"})
class RunControllerQueueIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RunQueueGate gate;

    @Autowired
    private ExecutionLimitsProperties limits;

    @MockBean
    private RoomBroadcastService broadcastService;

    @SpyBean
    private CodeRunner codeRunner;

    private static final String BODY = """
            {
              "request_id": "aabbccdd-1111-4000-aaaa-000000000099",
              "room_id":    "room-us11-inttest",
              "session_id": "ccddee00-2222-4000-bbbb-000000000099",
              "language":   "python",
              "source": {
                "kind":    "inline",
                "files":   [ { "name": "main.py", "content": "print('hello')" } ],
                "entry":   "main.py"
              }
            }
            """;

    private static HttpEntity<String> jsonRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(BODY, headers);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // Test 1: full-stack 503 shape (real TCP + HTTP, not MockMvc)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Full queue over real HTTP: 503, QUEUE_FULL body, Retry-After header")
    void queueFull_overRealHttp_returns503WithContractShape() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Hold the single slot directly on the gate (no slow runner needed)
            Future<?> held = pool.submit(() -> gate.run("held-slot", () -> {
                started.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            // Now POST /v1/runs — queue is full → must get 503
            ResponseEntity<Map> response = rest.postForEntity("/v1/runs", jsonRequest(), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            int expectedRetryAfter = limits.getQueueRetryAfterSeconds();
            assertThat(expectedRetryAfter).isEqualTo(5); // matches configured retry interval (DR3)

            // Retry-After header must equal the exact configured retry value
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                    .isEqualTo(String.valueOf(expectedRetryAfter));

            // Body shape: {request_id, error: {code, message, retry_after_seconds}}
            Map<?, ?> body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.get("request_id")).isEqualTo("aabbccdd-1111-4000-aaaa-000000000099");

            Map<?, ?> error = (Map<?, ?>) body.get("error");
            assertThat(error).isNotNull();
            assertThat(error.get("code")).isEqualTo("QUEUE_FULL");
            assertThat(error.get("message"))
                    .isEqualTo("Execution service is busy, try again in a few seconds.");
            assertThat(error.get("retry_after_seconds")).isEqualTo(expectedRetryAfter);

            // The refused run starts no container (codeRunner never invoked) and broadcasts nothing
            verify(codeRunner, never()).execute(any(RunSpec.class));
            verifyNoInteractions(broadcastService);

            release.countDown();
            held.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Test 2: repeated public run requests all count toward the same gate (FR16 v2)
    // Note: This exercises repeated calls to the public POST /v1/runs endpoint.
    // It verifies that successive public requests count toward the same queue gate.
    // The internal repeated-run / batch endpoint (POST /internal/runs, US-64/US-65/US-71)
    // is a deferred feature and is not tested here.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Repeated public run requests (second and third) all count toward the same gate limit")
    void repeatedRunRequests_allCountTowardSameGate() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Hold the single slot on the gate
            Future<?> held = pool.submit(() -> gate.run("held-repeated", () -> {
                started.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            int expectedRetryAfter = limits.getQueueRetryAfterSeconds();

            // Second request — refused
            ResponseEntity<Map> second = rest.postForEntity("/v1/runs", jsonRequest(), Map.class);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(second.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                    .isEqualTo(String.valueOf(expectedRetryAfter));
            Map<?, ?> secondError = (Map<?, ?>) second.getBody().get("error");
            assertThat(secondError.get("code")).isEqualTo("QUEUE_FULL");
            assertThat(secondError.get("retry_after_seconds")).isEqualTo(expectedRetryAfter);

            // Third request (subsequent public request while queue remains saturated) — also refused
            ResponseEntity<Map> third = rest.postForEntity("/v1/runs", jsonRequest(), Map.class);
            assertThat(third.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(third.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                    .isEqualTo(String.valueOf(expectedRetryAfter));
            Map<?, ?> thirdError = (Map<?, ?>) third.getBody().get("error");
            assertThat(thirdError.get("code")).isEqualTo("QUEUE_FULL");
            assertThat(thirdError.get("retry_after_seconds")).isEqualTo(expectedRetryAfter);

            // In-flight counter must still be 1 (only the held slot)
            assertThat(gate.inFlight()).isEqualTo(1);

            release.countDown();
            held.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: refused run does NOT disturb in-flight run; gate is undisturbed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Refused run leaves in-flight counter at 1; slot freed after run completes")
    void refusedRun_doesNotChangeInFlightCount_andSlotFreesAfterCompletion() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> held = pool.submit(() -> gate.run("held-check", () -> {
                started.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            // Refused run must not change inFlight
            assertThat(gate.inFlight()).isEqualTo(1);
            ResponseEntity<Map> refused = rest.postForEntity("/v1/runs", jsonRequest(), Map.class); // refused
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                    .isEqualTo(String.valueOf(limits.getQueueRetryAfterSeconds()));
            Map<?, ?> refusedError = (Map<?, ?>) refused.getBody().get("error");
            assertThat(refusedError.get("code")).isEqualTo("QUEUE_FULL");
            assertThat(refusedError.get("retry_after_seconds")).isEqualTo(limits.getQueueRetryAfterSeconds());
            assertThat(gate.inFlight()).isEqualTo(1); // still 1

            // Refused run did not invoke the code runner
            verify(codeRunner, never()).execute(any(RunSpec.class));

            // After in-flight run finishes the counter drops to 0
            release.countDown();
            held.get(5, TimeUnit.SECONDS);
            assertThat(gate.inFlight()).isZero();

            // Next run is accepted and executes normally (code runner invoked once)
            ResponseEntity<Map> nextRun = rest.postForEntity("/v1/runs", jsonRequest(), Map.class);
            assertThat(nextRun.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(nextRun.getBody()).isNotNull();
            assertThat(nextRun.getBody().get("stdout")).isEqualTo("Hello from python!\n");
            verify(codeRunner, times(1)).execute(any(RunSpec.class));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}

