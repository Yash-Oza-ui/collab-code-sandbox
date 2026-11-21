package com.collabcode.execution.controller;

import com.collabcode.execution.analysis.AnalysisService;
import com.collabcode.execution.permission.PermissionLookupResult;
import com.collabcode.execution.permission.PermissionLookupService;
import com.collabcode.execution.snapshot.SavedVersionReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalysisService analysisService;

    @MockBean
    private PermissionLookupService permissionLookupService;

    @MockBean
    private SavedVersionReader savedVersionReader;

    @Test
    @DisplayName("Valid analysis request returns 202 Accepted immediately (T-18 Decision 4)")
    void testValidAnalysisReturns202() throws Exception {
        String reqId = UUID.randomUUID().toString();
        PermissionLookupResult permission = new PermissionLookupResult(
                "mem-123", "User", "editor", "pair", "python", true, null, true
        );
        when(permissionLookupService.lookup("room-123", "sess-abc")).thenReturn(permission);

        when(analysisService.performAnalysisAsync(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        String body = String.format("""
                {
                  "request_id": "%s",
                  "room_id": "room-123",
                  "task": "complexity",
                  "language": "python",
                  "consent": true,
                  "source": {
                    "kind": "inline",
                    "code": "print('hello')"
                  }
                }
                """, reqId);

        mockMvc.perform(post("/v1/analyses")
                        .header("X-Session-Id", "sess-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.request_id").value(reqId))
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.analysis_id").isNotEmpty());

        verify(analysisService).performAnalysisAsync(
                eq(reqId), any(), eq("room-123"), eq("sess-abc"), eq("room"),
                eq("complexity"), eq("python"), eq(true), eq(true), any()
        );
    }

    @Test
    @DisplayName("Missing X-Session-Id header returns 422 INVALID_REQUEST")
    void testMissingSessionIdHeader() throws Exception {
        String reqId = UUID.randomUUID().toString();
        String body = String.format("""
                {
                  "request_id": "%s",
                  "room_id": "room-123",
                  "task": "complexity",
                  "language": "python",
                  "consent": true,
                  "source": {
                    "kind": "inline",
                    "code": "print('hello')"
                  }
                }
                """, reqId);

        mockMvc.perform(post("/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("Unauthorized user without can_run gets 403 RUN_NOT_ALLOWED")
    void testUnauthorizedUserReturns403() throws Exception {
        String reqId = UUID.randomUUID().toString();
        PermissionLookupResult permission = new PermissionLookupResult(
                "mem-viewer", "Viewer", "viewer", "pair", "python", false, "READ_ONLY", true
        );
        when(permissionLookupService.lookup("room-123", "sess-viewer")).thenReturn(permission);

        String body = String.format("""
                {
                  "request_id": "%s",
                  "room_id": "room-123",
                  "task": "complexity",
                  "language": "python",
                  "consent": true,
                  "source": {
                    "kind": "inline",
                    "code": "print('hello')"
                  }
                }
                """, reqId);

        mockMvc.perform(post("/v1/analyses")
                        .header("X-Session-Id", "sess-viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("Unsupported task returns 422 INVALID_REQUEST")
    void testUnsupportedTaskReturns422() throws Exception {
        String reqId = UUID.randomUUID().toString();
        String body = String.format("""
                {
                  "request_id": "%s",
                  "room_id": "room-123",
                  "task": "magic_solver",
                  "language": "python",
                  "consent": true,
                  "source": {
                    "kind": "inline",
                    "code": "print('hello')"
                  }
                }
                """, reqId);

        mockMvc.perform(post("/v1/analyses")
                        .header("X-Session-Id", "sess-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
