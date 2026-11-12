package com.collabcode.syncservice.internal;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Internal HTTP endpoints used by execution-service (and other internal components):
 *
 * 1. Broadcast endpoint (T-06 §5, T-17 V2.10, V2.14):
 * <pre>
 * POST /internal/rooms/{roomId}/messages
 * X-Internal-Token: &lt;INTERNAL_API_TOKEN&gt;
 * Content-Type: application/json
 * </pre>
 * Broadcasts a run result (or any control message) to room or targeted session connections.
 * Accepts optional "audience": {"kind":"room"} or {"kind":"sessions","session_ids":[...]}.
 * Removes "audience" before forwarding to WebSocket clients.
 *
 * 2. Session lookup endpoint (T-17 V2.11, Decision 11):
 * <pre>
 * GET /internal/rooms/{roomId}/sessions/{sessionId}
 * X-Internal-Token: &lt;INTERNAL_API_TOKEN&gt;
 * </pre>
 * Returns caller identity, role, permissions (can_run, run_denied_reason) and room settings (mode, language, ai_enabled).
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
    private final RoomStore roomStore;
    private final RoomService roomService;

    public InternalMessageController(
            RoomRegistry registry,
            ObjectMapper objectMapper,
            RoomStore roomStore,
            RoomService roomService) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.roomStore = roomStore;
        this.roomService = roomService;
    }

    /**
     * Session lookup (T-17 V2.11 / Decision 11).
     *
     * <p>On every run request, execution-service asks sync-service who the caller is
     * and what permissions they hold.
     */
    @GetMapping(value = "/rooms/{roomId}/sessions/{sessionId}",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getSession(
            @PathVariable String roomId,
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {

        // --- Auth check (V2.11, V2.14, section 7.2) ---
        if (internalApiToken == null || internalApiToken.isBlank() || !internalApiToken.equals(token)) {
            log.warn("Internal endpoint: unauthorized session lookup for room={}", roomId);
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Missing or invalid internal token.", null);
        }

        Room room = roomStore.get(roomId);
        if (room == null) {
            return error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND",
                    "Room not found: " + roomId, null);
        }

        Member member = roomService.findMemberBySession(roomId, sessionId);
        if (member == null) {
            return error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                    "Session not found in room: " + sessionId, null);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("member_id", member.getMemberId());
        resp.put("display_name", member.getDisplayName());
        resp.put("role", member.getRole().name().toLowerCase());
        resp.put("mode", room.getMode().name().toLowerCase());
        resp.put("language", room.getLanguage()); // null in Pair mode, language string in Open mode
        resp.put("can_run", member.isCanRun());

        String runDeniedReason = null;
        if (!member.isCanRun()) {
            runDeniedReason = member.getRole() == Role.OBSERVER ? "observer" : "run_revoked";
        }
        resp.put("run_denied_reason", runDeniedReason);
        resp.put("ai_enabled", room.isAiEnabled());

        return ResponseEntity.ok(resp);
    }

    /**
     * Internal broadcast endpoint with optional audience (T-17 V2.10, V2.14).
     */
    @PostMapping(value = "/rooms/{roomId}/messages",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> broadcast(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody String rawBody) {

        // Try extracting request_id for contract error responses (section 7.2 / V2.14)
        String requestId = null;
        JsonNode parsedNode = null;
        if (rawBody != null) {
            try {
                parsedNode = objectMapper.readTree(rawBody);
                if (parsedNode != null && parsedNode.has("request_id") && parsedNode.get("request_id").isTextual()) {
                    requestId = parsedNode.get("request_id").asText();
                }
            } catch (Exception ignored) {
            }
        }

        // --- Auth check (section 7.2, V2.14) ---
        if (internalApiToken == null || internalApiToken.isBlank() || !internalApiToken.equals(token)) {
            log.warn("Internal endpoint: unauthorized broadcast for room={}", roomId);
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Missing or invalid internal token.", requestId);
        }

        // --- Size check (bytes, not chars) (section 7.2, V2.14) ---
        if (rawBody == null || rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: larger than 4 MiB.", requestId);
        }

        // --- JSON syntax check (section 7.2, V2.14) ---
        if (parsedNode == null) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: not JSON.", requestId);
        }

        // --- Validate JSON shape: must have a non-empty string field 'type' (section 7.2, V2.14) ---
        JsonNode typeNode = parsedNode.get("type");
        if (typeNode == null || !typeNode.isTextual() || typeNode.asText().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: field 'type' is missing.", requestId);
        }

        // --- Parse and validate audience (T-17 V2.10, V2.14) ---
        JsonNode audienceNode = parsedNode.get("audience");
        Set<String> targetSessions = null;
        boolean roomAudience = true;

        if (audienceNode != null && !audienceNode.isNull()) {
            if (!audienceNode.isObject()) {
                return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                        "Message body is not valid: audience must be an object.", requestId);
            }
            JsonNode kindNode = audienceNode.get("kind");
            if (kindNode == null || !kindNode.isTextual() || kindNode.asText().isBlank()) {
                return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                        "Message body is not valid: audience.kind is missing or invalid.", requestId);
            }
            String kind = kindNode.asText();
            if ("room".equals(kind)) {
                roomAudience = true;
            } else if ("sessions".equals(kind)) {
                roomAudience = false;
                JsonNode sessionIdsNode = audienceNode.get("session_ids");
                if (sessionIdsNode == null || !sessionIdsNode.isArray()) {
                    return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                            "Message body is not valid: audience.session_ids must be an array.", requestId);
                }
                targetSessions = new HashSet<>();
                for (JsonNode elem : sessionIdsNode) {
                    if (!elem.isTextual() || elem.asText().isBlank()) {
                        return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                                "Message body is not valid: audience.session_ids must contain non-blank strings.", requestId);
                    }
                    targetSessions.add(elem.asText());
                }
            } else {
                return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                        "Message body is not valid: audience.kind '" + kind + "' is invalid.", requestId);
            }
        }

        // --- Check room existence (T-17 V2.10) ---
        boolean roomExists = roomStore != null && roomStore.exists(roomId);
        boolean hasConnections = registry.hasRoom(roomId);
        if (!roomExists && !hasConnections) {
            return error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND",
                    "Room not found: " + roomId, requestId);
        }

        // --- Strip audience before forwarding (T-17 V2.10) ---
        if (parsedNode instanceof ObjectNode objNode) {
            objNode.remove("audience");
        }
        String payloadToSend;
        try {
            payloadToSend = objectMapper.writeValueAsString(parsedNode);
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: failed to serialize.", requestId);
        }

        if (payloadToSend.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: larger than 4 MiB.", requestId);
        }

        // --- Deliver to targeted connections ---
        List<WebSocketSession> sessions = roomAudience
                ? registry.getAll(roomId)
                : registry.getBySessionIds(roomId, targetSessions);

        TextMessage frame = new TextMessage(payloadToSend);
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

        log.info("Internal broadcast: room={} audience={} delivered={}",
                roomId, roomAudience ? "room" : "sessions", delivered);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("room_id", roomId);
        response.put("delivered", delivered);
        return ResponseEntity.ok(response);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message, String requestId) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("code", code);
        inner.put("message", message);
        inner.put("retry_after_seconds", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("error", inner);
        return ResponseEntity.status(status).body(body);
    }
}
