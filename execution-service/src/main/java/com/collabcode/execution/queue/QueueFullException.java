package com.collabcode.execution.queue;

/**
 * Thrown by {@link RunQueueGate} when the run queue is full (FR16, US-11).
 * Mapped to HTTP 503 QUEUE_FULL by QueueFullExceptionHandler (T-06 section 7.1).
 */
public class QueueFullException extends RuntimeException {

    /** Exact message from docs/api-contracts.md section 7.1. */
    public static final String MESSAGE = "Execution service is busy, try again in a few seconds.";

    private final String requestId;
    private final int retryAfterSeconds;

    public QueueFullException(String requestId, int retryAfterSeconds) {
        super(MESSAGE);
        this.requestId = requestId;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getRequestId()       { return requestId; }
    public int getRetryAfterSeconds()  { return retryAfterSeconds; }
}
