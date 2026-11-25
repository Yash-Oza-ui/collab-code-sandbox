package com.collabcode.execution.analysis;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Asynchronous AI analysis execution and fallback management service (US-78, NFR9, CR08, T-18).
 *
 * Enforces:
 *   1. Non-blocking execution (NFR9): code execution results are always delivered first.
 *   2. Bounded wait: configured timeout (AnalysisProperties) prevents hung requests.
 *   3. Exactly-once emission: race conditions between timeout and late completion are guarded.
 *   4. Safe fallback mapping (US-78): error codes are mapped via FallbackMessageMapper.
 *   5. AI-free preservation: rule findings, complexity regression, or sanitizer details survive.
 *   6. Audience routing: results match the run result audience (Pair -> room, Open -> runner session).
 *   7. Privacy: no raw exceptions, stack traces, or secrets leak to the client.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final AnalysisProperties properties;
    private final RoomBroadcastService broadcastService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService executorService;
    private final com.collabcode.execution.store.RunResultStore runResultStore;

    @Autowired
    public AnalysisService(
            AnalysisProperties properties,
            RoomBroadcastService broadcastService,
            ObjectMapper objectMapper,
            @Autowired(required = false) com.collabcode.execution.store.RunResultStore runResultStore) {
        this(properties, broadcastService, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .build(), Executors.newCachedThreadPool(), runResultStore);
    }

    public AnalysisService(
            AnalysisProperties properties,
            RoomBroadcastService broadcastService,
            ObjectMapper objectMapper) {
        this(properties, broadcastService, objectMapper, null);
    }

    public AnalysisService(
            AnalysisProperties properties,
            RoomBroadcastService broadcastService,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            ExecutorService executorService) {
        this(properties, broadcastService, objectMapper, httpClient, executorService, null);
    }

    public AnalysisService(
            AnalysisProperties properties,
            RoomBroadcastService broadcastService,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            ExecutorService executorService,
            com.collabcode.execution.store.RunResultStore runResultStore) {
        this.properties = properties;
        this.broadcastService = broadcastService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.executorService = executorService;
        this.runResultStore = runResultStore;
    }

    /**
     * Launch asynchronous analysis in the background without delaying code execution (NFR9).
     *
     * @return CompletableFuture completing when the result or fallback message is broadcast.
     */
    public CompletableFuture<AnalysisResultMessage> performAnalysisAsync(
            String requestId,
            String analysisId,
            String roomId,
            String sessionId,
            String visibility,
            String task,
            String language,
            Boolean consent,
            Boolean aiEnabled,
            Map<String, Object> payload) {

        return CompletableFuture.supplyAsync(
                () -> performAnalysis(
                        requestId, analysisId, roomId, sessionId, visibility,
                        task, language, consent, aiEnabled, payload
                ),
                executorService
        );
    }

    /**
     * US-59: evaluate room AI permission on this service's executor, not on the
     * /v1/runs request thread. A failed lookup disables paid AI, never the
     * deterministic analysis. Caller consent is still enforced separately.
     */
    public CompletableFuture<AnalysisResultMessage> performAnalysisWithFreshSwitchAsync(
            String requestId,
            String analysisId,
            String roomId,
            String sessionId,
            String visibility,
            String task,
            String language,
            Boolean consent,
            BooleanSupplier aiEnabledSupplier,
            Map<String, Object> payload) {
        return CompletableFuture.supplyAsync(() -> {
            boolean enabled = false;
            try {
                enabled = aiEnabledSupplier != null && aiEnabledSupplier.getAsBoolean();
            } catch (Exception ignored) {
                // Network/lookup failures are fail closed for external AI.
            }
            return performAnalysis(requestId, analysisId, roomId, sessionId,
                    visibility, task, language, consent, enabled, payload);
        }, executorService);
    }

    /**
     * Perform analysis with bounded timeout, handle failures, and emit exactly once.
     */
    public AnalysisResultMessage performAnalysis(
            String requestId,
            String analysisId,
            String roomId,
            String sessionId,
            String visibility,
            String task,
            String language,
            Boolean consent,
            Boolean aiEnabled,
            Map<String, Object> payload) {

        String safeAnalysisId = (analysisId != null && !analysisId.isBlank())
                ? analysisId
                : UUID.randomUUID().toString();
        String safeTask = (task != null && !task.isBlank()) ? task : "complexity";

        AtomicBoolean emitted = new AtomicBoolean(false);
        int timeoutSeconds = Math.max(1, properties.getTimeoutSeconds());

        try {
            // Build target URL
            String endpointPath = mapTaskToEndpoint(safeTask);
            String targetUrl = properties.getUrl().replaceAll("/+$", "") + endpointPath;

            // Prepare outbound JSON body
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("request_id", requestId);
            requestBody.put("room_id", roomId);
            requestBody.put("analysis_id", safeAnalysisId);
            requestBody.put("request_type", safeTask);
            requestBody.put("language", language);
            if (payload != null) {
                requestBody.putAll(payload);
            }
            // These values come from the authenticated permission lookup and
            // caller consent, never from arbitrary analysis payload fields.
            requestBody.put("consent", Boolean.TRUE.equals(consent));
            requestBody.put("ai_enabled", Boolean.TRUE.equals(aiEnabled));
            requestBody.put("request_id", requestId);
            requestBody.put("room_id", roomId);
            requestBody.put("analysis_id", safeAnalysisId);
            requestBody.put("request_type", safeTask);
            requestBody.put("language", language);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(requestBody);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBytes))
                    .build();

            // Execute HTTP request with bounded wait
            HttpResponse<String> response;
            try {
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                log.warn("analysis timeout: request_id={} task={} timeout={}s", requestId, safeTask, timeoutSeconds);
                return emitFallbackOnce(
                        emitted, requestId, safeAnalysisId, roomId, sessionId, visibility,
                        safeTask, "PROVIDER_TIMEOUT", Collections.emptyMap()
                );
            } catch (ConnectException e) {
                log.warn("analysis connection failed: request_id={} url={}", requestId, targetUrl);
                return emitFallbackOnce(
                        emitted, requestId, safeAnalysisId, roomId, sessionId, visibility,
                        safeTask, "SERVICE_UNAVAILABLE", Collections.emptyMap()
                );
            } catch (IOException e) {
                log.warn("analysis io failure: request_id={} msg={}", requestId, e.getMessage());
                return emitFallbackOnce(
                        emitted, requestId, safeAnalysisId, roomId, sessionId, visibility,
                        safeTask, "SERVICE_UNAVAILABLE", Collections.emptyMap()
                );
            }

            // Handle HTTP status from ai-ml-service
            if (response.statusCode() == 200) {
                return processSuccessfulResponse(
                        emitted, response.body(), requestId, safeAnalysisId,
                        roomId, sessionId, visibility, safeTask
                );
            } else if (response.statusCode() >= 500) {
                log.warn("analysis upstream error {}: request_id={}", response.statusCode(), requestId);
                return emitFallbackOnce(
                        emitted, requestId, safeAnalysisId, roomId, sessionId, visibility,
                        safeTask, "SERVICE_UNAVAILABLE", Collections.emptyMap()
                );
            } else {
                log.warn("analysis unexpected status {}: request_id={}", response.statusCode(), requestId);
                return emitFallbackOnce(
                        emitted, requestId, safeAnalysisId, roomId, sessionId, visibility,
                        safeTask, "PROVIDER_ERROR", Collections.emptyMap()
                );
            }

        } catch (Exception e) {
            log.warn("analysis unexpected error: request_id={} ex={}", requestId, e.getClass().getSimpleName());
            return emitFallbackOnce(
                    emitted, requestId, safeAnalysisId, roomId, sessionId, visibility,
                    safeTask, "PROVIDER_ERROR", Collections.emptyMap()
                );
        }
    }

    private AnalysisResultMessage processSuccessfulResponse(
            AtomicBoolean emitted,
            String responseBody,
            String requestId,
            String analysisId,
            String roomId,
            String sessionId,
            String visibility,
            String task) {

        try {
            Map<String, Object> respMap = objectMapper.readValue(
                    responseBody,
                    new TypeReference<Map<String, Object>>() {}
            );

            // Extract ai_step
            Object rawAiStep = respMap.get("ai_step");
            String aiStatus = "ok";
            String errorCode = null;

            // Extract AI-free deterministic findings & task results
            Map<String, Object> resultPayload = extractResultPayload(respMap, task);

            if (rawAiStep instanceof Map<?, ?> aiMap) {
                Object s = aiMap.get("status");
                if (s instanceof String str) aiStatus = str;
                Object e = aiMap.get("error_code");
                if (e instanceof String errStr) errorCode = errStr;

                if (aiMap.containsKey("explanation")) {
                    resultPayload.put("explanation", aiMap.get("explanation"));
                }
                if (aiMap.containsKey("suggested_fix")) {
                    resultPayload.put("suggested_fix", aiMap.get("suggested_fix"));
                }
            }

            if ("skipped".equalsIgnoreCase(aiStatus) || errorCode != null) {
                // Blocked AI step -> produce fallback message (FR40.5, US-78)
                String fallbackMsg = FallbackMessageMapper.getFallbackMessage(errorCode);
                return emitFallbackOnce(
                        emitted, requestId, analysisId, roomId, sessionId, visibility,
                        task, errorCode, resultPayload, fallbackMsg
                );
            } else {
                // AI step succeeded -> deliver ok status
                String msg = (respMap.get("message") instanceof String s)
                        ? s
                        : (respMap.get("summary") instanceof String sum ? "Crash explained: " + sum : "Analysis completed successfully.");

                AnalysisResultMessage message = new AnalysisResultMessage(
                        requestId,
                        analysisId,
                        roomId,
                        task,
                        "ok",
                        new AnalysisResultMessage.AIStep("ok", null),
                        resultPayload,
                        msg
                );

                if (emitted.compareAndSet(false, true)) {
                    emitAnalysisMessage(requestId, roomId, sessionId, visibility, message);
                }
                return message;
            }

        } catch (Exception e) {
            log.warn("failed to parse analysis response body: request_id={}", requestId);
            return emitFallbackOnce(
                    emitted, requestId, analysisId, roomId, sessionId, visibility,
                    task, "PROVIDER_ERROR", Collections.emptyMap()
            );
        }
    }

    /**
     * Extract deterministic task payload while preserving AI-free findings.
     */
    public Map<String, Object> extractResultPayload(Map<String, Object> respMap, String task) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Exclude top-level envelope fields to isolate the task result payload
        Set<String> envelopeKeys = Set.of("request_id", "analysis_id", "room_id", "task", "status", "ai_step", "message");

        for (Map.Entry<String, Object> entry : respMap.entrySet()) {
            if (!envelopeKeys.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }

    private AnalysisResultMessage emitFallbackOnce(
            AtomicBoolean emitted,
            String requestId,
            String analysisId,
            String roomId,
            String sessionId,
            String visibility,
            String task,
            String errorCode,
            Map<String, Object> resultPayload) {

        String fallbackMessage = FallbackMessageMapper.getFallbackMessage(errorCode);
        return emitFallbackOnce(
                emitted, requestId, analysisId, roomId, sessionId, visibility,
                task, errorCode, resultPayload, fallbackMessage
        );
    }

    private AnalysisResultMessage emitFallbackOnce(
            AtomicBoolean emitted,
            String requestId,
            String analysisId,
            String roomId,
            String sessionId,
            String visibility,
            String task,
            String errorCode,
            Map<String, Object> resultPayload,
            String fallbackMessage) {

        // Canonical blocked error code for ai_step contract
        String safeErrorCode = (errorCode != null && !"SERVICE_UNAVAILABLE".equals(errorCode))
                ? errorCode
                : "PROVIDER_ERROR";

        AnalysisResultMessage message = new AnalysisResultMessage(
                requestId,
                analysisId,
                roomId,
                task,
                "fallback",
                new AnalysisResultMessage.AIStep("skipped", safeErrorCode),
                (resultPayload != null) ? resultPayload : Collections.emptyMap(),
                fallbackMessage
        );

        if (emitted.compareAndSet(false, true)) {
            emitAnalysisMessage(requestId, roomId, sessionId, visibility, message);
        }

        return message;
    }

    private void emitAnalysisMessage(
            String requestId,
            String roomId,
            String sessionId,
            String initialVisibility,
            AnalysisResultMessage message) {
        if (runResultStore != null) {
            runResultStore.addAnalysisMessage(requestId, message);
        }
        String effectiveVisibility = initialVisibility;
        if (runResultStore != null) {
            String current = runResultStore.getVisibility(requestId);
            if (current != null) {
                effectiveVisibility = current;
            }
        }
        if ("room".equalsIgnoreCase(effectiveVisibility)) {
            broadcastService.broadcastAnalysisResult(roomId, sessionId, "room", message);
            if (runResultStore != null) {
                runResultStore.get(requestId).ifPresent(r -> r.getRoomBroadcastAnalysisIds().add(message.getAnalysis_id()));
            }
        } else {
            broadcastService.broadcastAnalysisResult(roomId, sessionId, "private", message);
        }
    }

    private String mapTaskToEndpoint(String task) {
        if (task == null) return "/v1/analyses/complexity";
        return switch (task.toLowerCase()) {
            case "crash_explanation", "crash-explanation" -> "/v1/analyses/crash-explanation";
            case "slowdown" -> "/v1/analyses/slowdown";
            case "failing_input", "failing-input" -> "/v1/analyses/failing-input";
            default -> "/v1/analyses/complexity";
        };
    }
}
