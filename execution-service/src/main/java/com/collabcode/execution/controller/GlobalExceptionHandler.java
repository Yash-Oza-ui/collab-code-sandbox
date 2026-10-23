package com.collabcode.execution.controller;

import com.collabcode.execution.controller.RunController.UnsupportedLanguageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global 4xx error handler for execution-service.
 *
 * Error envelope shape follows docs/api-contracts.md section 7 (placeholder –
 * Femina's PR2 finalises the exact error codes and text).
 * The envelope fields used here are:
 *   { "request_id": <uuid or null>, "error": { "code": "...", "message": "..." } }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 400 – Missing or blank required field (Bean Validation failure).
     * Triggered when @Valid rejects the request body.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {

        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return errorResponse(
                HttpStatus.BAD_REQUEST,
                null,           // request_id unknown – field may be missing
                "INVALID_REQUEST",
                "Request validation failed: " + details
        );
    }

    /**
     * 422 – Language value is present but not one of the five DR1 identifiers.
     * (Semantically unprocessable, not just missing.)
     */
    @ExceptionHandler(UnsupportedLanguageException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedLanguage(
            UnsupportedLanguageException ex) {

        return errorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                null,
                "UNSUPPORTED_LANGUAGE",
                "Language not supported: '" + ex.getLanguage()
                        + "'. Accepted values: cpp, python, c, java, javascript."
        );
    }

    // ----- helper -----

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus status,
            String requestId,
            String code,
            String message) {

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retry_after_seconds", null);   // reserved (section 7)

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("error", error);

        return ResponseEntity.status(status).body(body);
    }
}
