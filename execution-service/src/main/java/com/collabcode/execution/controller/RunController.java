package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.model.RunRequest;
import com.collabcode.execution.model.RunResponse;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * Run endpoint – T-06 section 4.
 *
 * POST /v1/runs
 *   1. Validates the request (Bean Validation + language check).
 *   2. Runs the code via CodeRunner (FakeCodeRunner in Sprint 1; swapped for
 *      DockerCodeRunner by Quincy in feature/US-08-run-code-runner-hookup).
 *   3. Broadcasts the result to the room through sync-service (T-06 §5, FR4.5).
 *   4. Returns HTTP 200 with the full run-result body (T-06 §4.2).
 *
 * Ordering (NFR9): broadcast happens before the HTTP return so that other
 * participants start seeing the result at the same moment the caller does.
 * The ai-ml-service call (Sprint 2) happens AFTER the HTTP response is sent
 * and must never be added here in a way that delays the response.
 *
 * FR5.1 / NFR3: code is never executed on the host. CodeRunner enforces this.
 */
@RestController
@RequestMapping("/v1")
public class RunController {

    /**
     * DR1: the five supported language identifiers (T-06 section 2).
     * Values are the same as in docs/ai-ml-contract.md (T-07).
     */
    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("cpp", "python", "c", "java", "javascript");

    private final CodeRunner            codeRunner;
    private final RoomBroadcastService  broadcastService;

    public RunController(CodeRunner codeRunner, RoomBroadcastService broadcastService) {
        this.codeRunner       = codeRunner;
        this.broadcastService = broadcastService;
    }

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

        // Semantic language check (complement to @NotBlank from Bean Validation)
        if (!SUPPORTED_LANGUAGES.contains(req.getLanguage())) {
            throw new UnsupportedLanguageException(req.getLanguage());
        }

        // --- Execute the code ---
        // FakeCodeRunner is used until US-09 (DockerCodeRunner) merges.
        ExecutionResult result = codeRunner.execute(
                req.getLanguage(),
                req.getCode(),
                req.getStdin()
        );

        // --- Build the response (T-06 §4.2) ---
        RunResponse response = buildResponse(req, result);

        // --- Broadcast to room (T-06 §5, FR4.5) ---
        // Best-effort: RoomBroadcastService swallows its own errors (see its class).
        // This happens before return so other participants see the result at the
        // same moment the caller receives the HTTP response.
        broadcastService.broadcast(req.getRoom_id(), response);

        return ResponseEntity.ok(response);
    }

    // ----- private helpers -----

    /** Maps RunRequest + ExecutionResult → RunResponse (T-06 §4.2). */
    private RunResponse buildResponse(RunRequest req, ExecutionResult result) {
        RunResponse r = new RunResponse();
        // Echo request identifiers
        r.setRequest_id(req.getRequest_id());
        r.setRoom_id(req.getRoom_id());
        r.setLanguage(req.getLanguage());
        // Execution output
        r.setStdout(result.getStdout());
        r.setStderr(result.getStderr());
        r.setExit_code(result.getExitCode());
        r.setExecution_time_ms(result.getExecutionTimeMs());
        r.setPeak_memory_mb(result.getPeakMemoryMb());
        r.setTimed_out(result.isTimedOut());
        r.setOom_killed(result.isOomKilled());
        r.setFailure_kind(result.getFailureKind());
        r.setOutput_truncated(result.isOutputTruncated());
        r.setMessage(result.getMessage());
        return r;
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
