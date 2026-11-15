package com.collabcode.syncservice;

import com.collabcode.syncservice.handler.DocumentHandler;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomStore;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for sync-service (T-02 + T-20).
 *
 * <p>T-20 changes: rooms must exist before connecting (created via POST /v1/rooms).
 * The single relay socket is split into a control socket (text/join) and a document
 * socket (binary Yjs). Tests that require binary relay connect to the document socket;
 * tests that verify control messages use the control socket.
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li>{@link #healthEndpointReturns200} — /actuator/health → 200</li>
 *   <li>{@link #binaryFrameRelayedUnchangedToOtherClient} — bytes arrive identical (doc socket)</li>
 *   <li>{@link #senderDoesNotReceiveOwnFrame} — no echo to sender for non-awareness frames</li>
 *   <li>{@link #roomIsolation_clientInDifferentRoomReceivesNothing} — room boundary</li>
 *   <li>{@link #awarenessFrameEchoedBackToSender} — first-byte==1 echo (doc socket)</li>
 *   <li>{@link #disconnectedClientRemovedFromRoom} — close B, A sends, no server error</li>
 *   <li>{@link #internalEndpoint_broadcastsToAllInRoom} — POST /internal delivers to control sessions</li>
 *   <li>{@link #internalEndpoint_returns401ForBadToken} — missing/wrong token → 401</li>
 *   <li>{@link #internalEndpoint_returns400ForMissingTypeField} — body without type → 400</li>
 *   <li>{@link #connectionRejected_forMalformedRoomId} — close 4400 on bad room id</li>
 *   <li>{@link #connectionRejected_forMissingSessionId} — close 4400 on missing session_id</li>
 *   <li>{@link #controlSocket_unknownRoom_closes4404} — unknown room → 4404 (T-20)</li>
 *   <li>{@link #documentSocket_unknownRoom_closes4404} — unknown room → 4404 on doc socket (T-20)</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "CLIENT_ORIGIN=*",
                "INTERNAL_API_TOKEN=test-token-ci"
        }
)
class RelayIntegrationTest {

    @Autowired
    private RoomStore roomStore;

    @LocalServerPort
    int port;

    private static final String SESSION_A = "a0000000-0000-4000-8000-000000000001";
    private static final String SESSION_B = "b0000000-0000-4000-8000-000000000002";
    private static final String SESSION_C = "c0000000-0000-4000-8000-000000000003";
    private static final String TOKEN = "test-token-ci";

    // -----------------------------------------------------------------------
    // Room creation helper (T-20: room must exist before connecting)
    // -----------------------------------------------------------------------

    /** Create a real room via POST /v1/rooms; returns {room_id, owner_token, ...}. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> createRoom() {
        TestRestTemplate rest = new TestRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        HttpEntity<String> req = new HttpEntity<>(
                "{\"mode\":\"pair\",\"preset\":\"debug_together\"}", headers);
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port + "/v1/rooms", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

        @BeforeEach
    void seedTestRooms() {
        seedRoom("room-relay-test");
        seedRoom("room-echo-test");
        seedRoom("room-isolation-1");
        seedRoom("room-isolation-2");
        seedRoom("room-awareness-test");
        seedRoom("room-disconnect-test");
        seedRoom("room-internal-test");
    }

    private void seedRoom(String roomId) {
        roomStore.put(new Room(
                roomId,
                RoomMode.PAIR,
                "debug_together",
                null,
                "test-owner-token-hash"
        ));
    }

    // -----------------------------------------------------------------------
    // 1. Health endpoint
    // -----------------------------------------------------------------------

    @Test
    void healthEndpointReturns200() {
        TestRestTemplate rest = new TestRestTemplate();
        ResponseEntity<String> resp =
                rest.getForEntity("http://localhost:" + port + "/actuator/health", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -----------------------------------------------------------------------
    // 2. Binary relay — bytes arrive unchanged (document socket)
    // -----------------------------------------------------------------------

    @Test
    void binaryFrameRelayedUnchangedToOtherClient() throws Exception {
        Map<String, Object> room = createRoom();
        String roomId = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        // Join both clients on the control socket first (required by T-20)
        try (WsClient ctrlA = connectControl(roomId, SESSION_A);
             WsClient ctrlB = connectControl(roomId, SESSION_B)) {
            sendJoin(ctrlA, "Alice", ownerToken);
            drainText(ctrlA, 2); // joined + roster
            sendJoin(ctrlB, "Bob", null);
            drainText(ctrlB, 2); // joined + roster
            drainText(ctrlA, 1); // roster update to A

            try (WsClient docA = connectDoc(roomId, "main", SESSION_A);
                 WsClient docB = connectDoc(roomId, "main", SESSION_B)) {

                // A non-document-change frame: awareness (byte 0 = 1) to avoid edit check
                byte[] payload = {0x01, 0x01, (byte) 0xFF, 0x42, 0x7F};
                docA.sendBinary(payload);

                byte[] received = docB.pollBinary(3, TimeUnit.SECONDS);
                assertThat(received)
                        .as("B should receive exactly the bytes A sent")
                        .isEqualTo(payload);
            }
        }
    }

    // -----------------------------------------------------------------------
    // 3. Sender must NOT receive its own non-awareness frame
    // -----------------------------------------------------------------------

    @Test
    void senderDoesNotReceiveOwnFrame() throws Exception {
        Map<String, Object> room = createRoom();
        String roomId = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient ctrlA = connectControl(roomId, SESSION_A);
             WsClient ctrlB = connectControl(roomId, SESSION_B)) {
            sendJoin(ctrlA, "Alice", ownerToken);
            drainText(ctrlA, 2);
            sendJoin(ctrlB, "Bob", null);
            drainText(ctrlB, 2);
            drainText(ctrlA, 1);

            try (WsClient docA = connectDoc(roomId, "main", SESSION_A);
                 WsClient docB = connectDoc(roomId, "main", SESSION_B)) {

                // step-1 (byte0=0, byte1=0): not a document change → no edit check needed;
                // also not awareness → should NOT be echoed back to sender
                byte[] frame = {0x00, 0x00, 0x03};
                docA.sendBinary(frame);

                assertThat(docB.pollBinary(2, TimeUnit.SECONDS)).isNotNull();
                assertThat(docA.pollBinary(500, TimeUnit.MILLISECONDS))
                        .as("Sender must not receive its own non-awareness frame")
                        .isNull();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 4. Room isolation — client in a different room receives nothing
    // -----------------------------------------------------------------------

    @Test
    void roomIsolation_clientInDifferentRoomReceivesNothing() throws Exception {
        Map<String, Object> room1 = createRoom();
        Map<String, Object> room2 = createRoom();
        String room1Id = (String) room1.get("room_id");
        String room2Id = (String) room2.get("room_id");
        String ownerToken1 = (String) room1.get("owner_token");
        String ownerToken2 = (String) room2.get("owner_token");

        try (WsClient ctrlA = connectControl(room1Id, SESSION_A);
             WsClient ctrlB = connectControl(room1Id, SESSION_B);
             WsClient ctrlC = connectControl(room2Id, SESSION_C)) {
            sendJoin(ctrlA, "Alice", ownerToken1);
            drainText(ctrlA, 2);
            sendJoin(ctrlB, "Bob", null);
            drainText(ctrlB, 2);
            drainText(ctrlA, 1);
            sendJoin(ctrlC, "Carol", ownerToken2);
            drainText(ctrlC, 2);

            try (WsClient docA = connectDoc(room1Id, "main", SESSION_A);
                 WsClient docB = connectDoc(room1Id, "main", SESSION_B);
                 WsClient docC = connectDoc(room2Id, "main", SESSION_C)) {

                docA.sendBinary(new byte[]{0x01, 0x05}); // awareness

                assertThat(docB.pollBinary(3, TimeUnit.SECONDS))
                        .as("Client in same room must receive the frame").isNotNull();
                assertThat(docC.pollBinary(500, TimeUnit.MILLISECONDS))
                        .as("Client in a different room must receive nothing").isNull();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5. Awareness frame echoed back to sender
    // -----------------------------------------------------------------------

    @Test
    void awarenessFrameEchoedBackToSender() throws Exception {
        Map<String, Object> room = createRoom();
        String roomId = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient ctrl = connectControl(roomId, SESSION_A)) {
            sendJoin(ctrl, "Alice", ownerToken);
            drainText(ctrl, 2);

            try (WsClient doc = connectDoc(roomId, "main", SESSION_A)) {
                byte[] awareness = {DocumentHandler.AWARENESS_BYTE, 0x10, 0x20};
                doc.sendBinary(awareness);

                byte[] received = doc.pollBinary(3, TimeUnit.SECONDS);
                assertThat(received)
                        .as("Sender must receive its own awareness frame back")
                        .isEqualTo(awareness);
            }
        }
    }

    // -----------------------------------------------------------------------
    // 6. Disconnected client removed from room — no server error
    // -----------------------------------------------------------------------

    @Test
    void disconnectedClientRemovedFromRoom() throws Exception {
        Map<String, Object> room = createRoom();
        String roomId = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient ctrlA = connectControl(roomId, SESSION_A)) {
            sendJoin(ctrlA, "Alice", ownerToken);
            drainText(ctrlA, 2);

            WsClient ctrlB = connectControl(roomId, SESSION_B);
            sendJoin(ctrlB, "Bob", null);
            drainText(ctrlB, 2);
            drainText(ctrlA, 1);

            WsClient docA = connectDoc(roomId, "main", SESSION_A);
            WsClient docB = connectDoc(roomId, "main", SESSION_B);

            docB.close();
            Thread.sleep(200);

            docA.sendBinary(new byte[]{0x01, 0x09}); // awareness
            assertThat(docA.session().isOpen()).isTrue();

            docA.close();
            ctrlB.close();
        }
    }

    // -----------------------------------------------------------------------
    // 7. Internal endpoint — broadcasts to all control sessions in the room
    // -----------------------------------------------------------------------

    @Test
    void internalEndpoint_broadcastsToAllInRoom() throws Exception {
        Map<String, Object> room = createRoom();
        String roomId = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient ctrlA = connectControl(roomId, SESSION_A);
             WsClient ctrlB = connectControl(roomId, SESSION_B)) {
            sendJoin(ctrlA, "Alice", ownerToken);
            drainText(ctrlA, 2);
            sendJoin(ctrlB, "Bob", null);
            drainText(ctrlB, 2);
            drainText(ctrlA, 1);

            Thread.sleep(100);

            String body = "{\"type\":\"run-result\",\"request_id\":\"00000000-0000-4000-8000-000000000001\"}";
            HttpResponse<String> resp = postInternal(roomId, body);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"delivered\":2");

            String receivedByA = ctrlA.pollText(3, TimeUnit.SECONDS);
            String receivedByB = ctrlB.pollText(3, TimeUnit.SECONDS);
            assertThat(receivedByA).as("A should receive the broadcast").isNotNull();
            assertThat(receivedByB).as("B should receive the broadcast").isNotNull();
            assertThat(receivedByA).contains("run-result");
        }
    }

    // -----------------------------------------------------------------------
    // 8. Internal endpoint — 401 on bad token
    // -----------------------------------------------------------------------

    @Test
    void internalEndpoint_returns401ForBadToken() throws Exception {
        HttpResponse<String> resp = postInternalWithToken(
                "test-room", "{\"type\":\"run-result\"}", "wrong-token");
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    // -----------------------------------------------------------------------
    // 9. Internal endpoint — 400 when "type" field is absent
    // -----------------------------------------------------------------------

    @Test
    void internalEndpoint_returns400ForMissingTypeField() throws Exception {
        HttpResponse<String> resp = postInternal("test-room", "{\"data\":\"no type here\"}");
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    // -----------------------------------------------------------------------
    // 10. Control socket: 4400 for malformed room id
    // -----------------------------------------------------------------------

    @Test
    void connectionRejected_forMalformedRoomId() throws Exception {
        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/bad%20room!?session_id=" + SESSION_A
        ).get(5, TimeUnit.SECONDS);

        Thread.sleep(500);

        assertThat(session.isOpen())
                .as("Session with malformed room id must be closed by the server")
                .isFalse();
        session.close();
    }

    // -----------------------------------------------------------------------
    // 11. Control socket: 4400 for missing session_id
    // -----------------------------------------------------------------------

    @Test
    void connectionRejected_forMissingSessionId() throws Exception {
        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/valid-room"
        ).get(5, TimeUnit.SECONDS);

        Thread.sleep(500);

        assertThat(session.isOpen())
                .as("Session without session_id must be closed by the server")
                .isFalse();
        session.close();
    }

    // -----------------------------------------------------------------------
    // 12. Control socket: 4404 for an unknown room (T-20)
    // -----------------------------------------------------------------------

    @Test
    void controlSocket_unknownRoom_closes4404() throws Exception {
        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/DOESNOTEXIST0000000000?session_id=" + SESSION_A
        ).get(5, TimeUnit.SECONDS);

        Thread.sleep(500);

        assertThat(session.isOpen())
                .as("Control socket to unknown room must be closed")
                .isFalse();

        assertThat(handler.closes).isNotEmpty();
        assertThat(handler.closes.get(0).getCode()).isEqualTo(4404);

        session.close();
    }

    // -----------------------------------------------------------------------
    // 13. Document socket: 4404 for an unknown room (T-20)
    // -----------------------------------------------------------------------

    @Test
    void documentSocket_unknownRoom_closes4404() throws Exception {
        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/DOESNOTEXIST0000000000/docs/main?session_id=" + SESSION_A
        ).get(5, TimeUnit.SECONDS);

        Thread.sleep(500);

        assertThat(session.isOpen())
                .as("Document socket to unknown room must be closed")
                .isFalse();

        assertThat(handler.closes).isNotEmpty();
        assertThat(handler.closes.get(0).getCode()).isEqualTo(4404);

        session.close();
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private WsClient connectControl(String roomId, String sessionId) throws Exception {
        WsClient c = new WsClient(port, "/ws/rooms/" + roomId + "?session_id=" + sessionId);
        Thread.sleep(100);
        return c;
    }

    private WsClient connectDoc(String roomId, String docId, String sessionId) throws Exception {
        WsClient c = new WsClient(port,
                "/ws/rooms/" + roomId + "/docs/" + docId + "?session_id=" + sessionId);
        Thread.sleep(100);
        return c;
    }

    private void sendJoin(WsClient c, String displayName, String ownerToken) throws IOException {
        String ownerField = ownerToken != null
                ? ",\"owner_token\":\"" + ownerToken + "\""
                : "";
        c.sendText("{\"type\":\"join\",\"display_name\":\"" + displayName + "\"" + ownerField + "}");
    }

    /** Drain up to {@code n} text messages from the queue (ignore content). */
    private void drainText(WsClient c, int n) throws InterruptedException {
        for (int i = 0; i < n; i++) {
            c.pollText(2, TimeUnit.SECONDS);
        }
    }

    private HttpResponse<String> postInternalWithToken(String roomId, String body, String token)
            throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/internal/rooms/" + roomId + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("X-Internal-Token", token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postInternal(String roomId, String body) throws Exception {
        return postInternalWithToken(roomId, body, TOKEN);
    }

    // -----------------------------------------------------------------------
    // WsClient — wraps a WebSocketSession and captures incoming frames
    // -----------------------------------------------------------------------

    static class WsClient implements AutoCloseable {

        private final WebSocketSession session;
        private final BlockingQueue<byte[]> binaryQueue = new LinkedBlockingDeque<>();
        private final BlockingQueue<String> textQueue   = new LinkedBlockingDeque<>();

        WsClient(int port, String path) throws Exception {
            StandardWebSocketClient client = new StandardWebSocketClient();
            String url = "ws://localhost:" + port + path;
            session = client.execute(new AbstractWebSocketHandler() {

                @Override
                protected void handleBinaryMessage(WebSocketSession s, BinaryMessage msg) {
                    ByteBuffer view = msg.getPayload().duplicate();
                    view.rewind();
                    byte[] bytes = new byte[view.remaining()];
                    view.get(bytes);
                    binaryQueue.add(bytes);
                }

                @Override
                protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
                    textQueue.add(msg.getPayload());
                }

            }, url).get(5, TimeUnit.SECONDS);
        }

        void sendBinary(byte[] bytes) throws IOException {
            synchronized (session) { session.sendMessage(new BinaryMessage(bytes)); }
        }

        void sendText(String text) throws IOException {
            synchronized (session) { session.sendMessage(new TextMessage(text)); }
        }

        byte[] pollBinary(long timeout, TimeUnit unit) throws InterruptedException {
            return binaryQueue.poll(timeout, unit);
        }

        String pollText(long timeout, TimeUnit unit) throws InterruptedException {
            return textQueue.poll(timeout, unit);
        }

        WebSocketSession session() { return session; }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) session.close();
        }
    }

    // -----------------------------------------------------------------------
    // CapturingHandler — minimal handler for negative tests
    // -----------------------------------------------------------------------

    static class CapturingHandler extends AbstractWebSocketHandler {
        final List<CloseStatus> closes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closes.add(status);
        }
    }
}
