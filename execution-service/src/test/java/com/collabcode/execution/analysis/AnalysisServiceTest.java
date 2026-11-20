package com.collabcode.execution.analysis;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AnalysisServiceTest {

    private AnalysisProperties properties;
    private RoomBroadcastService broadcastService;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;
    private ExecutorService executorService;
    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        properties = new AnalysisProperties();
        properties.setUrl("http://ai-ml-service:8000");
        properties.setTimeoutSeconds(2);

        broadcastService = mock(RoomBroadcastService.class);
        objectMapper = new ObjectMapper();
        httpClient = mock(HttpClient.class);
        executorService = Executors.newSingleThreadExecutor();

        analysisService = new AnalysisService(properties, broadcastService, objectMapper, httpClient, executorService);
    }

    @ParameterizedTest
    @CsvSource({
            "CONSENT_MISSING, AI analysis is unavailable because AI consent is turned off.",
            "AI_DISABLED, AI analysis is currently disabled.",
            "COST_CAP_REACHED, AI analysis is temporarily unavailable because the monthly AI limit has been reached.",
            "ROOM_RATE_LIMITED, AI analysis is temporarily unavailable for this room. Please try again later.",
            "RATE_LIMITED_PROVIDER, AI analysis is temporarily unavailable. Please try again later.",
            "PROVIDER_ERROR, AI analysis could not be completed right now.",
            "PROVIDER_TIMEOUT, AI analysis took too long and was skipped."
    })
    @DisplayName("Blocked ai_step with known error codes emits fallback with safe message")
    void testBlockedAiStepKnownCodes(String errorCode, String expectedMsg) throws Exception {
        String jsonResponse = String.format("""
                {
                  "request_id": "req-1",
                  "analysis_id": "an-1",
                  "room_id": "room-1",
                  "task": "complexity",
                  "ai_step": {
                    "status": "skipped",
                    "error_code": "%s"
                  },
                  "growth_rate": 1.99,
                  "estimated_complexity": "O(n^2)",
                  "slow_patterns": [{"line": 4, "pattern": "nested_loops"}]
                }
                """, errorCode);

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(jsonResponse);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        AnalysisResultMessage result = analysisService.performAnalysis(
                "req-1", "an-1", "room-1", "sess-1", "room",
                "complexity", "python", false, true, Collections.emptyMap()
        );

        assertThat(result.getStatus()).isEqualTo("fallback");
        assertThat(result.getAi_step().getStatus()).isEqualTo("skipped");
        assertThat(result.getAi_step().getError_code()).isEqualTo(errorCode);
        assertThat(result.getMessage()).isEqualTo(expectedMsg);

        // Verify AI-free deterministic results are preserved
        assertThat(result.getResult()).containsKey("estimated_complexity");
        assertThat(result.getResult().get("estimated_complexity")).isEqualTo("O(n^2)");
        assertThat(result.getResult()).containsKey("slow_patterns");

        // Verify correlation identifiers
        assertThat(result.getRequest_id()).isEqualTo("req-1");
        assertThat(result.getAnalysis_id()).isEqualTo("an-1");
        assertThat(result.getRoom_id()).isEqualTo("room-1");

        // Verify broadcast was called
        verify(broadcastService, times(1)).broadcastAnalysisResult(
                eq("room-1"), eq("sess-1"), eq("room"), eq(result)
        );
    }

    @Test
    @DisplayName("Unknown error code in skipped ai_step falls back to safe generic message")
    void testUnknownErrorCodeFallback() throws Exception {
        String jsonResponse = """
                {
                  "request_id": "req-1",
                  "analysis_id": "an-1",
                  "room_id": "room-1",
                  "task": "complexity",
                  "ai_step": {
                    "status": "skipped",
                    "error_code": "STRANGE_INTERNAL_EXCEPTION_XYZ"
                  }
                }
                """;

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(jsonResponse);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        AnalysisResultMessage result = analysisService.performAnalysis(
                "req-1", "an-1", "room-1", "sess-1", "room",
                "complexity", "python", true, true, Collections.emptyMap()
        );

        assertThat(result.getStatus()).isEqualTo("fallback");
        assertThat(result.getMessage()).isEqualTo("AI analysis could not be completed right now.");
        assertThat(result.getMessage()).doesNotContain("STRANGE_INTERNAL_EXCEPTION_XYZ");
    }

    @Test
    @DisplayName("Connection failure (ai-ml-service unreachable) returns safe fallback without crashing")
    void testConnectionFailureReturnsFallback() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new ConnectException("Connection refused to http://ai-ml-service:8000"));

        AnalysisResultMessage result = analysisService.performAnalysis(
                "req-1", "an-1", "room-1", "sess-1", "room",
                "complexity", "python", true, true, Collections.emptyMap()
        );

        assertThat(result.getStatus()).isEqualTo("fallback");
        assertThat(result.getAi_step().getStatus()).isEqualTo("skipped");
        assertThat(result.getMessage()).isEqualTo("AI analysis service is temporarily unavailable.");
        assertThat(result.getMessage()).doesNotContain("Connection refused");
        assertThat(result.getMessage()).doesNotContain("ai-ml-service:8000");

        verify(broadcastService, times(1)).broadcastAnalysisResult(
                eq("room-1"), eq("sess-1"), eq("room"), eq(result)
        );
    }

    @Test
    @DisplayName("HttpTimeoutException triggers PROVIDER_TIMEOUT fallback within bounded wait")
    void testTimeoutReturnsFallback() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("request timed out after 2000 ms"));

        AnalysisResultMessage result = analysisService.performAnalysis(
                "req-1", "an-1", "room-1", "sess-1", "room",
                "complexity", "python", true, true, Collections.emptyMap()
        );

        assertThat(result.getStatus()).isEqualTo("fallback");
        assertThat(result.getAi_step().getStatus()).isEqualTo("skipped");
        assertThat(result.getAi_step().getError_code()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(result.getMessage()).isEqualTo("AI analysis took too long and was skipped.");
        assertThat(result.getMessage()).doesNotContain("2000 ms");

        verify(broadcastService, times(1)).broadcastAnalysisResult(
                eq("room-1"), eq("sess-1"), eq("room"), eq(result)
        );
    }

    @Test
    @DisplayName("500 Internal Server Error from upstream returns safe SERVICE_UNAVAILABLE fallback")
    void testUpstream500ReturnsFallback() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("Internal error details with stack trace");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        AnalysisResultMessage result = analysisService.performAnalysis(
                "req-1", "an-1", "room-1", "sess-1", "room",
                "complexity", "python", true, true, Collections.emptyMap()
        );

        assertThat(result.getStatus()).isEqualTo("fallback");
        assertThat(result.getMessage()).isEqualTo("AI analysis service is temporarily unavailable.");
        assertThat(result.getMessage()).doesNotContain("stack trace");
    }

    @Test
    @DisplayName("Audience routing matches run result visibility: Open mode sends to private session only")
    void testAudienceRoutingPrivateMode() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new ConnectException("Service unavailable"));

        analysisService.performAnalysis(
                "req-1", "an-1", "room-open-mode", "runner-session-123", "private",
                "complexity", "python", true, true, Collections.emptyMap()
        );

        verify(broadcastService, times(1)).broadcastAnalysisResult(
                eq("room-open-mode"),
                eq("runner-session-123"),
                eq("private"),
                any(AnalysisResultMessage.class)
        );
    }

    @Test
    @DisplayName("AI step success emits status ok and does not produce fallback")
    void testSuccessResult() throws Exception {
        String jsonResponse = """
                {
                  "request_id": "req-1",
                  "analysis_id": "an-1",
                  "room_id": "room-1",
                  "task": "complexity",
                  "status": "ok",
                  "ai_step": {
                    "status": "ok",
                    "error_code": null
                  },
                  "growth_rate": 1.99,
                  "estimated_complexity": "O(n^2)",
                  "message": "Complexity estimated as O(n^2)."
                }
                """;

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(jsonResponse);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        AnalysisResultMessage result = analysisService.performAnalysis(
                "req-1", "an-1", "room-1", "sess-1", "room",
                "complexity", "python", true, true, Collections.emptyMap()
        );

        assertThat(result.getStatus()).isEqualTo("ok");
        assertThat(result.getAi_step().getStatus()).isEqualTo("ok");
        assertThat(result.getAi_step().getError_code()).isNull();
        assertThat(result.getMessage()).isEqualTo("Complexity estimated as O(n^2).");
        assertThat(result.getResult()).containsKey("estimated_complexity");

        verify(broadcastService, times(1)).broadcastAnalysisResult(
                eq("room-1"), eq("sess-1"), eq("room"), eq(result)
        );
    }
}
