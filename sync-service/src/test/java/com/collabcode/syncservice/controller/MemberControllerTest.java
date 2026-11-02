package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.JoinOutcome;
import com.collabcode.syncservice.room.Role;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the member role change PATCH /v1/rooms/{room_id}/members/{member_id}
 * (US-27 server part, V2.17). Uses the JDK HttpClient because TestRestTemplate's
 * default factory does not support PATCH.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemberControllerTest {

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

    /** Create a Pair/Teach-Interview room, returning [roomId, ownerToken]. */
    private String[] createPairRoom() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"pair\",\"preset\":\"teach_interview\"}"))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode b = om.readTree(res.body());
        return new String[]{b.get("room_id").asText(), b.get("owner_token").asText()};
    }

    private HttpResponse<String> patch(String roomId, String memberId, String sessionId,
                                       String ownerToken, String bodyJson) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/members/" + memberId))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyJson));
        if (sessionId != null) {
            rb.header("X-Session-Id", sessionId);
        }
        if (ownerToken != null) {
            rb.header("X-Owner-Token", ownerToken);
        }
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String errorCode(HttpResponse<String> res) throws Exception {
        return om.readTree(res.body()).get("error").get("code").asText();
    }

    @Test
    void hostMakesGuestObserver() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], "host-sess", "Host", room[1]); // Host (valid token)
        JoinOutcome guest = roomService.join(room[0], "guest-sess", "Guest", null); // participant
        String guestId = guest.getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], guestId, "host-sess", room[1], "{\"role\":\"observer\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode b = om.readTree(res.body());
        assertThat(b.get("member_id").asText()).isEqualTo(guestId);
        assertThat(b.get("role").asText()).isEqualTo("observer");
        assertThat(b.get("permissions").get("can_edit").asBoolean()).isFalse();
        assertThat(b.get("permissions").get("can_run").asBoolean()).isFalse();
        // enforced on the server, not just in the response
        assertThat(store.get(room[0]).getMembers().get(guestId).getRole()).isEqualTo(Role.OBSERVER);
    }

    @Test
    void missingOrWrongOwnerToken_isNotOwner() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], "host-sess", "Host", room[1]);
        String guestId = roomService.join(room[0], "guest-sess", "Guest", null).getMember().getMemberId();

        HttpResponse<String> wrong = patch(room[0], guestId, "host-sess", "wrong-token", "{\"role\":\"observer\"}");
        assertThat(wrong.statusCode()).isEqualTo(403);
        assertThat(errorCode(wrong)).isEqualTo("NOT_OWNER");

        HttpResponse<String> missing = patch(room[0], guestId, "host-sess", null, "{\"role\":\"observer\"}");
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(errorCode(missing)).isEqualTo("NOT_OWNER");
    }

    @Test
    void callerNotInRoom_isSessionNotFound() throws Exception {
        String[] room = createPairRoom();
        String guestId = roomService.join(room[0], "guest-sess", "Guest", null).getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], guestId, "never-joined", room[1], "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(errorCode(res)).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void roleHost_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], "host-sess", "Host", room[1]);
        String guestId = roomService.join(room[0], "guest-sess", "Guest", null).getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], guestId, "host-sess", room[1], "{\"role\":\"host\"}");
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(errorCode(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void unknownMember_isMemberNotFound() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], "host-sess", "Host", room[1]);

        HttpResponse<String> res = patch(room[0], "m_doesnotexist", "host-sess", room[1], "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(errorCode(res)).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void unknownRoom_isRoomNotFound() throws Exception {
        HttpResponse<String> res = patch("NoSuchRoom0000000000AA", "m_x", "s", "t", "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(errorCode(res)).isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    void hostCannotChangeOwnRole() throws Exception {
        String[] room = createPairRoom();
        String hostId = roomService.join(room[0], "host-sess", "Host", room[1]).getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], hostId, "host-sess", room[1], "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(errorCode(res)).isEqualTo("INVALID_REQUEST");
    }
}
