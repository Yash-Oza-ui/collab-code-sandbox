package com.collabcode.syncservice.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the run-permission rule of US-60 (V2.20; FR41.1 to FR41.4):
 * {@link Room#runDeniedReason(Member)}, the state changes in {@link RoomService}, and how
 * the result reaches {@code joined} and {@code roster}. No Spring context is needed.
 */
class RoomRunPermissionTest {

    private static final String ROOM_ID = "Zq8-Lw2Ef6Th0Yx4Rn7JbA";

    private RoomStore store;
    private RoomService service;
    private Room room;
    private Member host;
    private Member ada;
    private Member lin;

    @BeforeEach
    void setUp() {
        store = new RoomStore();
        service = new RoomService(store);
        room = new Room(ROOM_ID, RoomMode.PAIR, "debug_together", null, OwnerTokens.hash("secret"));
        store.put(room);
        host = service.join(ROOM_ID, "host-sess", "Host", "secret").getMember();
        ada = service.join(ROOM_ID, "ada-sess", "Ada", null).getMember();
        lin = service.join(ROOM_ID, "lin-sess", "Lin", null).getMember();
    }

    // ----- FR41.1: any participant can run unless the Host restricts Run -----

    @Test
    void everyoneCanRunByDefault() {
        assertThat(room.isHostOnlyRun()).isFalse();
        for (Member m : List.of(host, ada, lin)) {
            assertThat(room.runDeniedReason(m)).isNull();
            assertThat(room.canRun(m)).isTrue();
        }
    }

    @Test
    void observerCannotRun_reasonObserver() {
        service.changeRole(ROOM_ID, ada.getMemberId(), Role.OBSERVER);

        assertThat(room.canRun(ada)).isFalse();
        assertThat(room.runDeniedReason(ada)).isEqualTo("observer");
    }

    // ----- FR41.2: take Run away from a named participant, give it back -----

    @Test
    void takingRunAway_refusesOnlyThatMember_reasonRunRevoked() {
        service.setMemberRunAllowed(ROOM_ID, ada.getMemberId(), false);

        assertThat(room.runDeniedReason(ada)).isEqualTo("run_revoked");
        assertThat(room.canRun(ada)).isFalse();
        assertThat(room.canRun(lin)).isTrue();
        assertThat(room.canRun(host)).isTrue();
    }

    @Test
    void givingRunBack_allowsAgain() {
        service.setMemberRunAllowed(ROOM_ID, ada.getMemberId(), false);
        service.setMemberRunAllowed(ROOM_ID, ada.getMemberId(), true);

        assertThat(room.runDeniedReason(ada)).isNull();
        assertThat(room.canRun(ada)).isTrue();
    }

    @Test
    void setMemberRunAllowed_unknownRoomOrMember_returnsNull() {
        assertThat(service.setMemberRunAllowed("no-such-room-0000000000", ada.getMemberId(), false)).isNull();
        assertThat(service.setMemberRunAllowed(ROOM_ID, "m_unknown", false)).isNull();
    }

    // ----- FR41.3: Host-only running, and back -----

    @Test
    void hostOnlyRunning_refusesEveryoneButTheHost_reasonHostOnly() {
        service.setHostOnlyRun(ROOM_ID, true);

        assertThat(room.runDeniedReason(ada)).isEqualTo("host_only");
        assertThat(room.runDeniedReason(lin)).isEqualTo("host_only");
        assertThat(room.runDeniedReason(host)).isNull();
        assertThat(room.canRun(host)).isTrue();
    }

    @Test
    void hostOnlyOff_allowsEveryoneAgain() {
        service.setHostOnlyRun(ROOM_ID, true);
        service.setHostOnlyRun(ROOM_ID, false);

        assertThat(room.canRun(ada)).isTrue();
        assertThat(room.canRun(lin)).isTrue();
    }

    @Test
    void setHostOnlyRun_unknownRoom_returnsNull() {
        assertThat(service.setHostOnlyRun("no-such-room-0000000000", true)).isNull();
    }

    // ----- reason precedence: observer, then run_revoked, then host_only -----

    @Test
    void revokedMember_whileHostOnlyIsOn_getsTheLastingReason() {
        service.setMemberRunAllowed(ROOM_ID, ada.getMemberId(), false);
        service.setHostOnlyRun(ROOM_ID, true);

        assertThat(room.runDeniedReason(ada)).isEqualTo("run_revoked");
        assertThat(room.runDeniedReason(lin)).isEqualTo("host_only");

        // Host-only off again: the personal restriction is still there.
        service.setHostOnlyRun(ROOM_ID, false);
        assertThat(room.runDeniedReason(ada)).isEqualTo("run_revoked");
        assertThat(room.canRun(lin)).isTrue();
    }

    @Test
    void observerReason_winsOverHostOnly() {
        service.changeRole(ROOM_ID, ada.getMemberId(), Role.OBSERVER);
        service.setHostOnlyRun(ROOM_ID, true);

        assertThat(room.runDeniedReason(ada)).isEqualTo("observer");
    }

    // ----- role changes -----

    @Test
    void roleChange_startsFromDefaults_soARestrictionIsCleared() {
        service.setMemberRunAllowed(ROOM_ID, ada.getMemberId(), false);

        service.changeRole(ROOM_ID, ada.getMemberId(), Role.OBSERVER);
        service.changeRole(ROOM_ID, ada.getMemberId(), Role.PARTICIPANT);

        assertThat(room.canRun(ada)).isTrue();
        assertThat(room.runDeniedReason(ada)).isNull();
    }

    @Test
    void transferHost_newHostRunsEvenInHostOnlyMode_oldHostIsRefused() {
        service.setHostOnlyRun(ROOM_ID, true);

        service.transferHost(ROOM_ID, ada.getMemberId(), OwnerTokens.hash("new-secret"));

        assertThat(room.runDeniedReason(ada)).isNull();          // the new Host
        assertThat(room.runDeniedReason(host)).isEqualTo("host_only"); // the old Host, now a participant
        assertThat(room.isHostOnlyRun()).isTrue();               // the room setting is not reset
    }

    @Test
    void transferHost_toARestrictedParticipant_clearsTheirRestriction() {
        service.setMemberRunAllowed(ROOM_ID, ada.getMemberId(), false);

        service.transferHost(ROOM_ID, ada.getMemberId(), OwnerTokens.hash("new-secret"));
        // Demoted again later, the old restriction must not come back.
        service.transferHost(ROOM_ID, lin.getMemberId(), OwnerTokens.hash("newer-secret"));

        assertThat(room.runDeniedReason(ada)).isNull();
    }

    @Test
    void regenerateLink_keepsTheRunSettings() {
        service.setMemberRunAllowed(ROOM_ID, ada.getMemberId(), false);
        service.setHostOnlyRun(ROOM_ID, true);

        Room fresh = service.regenerateLink(ROOM_ID);

        assertThat(fresh).isNotNull();
        assertThat(fresh.isHostOnlyRun()).isTrue();
        assertThat(fresh.runDeniedReason(fresh.getMembers().get(ada.getMemberId()))).isEqualTo("run_revoked");
        assertThat(fresh.runDeniedReason(fresh.getMembers().get(lin.getMemberId()))).isEqualTo("host_only");
    }

    // ----- joined and roster carry the same answer -----

    @Test
    @SuppressWarnings("unchecked")
    void rosterAndJoined_carryCanRunAndReason() {
        service.setMemberRunAllowed(ROOM_ID, ada.getMemberId(), false);
        service.setHostOnlyRun(ROOM_ID, true);

        Map<String, Object> roster = RoomMessages.roster(room);
        assertThat(roster.get("host_only_run")).isEqualTo(true);

        List<Map<String, Object>> members = (List<Map<String, Object>>) roster.get("members");
        Map<String, Object> adaPerms = permissionsOf(members, ada.getMemberId());
        Map<String, Object> linPerms = permissionsOf(members, lin.getMemberId());
        Map<String, Object> hostPerms = permissionsOf(members, host.getMemberId());

        assertThat(adaPerms.get("can_run")).isEqualTo(false);
        assertThat(adaPerms.get("run_denied_reason")).isEqualTo("run_revoked");
        assertThat(linPerms.get("can_run")).isEqualTo(false);
        assertThat(linPerms.get("run_denied_reason")).isEqualTo("host_only");
        assertThat(hostPerms.get("can_run")).isEqualTo(true);
        assertThat(hostPerms.get("run_denied_reason")).isNull();

        Map<String, Object> joinedPerms =
                (Map<String, Object>) RoomMessages.joined(room, lin).get("permissions");
        assertThat(joinedPerms.get("can_run")).isEqualTo(false);
        assertThat(joinedPerms.get("run_denied_reason")).isEqualTo("host_only");
    }

    @Test
    void roster_hostOnlyRunIsFalseByDefault() {
        assertThat(RoomMessages.roster(room).get("host_only_run")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> permissionsOf(List<Map<String, Object>> members, String memberId) {
        return members.stream()
                .filter(m -> memberId.equals(m.get("member_id")))
                .map(m -> (Map<String, Object>) m.get("permissions"))
                .findFirst()
                .orElseThrow();
    }
}
