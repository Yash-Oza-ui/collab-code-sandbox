package com.collabcode.execution.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * T-03 acceptance tests – Bhavya Vekariya (feature/T-03-execution-scaffold-limits-health).
 *
 * Covers:
 *   1. Health endpoint returns 200               (T-03 AC5, T-06 section 1)
 *   2. Valid request returns 200 with            (T-03 AC3)
 *      stdout, stderr and all result fields
 *   3. Missing 'code' field returns 422          (T-03 AC4, T-06 section 7.1)
 *   4. Missing 'language' returns 422            (T-03 AC4, T-06 section 7.1)
 *   5. Unsupported language returns 422          (T-03 AC4, DR1, T-06 section 7.1)
 *
 * No Docker calls are made. All tests run offline.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ health

    @Test
    @DisplayName("Health endpoint returns HTTP 200 (T-06 section 1)")
    void health_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

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

    // ------------------------------------------------------------------ missing fields → 422 INVALID_REQUEST

    @Test
    @DisplayName("Missing 'code' returns 422 with clear message (T-03 AC4, T-06 section 7.1)")
    void run_missingCode_returns422() throws Exception {
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
                .andExpect(jsonPath("$.error.message").value("Field 'code' is missing or invalid."));
    }

    @Test
    @DisplayName("Missing 'language' returns 422 with clear message (T-03 AC4, T-06 section 7.1)")
    void run_missingLanguage_returns422() throws Exception {
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
                  "code":       "puts 'hi'"
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
