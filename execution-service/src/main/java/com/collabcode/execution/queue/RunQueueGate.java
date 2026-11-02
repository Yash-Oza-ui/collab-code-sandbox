package com.collabcode.execution.queue;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * The ONE queue gate for every run path (FR16, US-11).
 *
 * A run holds a slot from the moment it is accepted until its result is
 * produced, so "slots in use" = runs in flight. In flight means running now,
 * or waiting for a runner once a runner that makes runs wait exists (US-09).
 * Today a run starts at once, so the limit is in effect a cap on concurrent
 * runs. When every slot is taken, a new run is refused at once with
 * {@link QueueFullException}. Nothing blocks, no container is started, and
 * runs already in flight are not touched.
 *
 * Today only POST /v1/runs calls this. Later paths (POST /internal/runs for
 * repeated and batch runs, US-64/65/71) must call the same method, so they
 * are counted against the same limit and no second counter is needed.
 *
 * The limit is read once, from ExecutionLimitsProperties (DR3, US-16).
 * Never log submitted code here.
 */
@Component
public class RunQueueGate {

    private static final Logger log = LoggerFactory.getLogger(RunQueueGate.class);

    private final Semaphore slots;
    private final int maxQueueLength;
    private final int retryAfterSeconds;

    public RunQueueGate(ExecutionLimitsProperties limits) {
        this.maxQueueLength    = limits.getMaxQueueLength();
        this.retryAfterSeconds = limits.getQueueRetryAfterSeconds();
        if (maxQueueLength < 1) {
            throw new IllegalStateException(
                    "app.execution.limits.max-queue-length must be at least 1 (FR16)");
        }
        if (retryAfterSeconds < 1) {
            throw new IllegalStateException(
                    "app.execution.limits.queue-retry-after-seconds must be at least 1 (FR16)");
        }
        this.slots = new Semaphore(maxQueueLength);
    }

    /**
     * Runs {@code work} if a slot is free; otherwise refuses at once.
     *
     * @param requestId echoed in the QUEUE_FULL error (may be null)
     * @param work      the run itself (for example codeRunner.execute(...))
     * @throws QueueFullException when no slot is free; {@code work} is NOT called
     */
    public <T> T run(String requestId, Supplier<T> work) {
        if (!slots.tryAcquire()) {
            log.warn("run refused: queue full ({} of {} slots in use)", inFlight(), maxQueueLength);
            throw new QueueFullException(requestId, retryAfterSeconds);
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }

    /** Runs currently in flight (accepted, not yet finished). Exposed for tests and monitoring. */
    public int inFlight() {
        return maxQueueLength - slots.availablePermits();
    }
}
