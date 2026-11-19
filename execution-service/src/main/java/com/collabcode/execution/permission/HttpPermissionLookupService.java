package com.collabcode.execution.permission;

import com.collabcode.execution.exception.RoomNotFoundException;
import com.collabcode.execution.exception.RoomServiceUnavailableException;
import com.collabcode.execution.exception.RunNotAllowedException;
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

/**
 * The real permission lookup (US-60, T-17 V2.11, V2.12; FR41.4).
 *
 * <p>Asks sync-service on <strong>every</strong> run request:
 * {@code GET {SYNC_SERVICE_INTERNAL_URL}/internal/rooms/{room_id}/sessions/{session_id}}
 * with {@code X-Internal-Token}. The answer is never cached, so a change the Host makes
 * (Run taken away, Host-only running) refuses the member's very next run. This bean is
 * used whenever the {@code stub-permissions} profile is not active.
 *
 * <p>It fails closed: anything that is not a clear answer from sync-service (no
 * connection, a timeout, 401, a 5xx, a body that is not a valid answer) is
 * {@link RoomServiceUnavailableException} (503 ROOM_SERVICE_UNAVAILABLE), never a
 * permission. The code is not run without a permission answer, and there is no retry.
 *
 * <p>Mapping of a refusal (the controller adds the request_id):
 * <ul>
 *   <li>200 -&gt; the {@link PermissionLookupResult}; the controller refuses when
 *       {@code can_run} is false, with {@code run_denied_reason} as {@code error.reason}</li>
 *   <li>404 {@code ROOM_NOT_FOUND} -&gt; {@link RoomNotFoundException}</li>
 *   <li>404 {@code SESSION_NOT_FOUND} -&gt; {@link RunNotAllowedException} with reason
 *       {@code not_in_room}</li>
 * </ul>
 * Nothing sensitive is logged: no session id, token or code.
 */
@Component
@Profile("!stub-permissions")
public class HttpPermissionLookupService implements PermissionLookupService {

    private static final Logger log = LoggerFactory.getLogger(HttpPermissionLookupService.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final String internalUrl;
    private final String internalToken;
    private final HttpClient httpClient;

    @Autowired
    public HttpPermissionLookupService(
            ObjectMapper objectMapper,
            @Value("${app.sync-service.internal-url:http://localhost:8081}") String internalUrl,
            @Value("${app.sync-service.internal-token:}") String internalToken) {
        this.objectMapper = objectMapper;
        this.internalUrl = internalUrl;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public PermissionLookupResult lookup(String roomId, String sessionId) {
        HttpResponse<String> response;
        try {
            // pathSegment + encode: a room id with '/', '?' or '..' can never reach another path.
            URI uri = UriComponentsBuilder.fromUriString(internalUrl)
                    .pathSegment("internal", "rooms", roomId, "sessions", sessionId)
                    .build()
                    .encode()
                    .toUri();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("X-Internal-Token", internalToken)
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Permission lookup interrupted");
            throw new RoomServiceUnavailableException(null);
        } catch (IOException | RuntimeException e) {
            log.warn("Permission lookup failed: {}", e.getClass().getSimpleName());
            throw new RoomServiceUnavailableException(null);
        }

        int status = response.statusCode();
        if (status == 200) {
            return parse(response.body());
        }
        if (status == 404) {
            String code = errorCode(response.body());
            if ("ROOM_NOT_FOUND".equals(code)) {
                throw new RoomNotFoundException(null, "Room not found.");
            }
            if ("SESSION_NOT_FOUND".equals(code)) {
                throw new RunNotAllowedException(null, "not_in_room");
            }
        }
        if (status == 401) {
            log.warn("Permission lookup refused by sync-service (401): check INTERNAL_API_TOKEN");
        } else {
            log.warn("Permission lookup unexpected status={}", status);
        }
        throw new RoomServiceUnavailableException(null);
    }

    /** Parse the V2.11 answer. A missing or non-boolean {@code can_run} is not an answer (fail closed). */
    private PermissionLookupResult parse(String body) {
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (IOException | RuntimeException e) {
            log.warn("Permission lookup: answer is not JSON");
            throw new RoomServiceUnavailableException(null);
        }
        if (node == null || !node.isObject()
                || node.get("can_run") == null || !node.get("can_run").isBoolean()) {
            log.warn("Permission lookup: answer is not a valid permission answer");
            throw new RoomServiceUnavailableException(null);
        }
        return new PermissionLookupResult(
                text(node, "member_id"),
                text(node, "display_name"),
                text(node, "role"),
                text(node, "mode"),
                text(node, "language"),
                node.get("can_run").booleanValue(),
                text(node, "run_denied_reason"),
                node.path("ai_enabled").asBoolean(false));
    }

    /** {@code error.code} of a sync-service error envelope, or null. */
    private String errorCode(String body) {
        try {
            JsonNode code = objectMapper.readTree(body).path("error").path("code");
            return code.isTextual() ? code.asText() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
