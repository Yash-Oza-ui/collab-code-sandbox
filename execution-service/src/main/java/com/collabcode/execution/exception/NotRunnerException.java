package com.collabcode.execution.exception;

/**
 * 403 NOT_RUNNER (T-17 V2.10, V2.12).
 * Raised when a session that did not run the code, or a session no longer in the room,
 * attempts to share the run result.
 */
public class NotRunnerException extends RuntimeException {
    private final String requestId;

    public NotRunnerException(String requestId) {
        this(requestId, "Only the runner may share this run result.");
    }

    public NotRunnerException(String requestId, String message) {
        super(message != null ? message : "Only the runner may share this run result.");
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }
}
