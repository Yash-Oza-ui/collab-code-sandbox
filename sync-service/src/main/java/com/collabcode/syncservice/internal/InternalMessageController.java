package com.collabcode.syncservice.internal;

import com.collabcode.syncservice.room.RoomRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal HTTP endpoint used by execution-service to broadcast a run result
 * (or any future control message) to every WebSocket client in a room
 * (docs/api-contracts.md section 5).
 *
 * <pre>
 * POST /internal/rooms/{roomId}/messages
 * X-Internal-Token: <INTERNAL_API_TOKEN>
 * Content-Type: application/json
 * </pre>
 *
 * <ul>
 *   <li>Returns 401 if the token is missing or wrong.
 *   <li>Returns 400 if the body is not valid JSON or lacks a non-empty
 *       {@code "type"} string.
 *   <li>Returns 200 with {@code {"room_id":..., "delivered":N}} on success.
 *       {@code delivered == 0} is not an error (room may be empty).
 * </ul>
 *
 * <p>Security: the token is read from the environment; it is never committed
 * (NFR10). If the variable is unset the service starts, but every call to
 * this endpoint returns 401 — intentional, because an unconfigured token
 * must not silently allow unauthenticated broadcast.
 *
 * <p>This endpoint is internal-only. The browser can reach port 8081 too, so
 * the shared secret is the only thing protecting it (contract section 5).
 */
@RestController
@RequestMapping("/internal")
public class InternalMessageController {

    private static final Logger log = LoggerFactory.getLogger(InternalMessageController.class);

    /** Maximum text frame size that the contract allows (section 3.5 rule 4). */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024; // 4 MiB

    @Value("${INTERNAL_API_TOKEN:}")
    private String internalApiToken;

    private final RoomRegistry registry;
    private final ObjectMapper objectMapper;

    public InternalMessageController(RoomRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/rooms/{roomId}/messages",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> broadcast(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody String rawBody) {

        // --- Auth ---
        if (internalApiToken.isBlank() || !internalApiToken.equals(token)) {
            log.warn("Internal endpoint: unauthorized request for room={}", roomId);
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Missing or invalid X-Internal-Token");
        }

        // --- Size check (bytes, not chars) ---
        if (rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "PAYLOAD_TOO_LARGE",
                    "Body must be at most 4 MiB");
        }

        // --- Validate JSON shape: must have a non-empty "type" string ---
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            JsonNode typeNode = node.get("type");
            if (typeNode == null || !typeNode.isTextual() || typeNode.asText().isBlank()) {
                return error(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                        "Body must be a JSON object with a non-empty string field 'type'");
            }
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Body is not valid JSON");
        }

        // --- Broadcast to every open session in the room ---
        List<WebSocketSession> sessions = registry.getAll(roomId);
        TextMessage frame = new TextMessage(rawBody);
        int delivered = 0;

        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(frame);
                    }
                    delivered++;
                }
            } catch (Exception e) {
                log.warn("Broadcast failed to wsSession={}: {}", session.getId(), e.getMessage());
            }
        }

        log.info("Internal broadcast: room={} delivered={}", roomId, delivered);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("room_id", roomId);
        response.put("delivered", delivered);
        return ResponseEntity.ok(response);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("code", code);
        inner.put("message", message);
        inner.put("retry_after_seconds", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", null);
        body.put("error", inner);
        return ResponseEntity.status(status).body(body);
    }
}
