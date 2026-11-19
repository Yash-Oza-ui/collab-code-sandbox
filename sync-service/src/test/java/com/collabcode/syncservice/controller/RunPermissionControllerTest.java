package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.Member;
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
 * Tests for the run-permission controls of US-60 (V2.20; FR41.1 to FR41.4):
 * {@code PATCH /v1/rooms/{room_id}/members/{member_id}/run-permission} and
 * {@code PATCH /v1/rooms/{room_id}/run-policy}, and the internal session lookup that
 * execution-service reads, so the whole server side is checked over real HTTP. Uses the
 * JDK HttpClient because TestRestTemplate's default factory does not support PATCH.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"INTERNAL_API_TOKEN=test-token-us60"})
class RunPermissionControllerTest {

    private static final String INTERNAL_TOKEN = "test-token-us60";

    // Valid UUID v4 session ids (X-Session-Id must be a UUID, contract §3.3, V2.6).
    private static final String HOST = "11111111-1111-4111-8111-111111111111";
    private static final String ADA = "22222222-2222-4222-8222-222222222222";
    private static final String LIN = "33333333-3333-4333-8333-333333333333";
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

    /** Create a Pair room over HTTP, returning [roomId, ownerToken]. */
    private String[] createPairRoom() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v1/rooms"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"pair\",\"preset\":\"debug_together\"}"))
                .build();
        JsonNode b = om.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
        return new String[]{b.get("room_id").asText(), b.get("owner_token").asText()};
    }

    private HttpResponse<String> patch(String path, String sessionId, String ownerToken, String bodyJson)
            throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .method("PATCH", bodyJson == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(bodyJson));
        if (sessionId != null) {
            rb.header("X-Session-Id", sessionId);
        }
        if (ownerToken != null) {
            rb.header("X-Owner-Token", ownerToken);
        }
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> setMemberRun(String roomId, String memberId, String sessionId,
                                              String ownerToken, String bodyJson) throws Exception {
        return patch("/v1/rooms/" + roomId + "/members/" + memberId + "/run-permission",
                sessionId, ownerToken, bodyJson);
    }

    private HttpResponse<String> setRunPolicy(String roomId, String sessionId, String ownerToken,
                                              String bodyJson) throws Exception {
        return patch("/v1/rooms/" + roomId + "/run-policy", sessionId, ownerToken, bodyJson);
    }

    /** The session lookup execution-service reads on every run request (V2.11). */
    private JsonNode lookup(String roomId, String sessionId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/internal/rooms/" + roomId + "/sessions/" + sessionId))
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        return om.readTree(res.body());
    }

    private String errorCode(HttpResponse<String> res) throws Exception {
        return om.readTree(res.body()).get("error").get("code").asText();
    }

    // ----- FR41.2: take Run away from a named participant and give it back -----

    @Test
    void hostTakesRunAway_lookupRefusesWithRunRevoked_thenGivesItBack() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String adaId = roomService.join(room[0], ADA, "Ada", null).getMember().getMemberId();
        roomService.join(room[0], LIN, "Lin", null);

        // Before: Run is on for everyone (FR41.1).
        assertThat(lookup(room[0], ADA).get("can_run").asBoolean()).isTrue();

        HttpResponse<String> off = setMemberRun(room[0], adaId, HOST, room[1], "{\"can_run\":false}");
        assertThat(off.statusCode()).isEqualTo(200);
        JsonNode entry = om.readTree(off.body());
        assertThat(entry.get("member_id").asText()).isEqualTo(adaId);
        assertThat(entry.get("permissions").get("can_run").asBoolean()).isFalse();
        assertThat(entry.get("permissions").get("run_denied_reason").asText()).isEqualTo("run_revoked");

        // The next run is refused for that member only (the lookup is read fresh each time).
        JsonNode ada = lookup(room[0], ADA);
        assertThat(ada.get("can_run").asBoolean()).isFalse();
        assertThat(ada.get("run_denied_reason").asText()).isEqualTo("run_revoked");
        assertThat(lookup(room[0], LIN).get("can_run").asBoolean()).isTrue();
        assertThat(lookup(room[0], HOST).get("can_run").asBoolean()).isTrue();

        HttpResponse<String> on = setMemberRun(room[0], adaId, HOST, room[1], "{\"can_run\":true}");
        assertThat(on.statusCode()).isEqualTo(200);
        JsonNode back = lookup(room[0], ADA);
        assertThat(back.get("can_run").asBoolean()).isTrue();
        assertThat(back.get("run_denied_reason").isNull()).isTrue();
    }

    @Test
    void settingTheSameValueTwice_isOkAndChangesNothing() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String adaId = roomService.join(room[0], ADA, "Ada", null).getMember().getMemberId();

        assertThat(setMemberRun(room[0], adaId, HOST, room[1], "{\"can_run\":true}").statusCode()).isEqualTo(200);
        assertThat(setMemberRun(room[0], adaId, HOST, room[1], "{\"can_run\":false}").statusCode()).isEqualTo(200);
        assertThat(setMemberRun(room[0], adaId, HOST, room[1], "{\"can_run\":false}").statusCode()).isEqualTo(200);
        assertThat(lookup(room[0], ADA).get("run_denied_reason").asText()).isEqualTo("run_revoked");
    }

    // ----- FR41.3: Host-only running and back -----

    @Test
    void hostOnlyOn_refusesEveryoneButTheHost_thenOffAllowsAgain() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        roomService.join(room[0], ADA, "Ada", null);
        roomService.join(room[0], LIN, "Lin", null);

        HttpResponse<String> on = setRunPolicy(room[0], HOST, room[1], "{\"host_only_run\":true}");
        assertThat(on.statusCode()).isEqualTo(200);
        assertThat(om.readTree(on.body()).get("host_only_run").asBoolean()).isTrue();

        JsonNode ada = lookup(room[0], ADA);
        assertThat(ada.get("can_run").asBoolean()).isFalse();
        assertThat(ada.get("run_denied_reason").asText()).isEqualTo("host_only");
        assertThat(lookup(room[0], LIN).get("run_denied_reason").asText()).isEqualTo("host_only");
        JsonNode host = lookup(room[0], HOST);
        assertThat(host.get("can_run").asBoolean()).isTrue();
        assertThat(host.get("run_denied_reason").isNull()).isTrue();

        HttpResponse<String> off = setRunPolicy(room[0], HOST, room[1], "{\"host_only_run\":false}");
        assertThat(off.statusCode()).isEqualTo(200);
        assertThat(om.readTree(off.body()).get("host_only_run").asBoolean()).isFalse();
        assertThat(lookup(room[0], ADA).get("can_run").asBoolean()).isTrue();
        assertThat(lookup(room[0], LIN).get("can_run").asBoolean()).isTrue();
    }

    // ----- Observer (US-27 case) is still refused with its own reason -----

    @Test
    void observer_isRefusedWithObserverReason_evenWhenRunPolicyChanges() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String adaId = roomService.join(room[0], ADA, "Ada", null).getMember().getMemberId();
        roomService.changeRole(room[0], adaId, Role.OBSERVER);
        setRunPolicy(room[0], HOST, room[1], "{\"host_only_run\":true}");

        JsonNode ada = lookup(room[0], ADA);
        assertThat(ada.get("can_run").asBoolean()).isFalse();
        assertThat(ada.get("run_denied_reason").asText()).isEqualTo("observer");
    }

    // ----- Only the Host can change run permission (NOT_OWNER) -----

    @Test
    void nonHostSession_isRefusedWithNotOwner_andNothingChanges() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        roomService.join(room[0], ADA, "Ada", null);
        String linId = roomService.join(room[0], LIN, "Lin", null).getMember().getMemberId();

        // A participant sends the request with no owner token, then with a wrong one.
        for (String token : new String[]{null, "wrong-token"}) {
            HttpResponse<String> member = setMemberRun(room[0], linId, ADA, token, "{\"can_run\":false}");
            assertThat(member.statusCode()).isEqualTo(403);
            assertThat(errorCode(member)).isEqualTo("NOT_OWNER");

            HttpResponse<String> policy = setRunPolicy(room[0], ADA, token, "{\"host_only_run\":true}");
            assertThat(policy.statusCode()).isEqualTo(403);
            assertThat(errorCode(policy)).isEqualTo("NOT_OWNER");
        }

        assertThat(lookup(room[0], LIN).get("can_run").asBoolean()).isTrue();
        assertThat(lookup(room[0], ADA).get("can_run").asBoolean()).isTrue();
        assertThat(store.get(room[0]).isHostOnlyRun()).isFalse();
    }

    // ----- errors, in the V2.6 order -----

    @Test
    void errorsFollowTheCallerIdentityOrder() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String adaId = roomService.join(room[0], ADA, "Ada", null).getMember().getMemberId();

        // Missing or malformed X-Session-Id -> 422 INVALID_REQUEST
        HttpResponse<String> noSession = setMemberRun(room[0], adaId, null, room[1], "{\"can_run\":false}");
        assertThat(noSession.statusCode()).isEqualTo(422);
        assertThat(errorCode(noSession)).isEqualTo("INVALID_REQUEST");
        assertThat(setRunPolicy(room[0], "not-a-uuid", room[1], "{\"host_only_run\":true}").statusCode())
                .isEqualTo(422);

        // Unknown room -> 404 ROOM_NOT_FOUND
        HttpResponse<String> noRoom = setMemberRun("no-such-room-0000000000", adaId, HOST, room[1],
                "{\"can_run\":false}");
        assertThat(noRoom.statusCode()).isEqualTo(404);
        assertThat(errorCode(noRoom)).isEqualTo("ROOM_NOT_FOUND");
        assertThat(errorCode(setRunPolicy("no-such-room-0000000000", HOST, room[1], "{\"host_only_run\":true}")))
                .isEqualTo("ROOM_NOT_FOUND");

        // Caller never joined -> 404 SESSION_NOT_FOUND
        HttpResponse<String> notJoined = setMemberRun(room[0], adaId, NOT_JOINED, room[1], "{\"can_run\":false}");
        assertThat(notJoined.statusCode()).isEqualTo(404);
        assertThat(errorCode(notJoined)).isEqualTo("SESSION_NOT_FOUND");

        // Unknown target -> 404 MEMBER_NOT_FOUND
        HttpResponse<String> noMember = setMemberRun(room[0], "m_unknown", HOST, room[1], "{\"can_run\":false}");
        assertThat(noMember.statusCode()).isEqualTo(404);
        assertThat(errorCode(noMember)).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void badBodies_areInvalidRequest_andNothingChanges() throws Exception {
        String[] room = createPairRoom();
        roomService.join(room[0], HOST, "Host", room[1]);
        String adaId = roomService.join(room[0], ADA, "Ada", null).getMember().getMemberId();

        String[] bad = {null, "", "not json", "[]", "{}", "{\"can_run\":\"false\"}", "{\"can_run\":null}",
                "{\"can_run\":0}", "{\"host_only_run\":true}"};
        for (String body : bad) {
            HttpResponse<String> res = setMemberRun(room[0], adaId, HOST, room[1], body);
            assertThat(res.statusCode()).as("body %s", body).isEqualTo(422);
            assertThat(errorCode(res)).isEqualTo("INVALID_REQUEST");
        }
        String[] badPolicy = {null, "", "not json", "{}", "{\"host_only_run\":\"true\"}", "{\"can_run\":true}"};
        for (String body : badPolicy) {
            HttpResponse<String> res = setRunPolicy(room[0], HOST, room[1], body);
            assertThat(res.statusCode()).as("body %s", body).isEqualTo(422);
            assertThat(errorCode(res)).isEqualTo("INVALID_REQUEST");
        }

        assertThat(lookup(room[0], ADA).get("can_run").asBoolean()).isTrue();
        assertThat(store.get(room[0]).isHostOnlyRun()).isFalse();
    }

    @Test
    void hostAndObserverTargets_areInvalidRequest() throws Exception {
        String[] room = createPairRoom();
        String hostId = roomService.join(room[0], HOST, "Host", room[1]).getMember().getMemberId();
        String adaId = roomService.join(room[0], ADA, "Ada", null).getMember().getMemberId();
        roomService.changeRole(room[0], adaId, Role.OBSERVER);

        HttpResponse<String> host = setMemberRun(room[0], hostId, HOST, room[1], "{\"can_run\":false}");
        assertThat(host.statusCode()).isEqualTo(422);
        assertThat(errorCode(host)).isEqualTo("INVALID_REQUEST");

        HttpResponse<String> observer = setMemberRun(room[0], adaId, HOST, room[1], "{\"can_run\":true}");
        assertThat(observer.statusCode()).isEqualTo(422);
        assertThat(errorCode(observer)).isEqualTo("INVALID_REQUEST");

        Member hostMember = store.get(room[0]).getMembers().get(hostId);
        assertThat(hostMember.isCanRun()).isTrue();
        assertThat(lookup(room[0], HOST).get("can_run").asBoolean()).isTrue();
    }
}
