package com.collabcode.execution.controller;

import com.collabcode.execution.budget.RunBudgetExceededException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 429 RUN_BUDGET_EXCEEDED (NFR14, CR10, US-64), matching
 * {@code docs/examples/v2-error-run-budget-exceeded.json} and
 * {@code docs/schemas/error-response.json}:
 * HTTP 429 Too Many Requests, standard {@code Retry-After} header, and the
 * common error envelope with {@code code}, {@code message}, {@code reason}
 * (identifying which budget was hit), and {@code retry_after_seconds}.
 *
 * Kept in its own advice class (mirroring {@link QueueFullExceptionHandler})
 * so it stays decoupled from {@link GlobalExceptionHandler}.
 */
@RestControllerAdvice
public class RunBudgetExceededExceptionHandler {

    @ExceptionHandler(RunBudgetExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRunBudgetExceeded(RunBudgetExceededException ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", RunBudgetExceededException.ERROR_CODE);
        error.put("message", ex.getMessage());
        error.put("reason", ex.getReason());
        error.put("retry_after_seconds", ex.getRetryAfterSeconds());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", ex.getRequestId());
        body.put("error", error);

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }
}
