package com.collabcode.syncservice.internal;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance and contract integration tests for T-20 Jay's deliverables (Issue #65, PR #104):
 * 1. Session lookup: GET /internal/rooms/{room_id}/sessions/{session_id} (T-17 V2.11)
 * 2. Targeted delivery via 'audience' field in POST /internal/rooms/{room_id}/messages (T-17 V2.10)
 * 3. Contract error codes: INVALID_MESSAGE (400), UNAUTHORIZED (401), ROOM_NOT_FOUND (404), SESSION_NOT_FOUND (404) (T-17 V2.14)
 * 4. Strict validation precedence (auth -> size -> parse -> type -> audience -> room) and authoritative lifecycle.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "INTERNAL_API_TOKEN=test-token-t20-internal"
        }
)
class InternalEndpointsIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RoomStore roomStore;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomRegistry roomRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String TOKEN = "test-token-t20-internal";

    private static final String SESSION_1 = "10000000-0000-4000-8000-000000000001";
    private static final String SESSION_2 = "20000000-0000-4000-8000-000000000002";
    private static final String SESSION_3 = "30000000-0000-4000-8000-000000000003";

    // -----------------------------------------------------------------------
    // 1. Session Lookup (T-17 V2.11 / Decision 11)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Session lookup in Pair mode returns role, permissions and room metadata")
    void sessionLookup_pairMode_returnsCorrectFields() throws Exception {
        String roomId = "room-lookup-pair";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, OwnerTokens.hash("token-owner"));
        roomStore.put(room);
        roomService.join(roomId, SESSION_1, "Alice", "token-owner");

        HttpResponse<String> resp = getSessionLookup(roomId, SESSION_1, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("member_id").asText()).startsWith("m_");
        assertThat(json.get("display_name").asText()).isEqualTo("Alice");
        assertThat(json.get("role").asText()).isEqualTo("host");
        assertThat(json.get("mode").asText()).isEqualTo("pair");
        assertThat(json.get("language").isNull()).isTrue();
        assertThat(json.get("can_run").asBoolean()).isTrue();
        assertThat(json.get("run_denied_reason").isNull()).isTrue();
        assertThat(json.get("ai_enabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Session lookup in Open mode returns language and participant role")
    void sessionLookup_openMode_returnsLanguage() throws Exception {
        String roomId = "room-lookup-open";
        Room room = new Room(roomId, RoomMode.OPEN, null, "python", "hash2");
        roomStore.put(room);
        roomService.join(roomId, SESSION_2, "Bob", null);

        HttpResponse<String> resp = getSessionLookup(roomId, SESSION_2, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("member_id").asText()).startsWith("m_");
        assertThat(json.get("display_name").asText()).isEqualTo("Bob");
        assertThat(json.get("role").asText()).isEqualTo("participant");
        assertThat(json.get("mode").asText()).isEqualTo("open");
        assertThat(json.get("language").asText()).isEqualTo("python");
        assertThat(json.get("can_run").asBoolean()).isTrue();
        assertThat(json.get("run_denied_reason").isNull()).isTrue();
    }

    @Test
    @DisplayName("Session lookup for Observer returns can_run: false and reason: observer")
    void sessionLookup_observer_returnsCanRunFalse() throws Exception {
        String roomId = "room-lookup-obs";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash3");
        roomStore.put(room);
        roomService.join(roomId, SESSION_3, "Charlie", null);

        Member member = roomService.findMemberBySession(roomId, SESSION_3);
        member.setRole(Role.OBSERVER);
        member.setCanRun(false);

        HttpResponse<String> resp = getSessionLookup(roomId, SESSION_3, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("role").asText()).isEqualTo("observer");
        assertThat(json.get("can_run").asBoolean()).isFalse();
        assertThat(json.get("run_denied_reason").asText()).isEqualTo("observer");
    }

    @Test
    @DisplayName("Session lookup with invalid token returns 401 UNAUTHORIZED")
    void sessionLookup_invalidToken_returns401() throws Exception {
        String roomId = "room-lookup-auth";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);
        roomService.join(roomId, SESSION_1, "User", null);

        HttpResponse<String> resp = getSessionLookup(roomId, SESSION_1, "wrong-token");
        assertThat(resp.statusCode()).isEqualTo(401);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(json.get("error").get("message").asText()).isEqualTo("Missing or invalid internal token.");
        assertThat(json.get("request_id").isNull()).isTrue();
    }

    @Test
    @DisplayName("Session lookup on unknown room returns 404 ROOM_NOT_FOUND")
    void sessionLookup_unknownRoom_returns404() throws Exception {
        HttpResponse<String> resp = getSessionLookup("room-does-not-exist", SESSION_1, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(404);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    @DisplayName("Session lookup on closed room returns 404 ROOM_NOT_FOUND")
    void sessionLookup_closedRoom_returns404() throws Exception {
        String roomId = "room-lookup-closed";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);
        roomService.join(roomId, SESSION_1, "User", null);
        room.close();

        HttpResponse<String> resp = getSessionLookup(roomId, SESSION_1, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(404);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    @DisplayName("Session lookup on session not in room returns 404 SESSION_NOT_FOUND")
    void sessionLookup_sessionNotInRoom_returns404() throws Exception {
        String roomId = "room-lookup-no-sess";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);

        HttpResponse<String> resp = getSessionLookup(roomId, "99999999-9999-4000-8000-999999999999", TOKEN);
        assertThat(resp.statusCode()).isEqualTo(404);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("SESSION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // 2. Room Existence Validation (Authoritative Lifecycle)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Stale WebSocket registration in nonexistent room does not permit broadcast (returns 404 ROOM_NOT_FOUND)")
    void broadcast_staleSocketInNonexistentRoom_returns404() throws Exception {
        String staleRoomId = "room-stale-lifecycle-check";
        // Directly add a stale open session to RoomRegistry without the room existing in RoomStore
        DummyWebSocketSession dummySession = new DummyWebSocketSession();
        roomRegistry.add(staleRoomId, dummySession);

        try {
            assertThat(roomRegistry.hasRoom(staleRoomId)).isTrue();
            assertThat(roomStore.exists(staleRoomId)).isFalse();

            String body = """
                    {
                      "type": "run-result",
                      "request_id": "00000000-0000-4000-8000-000000000010",
                      "room_id": "%s"
                    }
                    """.formatted(staleRoomId);

            HttpResponse<String> resp = postInternal(staleRoomId, body, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(404);

            JsonNode json = objectMapper.readTree(resp.body());
            assertThat(json.get("error").get("code").asText()).isEqualTo("ROOM_NOT_FOUND");
            assertThat(json.get("error").get("message").asText()).isEqualTo("Room not found: " + staleRoomId);
        } finally {
            roomRegistry.remove(dummySession);
        }
    }

    @Test
    @DisplayName("Closed room with registered socket returns 404 ROOM_NOT_FOUND (closed room lifecycle)")
    void broadcast_closedRoom_returns404() throws Exception {
        String closedRoomId = "room-closed-lifecycle-check";
        Room room = new Room(closedRoomId, RoomMode.PAIR, "debug_together", null, "hash");
        room.close();
        roomStore.put(room);

        String body = """
                {
                  "type": "run-result",
                  "request_id": "00000000-0000-4000-8000-000000000012",
                  "room_id": "%s"
                }
                """.formatted(closedRoomId);

        HttpResponse<String> resp = postInternal(closedRoomId, body, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(404);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("ROOM_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // 3. Authentication & Bounded Processing Order
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Invalid token with valid JSON returns 401 UNAUTHORIZED")
    void broadcast_invalidTokenWithValidJson_returns401() throws Exception {
        String roomId = "room-auth-order-1";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);

        String body = """
                {
                  "type": "run-result",
                  "request_id": "00000000-0000-4000-8000-000000000011"
                }
                """;

        HttpResponse<String> resp = postInternal(roomId, body, "bad-token");
        assertThat(resp.statusCode()).isEqualTo(401);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(json.get("error").get("message").asText()).isEqualTo("Missing or invalid internal token.");
        assertThat(json.get("request_id").isNull()).isTrue();
    }

    @Test
    @DisplayName("Invalid token with malformed JSON returns 401 UNAUTHORIZED (auth check precedes parsing)")
    void broadcast_invalidTokenWithMalformedJson_returns401() throws Exception {
        HttpResponse<String> resp = postInternal("any-room", "{malformed-json-here...", "bad-token");
        assertThat(resp.statusCode()).isEqualTo(401);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(json.get("request_id").isNull()).isTrue();
    }

    @Test
    @DisplayName("Invalid token with oversized payload (> 4 MiB) returns 401 UNAUTHORIZED (auth check precedes size check)")
    void broadcast_invalidTokenWithOversizedPayload_returns401() throws Exception {
        byte[] largeBytes = new byte[4 * 1024 * 1024 + 1024]; // > 4 MiB
        Arrays.fill(largeBytes, (byte) 'a');

        HttpResponse<String> resp = postInternalBytes("any-room", largeBytes, "bad-token", "application/json");
        assertThat(resp.statusCode()).isEqualTo(401);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(json.get("request_id").isNull()).isTrue();
    }

    @Test
    @DisplayName("Valid token with payload exceeding 4 MiB returns 400 INVALID_MESSAGE without parsing")
    void broadcast_oversizedPayload_returns400InvalidMessage() throws Exception {
        byte[] largeBytes = new byte[4 * 1024 * 1024 + 16]; // > 4 MiB
        Arrays.fill(largeBytes, (byte) 'x');

        HttpResponse<String> resp = postInternalBytes("any-room", largeBytes, TOKEN, "application/json");
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).isEqualTo("Message body is not valid: larger than 4 MiB.");
        assertThat(json.get("request_id").isNull()).isTrue();
    }

    @Test
    @DisplayName("Multibyte UTF-8 content near 4 MiB boundary is measured by bytes rather than char count")
    void broadcast_multibyteUtf8NearLimit_properlyBounded() throws Exception {
        // Multi-byte character € is 3 bytes in UTF-8
        String prefix = "{\"type\":\"run-result\",\"stdout\":\"";
        String suffix = "\"}";
        int availableBytes = 4 * 1024 * 1024 + 10 - prefix.getBytes(StandardCharsets.UTF_8).length - suffix.getBytes(StandardCharsets.UTF_8).length;
        // Construct string with multibyte chars that exceeds 4 MiB in bytes
        int charCount = (availableBytes / 3) + 1;
        String filler = "€".repeat(charCount);
        String oversizedJson = prefix + filler + suffix;

        HttpResponse<String> resp = postInternal("any-room", oversizedJson, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).isEqualTo("Message body is not valid: larger than 4 MiB.");
    }

    // -----------------------------------------------------------------------
    // 4. Contract Error Codes (T-17 V2.14 / Framework handling)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Empty request body returns 400 INVALID_MESSAGE")
    void broadcast_emptyBody_returns400() throws Exception {
        HttpResponse<String> resp = postInternal("any-room", "", TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).contains("empty or missing body");
    }

    @Test
    @DisplayName("Unsupported Content-Type returns 400 INVALID_MESSAGE")
    void broadcast_unsupportedContentType_returns400() throws Exception {
        String body = "{\"type\":\"run-result\"}";

        for (String unsupported : List.of("text/plain", "text/x-application/json", "application/octet-stream")) {
            HttpResponse<String> resp = postInternalWithContentType("any-room", body, TOKEN, unsupported);
            assertThat(resp.statusCode()).isEqualTo(400);

            JsonNode json = objectMapper.readTree(resp.body());
            assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
            assertThat(json.get("error").get("message").asText()).contains("unsupported Content-Type");
        }
    }

    @Test
    @DisplayName("Non-JSON body returns 400 INVALID_MESSAGE")
    void broadcast_notJson_returns400() throws Exception {
        HttpResponse<String> resp = postInternal("any-room", "not a json string", TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).isEqualTo("Message body is not valid: not JSON.");
    }

    @Test
    @DisplayName("JSON root that is not an object (array, number, string) returns 400 INVALID_MESSAGE")
    void broadcast_jsonRootNotObject_returns400() throws Exception {
        List<String> invalidRoots = List.of("[\"array\", \"of\", \"items\"]", "\"a standalone string\"", "12345");
        for (String invalidJson : invalidRoots) {
            HttpResponse<String> resp = postInternal("any-room", invalidJson, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(400);

            JsonNode json = objectMapper.readTree(resp.body());
            assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
            assertThat(json.get("error").get("message").asText()).isEqualTo("Message body is not valid: not JSON.");
        }
    }

    @Test
    @DisplayName("Missing 'type' field returns 400 INVALID_MESSAGE and preserves request_id")
    void broadcast_missingType_returns400() throws Exception {
        String reqId = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10";
        String body = """
                {
                  "request_id": "%s",
                  "data": "value"
                }
                """.formatted(reqId);

        HttpResponse<String> resp = postInternal("any-room", body, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("request_id").asText()).isEqualTo(reqId);
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).isEqualTo("Message body is not valid: field 'type' is missing.");
    }

    @Test
    @DisplayName("Blank or non-string 'type' field returns 400 INVALID_MESSAGE")
    void broadcast_blankOrNonStringType_returns400() throws Exception {
        String reqId = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e15";
        List<String> invalidTypeBodies = List.of(
                "{\"type\": \"   \", \"request_id\": \"%s\"}".formatted(reqId),
                "{\"type\": 999, \"request_id\": \"%s\"}".formatted(reqId),
                "{\"type\": null, \"request_id\": \"%s\"}".formatted(reqId)
        );

        for (String body : invalidTypeBodies) {
            HttpResponse<String> resp = postInternal("any-room", body, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(400);

            JsonNode json = objectMapper.readTree(resp.body());
            assertThat(json.get("request_id").asText()).isEqualTo(reqId);
            assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
            assertThat(json.get("error").get("message").asText()).isEqualTo("Message body is not valid: field 'type' is missing.");
        }
    }

    @Test
    @DisplayName("Malformed audience structure returns 400 INVALID_MESSAGE")
    void broadcast_malformedAudience_returns400() throws Exception {
        String reqId = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e11";
        String body = """
                {
                  "request_id": "%s",
                  "type": "run-result",
                  "audience": "not-an-object"
                }
                """.formatted(reqId);

        HttpResponse<String> resp = postInternal("any-room", body, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("request_id").asText()).isEqualTo(reqId);
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).contains("audience must be an object");
    }

    @Test
    @DisplayName("Unknown audience kind returns 400 INVALID_MESSAGE")
    void broadcast_unknownAudienceKind_returns400() throws Exception {
        String body = """
                {
                  "type": "run-result",
                  "audience": {
                    "kind": "all_users"
                  }
                }
                """;

        HttpResponse<String> resp = postInternal("any-room", body, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).contains("audience.kind 'all_users' is invalid");
    }

    @Test
    @DisplayName("Invalid session_ids in audience returns 400 INVALID_MESSAGE")
    void broadcast_invalidSessionIds_returns400() throws Exception {
        List<String> invalidBodies = List.of(
                "{\"type\":\"run-result\",\"audience\":{\"kind\":\"sessions\"}}",
                "{\"type\":\"run-result\",\"audience\":{\"kind\":\"sessions\",\"session_ids\":\"not-an-array\"}}",
                "{\"type\":\"run-result\",\"audience\":{\"kind\":\"sessions\",\"session_ids\":[]}}",
                "{\"type\":\"run-result\",\"audience\":{\"kind\":\"sessions\",\"session_ids\":[\"   \"]}}",
                "{\"type\":\"run-result\",\"audience\":{\"kind\":\"sessions\",\"session_ids\":[123]}}"
        );

        for (String body : invalidBodies) {
            HttpResponse<String> resp = postInternal("any-room", body, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(400);

            JsonNode json = objectMapper.readTree(resp.body());
            assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
            assertThat(json.get("error").get("message").asText()).contains("audience.session_ids");
        }
    }

    // -----------------------------------------------------------------------
    // 5. Audience Filtering, Cross-Room Isolation & Control-Socket Delivery
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Targeted delivery with audience {'kind':'sessions'} reaches only specified sessions")
    void targetedDelivery_reachesOnlySpecifiedSession() throws Exception {
        String roomId = "room-targeted-test";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);

        try (WsClient clientA = connect(roomId, SESSION_1);
             WsClient clientB = connect(roomId, SESSION_2)) {

            String body = """
                    {
                      "audience": {
                        "kind": "sessions",
                        "session_ids": ["%s"]
                      },
                      "type": "run-result",
                      "request_id": "00000000-0000-4000-8000-000000000001",
                      "room_id": "%s",
                      "stdout": "targeted message\\n"
                    }
                    """.formatted(SESSION_1, roomId);

            HttpResponse<String> resp = postInternal(roomId, body, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(200);

            JsonNode respJson = objectMapper.readTree(resp.body());
            assertThat(respJson.get("delivered").asInt()).isEqualTo(1);

            // Client A must receive the message
            String textA = clientA.pollText(3, TimeUnit.SECONDS);
            assertThat(textA).isNotNull();
            assertThat(textA).contains("targeted message");

            // Client A must NOT see 'audience' in forwarded frame (T-17 V2.10)
            JsonNode receivedJson = objectMapper.readTree(textA);
            assertThat(receivedJson.has("audience")).isFalse();
            assertThat(receivedJson.get("type").asText()).isEqualTo("run-result");

            // Client B must receive NOTHING
            String textB = clientB.pollText(300, TimeUnit.MILLISECONDS);
            assertThat(textB).isNull();
        }
    }

    @Test
    @DisplayName("Targeted delivery reaches multiple tabs of the same session_id")
    void targetedDelivery_reachesMultipleTabsOfSameSession() throws Exception {
        String roomId = "room-multi-tab";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);

        try (WsClient tab1 = connect(roomId, SESSION_1);
             WsClient tab2 = connect(roomId, SESSION_1);
             WsClient otherUser = connect(roomId, SESSION_2)) {

            String body = """
                    {
                      "audience": {
                        "kind": "sessions",
                        "session_ids": ["%s"]
                      },
                      "type": "analysis-result",
                      "request_id": "00000000-0000-4000-8000-000000000002",
                      "room_id": "%s"
                    }
                    """.formatted(SESSION_1, roomId);

            HttpResponse<String> resp = postInternal(roomId, body, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(200);

            JsonNode respJson = objectMapper.readTree(resp.body());
            assertThat(respJson.get("delivered").asInt()).isEqualTo(2);

            String textTab1 = tab1.pollText(3, TimeUnit.SECONDS);
            String textTab2 = tab2.pollText(3, TimeUnit.SECONDS);
            assertThat(textTab1).isNotNull();
            assertThat(textTab2).isNotNull();

            String textOther = otherUser.pollText(300, TimeUnit.MILLISECONDS);
            assertThat(textOther).isNull();
        }
    }

    @Test
    @DisplayName("Targeted delivery with multiple sessions reaches all selected sessions and no unselected sessions")
    void targetedDelivery_multipleSessionsTargeting() throws Exception {
        String roomId = "room-multi-session-targeting";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);

        try (WsClient client1 = connect(roomId, SESSION_1);
             WsClient client2 = connect(roomId, SESSION_2);
             WsClient client3 = connect(roomId, SESSION_3)) {

            String body = """
                    {
                      "audience": {
                        "kind": "sessions",
                        "session_ids": ["%s", "%s"]
                      },
                      "type": "run-result",
                      "request_id": "00000000-0000-4000-8000-000000000020",
                      "stdout": "for 1 and 2\\n"
                    }
                    """.formatted(SESSION_1, SESSION_2);

            HttpResponse<String> resp = postInternal(roomId, body, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(200);

            JsonNode respJson = objectMapper.readTree(resp.body());
            assertThat(respJson.get("delivered").asInt()).isEqualTo(2);

            assertThat(client1.pollText(3, TimeUnit.SECONDS)).isNotNull();
            assertThat(client2.pollText(3, TimeUnit.SECONDS)).isNotNull();
            assertThat(client3.pollText(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    @DisplayName("Default audience reaches every session in the room")
    void defaultAudience_reachesAllInRoom() throws Exception {
        String roomId = "room-default-aud";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);

        try (WsClient clientA = connect(roomId, SESSION_1);
             WsClient clientB = connect(roomId, SESSION_2)) {

            String body = """
                    {
                      "type": "run-result",
                      "request_id": "00000000-0000-4000-8000-000000000003",
                      "room_id": "%s",
                      "stdout": "hello room\\n"
                    }
                    """.formatted(roomId);

            HttpResponse<String> resp = postInternal(roomId, body, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(200);

            JsonNode respJson = objectMapper.readTree(resp.body());
            assertThat(respJson.get("delivered").asInt()).isEqualTo(2);

            assertThat(clientA.pollText(3, TimeUnit.SECONDS)).isNotNull();
            assertThat(clientB.pollText(3, TimeUnit.SECONDS)).isNotNull();
        }
    }

    @Test
    @DisplayName("Cross-room isolation: Broadcast to room 1 never leaks to room 2")
    void crossRoomIsolation_messagesDoNotLeak() throws Exception {
        String room1 = "room-isolation-1";
        String room2 = "room-isolation-2";
        roomStore.put(new Room(room1, RoomMode.PAIR, "debug_together", null, "hash1"));
        roomStore.put(new Room(room2, RoomMode.PAIR, "debug_together", null, "hash2"));

        try (WsClient clientRoom1 = connect(room1, SESSION_1);
             WsClient clientRoom2 = connect(room2, SESSION_2)) {

            String body = """
                    {
                      "type": "run-result",
                      "stdout": "secret room 1 output\\n"
                    }
                    """;

            HttpResponse<String> resp = postInternal(room1, body, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(200);

            assertThat(clientRoom1.pollText(3, TimeUnit.SECONDS)).contains("secret room 1 output");
            assertThat(clientRoom2.pollText(400, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    @DisplayName("Control socket delivery only: Document socket connections never receive internal text messages")
    void controlSocketDeliveryOnly_docSocketReceivesNothing() throws Exception {
        String roomId = "room-doc-isolation-test";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);
        roomService.join(roomId, SESSION_1, "Alice", "token-owner");

        // Open both control socket and document socket
        try (WsClient controlClient = connect(roomId, SESSION_1);
             WsClient docClient = connectDocSocket(roomId, "main", SESSION_1)) {

            String body = """
                    {
                      "type": "run-result",
                      "stdout": "control-only payload\\n"
                    }
                    """;

            HttpResponse<String> resp = postInternal(roomId, body, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(200);

            // Delivered count must be 1 (only the control socket)
            JsonNode respJson = objectMapper.readTree(resp.body());
            assertThat(respJson.get("delivered").asInt()).isEqualTo(1);

            assertThat(controlClient.pollText(3, TimeUnit.SECONDS)).isNotNull();
            assertThat(docClient.pollText(300, TimeUnit.MILLISECONDS)).isNull();
            assertThat(docClient.pollBinary(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    @DisplayName("Concurrent broadcasts to the same session serialize safely without exceptions or lost frames")
    void concurrentBroadcasts_toSameSession_threadSafe() throws Exception {
        String roomId = "room-concurrent-broadcast";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);

        try (WsClient client = connect(roomId, SESSION_1)) {
            int threadCount = 10;
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
            java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        String body = "{\"type\":\"concurrent-msg\",\"seq\":" + idx + "}";
                        HttpResponse<String> resp = postInternal(roomId, body, TOKEN);
                        if (resp.statusCode() == 200) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(successCount.get()).isEqualTo(threadCount);

            // Verify all 10 messages were received without exception or loss
            for (int i = 0; i < threadCount; i++) {
                String frame = client.pollText(5, TimeUnit.SECONDS);
                assertThat(frame).isNotNull();
                assertThat(frame).contains("concurrent-msg");
            }
        }
    }

    @Test
    @DisplayName("Targeted delivery with 0 matching connections returns 200 with delivered: 0")
    void targetedDelivery_noMatches_returns200Delivered0() throws Exception {
        String roomId = "room-zero-match";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);

        String body = """
                {
                  "audience": {
                    "kind": "sessions",
                    "session_ids": ["99999999-9999-4000-8000-999999999999"]
                  },
                  "type": "run-result",
                  "request_id": "00000000-0000-4000-8000-000000000004",
                  "room_id": "%s"
                }
                """.formatted(roomId);

        HttpResponse<String> resp = postInternal(roomId, body, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode respJson = objectMapper.readTree(resp.body());
        assertThat(respJson.get("delivered").asInt()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private HttpResponse<String> getSessionLookup(String roomId, String sessionId, String token) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/internal/rooms/" + roomId + "/sessions/" + sessionId))
                .GET();
        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postInternal(String roomId, String body, String token) throws Exception {
        return postInternalWithContentType(roomId, body, token, "application/json");
    }

    private HttpResponse<String> postInternalWithContentType(String roomId, String body, String token, String contentType) throws Exception {
        byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return postInternalBytes(roomId, bytes, token, contentType);
    }

    private HttpResponse<String> postInternalBytes(String roomId, byte[] bytes, String token, String contentType) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/internal/rooms/" + roomId + "/messages"));

        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (bytes != null && bytes.length > 0) {
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }

        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private WsClient connect(String roomId, String sessionId) throws Exception {
        return new WsClient(port, "/ws/rooms/" + roomId + "?session_id=" + sessionId);
    }

    private WsClient connectDocSocket(String roomId, String docId, String sessionId) throws Exception {
        return new WsClient(port, "/ws/rooms/" + roomId + "/docs/" + docId + "?session_id=" + sessionId);
    }

    static class WsClient implements AutoCloseable {

        private final WebSocketSession session;
        private final BlockingQueue<byte[]> binaryQueue = new LinkedBlockingDeque<>();
        private final BlockingQueue<String> textQueue   = new LinkedBlockingDeque<>();

        WsClient(int port, String pathAndQuery) throws Exception {
            StandardWebSocketClient client = new StandardWebSocketClient();
            String url = "ws://localhost:" + port + pathAndQuery;

            session = client.execute(new AbstractWebSocketHandler() {
                @Override
                protected void handleBinaryMessage(WebSocketSession s, BinaryMessage msg) {
                    binaryQueue.add(msg.getPayload().array());
                }

                @Override
                protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
                    textQueue.add(msg.getPayload());
                }
            }, url).get(5, TimeUnit.SECONDS);
        }

        String pollText(long timeout, TimeUnit unit) throws InterruptedException {
            return textQueue.poll(timeout, unit);
        }

        byte[] pollBinary(long timeout, TimeUnit unit) throws InterruptedException {
            return binaryQueue.poll(timeout, unit);
        }

        @Override
        public void close() throws Exception {
            if (session != null && session.isOpen()) {
                session.close();
            }
        }
    }

    static class DummyWebSocketSession implements WebSocketSession {
        private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();
        private boolean open = true;

        @Override public String getId() { return "dummy-session-1"; }
        @Override public URI getUri() { return URI.create("ws://localhost/dummy"); }
        @Override public org.springframework.http.HttpHeaders getHandshakeHeaders() { return new org.springframework.http.HttpHeaders(); }
        @Override public java.util.Map<String, Object> getAttributes() { return attributes; }
        @Override public java.security.Principal getPrincipal() { return null; }
        @Override public java.net.InetSocketAddress getLocalAddress() { return null; }
        @Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public java.util.List<org.springframework.web.socket.WebSocketExtension> getExtensions() { return java.util.Collections.emptyList(); }
        @Override public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) {}
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
        @Override public void close(org.springframework.web.socket.CloseStatus status) { open = false; }
    }
}
