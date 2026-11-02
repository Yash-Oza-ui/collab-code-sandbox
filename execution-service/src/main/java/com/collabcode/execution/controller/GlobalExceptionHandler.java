package com.collabcode.execution.controller;

import com.collabcode.execution.controller.RunController.UnsupportedLanguageException;
import com.collabcode.execution.exception.*;
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
 * Global error handler for execution-service (T-06 §7, T-17 V2.12).
 *
 * Error envelope shape:
 * {
 *   "request_id": "<uuid from request, or null>",
 *   "error": {
 *     "code": "SOME_CODE",
 *     "message": "Human-readable text",
 *     "reason": "observer" (optional, RUN_NOT_ALLOWED only),
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
        if (!"request_id".equals(fieldName)) {
            Object target = ex.getBindingResult().getTarget();
            if (target instanceof RunRequest req) {
                String candidate = req.getRequest_id();
                if (candidate != null && !candidate.isBlank() && candidate.matches(RunRequest.UUID_V4_REGEX)) {
                    requestId = candidate;
                }
            }
        }

        return errorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                requestId,
                "INVALID_REQUEST",
                "Field '" + fieldName + "' is missing or invalid.",
                null
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
                "Field 'body' is missing or invalid.",
                null
        );
    }

    /**
     * 422 – Invalid request semantics or source validation failure.
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(
            InvalidRequestException ex) {

        return errorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getRequestId(),
                "INVALID_REQUEST",
                ex.getMessage(),
                null
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
                "Language '" + ex.getLanguage() + "' is not supported. Use one of: cpp, python, c, java, javascript.",
                null
        );
    }

    /**
     * 403 – Caller is not permitted to run code (T-17 V2.11, V2.12).
     */
    @ExceptionHandler(RunNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> handleRunNotAllowed(
            RunNotAllowedException ex) {

        return errorResponse(
                HttpStatus.FORBIDDEN,
                ex.getRequestId(),
                "RUN_NOT_ALLOWED",
                ex.getMessage(),
                ex.getReason()
        );
    }

    /**
     * 503 – Permission lookup or snapshot read from sync-service failed (T-17 V2.8, V2.11, V2.12).
     */
    @ExceptionHandler(RoomServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleRoomServiceUnavailable(
            RoomServiceUnavailableException ex) {

        return errorResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                ex.getRequestId(),
                "ROOM_SERVICE_UNAVAILABLE",
                ex.getMessage(),
                null
        );
    }

    /**
     * 404 – Room not found (T-17 V2.12).
     */
    @ExceptionHandler(RoomNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRoomNotFound(
            RoomNotFoundException ex) {

        return errorResponse(
                HttpStatus.NOT_FOUND,
                ex.getRequestId(),
                "ROOM_NOT_FOUND",
                ex.getMessage(),
                null
        );
    }

    /**
     * 404 – Module not found in room (T-17 V2.12).
     */
    @ExceptionHandler(ModuleNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleModuleNotFound(
            ModuleNotFoundException ex) {

        return errorResponse(
                HttpStatus.NOT_FOUND,
                ex.getRequestId(),
                "MODULE_NOT_FOUND",
                ex.getMessage(),
                null
        );
    }

    /**
     * 422 – Module not runnable (T-17 V2.12).
     */
    @ExceptionHandler(ModuleNotRunnableException.class)
    public ResponseEntity<Map<String, Object>> handleModuleNotRunnable(
            ModuleNotRunnableException ex) {

        return errorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getRequestId(),
                "MODULE_NOT_RUNNABLE",
                ex.getMessage(),
                null
        );
    }

    /**
     * 422 – No entry file set (T-17 V2.12).
     */
    @ExceptionHandler(NoEntryFileException.class)
    public ResponseEntity<Map<String, Object>> handleNoEntryFile(
            NoEntryFileException ex) {

        return errorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getRequestId(),
                "NO_ENTRY_FILE",
                ex.getMessage(),
                null
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
                "Run request is larger than 1 MiB.",
                null
        );
    }

    // ----- helper -----

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus status,
            String requestId,
            String code,
            String message,
            String reason) {

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (reason != null && !reason.isBlank()) {
            error.put("reason", reason);
        }
        error.put("retry_after_seconds", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("error", error);

        return ResponseEntity.status(status).body(body);
    }
}
