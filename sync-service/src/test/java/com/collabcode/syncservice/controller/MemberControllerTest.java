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
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the member role change PATCH /v1/rooms/{room_id}/members/{member_id}
 * (US-27 server part, V2.17). Uses the JDK HttpClient because TestRestTemplate's
 * default factory does not support PATCH. Session ids are real UUID v4 values
 * because the endpoint validates X-Session-Id as a UUID (contract §3.3, V2.6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemberControllerTest {

    // Valid UUID v4 session ids used by the tests.
    private static final String HOST = "11111111-1111-4111-8111-111111111111";
    private static final String HOST2 = "22222222-2222-4222-8222-222222222222";
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

    /** Create a room from a JSON body, returning [roomId, ownerToken]. */
    private String[] createRoom(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode b = om.readTree(res.body());
        return new String[]{b.get("room_id").asText(), b.get("owner_token").asText()};
    }

    private String[] createPairRoom() throws Exception {
        return createRoom("{\"mode\":\"pair\",\"preset\":\"teach_interview\"}");
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

    private TestControlSocket connectControlSocket(
            String roomId,
            String sessionId,
            String displayName,
            String ownerToken) {

        TestControlSocket listener = new TestControlSocket();

        String url = "ws://localhost:" + port
                + "/ws/rooms/" + roomId
                + "?session_id=" + sessionId;

        WebSocket socket = http.newWebSocketBuilder()
                .buildAsync(URI.create(url), listener)
                .join();

        String joinMessage = ownerToken == null
                ? "{\"type\":\"join\",\"display_name\":\"" + displayName + "\"}"
                : "{\"type\":\"join\",\"display_name\":\"" + displayName
                        + "\",\"owner_token\":\"" + ownerToken + "\"}";

        socket.sendText(joinMessage, true).join();
        return listener;
    }

    private static class TestControlSocket implements WebSocket.Listener {

        private final LinkedBlockingQueue<String> messages =
                new LinkedBlockingQueue<>();

        private final StringBuilder fragments = new StringBuilder();

        private volatile WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket) {
            this.socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last) {

            fragments.append(data);

            if (last) {
                messages.offer(fragments.toString());
                fragments.setLength(0);
            }

            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        boolean awaitRoster(String displayName, String role)
                throws InterruptedException {

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(5);

            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();

                String message = messages.poll(
                        remaining, TimeUnit.NANOSECONDS);

                if (message == null) {
                    return false;
                }

                try {
                    JsonNode root = new ObjectMapper().readTree(message);

                    if (!"roster".equals(root.path("type").asText())) {
                        continue;
                    }

                    for (JsonNode member : root.path("members")) {
                        if (displayName.equals(
                                member.path("display_name").asText())
                                && role.equals(member.path("role").asText())) {
                            return true;
                        }
                    }
                } catch (Exception ignored) {
                    // Ignore non-JSON or unrelated control messages.
                }
            }

            return false;
        }

        void close() {
            WebSocket current = socket;

            if (current != null && !current.isOutputClosed()) {
                current.sendClose(
                        WebSocket.NORMAL_CLOSURE, "test complete").join();
            }
        }
    }

    @Test
    void hostMakesGuestObserver() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        JoinOutcome guest = roomService.join(room[0], GUEST, "Guest", null);
        String guestId = guest.getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], guestId, HOST, room[1], "{\"role\":\"observer\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode b = om.readTree(res.body());
        assertThat(b.get("member_id").asText()).isEqualTo(guestId);
        assertThat(b.get("role").asText()).isEqualTo("observer");
        assertThat(b.get("permissions").get("can_edit").asBoolean()).isFalse();
        assertThat(b.get("permissions").get("can_run").asBoolean()).isFalse();
        assertThat(store.get(room[0]).getMembers().get(guestId).getRole()).isEqualTo(Role.OBSERVER);
    }

    @Test
    void missingOrWrongOwnerToken_isNotOwner() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

        HttpResponse<String> wrong = patch(room[0], guestId, HOST, "wrong-token", "{\"role\":\"observer\"}");
        assertThat(wrong.statusCode()).isEqualTo(403);
        assertThat(errorCode(wrong)).isEqualTo("NOT_OWNER");

        HttpResponse<String> missing = patch(room[0], guestId, HOST, null, "{\"role\":\"observer\"}");
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(errorCode(missing)).isEqualTo("NOT_OWNER");
    }

    @Test
    void callerNotInRoom_isSessionNotFound() throws Exception {
        String[] room = createPairRoom();
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

        // a well-formed UUID that never joined -> SESSION_NOT_FOUND (not INVALID_REQUEST)
        HttpResponse<String> res = patch(room[0], guestId, NOT_JOINED, room[1], "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(errorCode(res)).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void roleHost_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], guestId, HOST, room[1], "{\"role\":\"host\"}");
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(errorCode(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void unknownMember_isMemberNotFound() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);

        HttpResponse<String> res = patch(room[0], "m_doesnotexist", HOST, room[1], "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(errorCode(res)).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void unknownRoom_isRoomNotFound() throws Exception {
        HttpResponse<String> res = patch("NoSuchRoom0000000000AA", "m_x", HOST, "t", "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(errorCode(res)).isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    void hostCannotChangeOwnRole() throws Exception {
        String[] room = createPairRoom();
        String hostId = roomService.join(room[0], HOST, "Host", room[1]).getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], hostId, HOST, room[1], "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(errorCode(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void cannotDemoteAnotherHost() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        // a second Host session (same owner token, different session/name)
        String host2Id = roomService.join(room[0], HOST2, "Host2", room[1]).getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], host2Id, HOST, room[1], "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(errorCode(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void missingSessionIdHeader_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], guestId, null, room[1], "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(errorCode(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void malformedSessionId_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

        // a non-empty value that is not a UUID -> 422 INVALID_REQUEST, not 404
        HttpResponse<String> res = patch(room[0], guestId, "not-a-uuid", room[1], "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(errorCode(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void joinedGuestWithoutToken_isNotOwner() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        roomService.join(room[0], GUEST, "Guest", null);
        String targetId = roomService.join(room[0], OTHER, "Other", null).getMember().getMemberId();

        // a real guest, using their own session id, sends no owner token
        HttpResponse<String> res = patch(room[0], targetId, GUEST, null, "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(errorCode(res)).isEqualTo("NOT_OWNER");
    }

    @Test
    void observerBackToParticipant_teachInterview_canEditFalse() throws Exception {
        String[] room = createPairRoom(); // teach_interview
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

        patch(room[0], guestId, HOST, room[1], "{\"role\":\"observer\"}");
        HttpResponse<String> res = patch(room[0], guestId, HOST, room[1], "{\"role\":\"participant\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode p = om.readTree(res.body()).get("permissions");
        assertThat(p.get("can_run").asBoolean()).isTrue();
        assertThat(p.get("can_edit").asBoolean()).isFalse(); // teach_interview: only the Host edits
    }

    @Test
    void observerBackToParticipant_debugTogether_canEditTrue() throws Exception {
        String[] room = createRoom("{\"mode\":\"pair\",\"preset\":\"debug_together\"}");
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

        patch(room[0], guestId, HOST, room[1], "{\"role\":\"observer\"}");
        HttpResponse<String> res = patch(room[0], guestId, HOST, room[1], "{\"role\":\"participant\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode p = om.readTree(res.body()).get("permissions");
        assertThat(p.get("can_run").asBoolean()).isTrue();
        assertThat(p.get("can_edit").asBoolean()).isTrue(); // debug_together: everyone edits
    }

    @Test
    void settingSameRoleTwice_returns200() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

        assertThat(patch(room[0], guestId, HOST, room[1], "{\"role\":\"observer\"}").statusCode())
                .isEqualTo(200);
        assertThat(patch(room[0], guestId, HOST, room[1], "{\"role\":\"observer\"}").statusCode())
                .isEqualTo(200);
    }

    @Test
    void missingBody_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], guestId, HOST, room[1], "");
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(errorCode(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void openModeObserver_omitsCanEdit() throws Exception {
        String[] room = createRoom("{\"mode\":\"open\",\"language\":\"python\"}");
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

        HttpResponse<String> res = patch(room[0], guestId, HOST, room[1], "{\"role\":\"observer\"}");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode p = om.readTree(res.body()).get("permissions");
        assertThat(p.has("can_run")).isTrue();
        assertThat(p.has("can_edit")).isFalse(); // Open mode omits can_edit
    }

    @Test
    void hostRoleChange_broadcastsUpdatedRosterToConnectedControlSockets() throws Exception {
        String[] room = createPairRoom();

        TestControlSocket hostSocket = connectControlSocket(
                room[0], HOST, "Host", room[1]);
        TestControlSocket guestSocket = null;

        try {
            // Wait until the Host has joined successfully.
            assertThat(hostSocket.awaitRoster("Host", "host")).isTrue();

            guestSocket = connectControlSocket(
                    room[0], GUEST, "Guest", null);

            // Both clients must receive the roster containing the Guest.
            assertThat(hostSocket.awaitRoster("Guest", "participant")).isTrue();
            assertThat(guestSocket.awaitRoster("Guest", "participant")).isTrue();

            String guestId = roomService.findMemberBySession(room[0], GUEST)
                    .getMemberId();

            HttpResponse<String> response = patch(
                    room[0], guestId, HOST, room[1], "{\"role\":\"observer\"}");

            assertThat(response.statusCode()).isEqualTo(200);

            // The role-change broadcast must reach both existing control sockets.
            assertThat(hostSocket.awaitRoster("Guest", "observer")).isTrue();
            assertThat(guestSocket.awaitRoster("Guest", "observer")).isTrue();
        } finally {
            hostSocket.close();
            if (guestSocket != null) {
                guestSocket.close();
            }
        }
    }
}
