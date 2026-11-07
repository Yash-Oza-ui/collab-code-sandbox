package com.collabcode.execution.controller;

import com.collabcode.execution.queue.QueueFullException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 503 QUEUE_FULL (FR16, US-11), exactly as docs/api-contracts.md section 7.1:
 * status 503, standard Retry-After header, and the common error envelope with
 * retry_after_seconds set. The number comes from configuration (DR3).
 *
 * Kept in its own advice class so it does not depend on GlobalExceptionHandler.
 */
@RestControllerAdvice
public class QueueFullExceptionHandler {

    @ExceptionHandler(QueueFullException.class)
    public ResponseEntity<Map<String, Object>> handleQueueFull(QueueFullException ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "QUEUE_FULL");
        error.put("message", QueueFullException.MESSAGE);
        error.put("retry_after_seconds", ex.getRetryAfterSeconds());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", ex.getRequestId());
        body.put("error", error);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }
}
