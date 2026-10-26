package com.collabcode.execution.controller;

import com.collabcode.execution.controller.RunController.UnsupportedLanguageException;
import com.collabcode.execution.model.RunRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global 4xx error handler for execution-service.
 *
 * Error envelope shape follows docs/api-contracts.md section 7:
 * {
 *   "request_id": "<uuid from request, or null>",
 *   "error": {
 *     "code": "SOME_CODE",
 *     "message": "Human-readable text",
 *     "retry_after_seconds": null
 *   }
 * }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 422 – Missing or invalid required field (T-06 section 7.1).
     * Triggered when @Valid rejects the request body.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {

        FieldError fe = ex.getBindingResult().getFieldError();
        String fieldName = (fe != null) ? fe.getField() : "unknown";

        String requestId = null;
        Object target = ex.getBindingResult().getTarget();
        if (target instanceof RunRequest req) {
            requestId = req.getRequest_id();
            if (requestId != null && requestId.isBlank()) {
                requestId = null;
            }
        }

        return errorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                requestId,
                "INVALID_REQUEST",
                "Field '" + fieldName + "' is missing or invalid."
        );
    }

    /**
     * 422 – Unreadable or malformed JSON body.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(
            HttpMessageNotReadableException ex) {

        return errorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                null,
                "INVALID_REQUEST",
                "Field 'body' is missing or invalid."
        );
    }

    /**
     * 422 – Language value is present but not one of the five DR1 identifiers (T-06 section 7.1).
     */
    @ExceptionHandler(UnsupportedLanguageException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedLanguage(
            UnsupportedLanguageException ex) {

        return errorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getRequestId(),
                "UNSUPPORTED_LANGUAGE",
                "Language '" + ex.getLanguage() + "' is not supported. Use one of: cpp, python, c, java, javascript."
        );
    }

    /**
     * 413 – Request body exceeds 1 MiB cap (T-06 section 7.1).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handlePayloadTooLarge(
            MaxUploadSizeExceededException ex) {

        return errorResponse(
                HttpStatus.PAYLOAD_TOO_LARGE,
                null,
                "REQUEST_TOO_LARGE",
                "Run request is larger than 1 MiB."
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
        error.put("retry_after_seconds", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("error", error);

        return ResponseEntity.status(status).body(body);
    }
}
