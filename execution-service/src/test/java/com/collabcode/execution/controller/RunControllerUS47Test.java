package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.model.RunResponse;
import com.collabcode.execution.model.RunSpec;
import com.collabcode.execution.permission.PermissionLookupResult;
import com.collabcode.execution.permission.StubPermissionLookupService;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Acceptance and unit tests for US-47: Run one runnable module (FR25.1, FR25.2, Issue #211).
 *
 * Covers:
 *   1. A module marked runnable can be run on its own; run contains only that module.
 *   2. Non-runnable module is refused with MODULE_NOT_RUNNABLE (422).
 *   3. Never-saved module is refused with MODULE_NOT_SAVED (422).
 *   4. Open mode inline code or legacy code is rejected with INVALID_REQUEST (422).
 *   5. Observer and revoked user get 403 RUN_NOT_ALLOWED with matching error.reason.
 *   6. Result visibility in Open mode is "private" and delivered only to runner's session.
 *   7. Failure stays null on success.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunControllerUS47Test {

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

    private static final String REQUEST_ID = "00000000-0000-4000-8000-000000000047";
    private static final String OPEN_ROOM  = "room-open-01";
    private static final String SESSION_ID = "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90";

    @BeforeEach
    void setUp() {
        savedVersionReader.reset();
        permissionLookupService.reset();
        when(codeRunner.execute(any(RunSpec.class)))
                .thenReturn(new ExecutionResult("result output\n", "", 0, 10.0, 5.0,
                        false, false, null, false, null));
    }

    @AfterEach
    void tearDown() {
        savedVersionReader.reset();
        permissionLookupService.reset();
    }

    @Test
    @DisplayName("US-47: A runnable module can be run on its own; run contains only that module (FR25.2)")
    void run_runnableModuleOnItsOwn_success() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_runnable_single"
                  }
                }
                """.formatted(REQUEST_ID, OPEN_ROOM, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout").value("result output\n"))
                .andExpect(jsonPath("$.visibility").value("private"))
                .andExpect(jsonPath("$.saved_versions").isArray())
                .andExpect(jsonPath("$.saved_versions[0].module_id").value("mod_runnable_single"))
                .andExpect(jsonPath("$.saved_versions[0].name").value("main.py"))
                .andExpect(jsonPath("$.saved_versions[0].version").value(1))
                .andExpect(jsonPath("$.failure").doesNotExist());

        ArgumentCaptor<RunSpec> specCaptor = ArgumentCaptor.forClass(RunSpec.class);
        verify(codeRunner).execute(specCaptor.capture());
        RunSpec captured = specCaptor.getValue();
        assertThat(captured.getFiles()).hasSize(1);
        assertThat(captured.getFiles().get(0).getName()).isEqualTo("main.py");
        assertThat(captured.getEntry()).isEqualTo("main.py");

        // Verify private broadcast delivered to runner's session only
        verify(broadcastService).broadcast(eq(OPEN_ROOM), eq(SESSION_ID), any(RunResponse.class));
    }

    @Test
    @DisplayName("US-47: A module that is not marked runnable is refused with MODULE_NOT_RUNNABLE (422)")
    void run_moduleNotRunnable_returns422ModuleNotRunnable() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_not_runnable"
                  }
                }
                """.formatted(REQUEST_ID, OPEN_ROOM, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("MODULE_NOT_RUNNABLE"))
                .andExpect(jsonPath("$.error.message").value("Module 'mod_not_runnable' is not marked runnable."));

        verify(codeRunner, never()).execute(any());
    }

    @Test
    @DisplayName("US-47: A module that has never been saved is refused with MODULE_NOT_SAVED (422)")
    void run_neverSavedModule_returns422ModuleNotSaved() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_never_saved"
                  }
                }
                """.formatted(REQUEST_ID, OPEN_ROOM, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("MODULE_NOT_SAVED"))
                .andExpect(jsonPath("$.error.message").value("Module 'mod_never_saved' has never been saved. Save it first."));

        verify(codeRunner, never()).execute(any());
    }

    @Test
    @DisplayName("US-47: A request in Open mode carrying inline code is rejected with INVALID_REQUEST (422)")
    void run_openModeInlineCode_rejectedWithInvalidRequest() throws Exception {
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
                """.formatted(REQUEST_ID, OPEN_ROOM, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Source kind 'inline' is not allowed in an open room."));

        verify(codeRunner, never()).execute(any());
    }

    @Test
    @DisplayName("US-47: Observer gets 403 RUN_NOT_ALLOWED with reason 'observer'")
    void run_observer_gets403RunNotAllowed() throws Exception {
        String observerSession = StubPermissionLookupService.OBSERVER_SESSION_ID;
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_runnable_single"
                  }
                }
                """.formatted(REQUEST_ID, OPEN_ROOM, observerSession);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.reason").value("observer"));

        verify(codeRunner, never()).execute(any());
    }

    @Test
    @DisplayName("US-47: User with run revoked gets 403 RUN_NOT_ALLOWED with reason 'run_revoked'")
    void run_runRevoked_gets403RunNotAllowed() throws Exception {
        String revokedSession = StubPermissionLookupService.REVOKED_SESSION_ID;
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "saved",
                    "target": "module",
                    "module_id": "mod_runnable_single"
                  }
                }
                """.formatted(REQUEST_ID, OPEN_ROOM, revokedSession);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.reason").value("run_revoked"));

        verify(codeRunner, never()).execute(any());
    }
}
