package com.collabcode.execution.queue;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the single queue gate (FR16, US-11). No Spring, no Docker.
 */
class RunQueueGateTest {

    private static RunQueueGate gate(int max, int retryAfter) {
        ExecutionLimitsProperties limits = new ExecutionLimitsProperties();
        limits.setMaxQueueLength(max);
        limits.setQueueRetryAfterSeconds(retryAfter);
        return new RunQueueGate(limits);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("Run is executed and its value returned when a slot is free")
    void run_whenSlotFree_executesWork() {
        RunQueueGate gate = gate(1, 5);
        assertThat(gate.run("r1", () -> "done")).isEqualTo("done");
        assertThat(gate.inFlight()).isZero();
    }

    @Test
    @DisplayName("Full queue refuses at once; work is NOT called; error carries request_id and retry-after")
    void run_whenFull_refusesAndDoesNotCallWork() throws Exception {
        RunQueueGate gate = gate(1, 7);
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
            assertThat(gate.inFlight()).isEqualTo(1);

            AtomicInteger calls = new AtomicInteger();
            assertThatThrownBy(() -> gate.run("second", () -> calls.incrementAndGet()))
                    .isInstanceOf(QueueFullException.class)
                    .satisfies(e -> {
                        QueueFullException q = (QueueFullException) e;
                        assertThat(q.getRequestId()).isEqualTo("second");
                        assertThat(q.getRetryAfterSeconds()).isEqualTo(7);
                    });
            assertThat(calls.get()).isZero();           // refused run started nothing
            assertThat(gate.inFlight()).isEqualTo(1);   // the running one is undisturbed

            release.countDown();
            held.get(5, TimeUnit.SECONDS);

            // slot freed -> next run accepted
            assertThat(gate.run("third", () -> "ok")).isEqualTo("ok");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Slot is released even when the work throws")
    void run_whenWorkThrows_slotIsReleased() {
        RunQueueGate gate = gate(1, 5);
        assertThatThrownBy(() -> gate.run("r1", () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(gate.inFlight()).isZero();
        assertThat(gate.run("r2", () -> "ok")).isEqualTo("ok");
    }

    @Test
    @DisplayName("Limit of N allows N runs at once and refuses the N+1th")
    void run_limitOfTwo_allowsTwoRefusesThird() throws Exception {
        RunQueueGate gate = gate(2, 5);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> gate.run("held", () -> {
                    started.countDown();
                    awaitQuietly(release);
                    return null;
                }));
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> gate.run("third", () -> "x"))
                    .isInstanceOf(QueueFullException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Limit below 1 fails fast at startup")
    void constructor_rejectsLimitBelowOne() {
        assertThatThrownBy(() -> gate(0, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-queue-length");
    }

    @Test
    @DisplayName("Retry-after below 1 fails fast at startup")
    void constructor_rejectsRetryAfterBelowOne() {
        assertThatThrownBy(() -> gate(1, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queue-retry-after-seconds");
    }
}
