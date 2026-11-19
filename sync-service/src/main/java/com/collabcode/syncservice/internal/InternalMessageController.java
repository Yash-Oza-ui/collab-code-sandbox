package com.collabcode.syncservice.internal;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.collabcode.syncservice.run.InputHasher;
import com.collabcode.syncservice.run.RunRecord;
import com.collabcode.syncservice.run.RunRecordStore;
import com.collabcode.syncservice.run.SavedVersionSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
    private final RunRecordStore runRecordStore;

    public InternalMessageController(
            RoomRegistry registry,
            ObjectMapper objectMapper,
            RoomStore roomStore,
            RoomService roomService) {
        this(registry, objectMapper, roomStore, roomService, null);
    }

    @Autowired
    public InternalMessageController(
            RoomRegistry registry,
            ObjectMapper objectMapper,
            RoomStore roomStore,
            RoomService roomService,
            @Autowired(required = false) RunRecordStore runRecordStore) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.roomStore = roomStore;
        this.roomService = roomService;
        this.runRecordStore = runRecordStore;
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

        // --- 1. Auth check (V2.11, V2.14, section 7.2) ---
        if (internalApiToken == null || internalApiToken.isBlank() || !internalApiToken.equals(token)) {
            log.warn("Internal endpoint: unauthorized session lookup for room={}", roomId);
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Missing or invalid internal token.", null);
        }

        // --- 2. Room existence check (authoritative RoomStore) ---
        Room room = roomStore != null ? roomStore.get(roomId) : null;
        if (room == null || room.isClosed()) {
            return error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND",
                    "Room not found: " + roomId, null);
        }

        // --- 3. Session lookup ---
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
        // US-60 (V2.20): the effective answer, including a Run restriction and Host-only
        // running. Asked fresh on every call; execution-service never caches it (FR41.4).
        String runDeniedReason = room.runDeniedReason(member);
        resp.put("can_run", runDeniedReason == null);
        resp.put("run_denied_reason", runDeniedReason);
        resp.put("ai_enabled", room.isAiEnabled());

        return ResponseEntity.ok(resp);
    }

    /**
     * Internal broadcast endpoint with optional audience (T-17 V2.10, V2.14).
     *
     * <p>Enforces strict validation order:
     * 1. Validate X-Internal-Token (reject unauthorized before body processing).
     * 2. Enforce Content-Type and request body presence/size before JSON parsing.
     * 3. Parse JSON syntax and structure.
     * 4. Extract request_id safely.
     * 5. Validate message structure (type field).
     * 6. Validate audience routing (if specified).
     * 7. Validate room existence against authoritative RoomStore.
     * 8. Strip audience, verify serialized size, and deliver to eligible WebSocket control sessions.
     */
    @PostMapping(value = "/rooms/{roomId}/messages",
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> broadcast(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            HttpServletRequest request) {

        // --- 1. Auth check (section 7.2, V2.14) ---
        // Must be validated before any body parsing or processing to prevent info leak / resource exhaustion.
        if (internalApiToken == null || internalApiToken.isBlank() || !internalApiToken.equals(token)) {
            log.warn("Internal endpoint: unauthorized broadcast for room={}", roomId);
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Missing or invalid internal token.", null);
        }

        // --- 2. Content-Type check ---
        String contentType = request != null ? request.getContentType() : null;
        if (contentType == null || contentType.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: unsupported Content-Type. Expected application/json.", null);
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            if (!mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                        "Message body is not valid: unsupported Content-Type. Expected application/json.", null);
            }
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: unsupported Content-Type. Expected application/json.", null);
        }

        // --- 3. Request body presence & bounded stream reading ---
        // Bounded stream reading: reject immediately if Content-Length exceeds 4 MiB,
        // and stop stream reading immediately if total bytes read exceed 4 MiB.
        if (request == null) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: empty or missing body.", null);
        }

        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: larger than 4 MiB.", null);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int totalRead = 0;
        boolean oversized = false;
        try (InputStream is = request.getInputStream()) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                totalRead += bytesRead;
                if (totalRead > MAX_BODY_BYTES) {
                    oversized = true;
                    break;
                }
                baos.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: error reading stream.", null);
        }

        if (oversized) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: larger than 4 MiB.", null);
        }

        byte[] rawBodyBytes = baos.toByteArray();
        if (rawBodyBytes.length == 0) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: empty or missing body.", null);
        }

        // --- 4. JSON parsing & root object validation ---
        JsonNode parsedNode;
        try {
            parsedNode = objectMapper.readTree(rawBodyBytes);
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: not JSON.", null);
        }

        if (parsedNode == null || !parsedNode.isObject()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: not JSON.", null);
        }

        // --- 5. Extract request_id safely for subsequent error responses ---
        String requestId = null;
        if (parsedNode.hasNonNull("request_id") && parsedNode.get("request_id").isTextual()) {
            requestId = parsedNode.get("request_id").asText();
        }

        // --- 6. Validate JSON shape: must have a non-empty string field 'type' (section 7.2, V2.14) ---
        JsonNode typeNode = parsedNode.get("type");
        if (typeNode == null || !typeNode.isTextual() || typeNode.asText().trim().isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: field 'type' is missing.", requestId);
        }

        // --- 7. Parse and validate audience (T-17 V2.10, V2.14) ---
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
                if (sessionIdsNode == null || !sessionIdsNode.isArray() || sessionIdsNode.isEmpty()) {
                    return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                            "Message body is not valid: audience.session_ids must be a non-empty array.", requestId);
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

        // --- 8. Check room existence against authoritative RoomStore (T-17 V2.10) ---
        Room room = roomStore != null ? roomStore.get(roomId) : null;
        if (room == null || room.isClosed()) {
            return error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND",
                    "Room not found: " + roomId, requestId);
        }

        // --- 9. Extract run-record metadata (FR29, US-52), then strip internal-only fields before forwarding (T-17 V2.10) ---
        String messageType = typeNode.asText().trim();
        RunRecord pendingRunRecord = "run-result".equalsIgnoreCase(messageType)
                ? extractRunRecord(roomId, parsedNode)
                : null;

        if (parsedNode instanceof ObjectNode objNode) {
            objNode.remove("audience");
            if ("run-result".equalsIgnoreCase(messageType)) {
                objNode.remove("input_hash");
                objNode.remove("files");
                objNode.remove("stdin");
            }
        }
        byte[] payloadToSendBytes;
        try {
            payloadToSendBytes = objectMapper.writeValueAsBytes(parsedNode);
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: failed to serialize.", requestId);
        }

        if (payloadToSendBytes.length > MAX_BODY_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                    "Message body is not valid: larger than 4 MiB.", requestId);
        }

        // --- 10. Persist run record with the result delivery call (FR29, US-52, T-18 Decision 9) ---
        if (pendingRunRecord != null && runRecordStore != null) {
            runRecordStore.record(pendingRunRecord);
        }

        // --- 11. Deliver to targeted control-socket connections ---
        List<WebSocketSession> sessions = roomAudience
                ? registry.getAll(roomId)
                : registry.getBySessionIds(roomId, targetSessions);

        String payloadToSend = new String(payloadToSendBytes, StandardCharsets.UTF_8);
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
                log.warn("Failed to deliver internal message to wsSession={}: {}", session.getId(), e.getMessage());
            }
        }

        log.info("Internal broadcast: room={} audience={} delivered={}",
                roomId, roomAudience ? "room" : "sessions", delivered);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("room_id", roomId);
        response.put("delivered", delivered);
        return ResponseEntity.ok(response);
    }

    /**
     * Reads stored run records for a room (FR29, US-52, US-68).
     *
     * <p>When {@code input_hash} is supplied, returns only runs for that room with the
     * matching input hash, ordered newest first (slowdown baseline query, US-68).
     * When omitted, returns all runs for the room, ordered newest first.
     */
    @GetMapping(value = "/rooms/{roomId}/runs",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getRuns(
            @PathVariable String roomId,
            @RequestParam(name = "input_hash", required = false) String inputHash,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {

        if (internalApiToken == null || internalApiToken.isBlank() || !internalApiToken.equals(token)) {
            log.warn("Internal endpoint: unauthorized run history lookup for room={}", roomId);
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Missing or invalid internal token.", null);
        }

        Room room = roomStore != null ? roomStore.get(roomId) : null;
        if (room == null || room.isClosed()) {
            return error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND",
                    "Room not found: " + roomId, null);
        }

        List<RunRecord> runs = Collections.emptyList();
        if (runRecordStore != null) {
            runs = (inputHash != null && !inputHash.isBlank())
                    ? runRecordStore.findByRoomAndInputHash(roomId, inputHash)
                    : runRecordStore.findByRoom(roomId);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("room_id", roomId);
        resp.put("runs", runs);
        return ResponseEntity.ok(resp);
    }

    /**
     * Deletes all run records for a room (FR32, US-52, US-56).
     * Idempotent so room teardown can purge records even if the room is already closed.
     */
    @DeleteMapping(value = "/rooms/{roomId}/runs",
                   produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> deleteRuns(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {

        if (internalApiToken == null || internalApiToken.isBlank() || !internalApiToken.equals(token)) {
            log.warn("Internal endpoint: unauthorized run record delete for room={}", roomId);
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Missing or invalid internal token.", null);
        }

        int deleted = runRecordStore != null ? runRecordStore.deleteByRoom(roomId) : 0;
        log.info("Internal run-record delete: room={} deleted={}", roomId, deleted);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("room_id", roomId);
        resp.put("deleted", deleted);
        return ResponseEntity.ok(resp);
    }

    /**
     * Extracts a privacy-safe {@link RunRecord} from a delivered {@code run-result} JSON payload.
     * Persists only FR29 / V2.9 fields; never persists {@code owner_token}, {@code session_id},
     * raw {@code stdin}, or source code text.
     */
    private static RunRecord extractRunRecord(String roomId, JsonNode node) {
        if (node == null) {
            return null;
        }

        // Runner display name: read ONLY runner.display_name; never persist session_id
        String runnerDisplayName = "";
        JsonNode runnerNode = node.get("runner");
        if (runnerNode != null) {
            if (runnerNode.isObject() && runnerNode.hasNonNull("display_name")
                    && runnerNode.get("display_name").isTextual()) {
                runnerDisplayName = runnerNode.get("display_name").asText();
            } else if (runnerNode.isTextual()) {
                runnerDisplayName = runnerNode.asText();
            }
        }

        // Time of run (ISO 8601 UTC from V2.9 ran_at)
        String ranAt = (node.hasNonNull("ran_at") && node.get("ran_at").isTextual()
                && !node.get("ran_at").asText().isBlank())
                ? node.get("ran_at").asText()
                : Instant.now().toString();

        // Execution language
        String language = (node.hasNonNull("language") && node.get("language").isTextual())
                ? node.get("language").asText()
                : "";

        // Executed filenames only (NEVER source code content)
        List<String> files = new ArrayList<>();
        JsonNode filesNode = node.get("files");
        if (filesNode != null && filesNode.isArray()) {
            for (JsonNode entry : filesNode) {
                if (entry.isTextual() && !entry.asText().isBlank()) {
                    files.add(entry.asText());
                } else if (entry.isObject() && entry.hasNonNull("name") && entry.get("name").isTextual()) {
                    String name = entry.get("name").asText();
                    if (!name.isBlank()) {
                        files.add(name);
                    }
                }
            }
        }

        // Open-mode saved versions metadata (safe V2.9 fields only; no code or session_id)
        List<SavedVersionSummary> savedVersions = new ArrayList<>();
        JsonNode savedVersionsNode = node.get("saved_versions");
        if (savedVersionsNode != null && savedVersionsNode.isArray()) {
            boolean deriveFiles = files.isEmpty();
            for (JsonNode sv : savedVersionsNode) {
                if (!sv.isObject()) {
                    continue;
                }
                String modId = sv.hasNonNull("module_id") && sv.get("module_id").isTextual()
                        ? sv.get("module_id").asText() : "";
                String modName = sv.hasNonNull("name") && sv.get("name").isTextual()
                        ? sv.get("name").asText() : "";
                int version = sv.hasNonNull("version") && sv.get("version").isNumber()
                        ? sv.get("version").asInt() : 0;
                String savedAt = sv.hasNonNull("saved_at") && sv.get("saved_at").isTextual()
                        ? sv.get("saved_at").asText() : "";

                SavedVersionSummary.SavedBySummary savedBy = null;
                JsonNode savedByNode = sv.get("saved_by");
                if (savedByNode != null && savedByNode.isObject()) {
                    String memberId = savedByNode.hasNonNull("member_id") && savedByNode.get("member_id").isTextual()
                            ? savedByNode.get("member_id").asText() : "";
                    String displayName = savedByNode.hasNonNull("display_name") && savedByNode.get("display_name").isTextual()
                            ? savedByNode.get("display_name").asText() : "";
                    savedBy = new SavedVersionSummary.SavedBySummary(memberId, displayName);
                }

                savedVersions.add(new SavedVersionSummary(modId, modName, version, savedBy, savedAt));
                if (deriveFiles && !modName.isBlank()) {
                    files.add(modName);
                }
            }
        }

        // Input hash: SHA-256 digest only (never store raw stdin)
        String inputHash;
        if (node.hasNonNull("input_hash") && node.get("input_hash").isTextual()
                && !node.get("input_hash").asText().isBlank()) {
            inputHash = node.get("input_hash").asText();
        } else if (node.hasNonNull("stdin") && node.get("stdin").isTextual()) {
            inputHash = InputHasher.hash(node.get("stdin").asText());
        } else {
            inputHash = InputHasher.EMPTY_SHA256;
        }

        double executionTimeMs = (node.hasNonNull("execution_time_ms") && node.get("execution_time_ms").isNumber())
                ? node.get("execution_time_ms").asDouble()
                : 0.0;

        double peakMemoryMb = (node.hasNonNull("peak_memory_mb") && node.get("peak_memory_mb").isNumber())
                ? node.get("peak_memory_mb").asDouble()
                : 0.0;

        return new RunRecord(
                roomId,
                runnerDisplayName,
                ranAt,
                language,
                files,
                savedVersions,
                inputHash,
                executionTimeMs,
                peakMemoryMb
        );
    }

    // -----------------------------------------------------------------------
    // Exception Handlers (ensure framework-level rejections match T-17 contract)
    // -----------------------------------------------------------------------

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                "Message body is not valid: not JSON.", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE",
                "Message body is not valid: unsupported Content-Type. Expected application/json.", null);
    }

    // -----------------------------------------------------------------------
    // Helper
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
