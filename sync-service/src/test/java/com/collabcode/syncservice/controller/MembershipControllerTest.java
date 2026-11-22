package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.module.ModuleRecord;
import com.collabcode.syncservice.module.ModuleService;
import com.collabcode.syncservice.room.JoinOutcome;
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
 * Tests for the US-28 (V2.18) Host controls: remove, re-admit, list-blocked and
 * regenerate-link. HTTP cases use the JDK HttpClient; two socket cases verify the
 * live {@code 4403} / {@code 4411} close codes and the {@code USER_BLOCKED}
 * reconnect refusal. Session ids are real UUID v4 values (contract §3.3, V2.6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"CLIENT_ORIGIN=*"})
class MembershipControllerTest {

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

    @Autowired
    ModuleService moduleService;

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

    private String[] createOpenRoom() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"open\",\"language\":\"python\"}"))
                .build();
        JsonNode b = om.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
        return new String[]{b.get("room_id").asText(), b.get("owner_token").asText()};
    }

    /** Request a module as the Host and approve it; returns the module_id. */
    private String requestAndApproveModule(String roomId, String hostSession, String ownerToken, String name) throws Exception {
        HttpRequest reqReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/modules"))
                .header("Content-Type", "application/json")
                .header("X-Session-Id", hostSession)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}"))
                .build();
        String moduleId = om.readTree(http.send(reqReq, HttpResponse.BodyHandlers.ofString()).body())
                .get("module_id").asText();
        HttpRequest approveReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/modules/" + moduleId + "/approve"))
                .header("Content-Type", "application/json")
                .header("X-Session-Id", hostSession)
                .header("X-Owner-Token", ownerToken)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        http.send(approveReq, HttpResponse.BodyHandlers.ofString());
        return moduleId;
    }

    private HttpResponse<String> getModules(String roomId, String sessionId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/modules"))
                .header("X-Session-Id", sessionId)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String sessionId, String ownerToken) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (sessionId != null) rb.header("X-Session-Id", sessionId);
        if (ownerToken != null) rb.header("X-Owner-Token", ownerToken);
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String sessionId, String ownerToken) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder().uri(URI.create(base() + path)).GET();
        if (sessionId != null) rb.header("X-Session-Id", sessionId);
        if (ownerToken != null) rb.header("X-Owner-Token", ownerToken);
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String code(HttpResponse<String> res) throws Exception {
        return om.readTree(res.body()).get("error").get("code").asText();
    }

    // ---------------------------------------------------------------- remove

    @Test
    void hostRemovesMember_returns200_andBlocks() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]);

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode b = om.readTree(res.body());
        assertThat(b.get("member_id").asText()).isEqualTo(guestId);
        assertThat(b.get("status").asText()).isEqualTo("removed");
        assertThat(store.get(room[0]).getMembers()).doesNotContainKey(guestId);
        assertThat(store.get(room[0]).isMemberBlocked(guestId)).isTrue();
        assertThat(roomService.join(room[0], GUEST, "Lin", null).getRefusedReason()).isEqualTo("USER_BLOCKED");
    }

    @Test
    void nonHostCannotRemove_isNotOwner() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String targetId = roomService.join(room[0], OTHER, "Other", null).getMember().getMemberId();
        roomService.join(room[0], GUEST, "Guest", null); // a real joined non-Host caller

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/" + targetId + "/remove", GUEST, null);
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(code(res)).isEqualTo("NOT_OWNER");
    }

    @Test
    void removingAHost_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        String hostId = roomService.join(room[0], HOST, "Host", room[1]).getMember().getMemberId();

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/" + hostId + "/remove", HOST, room[1]);
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(code(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void removeUnknownMember_isMemberNotFound() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/m_nope/remove", HOST, room[1]);
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(code(res)).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void removeCallerNotJoined_isSessionNotFound() throws Exception {
        String[] room = createPairRoom();
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", NOT_JOINED, room[1]);
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(code(res)).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void removeMalformedSession_isInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/m_x/remove", "not-a-uuid", room[1]);
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(code(res)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void removeUnknownRoom_isRoomNotFound() throws Exception {
        HttpResponse<String> res = post("/v1/rooms/NoSuchRoom0000000000AA/members/m_x/remove", HOST, "t");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(code(res)).isEqualTo("ROOM_NOT_FOUND");
    }

    // ---------------------------------------------------------- list blocked

    @Test
    void blockedList_returnsEntries_withoutSessionId() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();
        post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]);

        HttpResponse<String> res = get("/v1/rooms/" + room[0] + "/members/blocked", HOST, room[1]);

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode blocked = om.readTree(res.body()).get("blocked");
        assertThat(blocked).hasSize(1);
        JsonNode e = blocked.get(0);
        assertThat(e.get("member_id").asText()).isEqualTo(guestId);
        assertThat(e.get("display_name").asText()).isEqualTo("Lin");
        assertThat(e.hasNonNull("removed_at")).isTrue();
        assertThat(e.has("session_id")).isFalse();
        // the private session id must not leak anywhere in the body
        assertThat(res.body()).doesNotContain(GUEST);
    }

    @Test
    void blockedList_nonHost_isNotOwner() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        roomService.join(room[0], GUEST, "Guest", null);

        HttpResponse<String> res = get("/v1/rooms/" + room[0] + "/members/blocked", GUEST, null);
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(code(res)).isEqualTo("NOT_OWNER");
    }

    // --------------------------------------------------------------- readmit

    @Test
    void readmit_liftsBlock() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();
        post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]);

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/" + guestId + "/readmit", HOST, room[1]);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(om.readTree(res.body()).get("status").asText()).isEqualTo("readmitted");
        JoinOutcome rejoined = roomService.join(room[0], GUEST, "Lin", null);
        assertThat(rejoined.isJoined()).isTrue();
        assertThat(rejoined.getMember().getMemberId()).isNotEqualTo(guestId); // fresh member_id
    }

    @Test
    void readmit_unknownMember_isMemberNotFound() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/m_nope/readmit", HOST, room[1]);
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(code(res)).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void readmit_nonHost_isNotOwner() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();
        post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]);
        roomService.join(room[0], OTHER, "Other", null);

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/" + guestId + "/readmit", OTHER, null);
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(code(res)).isEqualTo("NOT_OWNER");
    }

    // ------------------------------------------------------- regenerate-link

    @Test
    void regenerate_returnsNewRoomId_oldIdGone_blockCleared() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();
        post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]); // a block to be cleared

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/regenerate-link", HOST, room[1]);

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode b = om.readTree(res.body());
        String newId = b.get("room_id").asText();
        assertThat(b.get("status").asText()).isEqualTo("regenerated");
        assertThat(newId).isNotEqualTo(room[0]);
        assertThat(newId).hasSize(22);
        assertThat(store.get(room[0])).isNull();          // old id no longer resolves
        assertThat(store.get(newId)).isNotNull();
        assertThat(store.get(newId).isMemberBlocked(guestId)).isFalse(); // block cleared
    }

    @Test
    void regenerate_nonHost_isNotOwner() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        roomService.join(room[0], GUEST, "Guest", null);

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/regenerate-link", GUEST, null);
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(code(res)).isEqualTo("NOT_OWNER");
    }

    // --------------------------------- socket behaviour: 4403 / 4411 / refusal

    @Test
    void removedConnectedMember_socketClosed4403_andReconnectRefusedUserBlocked() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        Ws guest = Ws.connect(port, "/ws/rooms/" + room[0] + "?session_id=" + GUEST);
        guest.sendText("{\"type\":\"join\",\"display_name\":\"Lin\"}");
        String guestId = awaitMemberId(room[0], GUEST);

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]);
        assertThat(res.statusCode()).isEqualTo(200);

        // the connected guest's control socket is closed with 4403 (V2.3)
        CloseStatus close = guest.awaitClose(3, TimeUnit.SECONDS);
        assertThat(close).isNotNull();
        assertThat(close.getCode()).isEqualTo(4403);

        // a brand-new connection from the same session is refused at join with USER_BLOCKED
        Ws again = Ws.connect(port, "/ws/rooms/" + room[0] + "?session_id=" + GUEST);
        try {
            again.sendText("{\"type\":\"join\",\"display_name\":\"Lin\"}");
            String refused = again.awaitText("join-refused", 3, TimeUnit.SECONDS);
            assertThat(refused).isNotNull();
            assertThat(refused).contains("USER_BLOCKED").contains("The Host has removed you from this room.");
        } finally {
            again.close();
        }
    }

    @Test
    void regenerate_closesOldSocketsWith4411() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        Ws guest = Ws.connect(port, "/ws/rooms/" + room[0] + "?session_id=" + GUEST);
        guest.sendText("{\"type\":\"join\",\"display_name\":\"Lin\"}");
        awaitMemberId(room[0], GUEST);

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/regenerate-link", HOST, room[1]);
        assertThat(res.statusCode()).isEqualTo(200);

        CloseStatus close = guest.awaitClose(3, TimeUnit.SECONDS);
        assertThat(close).isNotNull();
        assertThat(close.getCode()).isEqualTo(4411);
    }

    @Test
    void remove_broadcastsUpdatedRosterToRemaining() throws Exception {
        String[] room = createPairRoom();
        Ws host = Ws.connect(port, "/ws/rooms/" + room[0] + "?session_id=" + HOST);
        host.sendText("{\"type\":\"join\",\"display_name\":\"Host\",\"owner_token\":\"" + room[1] + "\"}");
        host.awaitText("joined", 3, TimeUnit.SECONDS);
        Ws guest = Ws.connect(port, "/ws/rooms/" + room[0] + "?session_id=" + GUEST);
        guest.sendText("{\"type\":\"join\",\"display_name\":\"Lin\"}");
        String guestId = awaitMemberId(room[0], GUEST);

        post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]);

        // The Host receives a fresh roster that no longer lists the removed member (V2.18 step 3).
        String roster = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            String r = host.awaitText("roster", 1, TimeUnit.SECONDS);
            if (r != null && !r.contains("Lin")) { roster = r; break; }
        }
        assertThat(roster).as("a roster without the removed member").isNotNull();
        assertThat(roster).contains("Host");
        guest.close();
        host.close();
    }

    @Test
    void removedSession_newDocumentSocket_refused4406() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();
        post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]);

        // A removed session is no longer a member, so its document socket is refused 4406 (not joined).
        Ws doc = Ws.connect(port, "/ws/rooms/" + room[0] + "/docs/main?session_id=" + GUEST);
        CloseStatus close = doc.awaitClose(3, TimeUnit.SECONDS);
        assertThat(close).isNotNull();
        assertThat(close.getCode()).isEqualTo(4406);
    }

    @Test
    void regenerate_preservesModuleOwnership() throws Exception {
        String[] room = createOpenRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String moduleId = requestAndApproveModule(room[0], HOST, room[1], "solver.py");

        String newId = om.readTree(post("/v1/rooms/" + room[0] + "/regenerate-link", HOST, room[1]).body())
                .get("room_id").asText();

        // The module survives the move, under the new id, with its owner intact (moveRoom).
        HttpResponse<String> mods = getModules(newId, HOST);
        assertThat(mods.statusCode()).isEqualTo(200);
        JsonNode arr = om.readTree(mods.body());
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).get("module_id").asText()).isEqualTo(moduleId);
        assertThat(arr.get(0).get("owner_member_id").asText()).isNotBlank();
    }

    @Test
    void removeMember_dropsPendingModuleRequests() throws Exception {
        String[] room = createOpenRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String guestId = roomService.join(room[0], GUEST, "Lin", null).getMember().getMemberId();

        // Guest requests a module (pending=true)
        HttpRequest createReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + room[0] + "/modules"))
                .header("Content-Type", "application/json")
                .header("X-Session-Id", GUEST)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"lin_algo.py\"}"))
                .build();
        HttpResponse<String> createRes = http.send(createReq, HttpResponse.BodyHandlers.ofString());
        assertThat(createRes.statusCode()).isEqualTo(202);

        // Host removes the guest
        HttpResponse<String> removeRes = post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]);
        assertThat(removeRes.statusCode()).isEqualTo(200);

        // Pending request for lin_algo.py is gone, so Host listing pending has 0 items
        HttpRequest pendingReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + room[0] + "/modules?pending=true"))
                .header("X-Session-Id", HOST)
                .header("X-Owner-Token", room[1])
                .GET()
                .build();
        HttpResponse<String> pendingRes = http.send(pendingReq, HttpResponse.BodyHandlers.ofString());
        assertThat(pendingRes.statusCode()).isEqualTo(200);
        JsonNode arr = om.readTree(pendingRes.body());
        assertThat(arr).isEmpty();
    }

    @Test
    void us42_removalTransfersOwnedModulesAndRevokesWriters() throws Exception {
        String[] room = createOpenRoom();
        String hostId = roomService.join(room[0], HOST, "Host", room[1]).getMember().getMemberId();
        String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();
        String otherId = roomService.join(room[0], OTHER, "Other", null).getMember().getMemberId();

        ModuleRecord guestOwned = moduleService.createModule(room[0], guestId, "guest.py", false);
        guestOwned.addWriter(otherId);
        guestOwned.setVersion(7);
        guestOwned.setRunnable(false);
        guestOwned.setEntry(true);

        ModuleRecord hostOwned = moduleService.createModule(room[0], hostId, "host.py", false);
        hostOwned.addWriter(guestId);
        hostOwned.addWriter(otherId);
        ModuleRecord untouched = moduleService.createModule(room[0], hostId, "untouched.py", false);
        ModuleRecord pending = moduleService.createModule(room[0], guestId, "pending.py", true);

        HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]);
        assertThat(res.statusCode()).isEqualTo(200);

        assertThat(guestOwned.ownerMemberId()).isEqualTo(hostId);
        assertThat(guestOwned.pending()).isFalse();
        assertThat(guestOwned.writers()).containsExactly(otherId);
        assertThat(guestOwned.version()).isEqualTo(7);
        assertThat(guestOwned.runnable()).isFalse();
        assertThat(guestOwned.isEntry()).isTrue();
        assertThat(guestOwned.canEdit(hostId)).isTrue();
        assertThat(guestOwned.canEdit(guestId)).isFalse();
        assertThat(guestOwned.canEdit(otherId)).isTrue();

        assertThat(hostOwned.ownerMemberId()).isEqualTo(hostId);
        assertThat(hostOwned.writers()).containsExactly(otherId);
        assertThat(untouched.ownerMemberId()).isEqualTo(hostId);
        assertThat(untouched.writers()).isEmpty();
        assertThat(moduleService.find(room[0], pending.moduleId())).isNull();
    }

    @Test
    void us42_removalBroadcastsUpdatedOwnerAndWriterPermissions() throws Exception {
        String[] room = createOpenRoom();
        Ws hostSocket = Ws.connect(port, "/ws/rooms/" + room[0] + "?session_id=" + HOST);
        try {
            hostSocket.sendText("{\"type\":\"join\",\"display_name\":\"Host\",\"owner_token\":\"" + room[1] + "\"}");
            assertThat(hostSocket.awaitText("joined", 3, TimeUnit.SECONDS)).isNotNull();
            String hostId = awaitMemberId(room[0], HOST);
            String guestId = roomService.join(room[0], GUEST, "Guest", null).getMember().getMemberId();

            ModuleRecord guestOwned = moduleService.createModule(room[0], guestId, "guest_event.py", false);
            ModuleRecord hostOwned = moduleService.createModule(room[0], hostId, "host_event.py", false);
            hostOwned.addWriter(guestId);

            HttpResponse<String> res = post("/v1/rooms/" + room[0] + "/members/" + guestId + "/remove", HOST, room[1]);
            assertThat(res.statusCode()).isEqualTo(200);

            String first = hostSocket.awaitText("module-updated", 3, TimeUnit.SECONDS);
            String second = hostSocket.awaitText("module-updated", 3, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(first + second).contains(guestOwned.moduleId(), hostOwned.moduleId());
            assertThat(first + second).contains(hostId);
            assertThat(first + second).doesNotContain("\"deleted\":true");
            assertThat(hostOwned.writers()).doesNotContain(guestId);
        } finally {
            hostSocket.close();
        }
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

        CloseStatus awaitClose(long t, TimeUnit u) throws InterruptedException {
            return closes.poll(t, u);
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
