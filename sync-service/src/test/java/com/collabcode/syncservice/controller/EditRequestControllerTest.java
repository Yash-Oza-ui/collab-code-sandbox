package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.JoinOutcome;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for US-38 (FR19.3, V2.22) Host approval and decline of edit access requests:
 * {@code POST /v1/rooms/{roomId}/edit-requests/{memberId}/approve}
 * {@code POST /v1/rooms/{roomId}/edit-requests/{memberId}/decline}
 * {@code GET /v1/rooms/{roomId}/edit-requests}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EditRequestControllerTest {

    private static final String HOST = "11111111-1111-4111-8111-111111111111";
    private static final String GUEST = "33333333-3333-4333-8333-333333333333";
    private static final String OTHER = "44444444-4444-4444-8444-444444444444";
    private static final String NOT_JOINED = "99999999-9999-4999-8999-999999999999";

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomStore store;

    private String base() {
        return "http://localhost:" + port;
    }

    private String[] createRoom(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode json = om.readTree(resp.body());
        return new String[]{json.get("room_id").asText(), json.get("owner_token").asText()};
    }

    private String[] createPairRoom() throws Exception {
        return createRoom("{\"mode\":\"pair\",\"preset\":\"teach_interview\"}");
    }

    private String[] createOpenRoom() throws Exception {
        return createRoom("{\"mode\":\"open\",\"language\":\"python\"}");
    }

    @Test
    void approve_givesEditAccess_andClearsRequest() throws Exception {
        String[] room = createPairRoom();
        String roomId = room[0];
        String token = room[1];

        roomService.join(roomId, HOST, "Host", token);
        JoinOutcome guestJoin = roomService.join(roomId, GUEST, "Lin", null);
        String guestMemberId = guestJoin.getMember().getMemberId();
        assertThat(guestJoin.getMember().isCanEdit()).isFalse();

        Room r = store.get(roomId);
        r.addEditRequest(guestMemberId, "Lin", Instant.now());
        assertThat(r.hasPendingEditRequest(guestMemberId)).isTrue();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests/" + guestMemberId + "/approve"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("member_id").asText()).isEqualTo(guestMemberId);
        assertThat(json.get("permissions").get("can_edit").asBoolean()).isTrue();

        Member target = r.getMembers().get(guestMemberId);
        assertThat(target.isCanEdit()).isTrue();
        assertThat(r.hasPendingEditRequest(guestMemberId)).isFalse();
    }

    @Test
    void decline_leavesEditAccessUnchanged_andClearsRequest() throws Exception {
        String[] room = createPairRoom();
        String roomId = room[0];
        String token = room[1];

        roomService.join(roomId, HOST, "Host", token);
        JoinOutcome guestJoin = roomService.join(roomId, GUEST, "Lin", null);
        String guestMemberId = guestJoin.getMember().getMemberId();
        assertThat(guestJoin.getMember().isCanEdit()).isFalse();

        Room r = store.get(roomId);
        r.addEditRequest(guestMemberId, "Lin", Instant.now());
        assertThat(r.hasPendingEditRequest(guestMemberId)).isTrue();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests/" + guestMemberId + "/decline"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("member_id").asText()).isEqualTo(guestMemberId);
        assertThat(json.get("status").asText()).isEqualTo("declined");

        Member target = r.getMembers().get(guestMemberId);
        assertThat(target.isCanEdit()).isFalse();
        assertThat(r.hasPendingEditRequest(guestMemberId)).isFalse();
    }

    @Test
    void approve_noPendingRequest_isNotFound() throws Exception {
        String[] room = createPairRoom();
        String roomId = room[0];
        String token = room[1];

        roomService.join(roomId, HOST, "Host", token);
        JoinOutcome guestJoin = roomService.join(roomId, GUEST, "Lin", null);
        String guestMemberId = guestJoin.getMember().getMemberId();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests/" + guestMemberId + "/approve"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void decline_noPendingRequest_isNotFound() throws Exception {
        String[] room = createPairRoom();
        String roomId = room[0];
        String token = room[1];

        roomService.join(roomId, HOST, "Host", token);
        JoinOutcome guestJoin = roomService.join(roomId, GUEST, "Lin", null);
        String guestMemberId = guestJoin.getMember().getMemberId();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests/" + guestMemberId + "/decline"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void approve_targetObserver_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        String roomId = room[0];
        String token = room[1];

        roomService.join(roomId, HOST, "Host", token);
        JoinOutcome guestJoin = roomService.join(roomId, GUEST, "Lin", null);
        String guestMemberId = guestJoin.getMember().getMemberId();

        Room r = store.get(roomId);
        r.addEditRequest(guestMemberId, "Lin", Instant.now());
        // Target role changed to observer before approve
        guestJoin.getMember().setRole(Role.OBSERVER);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests/" + guestMemberId + "/approve"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(422);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(json.get("error").get("message").asText())
                .isEqualTo("Observers cannot have edit access; make them a participant first.");
        assertThat(r.hasPendingEditRequest(guestMemberId)).isFalse();
    }

    @Test
    void approve_targetHost_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        String roomId = room[0];
        String token = room[1];

        JoinOutcome hostJoin = roomService.join(roomId, HOST, "Host", token);
        String hostMemberId = hostJoin.getMember().getMemberId();

        Room r = store.get(roomId);
        r.addEditRequest(hostMemberId, "Host", Instant.now());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests/" + hostMemberId + "/approve"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(422);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(json.get("error").get("message").asText())
                .isEqualTo("The Host's edit access cannot be changed.");
        assertThat(r.hasPendingEditRequest(hostMemberId)).isFalse();
    }

    @Test
    void approve_openMode_isInvalidRequest() throws Exception {
        String[] room = createOpenRoom();
        String roomId = room[0];
        String token = room[1];

        roomService.join(roomId, HOST, "Host", token);
        JoinOutcome guestJoin = roomService.join(roomId, GUEST, "Lin", null);
        String guestMemberId = guestJoin.getMember().getMemberId();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests/" + guestMemberId + "/approve"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(422);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(json.get("error").get("message").asText())
                .contains("Edit-access requests apply only in Pair mode");
    }

    @Test
    void decline_openMode_isInvalidRequest() throws Exception {
        String[] room = createOpenRoom();
        String roomId = room[0];
        String token = room[1];

        roomService.join(roomId, HOST, "Host", token);
        JoinOutcome guestJoin = roomService.join(roomId, GUEST, "Lin", null);
        String guestMemberId = guestJoin.getMember().getMemberId();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests/" + guestMemberId + "/decline"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(422);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(json.get("error").get("message").asText())
                .contains("Edit-access requests apply only in Pair mode");
    }

    @Test
    void nonHost_approve_isForbidden() throws Exception {
        String[] room = createPairRoom();
        String roomId = room[0];
        String token = room[1];

        roomService.join(roomId, HOST, "Host", token);
        JoinOutcome guestJoin = roomService.join(roomId, GUEST, "Lin", null);
        String guestMemberId = guestJoin.getMember().getMemberId();

        Room r = store.get(roomId);
        r.addEditRequest(guestMemberId, "Lin", Instant.now());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests/" + guestMemberId + "/approve"))
                .header("X-Session-Id", GUEST)
                .header("X-Owner-Token", "wrong-token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(403);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("NOT_OWNER");
    }

    @Test
    void nonHost_decline_isForbidden() throws Exception {
        String[] room = createPairRoom();
        String roomId = room[0];
        String token = room[1];

        roomService.join(roomId, HOST, "Host", token);
        JoinOutcome guestJoin = roomService.join(roomId, GUEST, "Lin", null);
        String guestMemberId = guestJoin.getMember().getMemberId();

        Room r = store.get(roomId);
        r.addEditRequest(guestMemberId, "Lin", Instant.now());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests/" + guestMemberId + "/decline"))
                .header("X-Session-Id", GUEST)
                .header("X-Owner-Token", "wrong-token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(403);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("NOT_OWNER");
    }

    @Test
    void malformedSessionId_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + room[0] + "/edit-requests/m_123/approve"))
                .header("X-Session-Id", "not-a-uuid")
                .header("X-Owner-Token", room[1])
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(422);
    }

    @Test
    void callerNotJoined_isSessionNotFound() throws Exception {
        String[] room = createPairRoom();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + room[0] + "/edit-requests/m_123/approve"))
                .header("X-Session-Id", NOT_JOINED)
                .header("X-Owner-Token", room[1])
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void roomNotFound_isRoomNotFound() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/unknown-room-12345/edit-requests/m_123/approve"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", "token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    void listPending_returnsPendingRequests() throws Exception {
        String[] room = createPairRoom();
        String roomId = room[0];
        String token = room[1];

        roomService.join(roomId, HOST, "Host", token);
        JoinOutcome guestJoin = roomService.join(roomId, GUEST, "Lin", null);
        String guestMemberId = guestJoin.getMember().getMemberId();

        Room r = store.get(roomId);
        r.addEditRequest(guestMemberId, "Lin", Instant.now());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/edit-requests"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", token)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode json = om.readTree(resp.body());
        assertThat(json.isArray()).isTrue();
        assertThat(json.size()).isEqualTo(1);
        assertThat(json.get(0).get("member_id").asText()).isEqualTo(guestMemberId);
        assertThat(json.get(0).get("display_name").asText()).isEqualTo("Lin");
        assertThat(json.get(0).has("requested_at")).isTrue();
    }
}
