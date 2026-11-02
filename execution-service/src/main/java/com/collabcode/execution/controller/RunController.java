package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.exception.*;
import com.collabcode.execution.model.*;
import com.collabcode.execution.permission.PermissionLookupResult;
import com.collabcode.execution.permission.PermissionLookupService;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import com.collabcode.execution.snapshot.SavedVersionReader;
import com.collabcode.execution.snapshot.SnapshotResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Run endpoint – T-17 V2.8, V2.11, V2.12 (Decision 8 & Decision 11).
 *
 * POST /v1/runs
 *   1. Validates request body, legacy code field rejection, and language.
 *   2. Checks caller permissions with sync-service via PermissionLookupService (V2.11).
 *   3. Validates source kind against room mode, language, and reads saved module snapshot (V2.8).
 *   4. Runs user code via CodeRunner with the run spec object (RunSpec).
 *   5. Broadcasts the result to the room through sync-service (FR4.5).
 *   6. Returns HTTP 200 with the full run-result body.
 */
@RestController
@RequestMapping("/v1")
public class RunController {

    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("cpp", "python", "c", "java", "javascript");

    private final CodeRunner codeRunner;
    private final RoomBroadcastService broadcastService;
    private final PermissionLookupService permissionLookupService;
    private final SavedVersionReader savedVersionReader;

    public RunController(
            CodeRunner codeRunner,
            RoomBroadcastService broadcastService,
            PermissionLookupService permissionLookupService,
            SavedVersionReader savedVersionReader) {
        this.codeRunner = codeRunner;
        this.broadcastService = broadcastService;
        this.permissionLookupService = permissionLookupService;
        this.savedVersionReader = savedVersionReader;
    }

    @PostMapping("/runs")
    public ResponseEntity<RunResponse> run(@Valid @RequestBody RunRequest req) {

        // Check 1: Reject legacy 'code' field (T-17 V2.8, T-19 AC4)
        if (req.getCode() != null) {
            throw new InvalidRequestException(
                    req.getRequest_id(),
                    "Field 'code' is no longer accepted. Use 'source'."
            );
        }

        // Check 2: Semantic language check (DR1)
        if (!SUPPORTED_LANGUAGES.contains(req.getLanguage())) {
            throw new UnsupportedLanguageException(req.getLanguage(), req.getRequest_id());
        }

        // Check 3: Exactly one source object is required
        RunSource source = req.getSource();
        if (source == null) {
            throw new InvalidRequestException(req.getRequest_id(), "Field 'source' is missing or invalid.");
        }

        // Validate source structure
        validateSourceStructure(req.getRequest_id(), source);

        // Check 4: Permission lookup (T-17 V2.11, V2.12)
        PermissionLookupResult lookup = permissionLookupService.lookup(req.getRoom_id(), req.getSession_id());
        if (!lookup.isCan_run()) {
            throw new RunNotAllowedException(req.getRequest_id(), lookup.getRun_denied_reason());
        }

        // Check 5: Source kind against room mode (T-17 V2.8 Rule 1)
        String roomMode = lookup.getMode() != null ? lookup.getMode().toLowerCase() : "pair";
        if (source instanceof InlineSource && !"pair".equals(roomMode)) {
            throw new InvalidRequestException(
                    req.getRequest_id(),
                    "Source kind 'inline' is not allowed in an open room."
            );
        }
        if (source instanceof SavedSource && !"open".equals(roomMode)) {
            throw new InvalidRequestException(
                    req.getRequest_id(),
                    "Source kind 'saved' is not allowed in a pair room."
            );
        }

        // Workspace language check in Open mode (FR20.1)
        if ("open".equals(roomMode) && lookup.getLanguage() != null
                && !lookup.getLanguage().equalsIgnoreCase(req.getLanguage())) {
            throw new InvalidRequestException(
                    req.getRequest_id(),
                    "Language '" + req.getLanguage() + "' does not match workspace language '" + lookup.getLanguage() + "'."
            );
        }

        // Check 6: Build the RunSpec object (inline or one-call snapshot read)
        RunSpec runSpec;
        if (source instanceof InlineSource inline) {
            runSpec = new RunSpec(req.getLanguage(), inline.getFiles(), inline.getEntry(), req.getStdin());
        } else {
            SavedSource saved = (SavedSource) source;
            // One-call snapshot rule (T-17 V2.8)
            SnapshotResult snapshot = savedVersionReader.readSnapshot(req.getRoom_id(), saved);
            if (snapshot == null || snapshot.getModules() == null || snapshot.getModules().isEmpty()) {
                throw new ModuleNotFoundException(req.getRequest_id(), "No modules returned in snapshot.");
            }
            List<RunFile> files = snapshot.getModules().stream()
                    .map(m -> new RunFile(m.getName(), m.getContent()))
                    .toList();
            runSpec = new RunSpec(req.getLanguage(), files, snapshot.getEntry(), req.getStdin());
        }

        // --- Execute code with spec object ---
        ExecutionResult result = codeRunner.execute(runSpec);

        // --- Build response ---
        RunResponse response = buildResponse(req, result);

        // --- Broadcast to room (FR4.5) ---
        broadcastService.broadcast(req.getRoom_id(), response);

        return ResponseEntity.ok(response);
    }

    private void validateSourceStructure(String requestId, RunSource source) {
        if (source instanceof UnknownSource || source.getKind() == null
                || (!"inline".equals(source.getKind()) && !"saved".equals(source.getKind()))) {
            throw new InvalidRequestException(requestId, "Source kind '" + source.getKind() + "' is invalid.");
        }

        if (source instanceof InlineSource inline) {
            List<RunFile> files = inline.getFiles();
            if (files == null || files.isEmpty()) {
                throw new InvalidRequestException(requestId, "Field 'files' must not be empty.");
            }
            if (files.size() != 1) {
                throw new InvalidRequestException(requestId, "Pair mode requires exactly one file in 'files'.");
            }
            for (RunFile file : files) {
                String name = file.getName();
                if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
                    throw new InvalidRequestException(
                            requestId,
                            "File name '" + name + "' is invalid. Plain file name with language extension required."
                    );
                }
                if (file.getContent() == null) {
                    throw new InvalidRequestException(requestId, "File content must not be null.");
                }
            }
            String entry = inline.getEntry();
            if (entry == null || entry.isBlank()) {
                throw new InvalidRequestException(requestId, "Field 'entry' is required.");
            }
            boolean entryFound = files.stream().anyMatch(f -> entry.equals(f.getName()));
            if (!entryFound) {
                throw new InvalidRequestException(requestId, "Entry file '" + entry + "' must match one of the files.");
            }
        } else if (source instanceof SavedSource saved) {
            String target = saved.getTarget();
            if (target == null || (!"module".equals(target) && !"entry".equals(target))) {
                throw new InvalidRequestException(requestId, "Target '" + target + "' is invalid. Must be 'module' or 'entry'.");
            }
            if ("module".equals(target)) {
                if (saved.getModule_id() == null || saved.getModule_id().isBlank()) {
                    throw new InvalidRequestException(requestId, "Field 'module_id' is required when target is 'module'.");
                }
            } else {
                Object moduleIds = saved.getModule_ids();
                if (moduleIds == null) {
                    throw new InvalidRequestException(requestId, "Field 'module_ids' is required when target is 'entry'.");
                }
                if ("all".equals(moduleIds)) {
                    // valid
                } else if (moduleIds instanceof List<?> list) {
                    if (list.isEmpty()) {
                        throw new InvalidRequestException(requestId, "Field 'module_ids' array must not be empty.");
                    }
                    for (Object item : list) {
                        if (!(item instanceof String s) || s.isBlank()) {
                            throw new InvalidRequestException(requestId, "Field 'module_ids' must contain non-blank strings.");
                        }
                    }
                    Set<Object> distinct = new HashSet<>(list);
                    if (distinct.size() != list.size()) {
                        throw new InvalidRequestException(requestId, "Field 'module_ids' must contain distinct module ids.");
                    }
                } else {
                    throw new InvalidRequestException(
                            requestId,
                            "Field 'module_ids' must be 'all' or a non-empty array of distinct module IDs."
                    );
                }
            }
        }
    }

    private RunResponse buildResponse(RunRequest req, ExecutionResult result) {
        RunResponse r = new RunResponse();
        r.setRequest_id(req.getRequest_id());
        r.setRoom_id(req.getRoom_id());
        r.setLanguage(req.getLanguage());
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

    public static class UnsupportedLanguageException extends RuntimeException {
        private final String language;
        private final String requestId;

        public UnsupportedLanguageException(String language) {
            this(language, null);
        }

        public UnsupportedLanguageException(String language, String requestId) {
            super("Language '" + language + "' is not supported. Use one of: cpp, python, c, java, javascript.");
            this.language = language;
            this.requestId = requestId;
        }

        public String getLanguage() { return language; }
        public String getRequestId() { return requestId; }
    }
}
