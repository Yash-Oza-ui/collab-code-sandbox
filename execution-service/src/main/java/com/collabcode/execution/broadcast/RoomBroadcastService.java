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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers a run result to participants in a room via sync-service (T-06 §5, T-17 V2.9, V2.10).
 *
 * Endpoint:
 *   POST {SYNC_SERVICE_INTERNAL_URL}/internal/rooms/{room_id}/messages
 *   X-Internal-Token: {INTERNAL_API_TOKEN}
 *
 * In Pair mode: message is broadcast to the whole room (visibility: "room").
 * In Open mode: message carries targeted audience or visibility: "private".
 *
 * This call is best-effort. A failure (sync-service down, wrong token, etc.)
 * is logged as a warning, but the caller's HTTP 200 response is never
 * affected (NFR9: execution result is delivered first).
 */
@Service
public class RoomBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(RoomBroadcastService.class);

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
     * Sends the run-result message to sync-service for the given room.
     */
    public void broadcast(String roomId, RunResponse response) {
        try {
            Map<String, Object> payload = buildPayload(response);
            String json = objectMapper.writeValueAsString(payload);

            String url = syncServiceInternalUrl
                    + "/internal/rooms/" + roomId + "/messages";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> httpResponse =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() == 200) {
                log.debug("broadcast: room={} status=200 body={}",
                        roomId, httpResponse.body());
            } else {
                log.warn("broadcast: room={} unexpected status={} body={}",
                        roomId, httpResponse.statusCode(), httpResponse.body());
            }

        } catch (Exception e) {
            log.warn("broadcast: room={} failed to deliver run-result to sync-service: {}",
                    roomId, e.getMessage());
        }
    }

    private Map<String, Object> buildPayload(RunResponse r) {
        Map<String, Object> m = new LinkedHashMap<>();
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
