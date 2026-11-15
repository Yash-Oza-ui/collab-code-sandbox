package com.collabcode.execution.exception;

/**
 * Thrown when caller is not allowed to run code (T-17 V2.11, V2.12).
 * Maps to HTTP 403 RUN_NOT_ALLOWED with error.reason.
 */
public class RunNotAllowedException extends RuntimeException {

    private final String requestId;
    private final String reason;

    public RunNotAllowedException(String requestId, String reason) {
        super(resolveMessage(reason));
        this.requestId = requestId;
        this.reason = reason;
    }

    public RunNotAllowedException(String requestId, String reason, String message) {
        super(message != null ? message : resolveMessage(reason));
        this.requestId = requestId;
        this.reason = reason;
    }

    public String getRequestId() { return requestId; }
    public String getReason() { return reason; }

    private static String resolveMessage(String reason) {
        if ("observer".equalsIgnoreCase(reason)) {
            return "Observers cannot run code.";
        } else if ("run_revoked".equalsIgnoreCase(reason)) {
            return "The Host has turned off running for you.";
        } else if ("host_only".equalsIgnoreCase(reason)) {
            return "Only the Host can run code in this room right now.";
        } else if ("not_in_room".equalsIgnoreCase(reason)) {
            return "You are not in this room.";
        }
        return "You are not permitted to run code in this room.";
    }
}

