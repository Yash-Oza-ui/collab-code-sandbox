package com.collabcode.syncservice.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** US-56: bounded, idempotent best-effort room-ended delivery. No user data in payload or logs. */
@Component
public class RoomEndedNotifier {
    private static final Logger log = LoggerFactory.getLogger(RoomEndedNotifier.class);
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String executionUrl;
    private final String token;
    private final long retryMs;

    @Autowired
    public RoomEndedNotifier(@Value("${app.room-cleanup.execution-url:http://localhost:8082}") String executionUrl,
                             @Value("${INTERNAL_API_TOKEN:}") String token,
                             @Value("${app.room-cleanup.retry-delay-ms:1000}") long retryMs,
                             ObjectMapper mapper) {
        this(executionUrl, token, retryMs, mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    RoomEndedNotifier(String executionUrl, String token, long retryMs,
                      ObjectMapper mapper, HttpClient http) {
        this.executionUrl = executionUrl.replaceAll("/+$", "");
        this.token = token;
        this.retryMs = retryMs;
        this.mapper = mapper;
        this.http = http;
    }

    public void queueRoomEnded(String roomId, String reason) {
        pending.putIfAbsent(roomId, new Pending(reason, 0, 0L));
    }

    @Scheduled(fixedDelayString = "${app.room-cleanup.check-interval-ms:500}")
    public void retryPending() {
        for (var entry : pending.entrySet()) {
            String roomId = entry.getKey();
            Pending state = entry.getValue();
            long now = System.currentTimeMillis();
            if (state.nextAt > now) continue;
            // Atomic claim: scheduled invocations cannot duplicate an attempt.
            if (!pending.replace(roomId, state, new Pending(state.reason, state.attempts, Long.MAX_VALUE))) continue;
            int attempt = state.attempts + 1;
            boolean delivered = notifyOnce(roomId, state.reason, attempt);
            if (delivered || attempt >= 3) {
                pending.remove(roomId);
                if (!delivered) log.error("US-56 remote cleanup exhausted room={} attempts={}", roomId, attempt);
            } else {
                long backoff = retryMs * (1L << (attempt - 1));
                pending.put(roomId, new Pending(state.reason, attempt, System.currentTimeMillis() + backoff));
            }
        }
    }

    private boolean notifyOnce(String roomId, String reason, int attempt) {
        if (token == null || token.isBlank()) {
            log.warn("US-56 cleanup token not configured room={} attempt={}", roomId, attempt);
            return false;
        }
        try {
            String body = mapper.writeValueAsString(Map.of("room_id", roomId, "reason", reason));
            HttpRequest req = HttpRequest.newBuilder(URI.create(executionUrl + "/internal/rooms/" + roomId + "/ended"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            log.info("US-56 room-ended room={} attempt={} http_status={}", roomId, attempt, status);
            return status == 200;
        } catch (Exception ex) {
            log.warn("US-56 room-ended unavailable room={} attempt={}", roomId, attempt);
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private record Pending(String reason, int attempts, long nextAt) { }
}
