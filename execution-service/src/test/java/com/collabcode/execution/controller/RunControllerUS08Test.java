package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.model.RunResponse;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * US-08 endpoint tests – Jay Limbasiya (feature/US-08-run-code).
 *
 * All tests run offline: no Docker, no real sync-service (FR5.1, NFR3).
 * CodeRunner is mocked so each test controls the exact ExecutionResult.
 * RoomBroadcastService is mocked to verify the broadcast call without
 * actually sending HTTP requests.
 *
 * Scenarios covered (matching the issue's Definition of Done):
 *   1. Success run  – stdout "hello\n", stderr "", exit 0
 *   2. Failure run  – stderr "boom", non-zero exit, failure_kind "non_zero_exit"
 *   3. Stdin run    – stdout "ABC\n"
 *   4. Compile error – stderr with error text, failure_kind "compile_error"
 *   5. Broadcast is called with room_id and correct type field (FR4.5)
 *   6. Missing 'code' → 400 INVALID_REQUEST
 *   7. Missing 'language' → 400 INVALID_REQUEST
 *   8. Unsupported language → 422 UNSUPPORTED_LANGUAGE
 *   9. All five DR1 languages accepted
 *  10. All required result fields present in every response (T-06 §4.2)
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunControllerUS08Test {

    @Autowired
    private MockMvc mockMvc;

    /** Replace the real CodeRunner bean so we control the result in every test. */
    @MockBean
    private CodeRunner codeRunner;

    /** Stub broadcast so tests never hit a real sync-service. */
    @MockBean
    private RoomBroadcastService broadcastService;

    // ------------------------------------------------------------------ helpers

    private static final String REQUEST_ID  = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10";
    private static final String ROOM_ID     = "room-demo-01";
    private static final String SESSION_ID  = "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90";

    /** Builds a minimal success ExecutionResult for test setup. */
    private ExecutionResult successResult(String stdout) {
        return new ExecutionResult(stdout, "", 0, 15.0, 4.2,
                false, false, null, false, null);
    }

    /** Builds a base JSON request body. */
    private String body(String language, String code, String stdin) {
        String stdinJson = (stdin == null) ? "" :
                String.format(",\n  \"stdin\": \"%s\"", stdin.replace("\"", "\\\""));
        return String.format("""
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "%s",
                  "code":       "%s"%s
                }
                """, REQUEST_ID, ROOM_ID, SESSION_ID, language,
                code.replace("\"", "\\\""), stdinJson);
    }

    // ------------------------------------------------------------------ 1. success

    @Test
    @DisplayName("Success run: stdout 'hello\\n', stderr empty, exit 0 (US-08 DoD)")
    void run_success_returnsHello() throws Exception {
        when(codeRunner.execute(eq("python"), anyString(), anyString()))
                .thenReturn(successResult("hello\n"));

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("python", "print('hello')", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                .andExpect(jsonPath("$.room_id").value(ROOM_ID))
                .andExpect(jsonPath("$.language").value("python"))
                .andExpect(jsonPath("$.stdout").value("hello\n"))
                .andExpect(jsonPath("$.stderr").value(""))
                .andExpect(jsonPath("$.exit_code").value(0))
                .andExpect(jsonPath("$.timed_out").value(false))
                .andExpect(jsonPath("$.oom_killed").value(false))
                .andExpect(jsonPath("$.failure_kind").isEmpty())
                .andExpect(jsonPath("$.output_truncated").value(false));
    }

    // ------------------------------------------------------------------ 2. failure

    @Test
    @DisplayName("Failure run: stderr 'boom', non-zero exit, failure_kind 'non_zero_exit' (US-08 DoD)")
    void run_failure_returnsBoomAndNonZeroExit() throws Exception {
        ExecutionResult failure = new ExecutionResult(
                "", "boom\n", 2, 12.0, 4.0,
                false, false, "non_zero_exit", false, null);
        when(codeRunner.execute(anyString(), anyString(), anyString()))
                .thenReturn(failure);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("python",
                                "import sys; sys.stderr.write('boom'); sys.exit(2)", "")))
                .andExpect(status().isOk())                          // still HTTP 200 (FR4.2)
                .andExpect(jsonPath("$.stderr").value("boom\n"))
                .andExpect(jsonPath("$.exit_code").value(2))
                .andExpect(jsonPath("$.failure_kind").value("non_zero_exit"))
                .andExpect(jsonPath("$.stdout").value(""));
    }

    // ------------------------------------------------------------------ 3. stdin

    @Test
    @DisplayName("Stdin run: stdout 'ABC\\n' when stdin is 'abc' (US-08 DoD)")
    void run_withStdin_echoesUpperCase() throws Exception {
        when(codeRunner.execute(eq("python"), anyString(), eq("abc")))
                .thenReturn(successResult("ABC\n"));

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("python", "print(input().upper())", "abc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout").value("ABC\n"))
                .andExpect(jsonPath("$.stderr").value(""))
                .andExpect(jsonPath("$.exit_code").value(0));
    }

    // ------------------------------------------------------------------ 4. compile error

    @Test
    @DisplayName("Compile error: error text in stderr, failure_kind 'compile_error', HTTP 200 (FR4.2)")
    void run_syntaxError_compileErrorInStderr() throws Exception {
        ExecutionResult compileError = new ExecutionResult(
                "", "SyntaxError: unexpected EOF\n", 1, 0.0, 0.0,
                false, false, "compile_error", false, null);
        when(codeRunner.execute(anyString(), anyString(), anyString()))
                .thenReturn(compileError);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("python", "def bad(:", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stderr").value("SyntaxError: unexpected EOF\n"))
                .andExpect(jsonPath("$.failure_kind").value("compile_error"))
                .andExpect(jsonPath("$.stdout").value(""))
                .andExpect(jsonPath("$.exit_code").value(1));
    }

    // ------------------------------------------------------------------ 5. FR4.5 broadcast

    @Test
    @DisplayName("FR4.5: broadcast is called exactly once with the room_id after execution")
    void run_success_broadcastCalledWithRoomId() throws Exception {
        when(codeRunner.execute(anyString(), anyString(), anyString()))
                .thenReturn(successResult("hello\n"));

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("python", "print('hello')", "")))
                .andExpect(status().isOk());

        // Verify broadcast was called once with the correct room_id
        ArgumentCaptor<String>      roomCaptor     = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RunResponse> responseCaptor = ArgumentCaptor.forClass(RunResponse.class);
        verify(broadcastService, times(1))
                .broadcast(roomCaptor.capture(), responseCaptor.capture());

        assertThat(roomCaptor.getValue()).isEqualTo(ROOM_ID);
        assertThat(responseCaptor.getValue().getRequest_id()).isEqualTo(REQUEST_ID);
    }

    // ------------------------------------------------------------------ 6. missing code → 400

    @Test
    @DisplayName("Missing 'code' field returns 400 INVALID_REQUEST (T-06 §7)")
    void run_missingCode_returns400() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python"
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());

        verifyNoInteractions(codeRunner, broadcastService);
    }

    // ------------------------------------------------------------------ 7. missing language → 400

    @Test
    @DisplayName("Missing 'language' field returns 400 INVALID_REQUEST")
    void run_missingLanguage_returns400() throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "code":       "print('hi')"
                }
                """.formatted(REQUEST_ID, ROOM_ID, SESSION_ID);

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        verifyNoInteractions(codeRunner, broadcastService);
    }

    // ------------------------------------------------------------------ 8. unsupported language → 422

    @Test
    @DisplayName("Unsupported language returns 422 UNSUPPORTED_LANGUAGE (DR1)")
    void run_unsupportedLanguage_returns422() throws Exception {
        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ruby", "puts 'hi'", "")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_LANGUAGE"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("ruby")));

        verifyNoInteractions(codeRunner, broadcastService);
    }

    // ------------------------------------------------------------------ 9. all five DR1 languages

    @Test
    @DisplayName("All five DR1 languages are accepted (DR1)")
    void run_allFiveLanguages_return200() throws Exception {
        for (String lang : new String[]{"cpp", "python", "c", "java", "javascript"}) {
            when(codeRunner.execute(eq(lang), anyString(), anyString()))
                    .thenReturn(successResult("ok\n"));

            mockMvc.perform(post("/v1/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(lang, "// test", "")))
                    .andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ 10. all required result fields

    @Test
    @DisplayName("Response always contains all T-06 §4.2 required fields")
    void run_response_allRequiredFieldsPresent() throws Exception {
        when(codeRunner.execute(anyString(), anyString(), anyString()))
                .thenReturn(successResult("hello\n"));

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("python", "print('hello')", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").exists())
                .andExpect(jsonPath("$.room_id").exists())
                .andExpect(jsonPath("$.language").exists())
                .andExpect(jsonPath("$.stdout").exists())
                .andExpect(jsonPath("$.stderr").exists())
                .andExpect(jsonPath("$.exit_code").exists())
                .andExpect(jsonPath("$.execution_time_ms").exists())
                .andExpect(jsonPath("$.peak_memory_mb").exists())
                .andExpect(jsonPath("$.timed_out").exists())
                .andExpect(jsonPath("$.oom_killed").exists())
                .andExpect(jsonPath("$.output_truncated").exists());
    }
}
