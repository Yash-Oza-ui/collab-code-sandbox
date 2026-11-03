package com.collabcode.syncservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the T-20 Bhavya acceptance criteria:
 * control socket join flow, NAME_TAKEN, edit-permission check (CR13),
 * contract reasons, doc_id isolation and close code 4406.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "CLIENT_ORIGIN=*",
                "INTERNAL_API_TOKEN=test-token-ci"
        }
)
class SocketsAndEditCheckIntegrationTest {

    @LocalServerPort
    int port;

    private static final String SESSION_A = "aa000000-0000-4000-8000-000000000001";
    private static final String SESSION_B = "bb000000-0000-4000-8000-000000000002";
    private static final String SESSION_C = "cc000000-0000-4000-8000-000000000003";

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> createRoom(String mode, String preset, String language) {
        TestRestTemplate rest = new TestRestTemplate();
        String body = "pair".equals(mode)
                ? "{\"mode\":\"pair\",\"preset\":\"" + preset + "\"}"
                : "{\"mode\":\"open\",\"language\":\"" + language + "\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        HttpEntity<String> req = new HttpEntity<>(body, headers);
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port + "/v1/rooms", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private WsClient connectControl(String roomId, String sessionId) throws Exception {
        WsClient c = new WsClient(port, "/ws/rooms/" + roomId + "?session_id=" + sessionId);
        Thread.sleep(150);
        return c;
    }

    private WsClient connectDoc(String roomId, String docId, String sessionId) throws Exception {
        WsClient c = new WsClient(port,
                "/ws/rooms/" + roomId + "/docs/" + docId + "?session_id=" + sessionId);
        Thread.sleep(150);
        return c;
    }

    private void sendJoin(WsClient c, String displayName, String ownerToken) throws IOException {
        String ownerField = ownerToken != null ? ",\"owner_token\":\"" + ownerToken + "\"" : "";
        c.sendText("{\"type\":\"join\",\"display_name\":\"" + displayName + "\"" + ownerField + "}");
    }

    private void drainText(WsClient c, int n) throws InterruptedException {
        for (int i = 0; i < n; i++) c.pollText(2, TimeUnit.SECONDS);
    }

    /** Read until a matching control message arrives, ignoring asynchronous roster updates. */
    private String pollControlMessage(WsClient client, String expectedType,
                                      long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            String message = client.pollText(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)),
                    TimeUnit.MILLISECONDS);
            if (message == null) {
                return null;
            }
            if (message.contains("\"type\":\"" + expectedType + "\"")) {
                return message;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // 1. Join + joined + roster flow on the control socket
    // -----------------------------------------------------------------------

    @Test
    void joinFlow_returnsJoinedAndRoster() throws Exception {
        Map<String, Object> room = createRoom("pair", "teach_interview", null);
        String roomId     = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient host = connectControl(roomId, SESSION_A)) {
            sendJoin(host, "Alice", ownerToken);

            String joinedMsg = host.pollText(3, TimeUnit.SECONDS);
            assertThat(joinedMsg)
                    .as("Host must receive 'joined'")
                    .contains("\"type\":\"joined\"", "\"role\":\"host\"");

            String rosterMsg = host.pollText(3, TimeUnit.SECONDS);
            assertThat(rosterMsg)
                    .as("Host must receive 'roster' after joining")
                    .contains("\"type\":\"roster\"", "\"display_name\":\"Alice\"");
        }
    }

    // -----------------------------------------------------------------------
    // 2. NAME_TAKEN: second session with the same display name is refused
    // -----------------------------------------------------------------------

    @Test
    void nameTaken_secondSessionRefused() throws Exception {
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId     = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient a = connectControl(roomId, SESSION_A);
             WsClient b = connectControl(roomId, SESSION_B)) {

            sendJoin(a, "Alice", ownerToken);
            drainText(a, 2); // joined + roster

            sendJoin(b, "Alice", null); // same name, different session

            String refused = pollControlMessage(b, "join-refused", 3, TimeUnit.SECONDS);
            assertThat(refused)
                    .as("Second session with same name must get join-refused")
                    .contains("\"type\":\"join-refused\"", "NAME_TAKEN");
        }
    }

    // -----------------------------------------------------------------------
    // 3. Edit check: forbidden sender's frame is dropped; edit-rejected sent (Pair mode)
    // -----------------------------------------------------------------------

    @Test
    void editCheck_forbiddenSender_frameDroppedAndEditRejectedSent() throws Exception {
        // teach_interview: only Host can edit by default; participant cannot
        Map<String, Object> room = createRoom("pair", "teach_interview", null);
        String roomId     = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient ctrlA = connectControl(roomId, SESSION_A);
             WsClient ctrlB = connectControl(roomId, SESSION_B)) {

            sendJoin(ctrlA, "Alice", ownerToken);
            drainText(ctrlA, 2);
            sendJoin(ctrlB, "Bob", null);
            drainText(ctrlB, 2);
            drainText(ctrlA, 1); // roster update pushed to A

            try (WsClient docA = connectDoc(roomId, "main", SESSION_A);
                 WsClient docB = connectDoc(roomId, "main", SESSION_B)) {

                // B sends a Yjs sync step-2 frame (0 1: document change); B cannot edit
                byte[] docChangeFrame = {0x00, 0x01, 0x10, 0x20};
                docB.sendBinary(docChangeFrame);

                // A must NOT receive the frame (dropped)
                byte[] received = docA.pollBinary(500, TimeUnit.MILLISECONDS);
                assertThat(received)
                        .as("Forbidden sender's doc-change frame must not be forwarded")
                        .isNull();

                // B's control socket must receive edit-rejected with T-17 contract reason
                String rejected = pollControlMessage(ctrlB, "edit-rejected", 3, TimeUnit.SECONDS);
                assertThat(rejected)
                        .as("Forbidden sender must receive edit-rejected on control socket")
                        .contains("\"type\":\"edit-rejected\"")
                        .contains("\"doc_id\":\"main\"")
                        .contains("\"reason\":\"no_edit_access\"");
            }
        }
    }

    // -----------------------------------------------------------------------
    // 4. Edit check: forbidden sender in Open mode returns "not_module_writer"
    // -----------------------------------------------------------------------

    @Test
    void editCheck_openMode_notModuleWriterReason() throws Exception {
        Map<String, Object> room = createRoom("open", null, "python");
        String roomId     = (String) room.get("room_id");

        try (WsClient ctrlB = connectControl(roomId, SESSION_B)) {
            sendJoin(ctrlB, "Bob", null);
            drainText(ctrlB, 2);

            try (WsClient docB = connectDoc(roomId, "mod_test1", SESSION_B)) {
                // Update frame (0 2: document change)
                byte[] updateFrame = {0x00, 0x02, 0x10, 0x20};
                docB.sendBinary(updateFrame);

                String rejected = pollControlMessage(ctrlB, "edit-rejected", 3, TimeUnit.SECONDS);
                assertThat(rejected)
                        .as("In Open mode, rejection reason must be 'not_module_writer'")
                        .contains("\"type\":\"edit-rejected\"")
                        .contains("\"doc_id\":\"mod_test1\"")
                        .contains("\"reason\":\"not_module_writer\"");
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5. Edit check: permitted sender's frame is forwarded unchanged (binary identical)
    // -----------------------------------------------------------------------

    @Test
    void editCheck_permittedSender_frameForwardedUnchanged() throws Exception {
        // debug_together: everyone can edit
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId     = (String) room.get("room_id");
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

                // B (canEdit=true) sends update frame (0 2)
                byte[] docChangeFrame = {0x00, 0x02, 0x42, 0x7F};
                docB.sendBinary(docChangeFrame);

                byte[] received = docA.pollBinary(3, TimeUnit.SECONDS);
                assertThat(received)
                        .as("Permitted sender's frame must be forwarded byte-for-byte")
                        .isEqualTo(docChangeFrame);

                // No edit-rejected for B
                String unexpectedRejection = pollControlMessage(
                        ctrlB, "edit-rejected", 300, TimeUnit.MILLISECONDS);
                assertThat(unexpectedRejection)
                        .as("Permitted sender must NOT receive edit-rejected")
                        .isNull();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 6. Awareness and step-1 frames bypass the edit check (always forwarded)
    // -----------------------------------------------------------------------

    @Test
    void awarenessAndStep1_bypassEditCheck_alwaysForwarded() throws Exception {
        // teach_interview: participant cannot edit, but non-change frames must pass
        Map<String, Object> room = createRoom("pair", "teach_interview", null);
        String roomId     = (String) room.get("room_id");
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

                // Awareness from B (byte 0 == 1): bypass edit check
                byte[] awarenessFrame = {0x01, 0x00, 0x05};
                docB.sendBinary(awarenessFrame);
                assertThat(docA.pollBinary(3, TimeUnit.SECONDS))
                        .as("Awareness frame must pass even if sender cannot edit")
                        .isEqualTo(awarenessFrame);

                // Step-1 from B (sync 0 + step-1 0 = 0 0): not a document change
                byte[] step1Frame = {0x00, 0x00, 0x20, 0x30};
                docB.sendBinary(step1Frame);
                assertThat(docA.pollBinary(3, TimeUnit.SECONDS))
                        .as("Step-1 frame must pass even if sender cannot edit")
                        .isEqualTo(step1Frame);
            }
        }
    }

    // -----------------------------------------------------------------------
    // 7. Document socket without prior join on control socket closes with 4406
    // -----------------------------------------------------------------------

    @Test
    void documentSocket_withoutPriorJoin_closesWith4406() throws Exception {
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId = (String) room.get("room_id");

        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/" + roomId + "/docs/main?session_id=" + SESSION_C
        ).get(5, TimeUnit.SECONDS);

        Thread.sleep(500);

        assertThat(session.isOpen())
                .as("Document socket opened without prior join must be closed")
                .isFalse();

        assertThat(handler.closes).isNotEmpty();
        assertThat(handler.closes.get(0).getCode())
                .as("Close code must be 4406 (not joined)")
                .isEqualTo(4406);

        session.close();
    }

    // -----------------------------------------------------------------------
    // 8. Document socket isolates different doc_ids in the same room
    // -----------------------------------------------------------------------

    @Test
    void documentSocket_isolatesDifferentDocIds() throws Exception {
        Map<String, Object> room = createRoom("open", null, "python");
        String roomId     = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient ctrlA = connectControl(roomId, SESSION_A);
             WsClient ctrlB = connectControl(roomId, SESSION_B)) {

            sendJoin(ctrlA, "Alice", ownerToken);
            drainText(ctrlA, 2);
            sendJoin(ctrlB, "Bob", null);
            drainText(ctrlB, 2);
            drainText(ctrlA, 1);

            // Client A connects to module 1; Client B connects to module 2
            try (WsClient docA = connectDoc(roomId, "mod_one", SESSION_A);
                 WsClient docB = connectDoc(roomId, "mod_two", SESSION_B)) {

                // A sends an awareness frame on mod_one
                byte[] awareness = {0x01, 0x42};
                docA.sendBinary(awareness);

                // B on mod_two must NOT receive it
                byte[] received = docB.pollBinary(500, TimeUnit.MILLISECONDS);
                assertThat(received)
                        .as("Client on mod_two must NOT receive frames from mod_one")
                        .isNull();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static class WsClient implements AutoCloseable {

        private final WebSocketSession session;
        private final BlockingQueue<byte[]> binaryQueue = new LinkedBlockingDeque<>();
        private final BlockingQueue<String>  textQueue  = new LinkedBlockingDeque<>();

        WsClient(int port, String path) throws Exception {
            StandardWebSocketClient client = new StandardWebSocketClient();
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

            }, "ws://localhost:" + port + path).get(5, TimeUnit.SECONDS);
        }

        void sendBinary(byte[] bytes) throws IOException {
            synchronized (session) { session.sendMessage(new BinaryMessage(bytes)); }
        }

        void sendText(String text) throws IOException {
            synchronized (session) { session.sendMessage(new TextMessage(text)); }
        }

        byte[] pollBinary(long t, TimeUnit u) throws InterruptedException { return binaryQueue.poll(t, u); }
        String pollText(long t, TimeUnit u) throws InterruptedException   { return textQueue.poll(t, u); }

        WebSocketSession session() { return session; }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) session.close();
        }
    }

    static class CapturingHandler extends AbstractWebSocketHandler {
        final List<CloseStatus> closes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closes.add(status);
        }
    }
}
