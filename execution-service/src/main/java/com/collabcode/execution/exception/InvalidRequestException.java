package com.collabcode.execution.exception;

/**
 * Thrown when request body or validation rules fail (T-06 §7.1, T-17 V2.8, V2.12).
 * Maps to HTTP 422 INVALID_REQUEST.
 */
public class InvalidRequestException extends RuntimeException {

    private final String requestId;

    public InvalidRequestException(String message) {
        this(null, message);
    }

    public InvalidRequestException(String requestId, String message) {
        super(message);
        this.requestId = requestId;
    }

    public String getRequestId() { return requestId; }
}

