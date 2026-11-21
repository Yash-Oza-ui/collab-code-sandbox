package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** US-59: defaults, Host-only enforcement, real HTTP lookup and room updates. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"INTERNAL_API_TOKEN=test-us59-token"})
class AiSwitchControllerTest {
    private static final String HOST = "11111111-1111-4111-8111-111111111111";
    private static final String GUEST = "22222222-2222-4222-8222-222222222222";
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired RoomStore rooms;
    @Autowired RoomService roomService;
    @Autowired RoomRegistry registry;

    private Room newRoom(RoomMode mode, String preset) {
        Room room = new Room(RoomIds.generate(), mode, preset,
                mode == RoomMode.OPEN ? "python" : null, OwnerTokens.hash("host-secret"));
        rooms.put(room);
        return room;
    }

    private HttpResponse<String> switchAi(Room room, String session, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v1/rooms/" + room.getRoomId() + "/ai-switch"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body));
        if (session != null) request.header("X-Session-Id", session);
        if (token != null) request.header("X-Owner-Token", token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private boolean lookup(Room room) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/internal/rooms/"
                        + room.getRoomId() + "/sessions/" + GUEST))
                .header("X-Internal-Token", "test-us59-token")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body()).get("ai_enabled").asBoolean();
    }

    @Test
    void defaultsAndOwnerOnlySwitchReachTheNextAnalysisLookup() throws Exception {
        Room teach = newRoom(RoomMode.PAIR, "teach_interview");
        Room debug = newRoom(RoomMode.PAIR, "debug_together");
        Room open = newRoom(RoomMode.OPEN, null);
        assertThat(teach.isAiEnabled()).isFalse();
        assertThat(debug.isAiEnabled()).isTrue();
        assertThat(open.isAiEnabled()).isTrue();
        roomService.join(teach.getRoomId(), HOST, "Host", "host-secret");
        Member guest = roomService.join(teach.getRoomId(), GUEST, "Guest", null).getMember();
        assertThat(lookup(teach)).isFalse();
        assertThat(RoomMessages.joined(teach, guest).get("ai_enabled")).isEqualTo(false);
        assertThat(RoomMessages.roster(teach).get("ai_enabled")).isEqualTo(false);

        var denied = switchAi(teach, GUEST, null, "{\"ai_enabled\":true}");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(denied.body()).get("error").get("code").asText()).isEqualTo("NOT_OWNER");
        assertThat(switchAi(teach, HOST, "host-secret", "{\"ai_enabled\":\"true\"}").statusCode())
                .isEqualTo(422);
        assertThat(switchAi(teach, HOST, "host-secret", "{\"ai_enabled\":true}").statusCode())
                .isEqualTo(200);
        assertThat(lookup(teach)).isTrue();
        assertThat(switchAi(teach, HOST, "host-secret", "{\"ai_enabled\":false}").statusCode())
                .isEqualTo(200);
        assertThat(lookup(teach)).isFalse();
        assertThat(teach.withNewId(RoomIds.generate()).isAiEnabled()).isFalse();

        roomService.join(debug.getRoomId(), HOST, "Debug Host", "host-secret");
        roomService.join(debug.getRoomId(), GUEST, "Debug Guest", null);
        var debugDenied = switchAi(debug, GUEST, null, "{\"ai_enabled\":false}");
        assertThat(debugDenied.statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(debugDenied.body()).get("error").get("code").asText())
                .isEqualTo("NOT_OWNER");
        assertThat(debug.isAiEnabled()).isTrue();
        assertThat(open.isAiEnabled()).isTrue();
    }

    @Test
    void sendsRoomStateToJoinedControlSocketsWithoutExposingOwnerToken() throws Exception {
        Room room = newRoom(RoomMode.PAIR, "debug_together");
        roomService.join(room.getRoomId(), HOST, "Host", "host-secret");
        roomService.join(room.getRoomId(), GUEST, "Guest", null);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("us59-joined-control");
        when(socket.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("joined", true);
        when(socket.getAttributes()).thenReturn(attributes);
        registry.add(room.getRoomId(), socket);
        try {
            assertThat(switchAi(room, HOST, "host-secret", "{\"ai_enabled\":false}").statusCode())
                    .isEqualTo(200);
            var sent = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
            verify(socket, atLeastOnce()).sendMessage(sent.capture());
            JsonNode frame = mapper.readTree(sent.getValue().getPayload());
            assertThat(frame.get("type").asText()).isEqualTo("room-state");
            assertThat(frame.get("ai_enabled").asBoolean()).isFalse();
            assertThat(frame.has("owner_token")).isFalse();
        } finally {
            registry.remove(socket);
        }
    }
}
