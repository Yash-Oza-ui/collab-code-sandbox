package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for US-54 (V2.19) make-host: the owner-token reissue, the single-Host
 * invariant, the {@code host-granted} control message (new token, to the new Host
 * only, never in the HTTP response), and the V2.12 error cases.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"CLIENT_ORIGIN=*"})
class HostTransferControllerTest {

    private static final String HOST = "11111111-1111-4111-8111-111111111111";
    private static final String GUEST = "33333333-3333-4333-8333-333333333333";
    private static final String OTHER = "44444444-4444-4444-8444-444444444444";
    private static final String NOT_JOINED = "99999999-9999-4999-8999-999999999999";

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    RoomService roomService;

    @Autowired
    RoomStore store;

    private String base() {
        return "http://localhost:" + port;
    }

    private String[] createPairRoom() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"pair\",\"preset\":\"teach_interview\"}"))
                .build();
        JsonNode b = om.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
        return new String[]{b.get("room_id").asText(), b.get("owner_token").asText()};
    }

    private HttpResponse<String> makeHost(String roomId, String memberId, String sessionId, String ownerToken) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/members/" + memberId + "/make-host"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (sessionId != null) rb.header("X-Session-Id", sessionId);
        if (ownerToken != null) rb.header("X-Owner-Token", ownerToken);
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String code(HttpResponse<String> res) throws Exception {
        return om.readTree(res.body()).get("error").get("code").asText();
    }

    // ------------------------------------------------- happy path (socket)

    @Test
    void makeHost_transfersRole_deliversTokenToNewHost_andReissues() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]); // current Host (REST caller)
        Ws guest = Ws.connect(port, "/ws/rooms/" + room[0] + "?session_id=" + GUEST);
        guest.sendText("{\"type\":\"join\",\"display_name\":\"Lin\"}");
        String guestId = awaitMemberId(room[0], GUEST);

        HttpResponse<String> res = makeHost(room[0], guestId, HOST, room[1]);

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode b = om.readTree(res.body());
        assertThat(b.get("host_member_id").asText()).isEqualTo(guestId);
        assertThat(b.get("status").asText()).isEqualTo("transferred");
        assertThat(res.body()).doesNotContain("owner_token"); // token NEVER in the HTTP response

        // the new Host receives host-granted with the new token on its control socket
        String granted = guest.awaitText("host-granted", 3, TimeUnit.SECONDS);
        assertThat(granted).isNotNull();
        JsonNode hg = om.readTree(granted);
        assertThat(hg.get("member_id").asText()).isEqualTo(guestId);
        String newToken = hg.get("owner_token").asText();
        assertThat(newToken).isNotBlank();

        // server state: exactly one Host (the new one), and the token was reissued
        assertThat(store.get(room[0]).getMembers().get(guestId).getRole()).isEqualTo(Role.HOST);
        assertThat(store.get(room[0]).getMembers().values().stream()
                .filter(m -> m.getRole() == Role.HOST).count()).isEqualTo(1);
        assertThat(OwnerTokens.matches(newToken, store.get(room[0]).getOwnerTokenHash())).isTrue();
        assertThat(OwnerTokens.matches(room[1], store.get(room[0]).getOwnerTokenHash())).isFalse();
        guest.close();
    }

    @Test
    void makeHost_oldOwnerTokenNoLongerTransfers() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();
        String otherId = roomService.join(room[0], OTHER, "Max", null).getMember().getMemberId();

        assertThat(makeHost(room[0], guestId, HOST, room[1]).statusCode()).isEqualTo(200);

        // the previous owner token can no longer act as Host
        HttpResponse<String> withOldToken = makeHost(room[0], otherId, HOST, room[1]);
        assertThat(withOldToken.statusCode()).isEqualTo(403);
        assertThat(code(withOldToken)).isEqualTo("NOT_OWNER");
    }

    // ----------------------------------------------------------- error cases

    @Test
    void nonHostCannotTransfer_isNotOwner() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        roomService.join(room[0], GUEST, "Guest", null);
        String otherId = roomService.join(room[0], OTHER, "Max", null).getMember().getMemberId();

        HttpResponse<String> res = makeHost(room[0], otherId, GUEST, null);
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(code(res)).isEqualTo("NOT_OWNER");
    }

    @Test
    void transferToSelf_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        String hostId = roomService.join(room[0], HOST, "Host", room[1]).getMember().getMemberId();

        HttpResponse<String> res = makeHost(room[0], hostId, HOST, room[1]);
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(code(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void transferToObserver_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();
        roomService.changeRole(room[0], guestId, Role.OBSERVER); // make the target an Observer

        HttpResponse<String> res = makeHost(room[0], guestId, HOST, room[1]);
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(code(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void unknownMember_isMemberNotFound() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);

        HttpResponse<String> res = makeHost(room[0], "m_nope", HOST, room[1]);
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(code(res)).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void unknownRoom_isRoomNotFound() throws Exception {
        HttpResponse<String> res = makeHost("NoSuchRoom0000000000AA", "m_x", HOST, "t");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(code(res)).isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    void callerNotJoined_isSessionNotFound() throws Exception {
        String[] room = createPairRoom();
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();

        HttpResponse<String> res = makeHost(room[0], guestId, NOT_JOINED, room[1]);
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(code(res)).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void malformedSession_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);

        HttpResponse<String> res = makeHost(room[0], "m_x", "not-a-uuid", room[1]);
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(code(res)).isEqualTo("INVALID_REQUEST");
    }

    private String awaitMemberId(String roomId, String sessionId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var m = roomService.findMemberBySession(roomId, sessionId);
            if (m != null) {
                return m.getMemberId();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("member did not join: " + sessionId);
    }

    /** Minimal WebSocket client that captures text frames and the close status. */
    static final class Ws implements AutoCloseable {
        private final WebSocketSession session;
        private final BlockingQueue<String> texts;
        private final BlockingQueue<CloseStatus> closes;

        private Ws(WebSocketSession session, BlockingQueue<String> texts, BlockingQueue<CloseStatus> closes) {
            this.session = session;
            this.texts = texts;
            this.closes = closes;
        }

        static Ws connect(int port, String path) throws Exception {
            BlockingQueue<String> texts = new LinkedBlockingDeque<>();
            BlockingQueue<CloseStatus> closes = new LinkedBlockingDeque<>();
            WebSocketSession s = new StandardWebSocketClient().execute(new AbstractWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                    texts.add(m.getPayload());
                }

                @Override
                public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                    closes.add(status);
                }
            }, "ws://localhost:" + port + path).get(5, TimeUnit.SECONDS);
            return new Ws(s, texts, closes);
        }

        void sendText(String text) throws Exception {
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        }

        String awaitText(String containingType, long t, TimeUnit u) throws InterruptedException {
            long deadline = System.nanoTime() + u.toNanos(t);
            while (System.nanoTime() < deadline) {
                long remMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                String msg = texts.poll(remMs, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    return null;
                }
                if (msg.contains("\"type\":\"" + containingType + "\"")) {
                    return msg;
                }
            }
            return null;
        }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) {
                session.close();
            }
        }
    }
}
