package com.collabcode.syncservice.internal;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance and contract integration tests for T-20 Jay's deliverables (Issue #65):
 * 1. Session lookup: GET /internal/rooms/{room_id}/sessions/{session_id} (T-17 V2.11)
 * 2. Targeted delivery via 'audience' field in POST /internal/rooms/{room_id}/messages (T-17 V2.10)
 * 3. Contract error codes: INVALID_MESSAGE (400), UNAUTHORIZED (401), ROOM_NOT_FOUND (404) (T-17 V2.14)
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
    // 2. Targeted Delivery & Audience (T-17 V2.10)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Targeted delivery with audience {'kind':'sessions'} reaches only specified sessions")
    void targetedDelivery_reachesOnlySpecifiedSession() throws Exception {
        String roomId = "room-targeted-test";
        Room room = new Room(roomId, RoomMode.PAIR, "debug_together", null, "hash");
        roomStore.put(room);

        try (WsClient clientA = connect(roomId, SESSION_1);
             WsClient clientB = connect(roomId, SESSION_2)) {

            Thread.sleep(150);

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
            String textB = clientB.pollText(500, TimeUnit.MILLISECONDS);
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

            Thread.sleep(150);

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

            String textOther = otherUser.pollText(500, TimeUnit.MILLISECONDS);
            assertThat(textOther).isNull();
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

            Thread.sleep(150);

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
    // 3. Contract Error Codes (T-17 V2.14 / Decision 14)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /internal/rooms/{roomId}/messages: non-JSON body returns 400 INVALID_MESSAGE")
    void internalBroadcast_notJson_returns400InvalidMessage() throws Exception {
        HttpResponse<String> resp = postInternal("test-room", "not a json", TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).isEqualTo("Message body is not valid: not JSON.");
    }

    @Test
    @DisplayName("POST /internal/rooms/{roomId}/messages: missing type returns 400 INVALID_MESSAGE and preserves request_id")
    void internalBroadcast_missingType_returns400InvalidMessage() throws Exception {
        String reqId = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10";
        String body = """
                {
                  "request_id": "%s",
                  "data": "no type"
                }
                """.formatted(reqId);

        HttpResponse<String> resp = postInternal("test-room", body, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("request_id").asText()).isEqualTo(reqId);
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).isEqualTo("Message body is not valid: field 'type' is missing.");
    }

    @Test
    @DisplayName("POST /internal/rooms/{roomId}/messages: malformed audience returns 400 INVALID_MESSAGE")
    void internalBroadcast_malformedAudience_returns400() throws Exception {
        String reqId = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e11";
        String body = """
                {
                  "request_id": "%s",
                  "type": "run-result",
                  "audience": "not-an-object"
                }
                """.formatted(reqId);

        HttpResponse<String> resp = postInternal("test-room", body, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("request_id").asText()).isEqualTo(reqId);
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).contains("audience must be an object");
    }

    @Test
    @DisplayName("POST /internal/rooms/{roomId}/messages: unknown audience kind returns 400 INVALID_MESSAGE")
    void internalBroadcast_unknownAudienceKind_returns400() throws Exception {
        String body = """
                {
                  "type": "run-result",
                  "audience": {
                    "kind": "all_users"
                  }
                }
                """;

        HttpResponse<String> resp = postInternal("test-room", body, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(400);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_MESSAGE");
        assertThat(json.get("error").get("message").asText()).contains("audience.kind 'all_users' is invalid");
    }

    @Test
    @DisplayName("POST /internal/rooms/{roomId}/messages: unknown room returns 404 ROOM_NOT_FOUND")
    void internalBroadcast_unknownRoom_returns404() throws Exception {
        String body = """
                {
                  "type": "run-result",
                  "request_id": "00000000-0000-4000-8000-000000000005"
                }
                """;

        HttpResponse<String> resp = postInternal("room-completely-unknown", body, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(404);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("ROOM_NOT_FOUND");
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
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/internal/rooms/" + roomId + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private WsClient connect(String roomId, String sessionId) throws Exception {
        return new WsClient(port, roomId, sessionId);
    }

    static class WsClient implements AutoCloseable {

        private final WebSocketSession session;
        private final BlockingQueue<byte[]> binaryQueue = new LinkedBlockingDeque<>();
        private final BlockingQueue<String> textQueue   = new LinkedBlockingDeque<>();

        WsClient(int port, String roomId, String sessionId) throws Exception {
            StandardWebSocketClient client = new StandardWebSocketClient();
            String url = "ws://localhost:" + port + "/ws/rooms/" + roomId + "?session_id=" + sessionId;

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

        @Override
        public void close() throws Exception {
            if (session != null && session.isOpen()) {
                session.close();
            }
        }
    }
}
