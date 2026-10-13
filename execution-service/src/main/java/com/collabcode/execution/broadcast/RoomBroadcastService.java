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
 * Delivers a run result to every participant in a room via sync-service.
 *
 * T-06 section 5: after execution finishes, execution-service POSTs the
 * run-result JSON to sync-service's internal endpoint. sync-service then
 * forwards it as a text frame to every WebSocket connection in the room.
 *
 * Endpoint:
 *   POST {SYNC_SERVICE_INTERNAL_URL}/internal/rooms/{room_id}/messages
 *   X-Internal-Token: {INTERNAL_API_TOKEN}
 *
 * This call is best-effort. A failure (sync-service down, wrong token, etc.)
 * is logged as a warning, but the caller's HTTP 200 response is never
 * affected (NFR9: execution result is never delayed by anything after it).
 */
@Service
public class RoomBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(RoomBroadcastService.class);

    /**
     * Base URL of sync-service. Read from environment variable
     * SYNC_SERVICE_INTERNAL_URL (T-06 section 1).
     * Default: http://localhost:8081 for local dev.
     */
    @Value("${app.sync-service.internal-url:http://localhost:8081}")
    private String syncServiceInternalUrl;

    /**
     * Shared secret protecting the internal endpoint (T-06 section 5).
     * Read from environment variable INTERNAL_API_TOKEN.
     * Defaults to empty string so local dev without the variable set
     * fails gracefully with a logged warning rather than an NPE.
     */
    @Value("${app.sync-service.internal-token:}")
    private String internalToken;

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient;

    public RoomBroadcastService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // Single reusable HttpClient; connects in under a second for localhost.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * Sends the run-result message to sync-service for the given room.
     *
     * Failures are logged and swallowed so callers are never blocked (NFR9).
     *
     * @param roomId   room to broadcast to
     * @param response the full run result (T-06 §4.2)
     */
    public void broadcast(String roomId, RunResponse response) {
        try {
            // Build the "run-result" text frame body (T-06 section 3.6).
            // We add "type" on top of the existing RunResponse fields.
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
            // Best-effort: never let a broadcast failure propagate to the caller.
            log.warn("broadcast: room={} failed to deliver run-result to sync-service: {}",
                    roomId, e.getMessage());
        }
    }

    // ----- private helpers -----

    /**
     * Builds the JSON body for the internal endpoint (T-06 section 3.6).
     * The "type" field is added here; all other fields come from RunResponse.
     */
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
        return m;
    }
}
