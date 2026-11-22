package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.JoinOutcome;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for US-58 (FR34, V2.23) Audit log of role and permission changes:
 * {@code GET /v1/rooms/{roomId}/audit-log}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditLogControllerTest {

    private static final String HOST_SESSION = "11111111-1111-4111-8111-111111111111";
    private static final String GUEST_SESSION = "22222222-2222-4222-8222-222222222222";
    private static final String OBSERVER_SESSION = "33333333-3333-4333-8333-333333333333";
    private static final String UNJOINED_SESSION = "99999999-9999-4999-8999-999999999999";

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

    private String[] createPairRoom() throws Exception {
        String body = """
                {
                  "mode": "pair",
                  "preset": "teach_interview"
                }
                """;
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

    private String[] createOpenRoom() throws Exception {
        String body = """
                {
                  "mode": "open",
                  "language": "python"
                }
                """;
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

    @Test
    @DisplayName("GET /audit-log: accessible to Host, Participant, and Observer")
    void auditLog_accessibleToAllRoomMembers() throws Exception {
        String[] roomData = createPairRoom();
        String roomId = roomData[0];
        String ownerToken = roomData[1];

        roomService.join(roomId, HOST_SESSION, "HostAlice", ownerToken);
        roomService.join(roomId, GUEST_SESSION, "GuestBob", null);
        roomService.join(roomId, OBSERVER_SESSION, "ObserverCharlie", null);

        // Make Charlie an observer
        Member charlie = roomService.findMemberBySession(roomId, OBSERVER_SESSION);
        roomService.changeRole(roomId, charlie.getMemberId(), Role.OBSERVER);

        // Host query
        HttpRequest hostReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", HOST_SESSION)
                .GET()
                .build();
        HttpResponse<String> hostResp = http.send(hostReq, HttpResponse.BodyHandlers.ofString());
        assertThat(hostResp.statusCode()).isEqualTo(200);
        JsonNode hostJson = om.readTree(hostResp.body());
        assertThat(hostJson.get("room_id").asText()).isEqualTo(roomId);
        assertThat(hostJson.get("entries").isArray()).isTrue();

        // Guest query
        HttpRequest guestReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", GUEST_SESSION)
                .GET()
                .build();
        HttpResponse<String> guestResp = http.send(guestReq, HttpResponse.BodyHandlers.ofString());
        assertThat(guestResp.statusCode()).isEqualTo(200);

        // Observer query (FR34: observers are concerned by permissions and can view)
        HttpRequest obsReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", OBSERVER_SESSION)
                .GET()
                .build();
        HttpResponse<String> obsResp = http.send(obsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(obsResp.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET /audit-log: rejects unjoined, invalid UUID, and unknown room")
    void auditLog_validationErrors() throws Exception {
        String[] roomData = createPairRoom();
        String roomId = roomData[0];
        String ownerToken = roomData[1];
        roomService.join(roomId, HOST_SESSION, "HostAlice", ownerToken);

        // Missing session header -> 422
        HttpRequest missingReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .GET()
                .build();
        HttpResponse<String> missingResp = http.send(missingReq, HttpResponse.BodyHandlers.ofString());
        assertThat(missingResp.statusCode()).isEqualTo(422);

        // Malformed session header -> 422
        HttpRequest malformedReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", "not-a-uuid")
                .GET()
                .build();
        HttpResponse<String> malformedResp = http.send(malformedReq, HttpResponse.BodyHandlers.ofString());
        assertThat(malformedResp.statusCode()).isEqualTo(422);

        // Unjoined session -> 404 SESSION_NOT_FOUND
        HttpRequest unjoinedReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", UNJOINED_SESSION)
                .GET()
                .build();
        HttpResponse<String> unjoinedResp = http.send(unjoinedReq, HttpResponse.BodyHandlers.ofString());
        assertThat(unjoinedResp.statusCode()).isEqualTo(404);
        assertThat(unjoinedResp.body()).contains("SESSION_NOT_FOUND");

        // Unknown room -> 404 ROOM_NOT_FOUND
        HttpRequest unknownRoomReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/nonExistentRoom12345/audit-log"))
                .header("X-Session-Id", HOST_SESSION)
                .GET()
                .build();
        HttpResponse<String> unknownRoomResp = http.send(unknownRoomReq, HttpResponse.BodyHandlers.ofString());
        assertThat(unknownRoomResp.statusCode()).isEqualTo(404);
        assertThat(unknownRoomResp.body()).contains("ROOM_NOT_FOUND");
    }

    @Test
    @DisplayName("AC6: Client cannot create, edit or delete audit log entries (405 Method Not Allowed)")
    void auditLog_clientCannotWriteOrDelete() throws Exception {
        String[] roomData = createPairRoom();
        String roomId = roomData[0];
        String ownerToken = roomData[1];
        roomService.join(roomId, HOST_SESSION, "HostAlice", ownerToken);

        // POST -> 405
        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", HOST_SESSION)
                .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"fake\"}"))
                .build();
        HttpResponse<String> postResp = http.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertThat(postResp.statusCode()).isEqualTo(405);

        // DELETE -> 405
        HttpRequest deleteReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", HOST_SESSION)
                .DELETE()
                .build();
        HttpResponse<String> deleteResp = http.send(deleteReq, HttpResponse.BodyHandlers.ofString());
        assertThat(deleteResp.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("AC1, AC2, AC3: Role change and Edit access recorded, refused change adds no entry, no session_id/code")
    void auditLog_recordsRoleAndEditAccessChanges() throws Exception {
        String[] roomData = createPairRoom();
        String roomId = roomData[0];
        String ownerToken = roomData[1];

        roomService.join(roomId, HOST_SESSION, "HostAlice", ownerToken);
        JoinOutcome guestOutcome = roomService.join(roomId, GUEST_SESSION, "GuestBob", null);
        String guestId = guestOutcome.getMember().getMemberId();

        // 1. Refused change (invalid owner token) -> NOT_OWNER, adds NO entry (AC3)
        HttpRequest refusedReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/members/" + guestId))
                .header("X-Session-Id", GUEST_SESSION) // Guest calling, no owner token
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"role\":\"observer\"}"))
                .build();
        HttpResponse<String> refusedResp = http.send(refusedReq, HttpResponse.BodyHandlers.ofString());
        assertThat(refusedResp.statusCode()).isEqualTo(403);

        // Check log is still empty
        HttpRequest getLogReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", HOST_SESSION)
                .GET()
                .build();
        HttpResponse<String> logResp1 = http.send(getLogReq, HttpResponse.BodyHandlers.ofString());
        assertThat(om.readTree(logResp1.body()).get("entries")).isEmpty();

        // 2. Successful role change: participant -> observer
        HttpRequest roleReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/members/" + guestId))
                .header("X-Session-Id", HOST_SESSION)
                .header("X-Owner-Token", ownerToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"role\":\"observer\"}"))
                .build();
        HttpResponse<String> roleResp = http.send(roleReq, HttpResponse.BodyHandlers.ofString());
        assertThat(roleResp.statusCode()).isEqualTo(200);

        // Verify audit log has 1 entry
        HttpResponse<String> logResp2 = http.send(getLogReq, HttpResponse.BodyHandlers.ofString());
        JsonNode entries2 = om.readTree(logResp2.body()).get("entries");
        assertThat(entries2).hasSize(1);
        JsonNode e1 = entries2.get(0);
        assertThat(e1.get("action").asText()).isEqualTo("role_change");
        assertThat(e1.get("actor").asText()).isEqualTo("HostAlice");
        assertThat(e1.get("target").asText()).isEqualTo("GuestBob");
        assertThat(e1.get("property").asText()).isEqualTo("role");
        assertThat(e1.get("old_value").asText()).isEqualTo("participant");
        assertThat(e1.get("new_value").asText()).isEqualTo("observer");
        assertThat(e1.get("timestamp").asText()).isNotBlank();
        // AC2: Verify NO session_id leaked
        assertThat(logResp2.body()).doesNotContain(HOST_SESSION);
        assertThat(logResp2.body()).doesNotContain(GUEST_SESSION);

        // 3. Change role back: observer -> participant
        HttpRequest roleBackReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/members/" + guestId))
                .header("X-Session-Id", HOST_SESSION)
                .header("X-Owner-Token", ownerToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"role\":\"participant\"}"))
                .build();
        http.send(roleBackReq, HttpResponse.BodyHandlers.ofString());

        // 4. Grant edit access: can_edit: true
        HttpRequest editReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/members/" + guestId + "/edit-access"))
                .header("X-Session-Id", HOST_SESSION)
                .header("X-Owner-Token", ownerToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"can_edit\":true}"))
                .build();
        HttpResponse<String> editResp = http.send(editReq, HttpResponse.BodyHandlers.ofString());
        assertThat(editResp.statusCode()).isEqualTo(200);

        // Verify newest-first order
        HttpResponse<String> logResp3 = http.send(getLogReq, HttpResponse.BodyHandlers.ofString());
        JsonNode entries3 = om.readTree(logResp3.body()).get("entries");
        assertThat(entries3).hasSize(3);
        assertThat(entries3.get(0).get("action").asText()).isEqualTo("edit_access");
        assertThat(entries3.get(0).get("property").asText()).isEqualTo("can_edit");
        assertThat(entries3.get(0).get("old_value").asBoolean()).isFalse();
        assertThat(entries3.get(0).get("new_value").asBoolean()).isTrue();

        assertThat(entries3.get(1).get("action").asText()).isEqualTo("role_change");
        assertThat(entries3.get(1).get("old_value").asText()).isEqualTo("observer");
        assertThat(entries3.get(1).get("new_value").asText()).isEqualTo("participant");

        assertThat(entries3.get(2).get("action").asText()).isEqualTo("role_change");
        assertThat(entries3.get(2).get("old_value").asText()).isEqualTo("participant");
        assertThat(entries3.get(2).get("new_value").asText()).isEqualTo("observer");
    }

    @Test
    @DisplayName("AC1: Run permissions (per-member and host_only_run) recorded in audit log")
    void auditLog_recordsRunPermissions() throws Exception {
        String[] roomData = createPairRoom();
        String roomId = roomData[0];
        String ownerToken = roomData[1];

        roomService.join(roomId, HOST_SESSION, "HostAlice", ownerToken);
        JoinOutcome guestOutcome = roomService.join(roomId, GUEST_SESSION, "GuestBob", null);
        String guestId = guestOutcome.getMember().getMemberId();

        // 1. Take Run away from GuestBob
        HttpRequest revokeRunReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/members/" + guestId + "/run-permission"))
                .header("X-Session-Id", HOST_SESSION)
                .header("X-Owner-Token", ownerToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"can_run\":false}"))
                .build();
        HttpResponse<String> revokeResp = http.send(revokeRunReq, HttpResponse.BodyHandlers.ofString());
        assertThat(revokeResp.statusCode()).isEqualTo(200);

        // 2. Turn on host_only_run
        HttpRequest hostOnlyReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/run-policy"))
                .header("X-Session-Id", HOST_SESSION)
                .header("X-Owner-Token", ownerToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"host_only_run\":true}"))
                .build();
        HttpResponse<String> hostOnlyResp = http.send(hostOnlyReq, HttpResponse.BodyHandlers.ofString());
        assertThat(hostOnlyResp.statusCode()).isEqualTo(200);

        // Check audit log
        HttpRequest getLogReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", HOST_SESSION)
                .GET()
                .build();
        HttpResponse<String> logResp = http.send(getLogReq, HttpResponse.BodyHandlers.ofString());
        JsonNode entries = om.readTree(logResp.body()).get("entries");
        assertThat(entries).hasSize(2);

        // Newest: host_only_run
        assertThat(entries.get(0).get("action").asText()).isEqualTo("host_only_run");
        assertThat(entries.get(0).get("property").asText()).isEqualTo("host_only_run");
        assertThat(entries.get(0).get("old_value").asBoolean()).isFalse();
        assertThat(entries.get(0).get("new_value").asBoolean()).isTrue();

        // Previous: run_permission
        assertThat(entries.get(1).get("action").asText()).isEqualTo("run_permission");
        assertThat(entries.get(1).get("property").asText()).isEqualTo("can_run");
        assertThat(entries.get(1).get("target").asText()).isEqualTo("GuestBob");
        assertThat(entries.get(1).get("old_value").asBoolean()).isTrue();
        assertThat(entries.get(1).get("new_value").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("AC1: Module write access grant and revoke recorded in Open mode")
    void auditLog_recordsModuleWriteAccess() throws Exception {
        String[] roomData = createOpenRoom();
        String roomId = roomData[0];
        String ownerToken = roomData[1];

        roomService.join(roomId, HOST_SESSION, "HostAlice", ownerToken);
        JoinOutcome guestOutcome = roomService.join(roomId, GUEST_SESSION, "GuestBob", null);
        String guestId = guestOutcome.getMember().getMemberId();

        // Create an approved module
        HttpRequest createModReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/modules"))
                .header("X-Session-Id", HOST_SESSION)
                .header("X-Owner-Token", ownerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"main.py\"}"))
                .build();
        HttpResponse<String> modResp = http.send(createModReq, HttpResponse.BodyHandlers.ofString());
        assertThat(modResp.statusCode()).isEqualTo(201);
        String moduleId = om.readTree(modResp.body()).get("module_id").asText();

        // Grant write access to GuestBob
        HttpRequest grantReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/modules/" + moduleId + "/writers"))
                .header("X-Session-Id", HOST_SESSION)
                .header("X-Owner-Token", ownerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"member_id\":\"" + guestId + "\"}"))
                .build();
        HttpResponse<String> grantResp = http.send(grantReq, HttpResponse.BodyHandlers.ofString());
        assertThat(grantResp.statusCode()).isEqualTo(200);

        // Repeated grant to GuestBob: writer set does NOT change, must not create a duplicate entry
        HttpResponse<String> repeatedGrantResp = http.send(grantReq, HttpResponse.BodyHandlers.ofString());
        assertThat(repeatedGrantResp.statusCode()).isEqualTo(200);

        // Revoke write access from GuestBob
        HttpRequest revokeReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/modules/" + moduleId + "/writers/" + guestId))
                .header("X-Session-Id", HOST_SESSION)
                .header("X-Owner-Token", ownerToken)
                .DELETE()
                .build();
        HttpResponse<String> revokeResp = http.send(revokeReq, HttpResponse.BodyHandlers.ofString());
        assertThat(revokeResp.statusCode()).isEqualTo(200);

        // Repeated revoke from GuestBob: writer set does NOT change, must not create a duplicate entry
        HttpResponse<String> repeatedRevokeResp = http.send(revokeReq, HttpResponse.BodyHandlers.ofString());
        assertThat(repeatedRevokeResp.statusCode()).isEqualTo(200);

        // Query audit log
        HttpRequest getLogReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", HOST_SESSION)
                .GET()
                .build();
        HttpResponse<String> logResp = http.send(getLogReq, HttpResponse.BodyHandlers.ofString());
        JsonNode entries = om.readTree(logResp.body()).get("entries");
        // Exactly 2 entries: 1 for initial grant, 1 for initial revoke (repeated requests created 0 entries)
        assertThat(entries).hasSize(2);

        // Newest: write_access revoke
        assertThat(entries.get(0).get("action").asText()).isEqualTo("write_access");
        assertThat(entries.get(0).get("target").asText()).isEqualTo("GuestBob");
        assertThat(entries.get(0).get("old_value").asBoolean()).isTrue();
        assertThat(entries.get(0).get("new_value").asBoolean()).isFalse();

        // Previous: write_access grant
        assertThat(entries.get(1).get("action").asText()).isEqualTo("write_access");
        assertThat(entries.get(1).get("target").asText()).isEqualTo("GuestBob");
        assertThat(entries.get(1).get("old_value").asBoolean()).isFalse();
        assertThat(entries.get(1).get("new_value").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("AC1: Host role transfer recorded in audit log")
    void auditLog_recordsHostTransfer() throws Exception {
        String[] roomData = createPairRoom();
        String roomId = roomData[0];
        String ownerToken = roomData[1];

        roomService.join(roomId, HOST_SESSION, "HostAlice", ownerToken);
        JoinOutcome guestOutcome = roomService.join(roomId, GUEST_SESSION, "GuestBob", null);
        String guestId = guestOutcome.getMember().getMemberId();

        // Transfer host to GuestBob
        HttpRequest transferReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/members/" + guestId + "/make-host"))
                .header("X-Session-Id", HOST_SESSION)
                .header("X-Owner-Token", ownerToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> transferResp = http.send(transferReq, HttpResponse.BodyHandlers.ofString());
        assertThat(transferResp.statusCode()).isEqualTo(200);

        // Query audit log as GuestBob (now Host)
        HttpRequest getLogReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/audit-log"))
                .header("X-Session-Id", GUEST_SESSION)
                .GET()
                .build();
        HttpResponse<String> logResp = http.send(getLogReq, HttpResponse.BodyHandlers.ofString());
        JsonNode entries = om.readTree(logResp.body()).get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("action").asText()).isEqualTo("host_transfer");
        assertThat(entries.get(0).get("actor").asText()).isEqualTo("HostAlice");
        assertThat(entries.get(0).get("target").asText()).isEqualTo("GuestBob");
        assertThat(entries.get(0).get("old_value").asText()).isEqualTo("participant");
        assertThat(entries.get(0).get("new_value").asText()).isEqualTo("host");
    }

    @Test
    @DisplayName("AC8: Room deletion purges the audit log (FR32, US-56)")
    void auditLog_clearedOnRoomDelete() throws Exception {
        String[] roomData = createPairRoom();
        String roomId = roomData[0];
        String ownerToken = roomData[1];

        roomService.join(roomId, HOST_SESSION, "HostAlice", ownerToken);
        JoinOutcome guestOutcome = roomService.join(roomId, GUEST_SESSION, "GuestBob", null);
        String guestId = guestOutcome.getMember().getMemberId();

        // Perform a role change to record an entry
        HttpRequest roleReq = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms/" + roomId + "/members/" + guestId))
                .header("X-Session-Id", HOST_SESSION)
                .header("X-Owner-Token", ownerToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"role\":\"observer\"}"))
                .build();
        http.send(roleReq, HttpResponse.BodyHandlers.ofString());

        Room r = store.get(roomId);
        assertThat(r.getAuditLog()).hasSize(1);

        // Delete / clear room state (simulating US-56 room cleanup)
        r.clearSensitiveState();
        assertThat(r.getAuditLog()).isEmpty();
    }
}
