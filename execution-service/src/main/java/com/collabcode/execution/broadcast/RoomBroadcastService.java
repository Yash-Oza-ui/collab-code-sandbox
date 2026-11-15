package com.collabcode.execution.broadcast;

import com.collabcode.execution.model.RunResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers a run result to participants in a room via sync-service (T-06 §5, T-17 V2.9, V2.10).
 *
 * Endpoint:
 *   POST {SYNC_SERVICE_INTERNAL_URL}/internal/rooms/{room_id}/messages
 *   X-Internal-Token: {INTERNAL_API_TOKEN}
 *
 * Audience (T-17 V2.10):
 *   - In Pair mode: message is broadcast with audience {"kind":"room"}.
 *   - In Open mode: message carries targeted session audience {"kind":"sessions","session_ids":[sessionId]}.
 *
 * Output limits (T-17 V2.9):
 *   - The forwarded frame is capped at sync-service's 4 MiB frame limit. If the serialized
 *     message exceeds 4 MiB, output is truncated and output_truncated is marked true.
 *
 * This call is best-effort. A failure (sync-service down, wrong token, etc.)
 * is logged as a warning, but the caller's HTTP 200 response is never
 * affected (NFR9: execution result is delivered first).
 */
@Service
public class RoomBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(RoomBroadcastService.class);
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024; // 4 MiB frame limit (T-17 V2.10)

    @Value("${app.sync-service.internal-url:http://localhost:8081}")
    private String syncServiceInternalUrl;

    @Value("${app.sync-service.internal-token:}")
    private String internalToken;

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient;

    public RoomBroadcastService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Backward-compatible overload. Defaults to room audience if visibility is not "private".
     */
    public void broadcast(String roomId, RunResponse response) {
        broadcast(roomId, null, response);
    }

    /**
     * Sends the run-result message to sync-service for the given room with targeted audience.
     */
    public void broadcast(String roomId, String sessionId, RunResponse response) {
        try {
            Map<String, Object> payload = buildPayload(response, sessionId);
            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);

            // Enforce 4 MiB frame limit
            if (jsonBytes.length > MAX_FRAME_BYTES) {
                payload.put("output_truncated", true);
                String stdout = response.getStdout() != null ? response.getStdout() : "";
                int excess = jsonBytes.length - MAX_FRAME_BYTES + 1024;
                if (stdout.length() > excess) {
                    payload.put("stdout", stdout.substring(0, stdout.length() - excess));
                } else {
                    payload.put("stdout", "");
                }
                jsonBytes = objectMapper.writeValueAsBytes(payload);
            }

            String url = syncServiceInternalUrl + "/internal/rooms/" + roomId + "/messages";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBytes))
                    .build();

            HttpResponse<String> httpResponse =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() == 200) {
                log.debug("broadcast: room={} status=200 body={}", roomId, httpResponse.body());
            } else {
                log.warn("broadcast: room={} unexpected status={} body={}",
                        roomId, httpResponse.statusCode(), httpResponse.body());
            }

        } catch (Exception e) {
            log.warn("broadcast: room={} failed to deliver run-result to sync-service: {}",
                    roomId, e.getMessage());
        }
    }

    public Map<String, Object> buildPayload(RunResponse r, String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();

        // T-17 V2.10: audience specification
        Map<String, Object> audience = new LinkedHashMap<>();
        if ("private".equalsIgnoreCase(r.getVisibility())) {
            audience.put("kind", "sessions");
            audience.put("session_ids", sessionId != null ? List.of(sessionId) : Collections.emptyList());
        } else {
            audience.put("kind", "room");
        }
        m.put("audience", audience);

        m.put("type",               "run-result");
        m.put("request_id",         r.getRequest_id());
        m.put("room_id",            r.getRoom_id());
        m.put("language",           r.getLanguage());
        m.put("stdout",             r.getStdout());
        m.put("stderr",             r.getStderr());
        m.put("exit_code",          r.getExit_code());
        m.put("execution_time_ms",  r.getExecution_time_ms());
        m.put("peak_memory_mb",     r.getPeak_memory_mb());
        m.put("timed_out",          r.isTimed_out());
        m.put("oom_killed",         r.isOom_killed());
        m.put("failure_kind",       r.getFailure_kind());
        m.put("output_truncated",   r.isOutput_truncated());
        m.put("message",            r.getMessage());
        m.put("compile_time_ms",    r.getCompile_time_ms());
        m.put("runner",             r.getRunner());
        m.put("ran_at",             r.getRan_at());
        m.put("visibility",         r.getVisibility());
        m.put("saved_versions",     r.getSaved_versions());
        m.put("failure",            r.getFailure());
        return m;
    }
}
