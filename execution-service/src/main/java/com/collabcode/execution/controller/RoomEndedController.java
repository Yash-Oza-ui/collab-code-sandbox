package com.collabcode.execution.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

/** US-56: authenticated, idempotent room-ending notification from sync-service. */
@RestController
public class RoomEndedController {
    private final String token;
    private final String aiUrl;
    private final HttpClient http;

    @Autowired
    public RoomEndedController(@Value("${app.internal.token:}") String token,
                               @Value("${app.ai-ml-service.url:http://localhost:8000}") String aiUrl) {
        this(token, aiUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    RoomEndedController(String token, String aiUrl, HttpClient http) {
        this.token = token;
        this.aiUrl = aiUrl.replaceAll("/+$", "");
        this.http = http;
    }

    @PostMapping("/internal/rooms/{roomId}/ended")
    public ResponseEntity<Map<String, Object>> roomEnded(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Internal-Token", required = false) String suppliedToken,
            @RequestBody(required = false) Map<String, Object> body) {
        // Reject empty configuration and compare constant-time; never log tokens.
        if (token == null || token.isBlank() || suppliedToken == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "UNAUTHORIZED"));
        }
        if (!roomId.matches("[A-Za-z0-9_-]{1,64}") || body == null
                || !roomId.equals(body.get("room_id"))
                || !("closed".equals(body.get("reason")) || "deleted".equals(body.get("reason")))) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "INVALID_REQUEST"));
        }
        try {
            // Repeated DELETE is harmless; execution-service has no persistent per-room run store.
            HttpRequest request = HttpRequest.newBuilder(URI.create(aiUrl + "/v1/rooms/" + roomId + "/state"))
                    .timeout(Duration.ofSeconds(3))
                    .header("X-Internal-Token", token)
                    .DELETE().build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 200) {
                return ResponseEntity.ok(Map.of("room_id", roomId, "status", "cleared"));
            }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "ROOM_STATE_CLEANUP_UNAVAILABLE"));
    }
}
