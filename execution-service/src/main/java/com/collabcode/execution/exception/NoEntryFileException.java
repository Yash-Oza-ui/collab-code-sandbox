package com.collabcode.execution.exception;

/**
 * Thrown when target: "entry" is run while no entry file is set in the room (T-17 V2.12).
 * Maps to HTTP 422 NO_ENTRY_FILE.
 */
public class NoEntryFileException extends RuntimeException {

    private final String requestId;

    public NoEntryFileException(String requestId, String message) {
        super(message);
        this.requestId = requestId;
    }

    public String getRequestId() { return requestId; }
}

