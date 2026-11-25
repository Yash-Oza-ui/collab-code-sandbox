package com.collabcode.execution.snapshot;

import com.collabcode.execution.exception.InvalidRequestException;
import com.collabcode.execution.exception.ModuleNotFoundException;
import com.collabcode.execution.exception.ModuleNotRunnableException;
import com.collabcode.execution.exception.ModuleNotSavedException;
import com.collabcode.execution.exception.NoEntryFileException;
import com.collabcode.execution.exception.RoomNotFoundException;
import com.collabcode.execution.exception.RoomServiceUnavailableException;
import com.collabcode.execution.model.SavedSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real snapshot reader (US-47, T-17 V2.8; FR25.1, FR25.2).
 *
 * <p>Calls sync-service:
 * {@code POST {SYNC_SERVICE_INTERNAL_URL}/internal/rooms/{room_id}/snapshot}
 * with {@code X-Internal-Token}. Reads all requested module versions together in one call
 * (the one-call snapshot rule).
 *
 * <p>Fails closed: returns 503 ROOM_SERVICE_UNAVAILABLE if sync-service is unavailable
 * or unreachable, or translates standard contract error codes (ROOM_NOT_FOUND, MODULE_NOT_FOUND,
 * MODULE_NOT_RUNNABLE, MODULE_NOT_SAVED, NO_ENTRY_FILE).
 */
@Component
@Profile("!stub-permissions & !stub-snapshot")
public class HttpSavedVersionReader implements SavedVersionReader {

    private static final Logger log = LoggerFactory.getLogger(HttpSavedVersionReader.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final String internalUrl;
    private final String internalToken;
    private final HttpClient httpClient;

    @Autowired
    public HttpSavedVersionReader(
            ObjectMapper objectMapper,
            @Value("${app.sync-service.internal-url:http://localhost:8081}") String internalUrl,
            @Value("${app.sync-service.internal-token:}") String internalToken) {
        this(objectMapper, internalUrl, internalToken, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    public HttpSavedVersionReader(
            ObjectMapper objectMapper,
            String internalUrl,
            String internalToken,
            HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.internalUrl = internalUrl;
        this.internalToken = (internalToken != null) ? internalToken : "";
        this.httpClient = httpClient;
    }

    @Override
    public SnapshotResult readSnapshot(String roomId, SavedSource source) {
        if (source == null) {
            throw new InvalidRequestException(null, "Field 'source' is missing or invalid.");
        }

        HttpResponse<String> response;
        try {
            URI uri = UriComponentsBuilder.fromUriString(internalUrl)
                    .pathSegment("internal", "rooms", roomId, "snapshot")
                    .build()
                    .encode()
                    .toUri();

            Map<String, Object> sourceMap = new LinkedHashMap<>();
            sourceMap.put("kind", "saved");
            sourceMap.put("target", source.getTarget());
            if ("module".equals(source.getTarget())) {
                sourceMap.put("module_id", source.getModule_id());
            } else {
                sourceMap.put("module_ids", source.getModule_ids());
            }
            Map<String, Object> reqBody = Map.of("source", sourceMap);
            byte[] bodyBytes = objectMapper.writeValueAsBytes(reqBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Snapshot read interrupted");
            throw new RoomServiceUnavailableException(null, "Room service is unavailable, so the run was not started. Try again.");
        } catch (IOException | RuntimeException e) {
            log.warn("Snapshot read failed: {}", e.getClass().getSimpleName());
            throw new RoomServiceUnavailableException(null, "Room service is unavailable, so the run was not started. Try again.");
        }

        int status = response.statusCode();
        if (status == 200) {
            return parseSnapshot(roomId, response.body());
        }

        String code = errorCode(response.body());
        String message = errorMessage(response.body());

        if (status == 404) {
            if ("ROOM_NOT_FOUND".equals(code)) {
                throw new RoomNotFoundException(null, message != null ? message : "Room not found.");
            }
            if ("MODULE_NOT_FOUND".equals(code)) {
                throw new ModuleNotFoundException(null, message != null ? message : "Module not found.");
            }
        }

        if (status == 422) {
            if ("MODULE_NOT_RUNNABLE".equals(code)) {
                throw new ModuleNotRunnableException(null, message != null ? message : "Module is not marked runnable.");
            }
            if ("MODULE_NOT_SAVED".equals(code)) {
                throw new ModuleNotSavedException(null, message != null ? message : "Module has never been saved. Save it first.");
            }
            if ("NO_ENTRY_FILE".equals(code)) {
                throw new NoEntryFileException(null, message != null ? message : "No entry file is set.");
            }
            if ("INVALID_REQUEST".equals(code)) {
                throw new InvalidRequestException(null, message != null ? message : "Invalid request.");
            }
        }

        if (status == 401) {
            log.warn("Snapshot read refused by sync-service (401): check INTERNAL_API_TOKEN");
        } else {
            log.warn("Snapshot read unexpected status={}", status);
        }
        throw new RoomServiceUnavailableException(null, "Room service is unavailable, so the run was not started. Try again.");
    }

    private SnapshotResult parseSnapshot(String roomId, String body) {
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Snapshot read: answer is not JSON");
            throw new RoomServiceUnavailableException(null, "Room service is unavailable, so the run was not started. Try again.");
        }

        if (node == null || !node.isObject()) {
            log.warn("Snapshot read: answer is not a valid snapshot response");
            throw new RoomServiceUnavailableException(null, "Room service is unavailable, so the run was not started. Try again.");
        }

        String returnedRoomId = node.path("room_id").asText(roomId);
        String entry = node.path("entry").asText("");
        List<SnapshotModule> modules = new ArrayList<>();
        JsonNode modulesNode = node.get("modules");
        if (modulesNode != null && modulesNode.isArray()) {
            for (JsonNode mNode : modulesNode) {
                String modId = mNode.path("module_id").asText("");
                String name = mNode.path("name").asText("");
                String content = mNode.path("content").asText("");
                int version = mNode.path("version").asInt(0);
                MemberInfo owner = parseMemberInfo(mNode.get("owner"));
                MemberInfo savedBy = parseMemberInfo(mNode.get("saved_by"));
                String savedAt = mNode.hasNonNull("saved_at") ? mNode.get("saved_at").asText() : null;
                modules.add(new SnapshotModule(modId, name, content, version, owner, savedBy, savedAt));
            }
        }

        return new SnapshotResult(returnedRoomId, entry, modules);
    }

    private static MemberInfo parseMemberInfo(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String memberId = node.path("member_id").asText("");
        String displayName = node.path("display_name").asText("");
        return new MemberInfo(memberId, displayName);
    }

    private String errorCode(String body) {
        try {
            JsonNode code = objectMapper.readTree(body).path("error").path("code");
            return code.isTextual() ? code.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String errorMessage(String body) {
        try {
            JsonNode msg = objectMapper.readTree(body).path("error").path("message");
            return msg.isTextual() ? msg.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
