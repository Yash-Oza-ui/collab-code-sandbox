package com.collabcode.execution.exception;

/**
 * Thrown when room is not found in sync-service (T-17 V2.12).
 * Maps to HTTP 404 ROOM_NOT_FOUND.
 */
public class RoomNotFoundException extends RuntimeException {

    private final String requestId;

    public RoomNotFoundException(String requestId, String message) {
        super(message);
        this.requestId = requestId;
    }

    public String getRequestId() { return requestId; }
}

