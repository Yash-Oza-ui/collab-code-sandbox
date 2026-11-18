package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-64 DoD verification: changing both repeated-run budget values in configuration
 * (aggregate-time-budget-ms and max-runs-per-request) directly changes runtime
 * behaviour of POST /internal/runs without any code change (DR3, NFR14).
 *
 * Compared to InternalRunControllerTest (max-runs=3, aggregate-budget=200ms):
 *   - Here max-runs=5 allows a 4-input test_batch that failed at 3 above, and rejects 6.
 *   - Here aggregate-time-budget-ms=500 allows 3x120ms runs (~360ms) that failed at 200ms above,
 *     and rejects when a slow batch exceeds 500ms.
 */
@SpringBootTest(properties = {
        "app.internal.token=test-token-us64-override",
        "app.execution.limits.max-runs-per-request=5",
        "app.execution.limits.aggregate-time-budget-ms=500",
        "app.execution.limits.budget-retry-after-seconds=12"
})
@ActiveProfiles({"stub-permissions", "stub-runner"})
@AutoConfigureMockMvc
class InternalRunBudgetConfigOverrideTest {

    private static final String TOKEN = "test-token-us64-override";
    private static final String REQ_ID = "7a3e2b10-6c5d-4f8a-9e12-3456789abcde";
    private static final String ROOM_ID = "Zq8-Lw2Ef6Th0Yx4Rn7JbA";

    @Autowired private MockMvc mockMvc;
    @MockBean private RoomBroadcastService broadcastService;

    @Test
    @DisplayName("Higher configured max-runs-per-request=5 permits 4 runs and refuses 6th with configured retry-after")
    void changedMaxRunsConfig_changesBehaviour() throws Exception {
        String fourInputs = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "print(input())",
                  "inputs": ["1", "2", "3", "4"]
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fourInputs))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch_results.length()").value(4));

        String sixInputs = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "print(input())",
                  "inputs": ["1", "2", "3", "4", "5", "6"]
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sixInputs))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(jsonPath("$.error.code").value("RUN_BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.error.reason").value("max_runs_per_request"))
                .andExpect(jsonPath("$.error.message")
                        .value("Repeated-run cap of 5 runs per request exceeded (NFR14)."))
                .andExpect(jsonPath("$.error.retry_after_seconds").value(12));
    }

    @Test
    @DisplayName("Higher configured aggregate-time-budget-ms=500 permits 3x120ms runs and refuses 5x200ms runs")
    void changedAggregateTimeBudgetConfig_changesBehaviour() throws Exception {
        // 3 runs * ~120ms = ~360ms < 500ms -> succeeds
        String threeSlow = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "# delay 120",
                  "inputs": ["1", "2", "3"]
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(threeSlow))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch_results.length()").value(3));

        // 5 runs * ~200ms: after 3 runs (~600ms >= 500ms), run 4 is refused by aggregate budget
        String fiveSlower = """
                {
                  "request_id": "%s",
                  "room_id": "%s",
                  "purpose": "test_batch",
                  "language": "python",
                  "code": "# delay 200",
                  "inputs": ["1", "2", "3", "4", "5"]
                }
                """.formatted(REQ_ID, ROOM_ID);

        mockMvc.perform(post("/internal/runs")
                        .header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fiveSlower))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(jsonPath("$.error.code").value("RUN_BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.error.reason").value("aggregate_time_budget"))
                .andExpect(jsonPath("$.error.message")
                        .value("Aggregate repeated-run execution budget of 500ms exceeded (NFR14)."))
                .andExpect(jsonPath("$.error.retry_after_seconds").value(12));
    }
}
