package com.collabcode.execution.exception;

/**
 * 404 RUN_NOT_FOUND (T-17 V2.10, V2.12).
 * Raised when execution-service does not know or no longer holds the request_id.
 */
public class RunNotFoundException extends RuntimeException {
    private final String requestId;

    public RunNotFoundException(String requestId) {
        this(requestId, "Run result was not found or has expired.");
    }

    public RunNotFoundException(String requestId, String message) {
        super(message != null ? message : "Run result was not found or has expired.");
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }
}
