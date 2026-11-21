package com.collabcode.execution.controller;

import com.collabcode.execution.analysis.AnalysisService;
import com.collabcode.execution.exception.InvalidRequestException;
import com.collabcode.execution.exception.RoomNotFoundException;
import com.collabcode.execution.exception.RoomServiceUnavailableException;
import com.collabcode.execution.exception.RunNotAllowedException;
import com.collabcode.execution.model.AnalysesRequest;
import com.collabcode.execution.model.AnalysesResponse;
import com.collabcode.execution.model.InlineSource;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSource;
import com.collabcode.execution.model.SavedSource;
import com.collabcode.execution.permission.PermissionLookupResult;
import com.collabcode.execution.permission.PermissionLookupService;
import com.collabcode.execution.snapshot.SavedVersionReader;
import com.collabcode.execution.snapshot.SnapshotResult;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Execution-service analysis entry point – T-18 Decision 4, §15.
 *
 * <pre>
 * POST /v1/analyses
 * X-Session-Id: &lt;session_id&gt;
 * </pre>
 *
 * Checks caller permissions with sync-service, returns HTTP 202 Accepted immediately
 * with an analysis_id, and dispatches the resolved payload asynchronously to ai-ml-service.
 * Results arrive via WebSocket as analysis-result messages (NFR9, CR08).
 */
@RestController
@RequestMapping("/v1")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("cpp", "python", "c", "java", "javascript");

    private static final Set<String> SUPPORTED_TASKS =
            Set.of("complexity", "slowdown", "failing_input", "crash_explanation");

    private final AnalysisService analysisService;
    private final PermissionLookupService permissionLookupService;
    private final SavedVersionReader savedVersionReader;

    public AnalysisController(
            AnalysisService analysisService,
            PermissionLookupService permissionLookupService,
            SavedVersionReader savedVersionReader) {
        this.analysisService = analysisService;
        this.permissionLookupService = permissionLookupService;
        this.savedVersionReader = savedVersionReader;
    }

    @PostMapping("/analyses")
    public ResponseEntity<AnalysesResponse> startAnalysis(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionHeader,
            @Valid @RequestBody AnalysesRequest req) {

        String sessionId = (sessionHeader != null && !sessionHeader.isBlank())
                ? sessionHeader.trim()
                : null;

        if (sessionId == null) {
            throw new InvalidRequestException(req.getRequest_id(), "Header 'X-Session-Id' is required.");
        }

        // Validate language
        if (!SUPPORTED_LANGUAGES.contains(req.getLanguage().toLowerCase())) {
            throw new InvalidRequestException(
                    req.getRequest_id(),
                    "Language '" + req.getLanguage() + "' is not supported. Use one of: cpp, python, c, java, javascript."
            );
        }

        // Validate task
        if (!SUPPORTED_TASKS.contains(req.getTask().toLowerCase())) {
            throw new InvalidRequestException(
                    req.getRequest_id(),
                    "Task '" + req.getTask() + "' is not supported. Use one of: complexity, slowdown, failing_input, crash_explanation."
            );
        }

        // Validate source presence (must have at least one of source, run_request_id, or code)
        if (req.getSource() == null && (req.getCode() == null || req.getCode().isBlank())
                && (req.getRun_request_id() == null || req.getRun_request_id().isBlank())) {
            throw new InvalidRequestException(
                    req.getRequest_id(),
                    "At least one of 'source', 'run_request_id', or 'code' is required."
            );
        }

        // Permission lookup (T-17 V2.11, V2.12)
        PermissionLookupResult lookup;
        try {
            lookup = permissionLookupService.lookup(req.getRoom_id(), sessionId);
        } catch (RoomServiceUnavailableException e) {
            throw new RoomServiceUnavailableException(req.getRequest_id(), e.getMessage());
        } catch (RoomNotFoundException e) {
            throw new RoomNotFoundException(req.getRequest_id(), e.getMessage());
        } catch (RunNotAllowedException e) {
            throw new RunNotAllowedException(req.getRequest_id(), e.getReason(), e.getMessage());
        }

        if (lookup == null || !lookup.isCan_run()) {
            String reason = (lookup != null) ? lookup.getRun_denied_reason() : null;
            throw new RunNotAllowedException(req.getRequest_id(), reason);
        }

        // Extract code content
        String code = resolveCode(req);

        // Visibility matching mode
        String mode = (lookup.getMode() != null) ? lookup.getMode().toLowerCase() : "pair";
        String visibility = "open".equals(mode) ? "private" : "room";

        String analysisId = UUID.randomUUID().toString();

        Map<String, Object> taskPayload = new LinkedHashMap<>();
        taskPayload.put("code", code);
        if ("crash_explanation".equalsIgnoreCase(req.getTask()) || "crash-explanation".equalsIgnoreCase(req.getTask())) {
            taskPayload.put("code_snippet", code);
            taskPayload.put("sanitizer_output", req.getStdin() != null ? req.getStdin() : "");
        }
        if (req.getStdin() != null) {
            taskPayload.put("stdin", req.getStdin());
        }
        if (req.getRun_request_id() != null) {
            taskPayload.put("run_request_id", req.getRun_request_id());
        }

        // Dispatch async analysis in background (NFR9)
        analysisService.performAnalysisAsync(
                req.getRequest_id(),
                analysisId,
                req.getRoom_id(),
                sessionId,
                visibility,
                req.getTask().toLowerCase(),
                req.getLanguage().toLowerCase(),
                req.getConsent(),
                lookup.isAi_enabled(),
                taskPayload
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new AnalysesResponse(req.getRequest_id(), analysisId));
    }

    private String resolveCode(AnalysesRequest req) {
        if (req.getCode() != null && !req.getCode().isBlank()) {
            return req.getCode();
        }
        RunSource source = req.getSource();
        if (source instanceof InlineSource inline && inline.getFiles() != null && !inline.getFiles().isEmpty()) {
            String entry = inline.getEntry();
            for (RunFile file : inline.getFiles()) {
                if (entry != null && entry.equals(file.getName())) {
                    return file.getContent();
                }
            }
            return inline.getFiles().get(0).getContent();
        } else if (source instanceof SavedSource saved) {
            try {
                SnapshotResult snapshot = savedVersionReader.readSnapshot(req.getRoom_id(), saved);
                if (snapshot != null && snapshot.getModules() != null && !snapshot.getModules().isEmpty()) {
                    return snapshot.getModules().get(0).getContent();
                }
            } catch (Exception e) {
                log.warn("failed to read snapshot for analysis: {}", e.getMessage());
            }
        }
        return "";
    }
}
