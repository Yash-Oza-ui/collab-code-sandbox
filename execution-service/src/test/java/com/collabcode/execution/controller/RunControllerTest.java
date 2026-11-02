package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.collabcode.execution.model.RunSpec;
import com.collabcode.execution.runner.CodeRunner;
import com.collabcode.execution.runner.ExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RunController tests updated for T-19 spec object (Jay Limbasiya).
 *
 * Covers:
 *   1. Health endpoint returns 200
 *   2. Valid inline request returns 200 with stdout, stderr and all result fields
 *   3. Legacy 'code' field is rejected with 422 INVALID_REQUEST
 *   4. Missing 'source' returns 422 INVALID_REQUEST
 *   5. Missing 'language' returns 422 INVALID_REQUEST
 *   6. Unsupported language returns 422 UNSUPPORTED_LANGUAGE
 *   7. All five DR1 languages are accepted
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CodeRunner codeRunner;

    @MockBean
    private RoomBroadcastService broadcastService;

    private static ExecutionResult okResult() {
        return new ExecutionResult("Hello from python!\n", "", 0,
                15.0, 4.2, false, false, null, false, null);
    }

    // ------------------------------------------------------------------ health

    @Test
    @DisplayName("Health endpoint returns HTTP 200 (T-06 section 1)")
    void health_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ valid request

    @Test
    @DisplayName("Valid inline request returns 200 with stdout and stderr present (T-19, FR4.2)")
    void run_validRequest_returns200WithStdoutAndStderr() throws Exception {
        when(codeRunner.execute(any(RunSpec.class)))
                .thenReturn(okResult());

        String body = """
                {
                  "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
                  "room_id":    "room-demo-01",
                  "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
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
                """;

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value("0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10"))
                .andExpect(jsonPath("$.room_id").value("room-demo-01"))
                .andExpect(jsonPath("$.language").value("python"))
                .andExpect(jsonPath("$.stdout").exists())
                .andExpect(jsonPath("$.stderr").exists())
                .andExpect(jsonPath("$.exit_code").exists())
                .andExpect(jsonPath("$.execution_time_ms").exists())
                .andExpect(jsonPath("$.peak_memory_mb").exists())
                .andExpect(jsonPath("$.timed_out").exists())
                .andExpect(jsonPath("$.oom_killed").exists())
                .andExpect(jsonPath("$.output_truncated").exists());
    }

    @Test
    @DisplayName("All five DR1 languages are accepted with inline source")
    void run_allSupportedLanguages_return200() throws Exception {
        when(codeRunner.execute(any(RunSpec.class)))
                .thenReturn(okResult());

        for (String lang : new String[]{"cpp", "python", "c", "java", "javascript"}) {
            String body = String.format("""
                    {
                      "request_id": "aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee",
                      "room_id":    "room-test",
                      "session_id": "ffffffff-0000-4111-aaaa-bbbbbbbbbbbb",
                      "language":   "%s",
                      "source": {
                        "kind": "inline",
                        "files": [
                          {
                            "name": "main.txt",
                            "content": "// test"
                          }
                        ],
                        "entry": "main.txt"
                      }
                    }
                    """, lang);

            mockMvc.perform(post("/v1/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ legacy code rejected

    @Test
    @DisplayName("Legacy 'code' field returns 422 INVALID_REQUEST (T-19 AC4)")
    void run_bodyWithLegacyCode_returns422() throws Exception {
        String body = """
                {
                  "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
                  "room_id":    "room-demo-01",
                  "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
                  "language":   "python",
                  "code":       "print('hello')"
                }
                """;

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value("0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Field 'code' is no longer accepted. Use 'source'."));
    }

    // ------------------------------------------------------------------ missing fields → 422 INVALID_REQUEST

    @Test
    @DisplayName("Missing 'source' returns 422 with clear message (T-19, T-06 section 7.1)")
    void run_missingSource_returns422() throws Exception {
        String body = """
                {
                  "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
                  "room_id":    "room-demo-01",
                  "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
                  "language":   "java"
                }
                """;

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value("0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Field 'source' is missing or invalid."));
    }

    @Test
    @DisplayName("Missing 'language' returns 422 with clear message (T-03 AC4, T-06 section 7.1)")
    void run_missingLanguage_returns422() throws Exception {
        String body = """
                {
                  "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
                  "room_id":    "room-demo-01",
                  "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
                  "source": {
                    "kind": "inline",
                    "files": [
                      {
                        "name": "main.py",
                        "content": "print('hi')"
                      }
                    ],
                    "entry": "main.py"
                  }
                }
                """;

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value("0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Field 'language' is missing or invalid."));
    }

    // ------------------------------------------------------------------ unsupported language → 422 UNSUPPORTED_LANGUAGE

    @Test
    @DisplayName("Unsupported language returns 422 with clear message (T-03 AC4, DR1, T-06 section 7.1)")
    void run_unsupportedLanguage_returns422() throws Exception {
        String body = """
                {
                  "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
                  "room_id":    "room-demo-01",
                  "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
                  "language":   "ruby",
                  "source": {
                    "kind": "inline",
                    "files": [
                      {
                        "name": "main.rb",
                        "content": "puts 'hi'"
                      }
                    ],
                    "entry": "main.rb"
                  }
                }
                """;

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.request_id").value("0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10"))
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_LANGUAGE"))
                .andExpect(jsonPath("$.error.message").value(
                        "Language 'ruby' is not supported. Use one of: cpp, python, c, java, javascript."));
    }
}
