package com.collabcode.execution.exception;

/**
 * Thrown when target: "module" targets a module not marked runnable (T-17 V2.12).
 * Maps to HTTP 422 MODULE_NOT_RUNNABLE.
 */
public class ModuleNotRunnableException extends RuntimeException {

    private final String requestId;

    public ModuleNotRunnableException(String requestId, String message) {
        super(message);
        this.requestId = requestId;
    }

    public String getRequestId() { return requestId; }
}

