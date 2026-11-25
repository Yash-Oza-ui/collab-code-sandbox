package com.collabcode.execution.exception;

/**
 * Thrown when target: "module" or target: "entry" targets a module that has never been saved (US-47, FR24.1, FR25.1).
 * Maps to HTTP 422 MODULE_NOT_SAVED.
 */
public class ModuleNotSavedException extends RuntimeException {

    private final String requestId;

    public ModuleNotSavedException(String requestId, String message) {
        super(message);
        this.requestId = requestId;
    }

    public String getRequestId() { return requestId; }
}
