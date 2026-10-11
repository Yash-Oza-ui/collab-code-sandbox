package com.collabcode.execution.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * T-03 controller acceptance tests – Jay Limbasiya (feature/T-03-execution-scaffold).
 *
 * Covers:
 *   1. Valid request returns 200 with stdout, stderr and all result fields (T-03 AC3)
 *   2. Missing 'code' field returns 4xx (T-03 AC4)
 *   3. Missing 'language' returns 4xx (T-03 AC4)
 *   4. Unsupported language returns 4xx (T-03 AC4, DR1)
 *
 * No Docker calls are made. All tests run offline.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ valid request

    @Test
    @DisplayName("Valid request returns 200 with stdout and stderr present (T-03 AC3, FR4.2)")
    void run_validRequest_returns200WithStdoutAndStderr() throws Exception {
        String body = """
                {
                  "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
                  "room_id":    "room-demo-01",
                  "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
                  "language":   "python",
                  "code":       "print('hello')",
                  "stdin":      ""
                }
                """;

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value("0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10"))
                .andExpect(jsonPath("$.room_id").value("room-demo-01"))
                .andExpect(jsonPath("$.language").value("python"))
                // FR4.2: stdout and stderr must always be present
                .andExpect(jsonPath("$.stdout").exists())
                .andExpect(jsonPath("$.stderr").exists())
                // Other required result fields (T-06 section 4.2)
                .andExpect(jsonPath("$.exit_code").exists())
                .andExpect(jsonPath("$.execution_time_ms").exists())
                .andExpect(jsonPath("$.peak_memory_mb").exists())
                .andExpect(jsonPath("$.timed_out").exists())
                .andExpect(jsonPath("$.oom_killed").exists())
                .andExpect(jsonPath("$.output_truncated").exists());
    }

    @Test
    @DisplayName("All five DR1 languages are accepted")
    void run_allSupportedLanguages_return200() throws Exception {
        for (String lang : new String[]{"cpp", "python", "c", "java", "javascript"}) {
            String body = String.format("""
                    {
                      "request_id": "aaaaaaaa-bbbb-4ccc-dddd-eeeeeeeeeeee",
                      "room_id":    "room-test",
                      "session_id": "ffffffff-0000-4111-aaaa-bbbbbbbbbbbb",
                      "language":   "%s",
                      "code":       "// test"
                    }
                    """, lang);

            mockMvc.perform(post("/v1/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ missing fields → 4xx

    @Test
    @DisplayName("Missing 'code' returns 400 with clear message (T-03 AC4)")
    void run_missingCode_returns400() throws Exception {
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    @Test
    @DisplayName("Missing 'language' returns 400 with clear message (T-03 AC4)")
    void run_missingLanguage_returns400() throws Exception {
        String body = """
                {
                  "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
                  "room_id":    "room-demo-01",
                  "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
                  "code":       "print('hi')"
                }
                """;

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // ------------------------------------------------------------------ unsupported language → 4xx

    @Test
    @DisplayName("Unsupported language returns 422 with clear message (T-03 AC4, DR1)")
    void run_unsupportedLanguage_returns422() throws Exception {
        String body = """
                {
                  "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
                  "room_id":    "room-demo-01",
                  "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
                  "language":   "ruby",
                  "code":       "puts 'hi'"
                }
                """;

        mockMvc.perform(post("/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_LANGUAGE"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("ruby")));
    }
}
