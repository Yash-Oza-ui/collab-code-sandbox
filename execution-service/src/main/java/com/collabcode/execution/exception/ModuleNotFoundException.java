package com.collabcode.execution.exception;

/**
 * Thrown when a requested module id is not found in the room (T-17 V2.12).
 * Maps to HTTP 404 MODULE_NOT_FOUND.
 */
public class ModuleNotFoundException extends RuntimeException {

    private final String requestId;

    public ModuleNotFoundException(String requestId, String message) {
        super(message);
        this.requestId = requestId;
    }

    public String getRequestId() { return requestId; }
}

