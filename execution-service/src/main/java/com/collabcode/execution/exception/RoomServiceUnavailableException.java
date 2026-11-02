package com.collabcode.execution.exception;

/**
 * Thrown when permission lookup or snapshot read from sync-service fails (T-17 V2.8, V2.11, V2.12).
 * Maps to HTTP 503 ROOM_SERVICE_UNAVAILABLE.
 */
public class RoomServiceUnavailableException extends RuntimeException {

    private final String requestId;

    public RoomServiceUnavailableException(String requestId) {
        super("Room service is unavailable, so the run was not started. Try again.");
        this.requestId = requestId;
    }

    public RoomServiceUnavailableException(String requestId, String message) {
        super(message != null ? message : "Room service is unavailable, so the run was not started. Try again.");
        this.requestId = requestId;
    }

    public String getRequestId() { return requestId; }
}

