package com.collabcode.execution.controller;

import com.collabcode.execution.model.RunRequest;
import com.collabcode.execution.model.RunResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * Run endpoint – T-06 section 4.
 *
 * POST /v1/runs
 *   – Accepts the code, language and optional stdin (FR4.1).
 *   – Validates that language is one of the five DR1 values.
 *   – Returns a placeholder result (T-03 scope). Real execution
 *     is added by US-08 (Jay) and US-09 (Quincy) on top of this scaffold.
 *
 * No user code is executed here (T-03 constraint).
 */
@RestController
@RequestMapping("/v1")
public class RunController {

    /**
     * DR1: the five supported language identifiers.
     * Values must match T-06 section 2 and docs/ai-ml-contract.md (T-07).
     */
    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("cpp", "python", "c", "java", "javascript");

    /**
     * POST /v1/runs
     *
     * Bean Validation (@Valid) rejects missing or blank required fields with
     * 422 INVALID_REQUEST before this method is reached (see GlobalExceptionHandler).
     *
     * Language is checked here because it is a semantic rule ("must be one
     * of five values"), not just a format constraint.
     */
    @PostMapping("/runs")
    public ResponseEntity<RunResponse> run(@Valid @RequestBody RunRequest req) {

        if (!SUPPORTED_LANGUAGES.contains(req.getLanguage())) {
            throw new UnsupportedLanguageException(req.getLanguage(), req.getRequest_id());
        }

        // PLACEHOLDER – no Docker call yet (T-03 constraint).
        // US-08 replaces this return statement with real execution logic.
        RunResponse response = RunResponse.stubPlaceholder(req);
        return ResponseEntity.ok(response);
    }

    // ----- inner exception class (keeps it co-located with the rule) -----

    /** Thrown when the requested language is not in SUPPORTED_LANGUAGES (T-06 section 7.1). */
    public static class UnsupportedLanguageException extends RuntimeException {
        private final String language;
        private final String requestId;

        public UnsupportedLanguageException(String language, String requestId) {
            super("Language '" + language + "' is not supported. Use one of: cpp, python, c, java, javascript.");
            this.language = language;
            this.requestId = requestId;
        }

        public String getLanguage() { return language; }
        public String getRequestId() { return requestId; }
    }
}
