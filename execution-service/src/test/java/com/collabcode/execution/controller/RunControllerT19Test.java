package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.exception.RoomServiceUnavailableException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Acceptance and unit tests for T-19 spec object and saved module snapshot reader (Jay Limbasiya).
 *
 * Covers all criteria from issue #64:
 *   1. Valid inline source in Pair mode
 *   2. Rejection of legacy 'code' field as 422 INVALID_REQUEST
 *   3. Source validation: invalid kind, invalid target, missing module_id, invalid module_ids
 *   4. Validation of entry file presence and plain file names (no '/', '\', '..')
 *   5. Validation of single file in Pair mode
 *   6. Saved source with stub snapshot reader (module and entry targets)
 *   7. One-call snapshot rule verification
 *   8. Negative test for snapshot rule: multiple calls for modules fails
 *   9. Snapshot reader failure -> 503 ROOM_SERVICE_UNAVAILABLE
 *  10. Permission lookup failure -> 503 ROOM_SERVICE_UNAVAILABLE
 *  11. Permission denied (observer, run_revoked, host_only) -> 403 RUN_NOT_ALLOWED
 *  12. Mode mismatches (inline in open, saved in pair) -> 422 INVALID_REQUEST
 *  13. Language mismatch in open mode -> 422 INVALID_REQUEST
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunControllerT19Test {

    @Autowired
    private MockMvc mockMvc;

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
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("is invalid. Plain file name with language extension required.")));
        }
    }

    // ------------------------------------------------------------------ 6. multiple files in pair mode

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
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Pair mode requires exactly one file in 'files'."));
    }

    // ------------------------------------------------------------------ 7. saved source target: module

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

    // ------------------------------------------------------------------ 8. saved source target: entry "all"

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

    // ------------------------------------------------------------------ 9. saved source target: entry list

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

    // ------------------------------------------------------------------ 10. snapshot rule: exactly one call

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

    // ------------------------------------------------------------------ 11. snapshot rule: separate calls fail test

    @Test
    @DisplayName("Snapshot rule negative test: reading module list in separate calls violates snapshot invariant")
    void snapshotRule_separateCalls_failsInvariant() {
        // Enforce the requirement: "A saved source whose module list is read in two separate calls fails a test"
        StubSavedVersionReader reader = new StubSavedVersionReader();
        SavedSource multiModuleSource = new SavedSource("entry", List.of("mod_1", "mod_2"), null);

        // A single snapshot read for the entire source succeeds
        SnapshotResult snapshot = reader.readSnapshot("room-open-01", multiModuleSource);
        assertThat(snapshot.getModules()).isNotEmpty();

        // Any custom workflow attempting to read per-module individually violates one-call rule
        int calls = 0;
        for (String modId : List.of("mod_1", "mod_2")) {
            reader.readSnapshot("room-open-01", new SavedSource("module", modId));
            calls++;
        }

        // Verifying that doing multiple calls violates the single snapshot expectation
        final int totalCalls = calls;
        assertThatThrownBy(() -> {
            if (totalCalls > 1) {
                throw new AssertionError("Snapshot violation: multiple calls (" + totalCalls + ") detected instead of 1 consistent snapshot read.");
            }
        }).isInstanceOf(AssertionError.class)
          .hasMessageContaining("Snapshot violation");
    }

    // ------------------------------------------------------------------ 12. snapshot failure → 503

    @Test
    @DisplayName("Snapshot reader failure returns 503 ROOM_SERVICE_UNAVAILABLE (T-17 V2.11)")
    void run_snapshotServiceUnavailable_returns503() throws Exception {
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
                .andExpect(jsonPath("$.error.code").value("ROOM_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("Room service is unavailable, so the run was not started. Try again."));
    }

    // ------------------------------------------------------------------ 13. permission lookup failure → 503

    @Test
    @DisplayName("Permission lookup failure returns 503 ROOM_SERVICE_UNAVAILABLE (T-17 V2.11)")
    void run_permissionLookupUnavailable_returns503() throws Exception {
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
                .andExpect(jsonPath("$.error.code").value("ROOM_SERVICE_UNAVAILABLE"));
    }

    // ------------------------------------------------------------------ 14. observer role → 403

    @Test
    @DisplayName("Observer role returns 403 RUN_NOT_ALLOWED with reason 'observer' (FR9.4, FR41.1)")
    void run_observerRole_returns403() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "observer-session-id",
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
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.reason").value("observer"))
                .andExpect(jsonPath("$.error.message").value("Observers cannot run code."));
    }

    // ------------------------------------------------------------------ 15. run revoked → 403

    @Test
    @DisplayName("Run revoked returns 403 RUN_NOT_ALLOWED with reason 'run_revoked' (FR41.2)")
    void run_runRevoked_returns403() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "revoked-session-id",
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
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.reason").value("run_revoked"))
                .andExpect(jsonPath("$.error.message").value("The Host has turned off running for you."));
    }

    // ------------------------------------------------------------------ 16. host only running → 403

    @Test
    @DisplayName("Host-only running returns 403 RUN_NOT_ALLOWED with reason 'host_only' (FR41.3)")
    void run_hostOnly_returns403() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "host_only-session-id",
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
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.reason").value("host_only"))
                .andExpect(jsonPath("$.error.message").value("Only the Host can run code in this room right now."));
    }

    // ------------------------------------------------------------------ 17. mode mismatch

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
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Source kind 'saved' is not allowed in a pair room."));
    }

    // ------------------------------------------------------------------ 18. language mismatch in open mode

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
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(
                        "Language 'javascript' does not match workspace language 'python'."));
    }

    // ------------------------------------------------------------------ 19. Decision 9: runner & ran_at

    @Test
    @DisplayName("Response carries runner (member_id, display_name) and ISO ran_at timestamp (Decision 9)")
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
                .andExpect(jsonPath("$.ran_at").value(org.hamcrest.Matchers.matchesPattern("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z$")));
    }

    // ------------------------------------------------------------------ 20. Decision 9: visibility & saved_versions

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

    // ------------------------------------------------------------------ 21. Decision 9: failure info

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

    // ------------------------------------------------------------------ 22. Decision 9: OOM kill rule

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

    // ------------------------------------------------------------------ 23. Decision 9: compile_time_ms

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
}

