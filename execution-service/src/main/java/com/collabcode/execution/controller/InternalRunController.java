package com.collabcode.execution.controller;

import com.collabcode.execution.budget.RunBudget;
import com.collabcode.execution.budget.RunBudgetExceededException;
import com.collabcode.execution.budget.RunBudgetFactory;
import com.collabcode.execution.controller.RunController.UnsupportedLanguageException;
import com.collabcode.execution.exception.InvalidRequestException;
import com.collabcode.execution.model.BatchRunSpec;
import com.collabcode.execution.model.InternalRunRequest;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Internal repeated-run endpoint for ai-ml-service (T-18 Decision 7, §18; US-64, NFR14, CR10).
 *
 * <pre>
 * POST /internal/runs
 * X-Internal-Token: &lt;INTERNAL_API_TOKEN&gt;
 * Content-Type: application/json
 * </pre>
 *
 * Enforces:
 *   1. Shared-secret authentication via {@code X-Internal-Token} before body parsing (401).
 *   2. Request validation matching {@code docs/schemas/internal-run-request.json} (422).
 *   3. Per-request {@link RunBudget} (runs-per-request cap and aggregate time budget, NFR14 -> 429).
 *   4. Single shared {@link com.collabcode.execution.queue.RunQueueGate} (FR16, US-11 -> 503).
 *   5. Same container isolation and per-run 5 s wall-time / memory limits via {@link CodeRunner} (NFR3, NFR4).
 *   6. Never broadcasts internal runs as user-visible {@code run-result} messages (Decision 7).
 *   7. Never logs submitted source code or stdin/input text (NFR8.2).
 */
@RestController
@RequestMapping("/internal")
public class InternalRunController {

    private static final Logger log = LoggerFactory.getLogger(InternalRunController.class);

    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("cpp", "python", "c", "java", "javascript");

    private static final Set<String> SANITIZER_LANGUAGES =
            Set.of("c", "cpp");

    private static final Set<String> SUPPORTED_PURPOSES =
            Set.of("measurement", "test_batch", "sanitizer");

    /** Default repeats per input size for measurement mode (T-18 §16.1, §18.1). */
    public static final int DEFAULT_MEASUREMENT_REPEATS = 3;

    private final CodeRunner codeRunner;
    private final RunBudgetFactory runBudgetFactory;
    private final ObjectMapper objectMapper;
    private final String internalApiToken;

    public InternalRunController(
            CodeRunner codeRunner,
            RunBudgetFactory runBudgetFactory,
            ObjectMapper objectMapper,
            @Value("${app.internal.token:${app.sync-service.internal-token:}}") String internalApiToken) {
        this.codeRunner = codeRunner;
        this.runBudgetFactory = runBudgetFactory;
        this.objectMapper = objectMapper;
        this.internalApiToken = internalApiToken;
    }

    @PostMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> executeInternalRuns(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) byte[] rawBody) {

        // 1. Authenticate X-Internal-Token before parsing body (prevents info leak / DoS).
        if (!isValidToken(token)) {
            log.warn("internal-runs: unauthorized request");
            return errorResponse(
                    HttpStatus.UNAUTHORIZED,
                    null,
                    "UNAUTHORIZED",
                    "Missing or invalid internal token."
            );
        }

        // 2. Parse JSON body.
        if (rawBody == null || rawBody.length == 0) {
            throw new InvalidRequestException(null, "Request body is missing or empty.");
        }

        InternalRunRequest req;
        try {
            req = objectMapper.readValue(rawBody, InternalRunRequest.class);
        } catch (Exception e) {
            throw new InvalidRequestException(null, "Field 'body' is missing or invalid JSON.");
        }

        if (req == null) {
            throw new InvalidRequestException(null, "Request body is missing or invalid.");
        }

        // 3. Validate contract fields (docs/schemas/internal-run-request.json).
        String requestId = req.getRequest_id();
        if (requestId == null || requestId.isBlank() || !requestId.matches(InternalRunRequest.UUID_REGEX)) {
            throw new InvalidRequestException(null, "Field 'request_id' is missing or invalid.");
        }

        if (req.getRoom_id() == null || req.getRoom_id().isBlank()) {
            throw new InvalidRequestException(requestId, "Field 'room_id' is missing or invalid.");
        }

        String purpose = req.getPurpose();
        if (purpose == null || !SUPPORTED_PURPOSES.contains(purpose)) {
            throw new InvalidRequestException(
                    requestId,
                    "Field 'purpose' must be one of: measurement, test_batch, sanitizer."
            );
        }

        String language = req.getLanguage();
        if (language == null || language.isBlank()) {
            throw new InvalidRequestException(requestId, "Field 'language' is missing or invalid.");
        }
        String normalizedLang = language.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(normalizedLang)) {
            throw new UnsupportedLanguageException(language, requestId);
        }
        if ("sanitizer".equals(purpose) && !SANITIZER_LANGUAGES.contains(normalizedLang)) {
            return errorResponse(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    requestId,
                    "UNSUPPORTED_LANGUAGE",
                    "Sanitizer execution is supported for C and C++ only, received '" + language + "'."
            );
        }

        if (req.getCode() == null || req.getCode().isEmpty()) {
            throw new InvalidRequestException(requestId, "Field 'code' is required and cannot be empty.");
        }

        // 4. Create one per-request RunBudget (never global; routes every run through RunQueueGate).
        RunBudget budget = runBudgetFactory.create(requestId);
        String entryName = defaultEntryFileName(normalizedLang);

        Map<String, Object> response = switch (purpose) {
            case "measurement" -> runMeasurement(req, normalizedLang, entryName, budget);
            case "test_batch"  -> runTestBatch(req, normalizedLang, entryName, budget);
            case "sanitizer"   -> runSanitizer(req, normalizedLang, entryName, budget);
            default -> throw new InvalidRequestException(requestId, "Unsupported purpose: " + purpose);
        };

        log.info("internal-runs: request_id={} room_id={} purpose={} language={} runs_completed={} elapsed_ms={}",
                requestId, req.getRoom_id(), purpose, normalizedLang,
                budget.getRunsCompleted(), Math.round(budget.getTotalRunTimeMs()));

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> runMeasurement(
            InternalRunRequest req,
            String language,
            String entryName,
            RunBudget budget) {

        List<String> inputs = validateInputs(req.getRequest_id(), req.getInputs());
        int repeats = req.getRepeats() != null ? req.getRepeats() : DEFAULT_MEASUREMENT_REPEATS;
        if (repeats < 1) {
            throw new InvalidRequestException(req.getRequest_id(), "Field 'repeats' must be at least 1.");
        }

        List<Map<String, Object>> completedRuns = new ArrayList<>();
        for (String input : inputs) {
            List<Double> runtimes = new ArrayList<>(repeats);
            for (int r = 0; r < repeats; r++) {
                RunSpec spec = new RunSpec(
                        language,
                        List.of(new RunFile(entryName, req.getCode())),
                        entryName,
                        input
                );
                ExecutionResult res = budget.execute(() -> codeRunner.execute(spec));
                runtimes.add(res.getExecutionTimeMs());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("input", input);
            entry.put("runtimes_ms", runtimes);
            completedRuns.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", req.getRequest_id());
        body.put("purpose", "measurement");
        body.put("completed_runs", completedRuns);
        return body;
    }

    private Map<String, Object> runTestBatch(
            InternalRunRequest req,
            String language,
            String entryName,
            RunBudget budget) {

        List<String> inputs = validateInputs(req.getRequest_id(), req.getInputs());
        List<String> expectedOutputs = req.getExpected_outputs();
        if (expectedOutputs != null && expectedOutputs.size() != inputs.size()) {
            throw new InvalidRequestException(
                    req.getRequest_id(),
                    "Field 'expected_outputs' length must match 'inputs' length."
            );
        }

        BatchRunSpec batchSpec = new BatchRunSpec(
                language,
                List.of(new RunFile(entryName, req.getCode())),
                entryName,
                inputs,
                expectedOutputs
        );

        List<ExecutionResult> results;
        try {
            results = budget.executeInQueueGate(() -> codeRunner.executeBatch(batchSpec, budget));
        } catch (RunBudgetExceededException ex) {
            // US-71 / NFR14: Results of tests already run are preserved and returned in contract shape
            List<Map<String, Object>> partialBatchResults = new ArrayList<>();
            List<ExecutionResult> completed = ex.getCompletedResults();
            for (int i = 0; i < completed.size(); i++) {
                ExecutionResult res = completed.get(i);
                String input = i < inputs.size() ? inputs.get(i) : "";
                String expected = (expectedOutputs != null && i < expectedOutputs.size()) ? expectedOutputs.get(i) : null;
                String status = classifyBatchStatus(res, expected);
                partialBatchResults.add(formatBatchItem(input, status, res, expected));
            }
            throw new RunBudgetExceededException(
                    ex.getRequestId(),
                    ex.getLimitKind(),
                    ex.getRunsCompleted(),
                    ex.getMaxRuns(),
                    ex.getElapsedTimeMs(),
                    ex.getAggregateTimeBudgetMs(),
                    ex.getRetryAfterSeconds(),
                    ex.getCompletedResults(),
                    partialBatchResults
            );
        }

        List<Map<String, Object>> batchResults = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            ExecutionResult res = results.get(i);
            String input = inputs.get(i);
            String expected = expectedOutputs != null ? expectedOutputs.get(i) : null;
            String status = classifyBatchStatus(res, expected);
            batchResults.add(formatBatchItem(input, status, res, expected));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", req.getRequest_id());
        body.put("purpose", "test_batch");
        body.put("batch_results", batchResults);
        return body;
    }

    private static Map<String, Object> formatBatchItem(
            String input,
            String status,
            ExecutionResult res,
            String expectedOutput) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("input", input);
        item.put("status", status);
        item.put("stdout", res != null ? res.getStdout() : "");
        item.put("stderr", res != null ? res.getStderr() : "");
        if (expectedOutput != null) {
            item.put("expected_output", expectedOutput);
        }
        item.put("exit_code", res != null ? res.getExitCode() : 1);
        item.put("execution_time_ms", res != null ? res.getExecutionTimeMs() : 0.0);
        if (res != null) {
            item.put("timed_out", res.isTimedOut());
            item.put("oom_killed", res.isOomKilled());
            item.put("output_truncated", res.isOutputTruncated());
        }
        return item;
    }

    private Map<String, Object> runSanitizer(
            InternalRunRequest req,
            String language,
            String entryName,
            RunBudget budget) {

        RunSpec spec = new RunSpec(
                language,
                List.of(new RunFile(entryName, req.getCode())),
                entryName,
                req.getStdin() != null ? req.getStdin() : "",
                true,
                req.getSanitizers()
        );
        ExecutionResult res = budget.execute(() -> codeRunner.execute(spec));

        String status;
        if (res.isTimedOut()) {
            status = "timeout";
        } else if ("compile_error".equals(res.getFailureKind())) {
            status = "compile_error";
        } else if (res.getExitCode() != 0 || res.isOomKilled()) {
            status = "crashed";
        } else {
            status = "ok";
        }

        String sanitizerOutput = (res.getStderr() != null && !res.getStderr().isEmpty())
                ? res.getStderr()
                : (res.getStdout() != null ? res.getStdout() : "");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status);
        report.put("exit_code", res.getExitCode());
        report.put("sanitizer_output", sanitizerOutput);
        report.put("execution_time_ms", res.getExecutionTimeMs());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", req.getRequest_id());
        body.put("purpose", "sanitizer");
        body.put("sanitizer_report", report);
        return body;
    }

    private static List<String> validateInputs(String requestId, List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new InvalidRequestException(requestId, "Field 'inputs' must be a non-empty array.");
        }
        for (String input : inputs) {
            if (input == null) {
                throw new InvalidRequestException(requestId, "Field 'inputs' must not contain null entries.");
            }
        }
        return inputs;
    }

    private static String classifyBatchStatus(ExecutionResult res, String expectedOutput) {
        if (res.isTimedOut()) {
            return "timeout";
        }
        if (res.getExitCode() != 0 || res.isOomKilled() || res.getFailureKind() != null) {
            return "crash";
        }
        if (expectedOutput != null) {
            String actualNorm = res.getStdout().replace("\r\n", "\n").trim();
            String expectedNorm = expectedOutput.replace("\r\n", "\n").trim();
            if (!actualNorm.equals(expectedNorm)) {
                return "wrong_output";
            }
        }
        return "pass";
    }

    private static String defaultEntryFileName(String language) {
        return switch (language) {
            case "python"     -> "main.py";
            case "javascript" -> "main.js";
            case "java"       -> "Main.java";
            case "cpp"        -> "main.cpp";
            case "c"          -> "main.c";
            default           -> "main.txt";
        };
    }

    private boolean isValidToken(String provided) {
        if (internalApiToken == null || internalApiToken.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                internalApiToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ResponseEntity<Map<String, Object>> errorResponse(
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
