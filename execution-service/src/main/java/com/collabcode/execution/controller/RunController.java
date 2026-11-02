package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.exception.*;
import com.collabcode.execution.model.*;
import com.collabcode.execution.permission.PermissionLookupResult;
import com.collabcode.execution.permission.PermissionLookupService;
import com.collabcode.execution.queue.RunQueueGate;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import com.collabcode.execution.snapshot.MemberInfo;
import com.collabcode.execution.snapshot.SavedVersionReader;
import com.collabcode.execution.snapshot.SnapshotModule;
import com.collabcode.execution.snapshot.SnapshotResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Run endpoint – T-17 V2.8 to V2.12 (Decisions 8 and 9, T-19) & US-11 (FR16).
 *
 * POST /v1/runs
 *   1. Validates request body, rejects legacy 'code' field, and checks language and file extensions.
 *   2. Checks caller permissions with sync-service via PermissionLookupService (V2.11).
 *   3. Validates source kind against room mode, language, and reads saved module snapshot (V2.8).
 *   4. Runs user code via CodeRunner with the run spec object (RunSpec) through the queue gate (RunQueueGate, FR16).
 *   5. Populates Decision 9 result fields (runner, ran_at, visibility, saved_versions, failure, compile_time_ms).
 *   6. Broadcasts the result to the room / targeted session through sync-service (FR4.5, V2.10).
 *   7. Returns HTTP 200 with the full run-result body.
 */
@RestController
@RequestMapping("/v1")
public class RunController {

    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("cpp", "python", "c", "java", "javascript");

    private static final DateTimeFormatter RAN_AT_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final CodeRunner codeRunner;
    private final RoomBroadcastService broadcastService;
    private final RunQueueGate runQueueGate;
    private final PermissionLookupService permissionLookupService;
    private final SavedVersionReader savedVersionReader;
    private final ExecutionLimitsProperties limitsProperties;

    @org.springframework.beans.factory.annotation.Autowired
    public RunController(
            CodeRunner codeRunner,
            RoomBroadcastService broadcastService,
            RunQueueGate runQueueGate,
            PermissionLookupService permissionLookupService,
            SavedVersionReader savedVersionReader,
            ExecutionLimitsProperties limitsProperties) {
        this.codeRunner = codeRunner;
        this.broadcastService = broadcastService;
        this.runQueueGate = runQueueGate;
        this.permissionLookupService = permissionLookupService;
        this.savedVersionReader = savedVersionReader;
        this.limitsProperties = limitsProperties;
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

        // Validate source structure and file extensions (DR4)
        validateSourceStructure(req.getRequest_id(), req.getLanguage(), source);

        // Check 4: Permission lookup (T-17 V2.11, V2.12)
        PermissionLookupResult lookup;
        try {
            lookup = permissionLookupService.lookup(req.getRoom_id(), req.getSession_id());
        } catch (RoomServiceUnavailableException e) {
            throw new RoomServiceUnavailableException(req.getRequest_id(), e.getMessage());
        } catch (RoomNotFoundException e) {
            throw new RoomNotFoundException(req.getRequest_id(), e.getMessage());
        } catch (RunNotAllowedException e) {
            throw new RunNotAllowedException(req.getRequest_id(), e.getReason(), e.getMessage());
        }

        // Validate lookup completeness (no invented fallback defaults)
        if (lookup == null
                || lookup.getMember_id() == null || lookup.getMember_id().isBlank()
                || lookup.getDisplay_name() == null || lookup.getDisplay_name().isBlank()
                || lookup.getMode() == null || lookup.getMode().isBlank()) {
            throw new RoomServiceUnavailableException(
                    req.getRequest_id(),
                    "Room service is unavailable, so the run was not started. Try again."
            );
        }

        if (!lookup.isCan_run()) {
            throw new RunNotAllowedException(req.getRequest_id(), lookup.getRun_denied_reason());
        }

        // Check 5: Source kind against room mode (T-17 V2.8 Rule 1)
        String roomMode = lookup.getMode().toLowerCase();
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
        SnapshotResult snapshot = null;
        if (source instanceof InlineSource inline) {
            runSpec = new RunSpec(req.getLanguage(), inline.getFiles(), inline.getEntry(), req.getStdin());
        } else {
            SavedSource saved = (SavedSource) source;
            // One-call snapshot rule (T-17 V2.8)
            try {
                snapshot = savedVersionReader.readSnapshot(req.getRoom_id(), saved);
            } catch (RoomServiceUnavailableException e) {
                throw new RoomServiceUnavailableException(req.getRequest_id(), e.getMessage());
            } catch (ModuleNotFoundException e) {
                throw new ModuleNotFoundException(req.getRequest_id(), e.getMessage());
            } catch (ModuleNotRunnableException e) {
                throw new ModuleNotRunnableException(req.getRequest_id(), e.getMessage());
            } catch (NoEntryFileException e) {
                throw new NoEntryFileException(req.getRequest_id(), e.getMessage());
            }

            if (snapshot == null || snapshot.getModules() == null || snapshot.getModules().isEmpty()) {
                throw new ModuleNotFoundException(req.getRequest_id(), "No modules returned in snapshot.");
            }
            List<RunFile> files = snapshot.getModules().stream()
                    .map(m -> new RunFile(m.getName(), m.getContent()))
                    .toList();
            runSpec = new RunSpec(req.getLanguage(), files, snapshot.getEntry(), req.getStdin());
        }

        // Capture start timestamp immediately before code execution (exact millisecond precision, FR26)
        String ranAt = RAN_AT_FORMATTER.format(Instant.now());

        // --- Execute code through queue gate (FR16 / US-11) ---
        ExecutionResult result = runQueueGate.run(req.getRequest_id(), () ->
                codeRunner.execute(runSpec)
        );

        // --- Build response with Decision 9 fields & output cap enforcement ---
        RunResponse response = buildResponse(req, result, lookup, snapshot, ranAt);

        // --- Broadcast to room or targeted session through sync-service (FR4.5, V2.10) ---
        broadcastService.broadcast(req.getRoom_id(), req.getSession_id(), response);

        return ResponseEntity.ok(response);
    }

    private void validateSourceStructure(String requestId, String language, RunSource source) {
        if (source instanceof UnknownSource || source.getKind() == null
                || (!"inline".equals(source.getKind()) && !"saved".equals(source.getKind()))) {
            throw new InvalidRequestException(
                    requestId,
                    "Source kind '" + (source != null ? source.getKind() : "null") + "' is invalid."
            );
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
                if (!hasValidExtension(name, language)) {
                    throw new InvalidRequestException(
                            requestId,
                            "File name '" + name + "' does not match language '" + language + "'."
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

    private static boolean hasValidExtension(String fileName, String language) {
        if (fileName == null || language == null) return false;
        String lower = fileName.toLowerCase();
        return switch (language.toLowerCase()) {
            case "python" -> lower.endsWith(".py");
            case "javascript" -> lower.endsWith(".js");
            case "java" -> lower.endsWith(".java");
            case "cpp" -> lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx");
            case "c" -> lower.endsWith(".c");
            default -> false;
        };
    }

    private RunResponse buildResponse(
            RunRequest req,
            ExecutionResult result,
            PermissionLookupResult lookup,
            SnapshotResult snapshot,
            String ranAt) {

        RunResponse r = new RunResponse();
        r.setRequest_id(req.getRequest_id());
        r.setRoom_id(req.getRoom_id());
        r.setLanguage(req.getLanguage());

        // Output limits (NFR4.4, Decision 9, T-17 V2.9)
        long configuredCap = (limitsProperties != null && limitsProperties.getMaxOutputBytes() > 0)
                ? limitsProperties.getMaxOutputBytes()
                : 1_048_576L; // 1 MiB default
        long outputCap = Math.min(configuredCap, 4 * 1024 * 1024L - 65536L);

        String stdout = result.getStdout() != null ? result.getStdout() : "";
        String stderr = result.getStderr() != null ? result.getStderr() : "";
        boolean truncated = result.isOutputTruncated();

        byte[] stdoutBytes = stdout.getBytes(StandardCharsets.UTF_8);
        byte[] stderrBytes = stderr.getBytes(StandardCharsets.UTF_8);
        long combined = (long) stdoutBytes.length + stderrBytes.length;

        if (combined > outputCap) {
            truncated = true;
            if (stdoutBytes.length >= outputCap) {
                stdout = new String(stdoutBytes, 0, (int) outputCap, StandardCharsets.UTF_8);
                stderr = "";
            } else {
                int allowedStderr = (int) (outputCap - stdoutBytes.length);
                stderr = new String(stderrBytes, 0, allowedStderr, StandardCharsets.UTF_8);
            }
        }

        r.setStdout(stdout);
        r.setStderr(stderr);
        r.setExit_code(result.getExitCode());
        r.setExecution_time_ms(result.getExecutionTimeMs());
        r.setPeak_memory_mb(result.getPeakMemoryMb());
        r.setTimed_out(result.isTimedOut());

        // Decision 9 OOM kill rule: failure_kind is "non_zero_exit" with oom_killed: true, never "oom"
        boolean oom = result.isOomKilled();
        r.setOom_killed(oom);
        if (oom) {
            r.setFailure_kind("non_zero_exit");
        } else {
            r.setFailure_kind(result.getFailureKind());
        }

        r.setOutput_truncated(truncated);
        r.setMessage(result.getMessage());

        // Decision 9: compile_time_ms (null for languages with no compile stage)
        r.setCompile_time_ms(result.getCompileTimeMs());

        // Decision 9: runner (from validated permission lookup)
        r.setRunner(new MemberInfo(lookup.getMember_id(), lookup.getDisplay_name()));

        // Decision 9: ran_at (ISO 8601 UTC with exact millisecond precision and Z)
        r.setRan_at(ranAt);

        // Decision 10: visibility ("room" for Pair, "private" for Open)
        String mode = lookup.getMode().toLowerCase();
        String visibility = "open".equals(mode) ? "private" : "room";
        r.setVisibility(visibility);

        // Decision 9: saved_versions (Open mode: snapshot modules; Pair mode: empty list [])
        if ("open".equals(mode) && snapshot != null && snapshot.getModules() != null) {
            List<SavedVersionInfo> savedVersions = snapshot.getModules().stream()
                    .map(m -> new SavedVersionInfo(
                            m.getModule_id(),
                            m.getName(),
                            m.getVersion(),
                            m.getSaved_by(),
                            m.getSaved_at()
                    ))
                    .toList();
            r.setSaved_versions(savedVersions);
        } else {
            r.setSaved_versions(Collections.emptyList());
        }

        // Decision 9: failure (Open mode when failed and points to one module; null otherwise)
        if ("open".equals(mode) && snapshot != null && (result.getExitCode() != 0 || result.getFailureKind() != null)) {
            FailureInfo failure = resolveFailure(result, snapshot, (SavedSource) req.getSource());
            r.setFailure(failure);
        } else {
            r.setFailure(null);
        }

        return r;
    }

    private FailureInfo resolveFailure(ExecutionResult result, SnapshotResult snapshot, SavedSource source) {
        if (result.isTimedOut()) {
            return null; // Timeouts cannot be pinned to one module
        }

        List<SnapshotModule> modules = snapshot.getModules();
        if (modules == null || modules.isEmpty()) {
            return null;
        }

        // 1. If ExecutionResult explicitly named a failing module
        String failingTarget = result.getFailingModule();
        if (failingTarget != null) {
            for (SnapshotModule m : modules) {
                if (failingTarget.equalsIgnoreCase(m.getName()) || failingTarget.equals(m.getModule_id())) {
                    return new FailureInfo(m.getModule_id(), m.getName(), m.getOwner(), m.getSaved_by());
                }
            }
        }

        // 2. If target was a single module, that module failed
        if ("module".equals(source.getTarget()) && modules.size() == 1) {
            SnapshotModule m = modules.get(0);
            return new FailureInfo(m.getModule_id(), m.getName(), m.getOwner(), m.getSaved_by());
        }

        // 3. Check stderr for module names
        String stderr = result.getStderr();
        if (stderr != null && !stderr.isBlank()) {
            for (SnapshotModule m : modules) {
                if (stderr.contains(m.getName()) || stderr.contains(m.getModule_id())) {
                    return new FailureInfo(m.getModule_id(), m.getName(), m.getOwner(), m.getSaved_by());
                }
            }
        }

        return null;
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
