package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunResponse;
import com.collabcode.execution.model.RunSpec;
import com.collabcode.execution.model.SavedSource;
import com.collabcode.execution.permission.PermissionLookupResult;
import com.collabcode.execution.permission.StubPermissionLookupService;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import com.collabcode.execution.snapshot.MemberInfo;
import com.collabcode.execution.snapshot.SnapshotModule;
import com.collabcode.execution.snapshot.SnapshotResult;
import com.collabcode.execution.snapshot.StubSavedVersionReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Acceptance and unit tests for T-19 spec object and saved module snapshot reader (Jay Limbasiya & Yash Oza).
 *
 * Covers all criteria from issue #64 and T-17 contract review:
 *   1. Valid inline source in Pair mode
 *   2. Rejection of legacy 'code' field as 422 INVALID_REQUEST
 *   3. Source validation: invalid kind, invalid target, missing module_id, invalid module_ids
 *   4. Validation of entry file presence and plain file names (no '/', '\', '..')
 *   5. Validation of language-specific file extensions
 *   6. Rejection of explicit null file content
 *   7. Validation of request_id and session_id as UUID v4
 *   8. Validation of single file in Pair mode
 *   9. Saved source with stub snapshot reader (module and entry targets)
 *  10. One-call snapshot rule verification (consistent snapshot)
 *  11. Entry module guaranteed in snapshot
 *  12. Snapshot reader failure -> 503 ROOM_SERVICE_UNAVAILABLE (request_id preserved)
 *  13. Permission lookup failure -> 503 ROOM_SERVICE_UNAVAILABLE (request_id preserved)
 *  14. Incomplete PermissionLookupResult validation -> 503 ROOM_SERVICE_UNAVAILABLE
 *  15. Permission denied (observer, run_revoked, host_only) -> 403 RUN_NOT_ALLOWED (request_id preserved)
 *  16. Mode mismatches (inline in open, saved in pair) -> 422 INVALID_REQUEST
 *  17. Language mismatch in open mode -> 422 INVALID_REQUEST
 *  18. Decision 9 result fields (runner, ran_at with exact millisecond precision, visibility, saved_versions, failure, compile_time_ms)
 *  19. OOM kill rule: non_zero_exit with oom_killed: true, never "oom"
 *  20. Broadcast audience targeting (room for Pair, session list for Open)
 *  21. Output cap enforcement across combined stdout + stderr
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunControllerT19Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CodeRunner codeRunner;

    @MockBean
    private RoomBroadcastService broadcastService;

    @SpyBean
    private StubSavedVersionReader savedVersionReader;

    @SpyBean
    private StubPermissionLookupService permissionLookupService;

    private static final String REQUEST_ID = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10";
    private static final String ROOM_ID    = "room-pair-01";
    private static final String SESSION_ID = "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90";

    @BeforeEach
    void setUp() {
        savedVersionReader.reset();
        permissionLookupService.reset();
        when(codeRunner.execute(any(RunSpec.class)))
                .thenReturn(new ExecutionResult("hello\n", "", 0, 15.0, 4.2,
                        false, false, null, false, null));
    }

    @AfterEach
    void tearDown() {
        savedVersionReader.reset();
        permissionLookupService.reset();
    }

    // ------------------------------------------------------------------ 1. inline success

    @Test
    @DisplayName("Valid inline source in Pair mode returns 200 and passes RunSpec to CodeRunner")
    void run_validInlineSource_returns200() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "stdin":      "",
                  "source": {
                    "kind": "inline",
                    "files": [
                      {
                        "name": "main.py",
                        "content": "print('hello')"
                      }
                    ],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout").value("hello\n"));

        ArgumentCaptor<RunSpec> specCaptor = ArgumentCaptor.forClass(RunSpec.class);
        verify(codeRunner).execute(specCaptor.capture());
        RunSpec captured = specCaptor.getValue();
        assertThat(captured.getLanguage()).isEqualTo("python");
        assertThat(captured.getEntry()).isEqualTo("main.py");
        assertThat(captured.getFiles()).hasSize(1);
        assertThat(captured.getFiles().get(0).getName()).isEqualTo("main.py");
    }

    // ------------------------------------------------------------------ 2. legacy code rejected

    @Test
    @DisplayName("Body with 'code' instead of 'source' returns 422 INVALID_REQUEST")
    void run_bodyWithCode_returns422() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "code":       "print(1)"
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Field 'code' is no longer accepted. Use 'source'."));
    }

    // ------------------------------------------------------------------ 3. invalid source kind

    @Test
    @DisplayName("Source with invalid kind returns 422 INVALID_REQUEST")
    void run_invalidKind_returns422() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "custom_kind"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Source kind 'custom_kind' is invalid."));
    }

    // ------------------------------------------------------------------ 4. entry not in files

    @Test
    @DisplayName("Inline source where entry is not in files returns 422 INVALID_REQUEST")
    void run_entryNotInFiles_returns422() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [
                      {
                        "name": "solution.py",
                        "content": "print(1)"
                      }
                    ],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Entry file 'main.py' must match one of the files."));
    }

    // ------------------------------------------------------------------ 5. invalid file names

    @Test
    @DisplayName("Inline file name with path traversal or slash returns 422 INVALID_REQUEST (DR4)")
    void run_invalidFileName_returns422() throws Exception {
        for (String badName : new String[]{"../evil.py", "dir/main.py", "dir\\main.py", "..\\foo.py"}) {
            String body = String.format("""
                    {
                      "request_id": "%s",
                      "room_id":    "%s",
                      "session_id": "%s",
                      "language":   "python",
                      "source": {
                        "kind": "inline",
                        "files": [
                          {
                            "name": "%s",
                            "content": "print(1)"
                          }
                        ],
                        "entry": "%s"
                      }
                    }
                    """, REQUEST_ID, ROOM_ID, SESSION_ID, badName.replace("\\", "\\\\"), badName.replace("\\", "\\\\"));

            mockMvc.perform(post("/v1/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("is invalid. Plain file name with language extension required.")));
        }
    }

    // ------------------------------------------------------------------ 6. file extension validation

    @Test
    @DisplayName("File extension not matching language returns 422 INVALID_REQUEST")
    void run_invalidFileExtension_returns422() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [
                      {
                        "name": "main.java",
                        "content": "print(1)"
                      }
                    ],
                    "entry": "main.java"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("File name 'main.java' does not match language 'python'."));
    }

    // ------------------------------------------------------------------ 7. explicit null file content

    @Test
    @DisplayName("Explicit null file content in RunFile is rejected with 422 INVALID_REQUEST")
    void run_explicitNullFileContent_returns422() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [
                      {
                        "name": "main.py",
                        "content": null
                      }
                    ],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("File content must not be null."));
    }

    // ------------------------------------------------------------------ 8. UUID v4 validation

    @Test
    @DisplayName("Invalid request_id (not UUID v4) returns 422 with request_id null in error envelope")
    void run_invalidRequestId_returns422WithNullRequestId() throws Exception {
        String body = """
                {
                  "request_id": "not-a-uuid-v4",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print(1)" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("Invalid session_id (not UUID v4) returns 422 and preserves valid request_id")
    void run_invalidSessionId_returns422WithPreservedRequestId() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "not-a-valid-session-uuid",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print(1)" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Field 'session_id' is missing or invalid."));
    }

    // ------------------------------------------------------------------ 9. multiple files in pair mode

    @Test
    @DisplayName("Inline source with multiple files in Pair mode returns 422 INVALID_REQUEST (FR18)")
    void run_multipleFilesInPairMode_returns422() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [
                      { "name": "main.py", "content": "print(1)" },
                      { "name": "helper.py", "content": "print(2)" }
                    ],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Pair mode requires exactly one file in 'files'."));
    }

    // ------------------------------------------------------------------ 10. saved source targets

    @Test
    @DisplayName("Saved source with target 'module' in Open room executes through snapshot reader")
    void run_savedSourceTargetModule_success() throws Exception {
        String openRoom = "room-open-01";
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_9Qx7mBvA2nR0"
                  }
                }
                """.formatted(REQUEST_ID, openRoom, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(savedVersionReader, times(1)).readSnapshot(eq(openRoom), any(SavedSource.class));
        verify(codeRunner).execute(any(RunSpec.class));
    }

    @Test
    @DisplayName("Saved source with target 'entry' and module_ids 'all' executes in Open room")
    void run_savedSourceTargetEntryAll_success() throws Exception {
        String openRoom = "room-open-01";
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "entry",
                    "module_ids": "all"
                  }
                }
                """.formatted(REQUEST_ID, openRoom, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(savedVersionReader, times(1)).readSnapshot(eq(openRoom), any(SavedSource.class));
    }

    @Test
    @DisplayName("Saved source with target 'entry' and array of module_ids executes in Open room")
    void run_savedSourceTargetEntryList_success() throws Exception {
        String openRoom = "room-open-01";
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "entry",
                    "module_ids": ["mod_9Qx7mBvA2nR0", "mod_5Lm8Np2Qr4St"]
                  }
                }
                """.formatted(REQUEST_ID, openRoom, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(savedVersionReader, times(1)).readSnapshot(eq(openRoom), any(SavedSource.class));
    }

    // ------------------------------------------------------------------ 11. snapshot rule & entry invariant

    @Test
    @DisplayName("Snapshot rule: multiple modules are read in exactly ONE snapshot call (FR25.1)")
    void run_multipleModules_readInExactlyOneCall() throws Exception {
        String openRoom = "room-open-01";
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "entry",
                    "module_ids": ["mod_1", "mod_2", "mod_3"]
                  }
                }
                """.formatted(REQUEST_ID, openRoom, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Must be read together in exactly ONE call to form a consistent snapshot
        verify(savedVersionReader, times(1)).readSnapshot(eq(openRoom), any(SavedSource.class));
    }

    @Test
    @DisplayName("Snapshot invariant: entry module (main.py) is always present even if omitted from module_ids (T-17 V2.8)")
    void snapshotRule_singleConsistentRead_entryIncluded() throws Exception {
        String openRoom = "room-open-01";
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "entry",
                    "module_ids": ["mod_5Lm8Np2Qr4St"]
                  }
                }
                """.formatted(REQUEST_ID, openRoom, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved_versions").isArray())
                .andExpect(jsonPath("$.saved_versions[0].name").value("main.py"));

        verify(savedVersionReader, times(1)).readSnapshot(eq(openRoom), any(SavedSource.class));
    }

    // ------------------------------------------------------------------ 12. snapshot failure preserves request_id

    @Test
    @DisplayName("Snapshot reader failure returns 503 ROOM_SERVICE_UNAVAILABLE and preserves request_id")
    void run_snapshotServiceUnavailable_preservesRequestId_returns503() throws Exception {
        savedVersionReader.setAvailable(false);
        String openRoom = "room-open-01";
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_1"
                  }
                }
                """.formatted(REQUEST_ID, openRoom, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("ROOM_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("Room service is unavailable, so the run was not started. Try again."));
    }

    // ------------------------------------------------------------------ 13. permission lookup failure preserves request_id

    @Test
    @DisplayName("Permission lookup failure returns 503 ROOM_SERVICE_UNAVAILABLE and preserves request_id")
    void run_permissionLookupUnavailable_preservesRequestId_returns503() throws Exception {
        permissionLookupService.setAvailable(false);
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print(1)" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("ROOM_SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("Incomplete PermissionLookupResult (missing fields) returns 503 instead of invented defaults")
    void run_incompletePermissionLookup_returns503() throws Exception {
        permissionLookupService.setCustomLookup(new PermissionLookupResult(
                null, null, "participant", null, null, true, null, false
        ));

        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print(1)" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("ROOM_SERVICE_UNAVAILABLE"));
    }

    // ------------------------------------------------------------------ 14. permission denied preserves request_id

    @Test
    @DisplayName("Observer role returns 403 RUN_NOT_ALLOWED with reason 'observer' and preserves request_id")
    void run_observerRole_returns403() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print(1)" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, StubPermissionLookupService.OBSERVER_SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.reason").value("observer"))
                .andExpect(jsonPath("$.error.message").value("Observers cannot run code."));
    }

    @Test
    @DisplayName("Run revoked returns 403 RUN_NOT_ALLOWED with reason 'run_revoked' and preserves request_id")
    void run_runRevoked_returns403() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print(1)" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, StubPermissionLookupService.REVOKED_SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.reason").value("run_revoked"))
                .andExpect(jsonPath("$.error.message").value("The Host has turned off running for you."));
    }

    @Test
    @DisplayName("Host-only running returns 403 RUN_NOT_ALLOWED with reason 'host_only' and preserves request_id")
    void run_hostOnly_returns403() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print(1)" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, StubPermissionLookupService.HOST_ONLY_SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.reason").value("host_only"))
                .andExpect(jsonPath("$.error.message").value("Only the Host can run code in this room right now."));
    }

    // ------------------------------------------------------------------ 15. mode mismatches

    @Test
    @DisplayName("Inline source in Open mode returns 422 INVALID_REQUEST")
    void run_inlineInOpenMode_returns422() throws Exception {
        String openRoom = "room-open-01";
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print(1)" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, openRoom, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Source kind 'inline' is not allowed in an open room."));
    }

    @Test
    @DisplayName("Saved source in Pair mode returns 422 INVALID_REQUEST")
    void run_savedInPairMode_returns422() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_1"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Source kind 'saved' is not allowed in a pair room."));
    }

    // ------------------------------------------------------------------ 16. language mismatch in open mode

    @Test
    @DisplayName("Language in Open room differing from workspace language returns 422 INVALID_REQUEST (FR20.1)")
    void run_openLanguageMismatch_returns422() throws Exception {
        String openRoom = "room-open-01";
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "javascript",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_1"
                  }
                }
                """.formatted(REQUEST_ID, openRoom, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(
                        "Language 'javascript' does not match workspace language 'python'."));
    }

    // ------------------------------------------------------------------ 17. Decision 9: runner & ran_at precision

    @Test
    @DisplayName("Response carries runner and ran_at with exact millisecond precision (uuuu-MM-dd'T'HH:mm:ss.SSS'Z')")
    void run_decision9_runnerAndRanAt() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "stdin":      "",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print('hello')" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runner.member_id").value("m_7a3c9f"))
                .andExpect(jsonPath("$.runner.display_name").value("Ada"))
                .andExpect(jsonPath("$.ran_at").isString())
                .andExpect(jsonPath("$.ran_at").value(org.hamcrest.Matchers.matchesPattern("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")));
    }

    // ------------------------------------------------------------------ 18. Decision 9: visibility & saved_versions

    @Test
    @DisplayName("Pair mode returns visibility 'room' and empty saved_versions array [] (Decision 9 & 10)")
    void run_decision9_pairMode_visibilityAndSavedVersions() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print('hello')" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("room"))
                .andExpect(jsonPath("$.saved_versions").isArray())
                .andExpect(jsonPath("$.saved_versions").isEmpty())
                .andExpect(jsonPath("$.failure").doesNotExist());
    }

    @Test
    @DisplayName("Open mode returns visibility 'private' and populated saved_versions list (Decision 9 & 10)")
    void run_decision9_openMode_visibilityAndSavedVersions() throws Exception {
        String openRoom = "room-open-01";
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_9Qx7mBvA2nR0"
                  }
                }
                """.formatted(REQUEST_ID, openRoom, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("private"))
                .andExpect(jsonPath("$.saved_versions").isArray())
                .andExpect(jsonPath("$.saved_versions[0].module_id").value("mod_9Qx7mBvA2nR0"))
                .andExpect(jsonPath("$.saved_versions[0].name").value("main.py"))
                .andExpect(jsonPath("$.saved_versions[0].version").value(1))
                .andExpect(jsonPath("$.saved_versions[0].saved_by.member_id").value("m_7a3c9f"))
                .andExpect(jsonPath("$.saved_versions[0].saved_by.display_name").value("Ada"))
                .andExpect(jsonPath("$.saved_versions[0].saved_at").value("2026-11-01T10:12:04.118Z"));
    }

    // ------------------------------------------------------------------ 19. Decision 9: failure info

    @Test
    @DisplayName("Open mode failure pinned to module populates failure object (Decision 9)")
    void run_decision9_openMode_failurePinnedToModule() throws Exception {
        String openRoom = "room-open-01";
        when(codeRunner.execute(any(RunSpec.class))).thenReturn(new ExecutionResult(
                "", "SyntaxError in main.py\n", 1, 0.0, 0.0,
                false, false, "compile_error", false, null, null, "main.py"
        ));

        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_9Qx7mBvA2nR0"
                  }
                }
                """.formatted(REQUEST_ID, openRoom, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failure.module_id").value("mod_9Qx7mBvA2nR0"))
                .andExpect(jsonPath("$.failure.name").value("main.py"))
                .andExpect(jsonPath("$.failure.owner.member_id").value("m_7a3c9f"))
                .andExpect(jsonPath("$.failure.last_saved_by.member_id").value("m_7a3c9f"));
    }

    // ------------------------------------------------------------------ 20. Decision 9: OOM kill rule

    @Test
    @DisplayName("OOM kill in response has failure_kind 'non_zero_exit' and oom_killed true, never 'oom' (Decision 9)")
    void run_decision9_oomKillRule() throws Exception {
        when(codeRunner.execute(any(RunSpec.class))).thenReturn(new ExecutionResult(
                "", "Killed: Out of memory\n", 137, 25.0, 64.0,
                false, true, "oom", false, "Memory limit exceeded", null, null
        ));

        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "alloc_oom()" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oom_killed").value(true))
                .andExpect(jsonPath("$.failure_kind").value("non_zero_exit"))
                .andExpect(jsonPath("$.exit_code").value(137));
    }

    // ------------------------------------------------------------------ 21. Decision 9: compile_time_ms

    @Test
    @DisplayName("compile_time_ms is present when returned by CodeRunner, null for interpreted (Decision 9)")
    void run_decision9_compileTimeMs() throws Exception {
        when(codeRunner.execute(any(RunSpec.class))).thenReturn(new ExecutionResult(
                "hello cpp\n", "", 0, 10.0, 4.0,
                false, false, null, false, null, 15.5, null
        ));

        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "cpp",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.cpp", "content": "int main() {}" }],
                    "entry": "main.cpp"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compile_time_ms").value(15.5))
                .andExpect(jsonPath("$.execution_time_ms").value(10.0));
    }

    // ------------------------------------------------------------------ 22. Audience payloads (T-17 V2.10)

    @Test
    @DisplayName("Broadcast payload includes audience {'kind':'room'} in Pair mode")
    void broadcast_pairMode_payloadCarriesRoomAudience() {
        RoomBroadcastService service = new RoomBroadcastService(objectMapper);
        RunResponse resp = new RunResponse();
        resp.setVisibility("room");
        resp.setRequest_id(REQUEST_ID);

        Map<String, Object> payload = service.buildPayload(resp, SESSION_ID);
        assertThat(payload).containsKey("audience");
        @SuppressWarnings("unchecked")
        Map<String, Object> aud = (Map<String, Object>) payload.get("audience");
        assertThat(aud.get("kind")).isEqualTo("room");
    }

    @Test
    @DisplayName("Broadcast payload includes audience {'kind':'sessions','session_ids':[...]} in Open mode")
    void broadcast_openMode_payloadCarriesSessionAudience() {
        RoomBroadcastService service = new RoomBroadcastService(objectMapper);
        RunResponse resp = new RunResponse();
        resp.setVisibility("private");
        resp.setRequest_id(REQUEST_ID);

        Map<String, Object> payload = service.buildPayload(resp, SESSION_ID);
        assertThat(payload).containsKey("audience");
        @SuppressWarnings("unchecked")
        Map<String, Object> aud = (Map<String, Object>) payload.get("audience");
        assertThat(aud.get("kind")).isEqualTo("sessions");
        assertThat(aud.get("session_ids")).isEqualTo(List.of(SESSION_ID));
    }

    // ------------------------------------------------------------------ 23. Output cap enforcement

    @Test
    @DisplayName("Output exceeding configured cap is truncated with output_truncated: true")
    void run_outputCap_truncatesOutput() throws Exception {
        String longStdout = "A".repeat(2_000_000); // 2 MB > 1 MB cap
        when(codeRunner.execute(any(RunSpec.class))).thenReturn(new ExecutionResult(
                longStdout, "", 0, 15.0, 4.2,
                false, false, null, false, null
        ));

        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print('long')" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output_truncated").value(true));
    }
}
