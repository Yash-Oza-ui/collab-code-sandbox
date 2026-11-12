package com.collabcode.syncservice;

import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    RoomService roomService;

    @Autowired
    RoomStore roomStore;

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
    // Helper: use the V2.6 request-and-Host-approval flow, never a test-only registry.
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String createAndApproveModule(String roomId, String sessionId, String ownerToken, String name) {
        TestRestTemplate rest = new TestRestTemplate();
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        requestHeaders.set("X-Session-Id", sessionId);
        ResponseEntity<Map> requested = rest.postForEntity("http://localhost:" + port + "/v1/rooms/" + roomId + "/modules",
                new HttpEntity<>(Map.of("name", name), requestHeaders), Map.class);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(requested.getBody()).containsEntry("pending", true);
        String moduleId = (String) requested.getBody().get("module_id");

        HttpHeaders approveHeaders = new HttpHeaders();
        approveHeaders.set("X-Session-Id", sessionId);
        approveHeaders.set("X-Owner-Token", ownerToken);
        ResponseEntity<Map> approved = rest.postForEntity("http://localhost:" + port + "/v1/rooms/" + roomId
                        + "/modules/" + moduleId + "/approve", new HttpEntity<>(approveHeaders), Map.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()).containsEntry("pending", false);
        return moduleId;
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
                assertThat(docB.pollText(300, TimeUnit.MILLISECONDS))
                        .as("Document socket must never receive a text edit-rejected fallback")
                        .isNull();
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
        String ownerToken = (String) room.get("owner_token");

        try (WsClient ctrlA = connectControl(roomId, SESSION_A);
             WsClient ctrlB = connectControl(roomId, SESSION_B)) {
            sendJoin(ctrlA, "Alice", ownerToken);
            drainText(ctrlA, 2);
            sendJoin(ctrlB, "Bob", null);
            drainText(ctrlB, 2);
            drainText(ctrlA, 1);
            String moduleId = createAndApproveModule(roomId, SESSION_A, ownerToken, "test.py");

            try (WsClient docB = connectDoc(roomId, moduleId, SESSION_B)) {
                // Update frame (0 2: document change)
                byte[] updateFrame = {0x00, 0x02, 0x10, 0x20};
                docB.sendBinary(updateFrame);

                String rejected = pollControlMessage(ctrlB, "edit-rejected", 3, TimeUnit.SECONDS);
                assertThat(rejected)
                        .as("In Open mode, rejection reason must be 'not_module_writer'")
                        .contains("\"type\":\"edit-rejected\"")
                        .contains("\"doc_id\":\"" + moduleId + "\"")
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

            // Register modules through the contract before connecting (4405 validation).
            String firstModule = createAndApproveModule(roomId, SESSION_A, ownerToken, "one.py");
            String secondModule = createAndApproveModule(roomId, SESSION_A, ownerToken, "two.py");

            // Client A connects to module 1; Client B connects to module 2
            try (WsClient docA = connectDoc(roomId, firstModule, SESSION_A);
                 WsClient docB = connectDoc(roomId, secondModule, SESSION_B)) {

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
    // 9. Control socket: invalid JSON -> join-refused and socket closure
    // -----------------------------------------------------------------------

    @Test
    void controlSocket_invalidJson_joinRefusedAndSocketClosed() throws Exception {
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId = (String) room.get("room_id");

        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/" + roomId + "?session_id=" + SESSION_A
        ).get(5, TimeUnit.SECONDS);

        // Send invalid JSON
        synchronized (session) { session.sendMessage(new TextMessage("not-valid-json{")); }
        Thread.sleep(500);

        assertThat(handler.texts).isNotEmpty();
        assertThat(handler.texts.get(0)).contains("\"type\":\"join-refused\"");
        assertThat(session.isOpen())
                .as("Socket must be closed after join-refused for invalid JSON")
                .isFalse();
        assertThat(handler.closes).isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // 10. Control socket: wrong first message type -> join-refused and socket closure
    // -----------------------------------------------------------------------

    @Test
    void controlSocket_wrongMessageType_joinRefusedAndSocketClosed() throws Exception {
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId = (String) room.get("room_id");

        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/" + roomId + "?session_id=" + SESSION_A
        ).get(5, TimeUnit.SECONDS);

        // Send a valid JSON message but with wrong type
        synchronized (session) { session.sendMessage(new TextMessage("{\"type\":\"hello\",\"display_name\":\"Alice\"}")); }
        Thread.sleep(500);

        assertThat(handler.texts).isNotEmpty();
        assertThat(handler.texts.get(0)).contains("\"type\":\"join-refused\"");
        assertThat(session.isOpen())
                .as("Socket must be closed after join-refused for wrong message type")
                .isFalse();
        assertThat(handler.closes).isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // 11. Control socket: missing/blank display_name -> join-refused and socket closure
    // -----------------------------------------------------------------------

    @Test
    void controlSocket_missingDisplayName_joinRefusedAndSocketClosed() throws Exception {
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId = (String) room.get("room_id");

        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/" + roomId + "?session_id=" + SESSION_A
        ).get(5, TimeUnit.SECONDS);

        // Send join with blank display_name
        synchronized (session) { session.sendMessage(new TextMessage("{\"type\":\"join\",\"display_name\":\"   \"}")); }
        Thread.sleep(500);

        assertThat(handler.texts).isNotEmpty();
        assertThat(handler.texts.get(0)).contains("\"type\":\"join-refused\"");
        assertThat(session.isOpen())
                .as("Socket must be closed after join-refused for missing/blank display_name")
                .isFalse();
        assertThat(handler.closes).isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // 12. Control socket: APPROVAL_PENDING -> join-refused, socket stays open
    //     (FR9.3 / US-26): when the room requires Host approval, the server
    //     sends join-refused with reason APPROVAL_PENDING and keeps the socket
    //     open so the client can receive join-approved or join-declined later.
    // -----------------------------------------------------------------------

    @Test
    void controlSocket_approvalPending_socketRemainsOpen() throws Exception {
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId = (String) room.get("room_id");

        // Set the room to require approval (simulates FR9.3 join approval flow)
        Room r = roomStore.get(roomId);
        assertThat(r).as("Room must exist in RoomStore").isNotNull();
        r.setRequiresApproval(true);

        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient wsClient = new StandardWebSocketClient();
        WebSocketSession session = wsClient.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/" + roomId + "?session_id=" + SESSION_A
        ).get(5, TimeUnit.SECONDS);

        // Send a valid join -- server must respond with APPROVAL_PENDING
        synchronized (session) {
            session.sendMessage(new TextMessage("{\"type\":\"join\",\"display_name\":\"Alice\"}"));
        }
        Thread.sleep(600);

        // Must receive join-refused with reason APPROVAL_PENDING
        assertThat(handler.texts)
                .as("Client must receive a join-refused frame")
                .isNotEmpty();
        assertThat(handler.texts.get(0))
                .as("join-refused must have reason APPROVAL_PENDING")
                .contains("\"type\":\"join-refused\"")
                .contains("APPROVAL_PENDING");

        // Socket must remain OPEN (not closed for APPROVAL_PENDING)
        assertThat(session.isOpen())
                .as("Control socket must remain open while APPROVAL_PENDING -- not be closed")
                .isTrue();

        assertThat(handler.closes)
                .as("No close event must have fired for APPROVAL_PENDING")
                .isEmpty();

        // Resolve the controlled approval fixture and prove the same connection
        // can receive the resulting join flow; this is not a successful-join
        // lookalike pretending to cover APPROVAL_PENDING.
        r.setRequiresApproval(false);
        synchronized (session) {
            session.sendMessage(new TextMessage("{\"type\":\"join\",\"display_name\":\"Alice\"}"));
        }
        String joined = handler.texts.stream()
                .filter(text -> text.contains("\"type\":\"joined\""))
                .findFirst()
                .orElse(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (joined == null && System.nanoTime() < deadline) {
            Thread.sleep(25);
            joined = handler.texts.stream()
                    .filter(text -> text.contains("\"type\":\"joined\""))
                    .findFirst()
                    .orElse(null);
        }
        assertThat(joined).as("Pending connection must receive its follow-up join result").isNotNull();

        session.close();
    }

    // -----------------------------------------------------------------------
    // 13. Document socket: Pair mode with 'main' -> accepted (4405 not triggered)
    // -----------------------------------------------------------------------

    @Test
    void docSocket_pairMode_mainAccepted() throws Exception {
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId     = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient ctrl = connectControl(roomId, SESSION_A)) {
            sendJoin(ctrl, "Alice", ownerToken);
            drainText(ctrl, 2);

            try (WsClient doc = connectDoc(roomId, "main", SESSION_A)) {
                assertThat(doc.session().isOpen())
                        .as("Pair mode: 'main' must be accepted (not closed with 4405)")
                        .isTrue();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 14. Document socket: Pair mode with unknown doc_id -> 4405
    // -----------------------------------------------------------------------

    @Test
    void docSocket_pairMode_unknownDocId_closesWith4405() throws Exception {
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId     = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient ctrl = connectControl(roomId, SESSION_A)) {
            sendJoin(ctrl, "Alice", ownerToken);
            drainText(ctrl, 2);

            CapturingHandler handler = new CapturingHandler();
            StandardWebSocketClient client = new StandardWebSocketClient();
            WebSocketSession session = client.execute(
                    handler,
                    "ws://localhost:" + port + "/ws/rooms/" + roomId + "/docs/not-main?session_id=" + SESSION_A
            ).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);

            assertThat(session.isOpen())
                    .as("Pair mode: unknown doc_id must close the socket")
                    .isFalse();
            assertThat(handler.closes).isNotEmpty();
            assertThat(handler.closes.get(0).getCode())
                    .as("Close code must be 4405 (unknown doc_id in Pair mode)")
                    .isEqualTo(4405);
        }
    }

    // -----------------------------------------------------------------------
    // 15. Document socket: Open mode with registered module -> accepted
    // -----------------------------------------------------------------------

    @Test
    void docSocket_openMode_registeredModule_accepted() throws Exception {
        Map<String, Object> room = createRoom("open", null, "python");
        String roomId     = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient ctrl = connectControl(roomId, SESSION_A)) {
            sendJoin(ctrl, "Alice", ownerToken);
            drainText(ctrl, 2);
            String moduleId = createAndApproveModule(roomId, SESSION_A, ownerToken, "known.py");

            try (WsClient doc = connectDoc(roomId, moduleId, SESSION_A)) {
                assertThat(doc.session().isOpen())
                        .as("Open mode: registered module_id must be accepted")
                        .isTrue();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 16. Document socket: Open mode with unknown module_id -> 4405
    // -----------------------------------------------------------------------

    @Test
    void docSocket_openMode_unknownModuleId_closesWith4405() throws Exception {
        Map<String, Object> room = createRoom("open", null, "python");
        String roomId     = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");
        // Do NOT register any module -- doc_id "mod_unknown" is not in the registry

        try (WsClient ctrl = connectControl(roomId, SESSION_A)) {
            sendJoin(ctrl, "Alice", ownerToken);
            drainText(ctrl, 2);

            CapturingHandler handler = new CapturingHandler();
            StandardWebSocketClient client = new StandardWebSocketClient();
            WebSocketSession session = client.execute(
                    handler,
                    "ws://localhost:" + port + "/ws/rooms/" + roomId + "/docs/mod_unknown?session_id=" + SESSION_A
            ).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);

            assertThat(session.isOpen())
                    .as("Open mode: unregistered module_id must close the socket")
                    .isFalse();
            assertThat(handler.closes).isNotEmpty();
            assertThat(handler.closes.get(0).getCode())
                    .as("Close code must be 4405 (unknown module_id in Open mode)")
                    .isEqualTo(4405);
        }
    }

    // -----------------------------------------------------------------------
    // 17. Document socket: unknown room -> 4404
    // -----------------------------------------------------------------------

    @Test
    void docSocket_unknownRoom_closesWith4404() throws Exception {
        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/nonexistentroom/docs/main?session_id=" + SESSION_A
        ).get(5, TimeUnit.SECONDS);

        Thread.sleep(500);

        assertThat(session.isOpen())
                .as("Unknown room: socket must be closed")
                .isFalse();
        assertThat(handler.closes).isNotEmpty();
        assertThat(handler.closes.get(0).getCode())
                .as("Close code must be 4404 (unknown room)")
                .isEqualTo(4404);
    }

    // -----------------------------------------------------------------------
    // 18. Document socket before control join -> 4406 (Open mode)
    // -----------------------------------------------------------------------

    @Test
    void docSocket_openMode_beforeControlJoin_closesWith4406() throws Exception {
        Map<String, Object> room = createRoom("open", null, "python");
        String roomId = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        try (WsClient host = connectControl(roomId, SESSION_A)) {
            sendJoin(host, "Alice", ownerToken);
            drainText(host, 2);
            String moduleId = createAndApproveModule(roomId, SESSION_A, ownerToken, "early.py");

            // Open the doc socket WITHOUT first joining the control socket.
            CapturingHandler handler = new CapturingHandler();
            StandardWebSocketClient client = new StandardWebSocketClient();
            WebSocketSession session = client.execute(
                    handler,
                    "ws://localhost:" + port + "/ws/rooms/" + roomId + "/docs/" + moduleId + "?session_id=" + SESSION_B
            ).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);

            assertThat(session.isOpen())
                .as("Doc socket before control join must be closed")
                .isFalse();
            assertThat(handler.closes).isNotEmpty();
            assertThat(handler.closes.get(0).getCode())
                .as("Close code must be 4406 (not joined) -- not 4405")
                    .isEqualTo(4406);
        }
    }

    // -----------------------------------------------------------------------
    // 19. Control socket: closed room -> 4410
    // -----------------------------------------------------------------------

    @Test
    void controlSocket_closedRoom_closesWith4410() throws Exception {
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId = (String) room.get("room_id");

        // Close the room before anyone connects
        roomStore.get(roomId).close();

        CapturingHandler handler = new CapturingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                handler,
                "ws://localhost:" + port + "/ws/rooms/" + roomId + "?session_id=" + SESSION_A
        ).get(5, TimeUnit.SECONDS);

        Thread.sleep(500);

        assertThat(session.isOpen())
                .as("Closed room: control socket must be rejected immediately")
                .isFalse();
        assertThat(handler.closes).isNotEmpty();
        assertThat(handler.closes.get(0).getCode())
                .as("Close code must be 4410 (room closed)")
                .isEqualTo(4410);
    }

    // -----------------------------------------------------------------------
    // 20. Document socket: closed room -> 4410
    // -----------------------------------------------------------------------

    @Test
    void docSocket_closedRoom_closesWith4410() throws Exception {
        Map<String, Object> room = createRoom("pair", "debug_together", null);
        String roomId     = (String) room.get("room_id");
        String ownerToken = (String) room.get("owner_token");

        // Join the room first, then close it
        try (WsClient ctrl = connectControl(roomId, SESSION_A)) {
            sendJoin(ctrl, "Alice", ownerToken);
            drainText(ctrl, 2);

            // Now close the room
            roomStore.get(roomId).close();

            // Try to open a document socket -- must be rejected with 4410
            CapturingHandler handler = new CapturingHandler();
            StandardWebSocketClient client = new StandardWebSocketClient();
            WebSocketSession session = client.execute(
                    handler,
                    "ws://localhost:" + port + "/ws/rooms/" + roomId + "/docs/main?session_id=" + SESSION_A
            ).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);

            assertThat(session.isOpen())
                    .as("Closed room: document socket must be rejected")
                    .isFalse();
            assertThat(handler.closes).isNotEmpty();
            assertThat(handler.closes.get(0).getCode())
                    .as("Close code must be 4410 (room closed)")
                    .isEqualTo(4410);
        }
    }

    // -----------------------------------------------------------------------
    // Inner helpers
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
        final List<String> texts = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            texts.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closes.add(status);
        }
    }
}
