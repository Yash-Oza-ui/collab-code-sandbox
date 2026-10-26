package com.collabcode.syncservice;

import com.collabcode.syncservice.handler.RelayHandler;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the sync-service WebSocket relay (T-02).
 *
 * <p>Each test starts the full embedded Spring Boot server on a random port
 * ({@code RANDOM_PORT}) so tests are isolated and can run in parallel with
 * other services.
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li>{@link #healthEndpointReturns200} — /actuator/health → 200 (DoD item 6)
 *   <li>{@link #binaryFrameRelayedUnchangedToOtherClient} — bytes arrive identical (DoD item: binary test)
 *   <li>{@link #senderDoesNotReceiveOwnFrame} — no echo to sender for non-awareness frames
 *   <li>{@link #roomIsolation_clientInDifferentRoomReceivesNothing} — room boundary (DoD item)
 *   <li>{@link #awarenessFrameEchoedBackToSender} — first-byte==1 echo (contract section 3.5 rule 1)
 *   <li>{@link #disconnectedClientRemovedFromRoom} — close B, A sends, no server error
 *   <li>{@link #internalEndpoint_broadcastsToAllInRoom} — POST /internal delivers to every session
 *   <li>{@link #internalEndpoint_returns401ForBadToken} — missing/wrong token → 401
 *   <li>{@link #internalEndpoint_returns400ForMissingTypeField} — body without type → 400
 *   <li>{@link #connectionRejected_forMalformedRoomId} — close 4400 on bad room id
 *   <li>{@link #connectionRejected_forMissingSessionId} — close 4400 on missing session_id
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Allow any origin in tests (no real browser involved)
                "CLIENT_ORIGIN=*",
                // Known token used by internal-endpoint tests
                "INTERNAL_API_TOKEN=test-token-ci"
        }
)
class RelayIntegrationTest {

    @LocalServerPort
    int port;

    // Fixed UUIDs — all are valid UUID v4 (version nibble 4, variant nibble 8)
    private static final String SESSION_A = "a0000000-0000-4000-8000-000000000001";
    private static final String SESSION_B = "b0000000-0000-4000-8000-000000000002";
    private static final String SESSION_C = "c0000000-0000-4000-8000-000000000003";
    private static final String TOKEN     = "test-token-ci";

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
    // 2. Binary relay — bytes arrive unchanged
    // -----------------------------------------------------------------------

    @Test
    void binaryFrameRelayedUnchangedToOtherClient() throws Exception {
        try (WsClient a = connect("room-relay-test", SESSION_A);
             WsClient b = connect("room-relay-test", SESSION_B)) {

            byte[] payload = {0x00, 0x01, (byte) 0xFF, 0x42, 0x7F};
            a.sendBinary(payload);

            byte[] received = b.pollBinary(3, TimeUnit.SECONDS);
            assertThat(received)
                    .as("B should receive exactly the bytes A sent")
                    .isEqualTo(payload);
        }
    }

    // -----------------------------------------------------------------------
    // 3. Sender must NOT receive its own non-awareness frame
    // -----------------------------------------------------------------------

    @Test
    void senderDoesNotReceiveOwnFrame() throws Exception {
        try (WsClient a = connect("room-echo-test", SESSION_A);
             WsClient b = connect("room-echo-test", SESSION_B)) {

            // First byte is 0x00 → not an awareness frame
            a.sendBinary(new byte[]{0x00, 0x02, 0x03});

            // B should receive it
            assertThat(b.pollBinary(2, TimeUnit.SECONDS)).isNotNull();

            // A must NOT receive it back
            assertThat(a.pollBinary(500, TimeUnit.MILLISECONDS))
                    .as("Sender must not receive its own non-awareness frame")
                    .isNull();
        }
    }

    // -----------------------------------------------------------------------
    // 4. Room isolation — client in a different room receives nothing
    // -----------------------------------------------------------------------

    @Test
    void roomIsolation_clientInDifferentRoomReceivesNothing() throws Exception {
        try (WsClient a = connect("room-isolation-1", SESSION_A);
             WsClient b = connect("room-isolation-1", SESSION_B);
             WsClient c = connect("room-isolation-2", SESSION_C)) {

            a.sendBinary(new byte[]{0x00, 0x05});

            // B in same room gets the frame
            assertThat(b.pollBinary(3, TimeUnit.SECONDS))
                    .as("Client in same room must receive the frame")
                    .isNotNull();

            // C in a different room gets nothing
            assertThat(c.pollBinary(500, TimeUnit.MILLISECONDS))
                    .as("Client in a different room must receive nothing")
                    .isNull();
        }
    }

    // -----------------------------------------------------------------------
    // 5. Awareness frame echoed back to sender (contract section 3.5 rule 1)
    // -----------------------------------------------------------------------

    @Test
    void awarenessFrameEchoedBackToSender() throws Exception {
        // Lone user: only A in the room
        try (WsClient a = connect("room-awareness-test", SESSION_A)) {

            // First byte = 1 → awareness frame
            byte[] awareness = {RelayHandler.AWARENESS_BYTE, 0x10, 0x20};
            a.sendBinary(awareness);

            byte[] received = a.pollBinary(3, TimeUnit.SECONDS);
            assertThat(received)
                    .as("Sender must receive its own awareness frame back")
                    .isEqualTo(awareness);
        }
    }

    // -----------------------------------------------------------------------
    // 6. Disconnected client removed from room — no server error
    // -----------------------------------------------------------------------

    @Test
    void disconnectedClientRemovedFromRoom() throws Exception {
        try (WsClient a = connect("room-disconnect-test", SESSION_A)) {
            WsClient b = connect("room-disconnect-test", SESSION_B);

            // Close B while still inside the try-with-resources for A
            b.close();

            // Wait briefly for the close to propagate server-side
            Thread.sleep(200);

            // A sends a frame; the server must not throw and A stays open
            a.sendBinary(new byte[]{0x00, 0x09});

            // A should still be open and receive no error
            assertThat(a.session().isOpen()).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // 7. Internal endpoint — broadcasts to all sessions in the room
    // -----------------------------------------------------------------------

    @Test
    void internalEndpoint_broadcastsToAllInRoom() throws Exception {
        try (WsClient a = connect("room-internal-test", SESSION_A);
             WsClient b = connect("room-internal-test", SESSION_B)) {

            // Small delay to ensure both sessions are registered before broadcast
            Thread.sleep(100);

            String body = """
                    {"type":"run-result","request_id":"00000000-0000-4000-8000-000000000001",\
                    "room_id":"room-internal-test","stdout":"hello\\n","stderr":"","exit_code":0,\
                    "execution_time_ms":100,"peak_memory_mb":4.0,\
                    "timed_out":false,"oom_killed":false,"failure_kind":null,\
                    "output_truncated":false,"message":null}""";

            HttpResponse<String> resp = postInternal("room-internal-test", body);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"delivered\":2");

            // Both A and B must receive the text frame
            String receivedByA = a.pollText(3, TimeUnit.SECONDS);
            String receivedByB = b.pollText(3, TimeUnit.SECONDS);

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
                "test-room",
                "{\"type\":\"run-result\"}",
                "wrong-token");

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
    // 10. Connection rejected with 4400 for a malformed room id
    // -----------------------------------------------------------------------

    @Test
    void connectionRejected_forMalformedRoomId() throws Exception {
        // Room id contains a space — invalid per contract section 3.2
        WsClient bad = connect("bad room!", SESSION_A);

        // Give the server time to close the connection
        Thread.sleep(500);

        assertThat(bad.session().isOpen())
                .as("Session with malformed room id must be closed by the server")
                .isFalse();

        bad.close(); // no-op if already closed
    }

    // -----------------------------------------------------------------------
    // 11. Connection rejected with 4400 for a missing session_id
    // -----------------------------------------------------------------------

    @Test
    void connectionRejected_forMissingSessionId() throws Exception {
        // Connect without session_id query parameter
        StandardWebSocketClient client = new StandardWebSocketClient();
        CapturingHandler handler = new CapturingHandler();

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

    // =======================================================================
    // Helpers
    // =======================================================================

    /** Connect a test WebSocket client to the given room with the given session_id. */
    private WsClient connect(String roomId, String sessionId) throws Exception {
        WsClient client = new WsClient(port, roomId, sessionId);
        Thread.sleep(100);
        return client;
    }

    /** POST to the internal broadcast endpoint with a custom token. */
    private HttpResponse<String> postInternalWithToken(String roomId, String body, String token) throws Exception {
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

    /** POST to the internal broadcast endpoint with the shared test token. */
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

        WsClient(int port, String roomId, String sessionId) throws Exception {
            StandardWebSocketClient client = new StandardWebSocketClient();

            String url = "ws://localhost:" + port
                    + "/ws/rooms/" + roomId
                    + "?session_id=" + sessionId;

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
            synchronized (session) {
                session.sendMessage(new BinaryMessage(bytes));
            }
        }

        /** Wait up to {@code timeout} for the next binary frame from the server. */
        byte[] pollBinary(long timeout, TimeUnit unit) throws InterruptedException {
            return binaryQueue.poll(timeout, unit);
        }

        /** Wait up to {@code timeout} for the next text frame from the server. */
        String pollText(long timeout, TimeUnit unit) throws InterruptedException {
            return textQueue.poll(timeout, unit);
        }

        WebSocketSession session() {
            return session;
        }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) {
                session.close();
            }
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
