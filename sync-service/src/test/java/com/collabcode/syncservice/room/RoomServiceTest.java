package com.collabcode.syncservice.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for join/roster logic (T-20 Femina part, T-17 V2.3 / V2.4),
 * covering the acceptance criteria of issue #65: roles, NAME_TAKEN, reconnect
 * and the roster/joined message shapes. No Spring context is needed.
 */
class RoomServiceTest {

    private RoomStore store;
    private RoomService service;

    @BeforeEach
    void setUp() {
        store = new RoomStore();
        service = new RoomService(store);
    }

    private Room pairRoom(String preset, String ownerToken) {
        Room room = new Room("Zq8-Lw2Ef6Th0Yx4Rn7JbA", RoomMode.PAIR, preset, null,
                OwnerTokens.hash(ownerToken));
        store.put(room);
        return room;
    }

    private Room openRoom(String ownerToken) {
        Room room = new Room("k3Yc1t9Qx7mBvA2nR0sLpQ", RoomMode.OPEN, null, "python",
                OwnerTokens.hash(ownerToken));
        store.put(room);
        return room;
    }

    @Test
    void validOwnerToken_joinsAsHost() {
        Room room = pairRoom("teach_interview", "secret");
        JoinOutcome out = service.join(room.getRoomId(), "sess-1", "Ada", "secret");

        assertThat(out.isJoined()).isTrue();
        assertThat(out.getMember().getRole()).isEqualTo(Role.HOST);
        assertThat(out.getMember().getMemberId()).startsWith("m_");
    }

    @Test
    void missingOrWrongToken_joinsAsParticipant() {
        Room room = pairRoom("teach_interview", "secret");

        assertThat(service.join(room.getRoomId(), "sess-2", "Lin", null)
                .getMember().getRole()).isEqualTo(Role.PARTICIPANT);
        assertThat(service.join(room.getRoomId(), "sess-3", "Max", "wrong")
                .getMember().getRole()).isEqualTo(Role.PARTICIPANT);
    }

    @Test
    void duplicateName_fromDifferentSession_isRefused() {
        Room room = pairRoom("teach_interview", "secret");
        service.join(room.getRoomId(), "sess-1", "Ada", "secret");

        JoinOutcome dup = service.join(room.getRoomId(), "sess-2", "Ada", null);
        assertThat(dup.isJoined()).isFalse();
        assertThat(dup.getRefusedReason()).isEqualTo("NAME_TAKEN");
    }

    @Test
    void sameSessionSameName_reconnectIsAccepted_notDuplicated() {
        Room room = pairRoom("teach_interview", "secret");
        JoinOutcome first = service.join(room.getRoomId(), "sess-1", "Ada", "secret");
        JoinOutcome again = service.join(room.getRoomId(), "sess-1", "Ada", "secret");

        assertThat(again.isJoined()).isTrue();
        assertThat(again.getMember().getMemberId()).isEqualTo(first.getMember().getMemberId());
        assertThat(service.roster(room.getRoomId())).hasSize(1);
    }

    @Test
    void pairPresets_setDefaultEditAccess() {
        Room teach = pairRoom("teach_interview", "s1");
        assertThat(service.join(teach.getRoomId(), "a", "A", null)
                .getMember().isCanEdit()).isFalse(); // only the Host edits by default

        setUp(); // fresh store
        Room debug = new Room("DebugRoom0000000000AA", RoomMode.PAIR, "debug_together", null,
                OwnerTokens.hash("s2"));
        store.put(debug);
        assertThat(service.join(debug.getRoomId(), "b", "B", null)
                .getMember().isCanEdit()).isTrue(); // everyone edits by default
    }

    @Test
    void joinedMessage_hasContractShape_andNoSessionId() {
        Room room = pairRoom("teach_interview", "secret");
        JoinOutcome out = service.join(room.getRoomId(), "sess-1", "Ada", "secret");

        Map<String, Object> msg = RoomMessages.joined(room, out.getMember());
        assertThat(msg).containsEntry("type", "joined")
                .containsEntry("member_id", out.getMember().getMemberId())
                .containsEntry("role", "host")
                .containsEntry("mode", "pair")
                .containsEntry("preset", "teach_interview")
                .doesNotContainKey("session_id");

        @SuppressWarnings("unchecked")
        Map<String, Object> perms = (Map<String, Object>) msg.get("permissions");
        assertThat(perms).containsKeys("can_edit", "can_run");
    }

    @Test
    void openMode_omitsCanEdit_inMessages() {
        Room room = openRoom("secret");
        JoinOutcome out = service.join(room.getRoomId(), "sess-1", "Ada", "secret");

        @SuppressWarnings("unchecked")
        Map<String, Object> perms =
                (Map<String, Object>) RoomMessages.joined(room, out.getMember()).get("permissions");
        assertThat(perms).containsKey("can_run").doesNotContainKey("can_edit");
    }

    @Test
    void rosterMessage_listsMembersByMemberId_withoutSessionId() {
        Room room = pairRoom("teach_interview", "secret");
        service.join(room.getRoomId(), "sess-1", "Ada", "secret");
        service.join(room.getRoomId(), "sess-2", "Lin", null);

        Map<String, Object> roster = RoomMessages.roster(room);
        assertThat(roster).containsEntry("type", "roster");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) roster.get("members");
        assertThat(members).hasSize(2);
        assertThat(members).allSatisfy(m ->
                assertThat(m).containsKey("member_id").doesNotContainKey("session_id"));
    }
    @Test
    void caseVariantsAndUnicodeCompatibilityNames_areTakenAcrossSessions() {
        Room room = pairRoom("teach_interview", "secret");
        JoinOutcome first = service.join(room.getRoomId(), "sess-1", "Ada", null);

        for (String variant : List.of("ada", "ADA", "Ａｄａ", " Ada ")) {
            JoinOutcome duplicate = service.join(room.getRoomId(), "session-" + variant, variant, null);
            assertThat(duplicate.isJoined()).as("variant %s", variant).isFalse();
            assertThat(duplicate.getRefusedReason()).isEqualTo("NAME_TAKEN");
        }
        assertThat(service.roster(room.getRoomId())).hasSize(1);
        assertThat(first.getMember().getDisplayName()).isEqualTo("Ada");
    }

    @Test
    void sameSessionWithDifferentCasing_keepsOriginalServerRosterName() {
        Room room = pairRoom("teach_interview", "secret");
        JoinOutcome first = service.join(room.getRoomId(), "sess-1", "Ada", null);
        JoinOutcome second = service.join(room.getRoomId(), "sess-1", "ada", null);
        assertThat(second.isJoined()).isTrue();
        assertThat(second.getMember().getMemberId()).isEqualTo(first.getMember().getMemberId());
        assertThat(second.getMember().getDisplayName()).isEqualTo("Ada");
    }

    @Test
    void simultaneousJoinsForSameName_admitExactlyOneSession() throws Exception {
        Room room = pairRoom("teach_interview", "secret");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<JoinOutcome> first = pool.submit(() -> {
                start.await();
                return service.join(room.getRoomId(), "sess-1", "Grace", null);
            });
            Future<JoinOutcome> second = pool.submit(() -> {
                start.await();
                return service.join(room.getRoomId(), "sess-2", "grace", null);
            });
            start.countDown();
            JoinOutcome a = first.get(5, TimeUnit.SECONDS);
            JoinOutcome b = second.get(5, TimeUnit.SECONDS);

            assertThat(List.of(a, b).stream().filter(JoinOutcome::isJoined).count()).isEqualTo(1L);
            assertThat(List.of(a, b).stream().filter(out -> !out.isJoined())
                    .map(JoinOutcome::getRefusedReason).toList()).containsExactly("NAME_TAKEN");
            assertThat(service.roster(room.getRoomId())).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void takenNameIsRejectedEvenWhenRoomRequiresApproval() {
        Room room = pairRoom("teach_interview", "secret");
        service.join(room.getRoomId(), "sess-1", "Ada", null);
        room.setRequiresApproval(true);

        JoinOutcome duplicate = service.join(room.getRoomId(), "sess-2", "ada", null);
        assertThat(duplicate.isJoined()).isFalse();
        assertThat(duplicate.getRefusedReason()).isEqualTo("NAME_TAKEN");

        JoinOutcome freshName = service.join(room.getRoomId(), "sess-3", "Lin", null);
        assertThat(freshName.isJoined()).isFalse();
        assertThat(freshName.getRefusedReason()).isEqualTo("APPROVAL_PENDING");
    }

    @Test
    void setEditAccess_updatesMemberAndReturnsMember() {
        Room room = pairRoom("teach_interview", "secret");
        JoinOutcome guest = service.join(room.getRoomId(), "sess-guest", "Lin", null);
        String guestId = guest.getMember().getMemberId();

        assertThat(guest.getMember().isCanEdit()).isFalse();

        Member updated = service.setEditAccess(room.getRoomId(), guestId, true);
        assertThat(updated).isNotNull();
        assertThat(updated.isCanEdit()).isTrue();
        assertThat(guest.getMember().isCanEdit()).isTrue();

        Member revoked = service.setEditAccess(room.getRoomId(), guestId, false);
        assertThat(revoked).isNotNull();
        assertThat(revoked.isCanEdit()).isFalse();
        assertThat(guest.getMember().isCanEdit()).isFalse();
    }

    @Test
    void setEditAccess_returnsNullWhenUnknownRoomOrMember() {
        Room room = pairRoom("teach_interview", "secret");
        service.join(room.getRoomId(), "sess-guest", "Lin", null);

        assertThat(service.setEditAccess("non-existent-room", "m-1", true)).isNull();
        assertThat(service.setEditAccess(room.getRoomId(), "non-existent-member", true)).isNull();
    }

    @Test
    void teachInterview_hostCanEdit_firstAndLaterParticipantsCannot() {
        Room teach = pairRoom("teach_interview", "secret");

        // Host joins with valid owner token
        JoinOutcome host = service.join(teach.getRoomId(), "sess-host", "Host", "secret");
        assertThat(host.getMember().getRole()).isEqualTo(Role.HOST);
        assertThat(host.getMember().isCanEdit()).isTrue();

        // First participant joins
        JoinOutcome p1 = service.join(teach.getRoomId(), "sess-p1", "Participant1", null);
        assertThat(p1.getMember().getRole()).isEqualTo(Role.PARTICIPANT);
        assertThat(p1.getMember().isCanEdit()).isFalse();

        // Later participant joins
        JoinOutcome p2 = service.join(teach.getRoomId(), "sess-p2", "Participant2", null);
        assertThat(p2.getMember().getRole()).isEqualTo(Role.PARTICIPANT);
        assertThat(p2.getMember().isCanEdit()).isFalse();
    }

    @Test
    void debugTogether_hostAndAllJoinersCanEdit_includingAfterRevocation() {
        Room debug = new Room("DebugRoom0000000000AA", RoomMode.PAIR, "debug_together", null,
                OwnerTokens.hash("secret"));
        store.put(debug);

        // Host joins
        JoinOutcome host = service.join(debug.getRoomId(), "sess-host", "Host", "secret");
        assertThat(host.getMember().getRole()).isEqualTo(Role.HOST);
        assertThat(host.getMember().isCanEdit()).isTrue();

        // First participant joins
        JoinOutcome p1 = service.join(debug.getRoomId(), "sess-p1", "Participant1", null);
        assertThat(p1.getMember().getRole()).isEqualTo(Role.PARTICIPANT);
        assertThat(p1.getMember().isCanEdit()).isTrue();

        // Host revokes p1's edit access
        service.setEditAccess(debug.getRoomId(), p1.getMember().getMemberId(), false);
        assertThat(p1.getMember().isCanEdit()).isFalse();

        // Later participant joins: starts with default can_edit=true
        JoinOutcome p2 = service.join(debug.getRoomId(), "sess-p2", "Participant2", null);
        assertThat(p2.getMember().getRole()).isEqualTo(Role.PARTICIPANT);
        assertThat(p2.getMember().isCanEdit()).isTrue();
    }

    @Test
    void observerCannotEdit_inEitherPreset() {
        // Teach / Interview
        Room teach = pairRoom("teach_interview", "secret");
        JoinOutcome teachMember = service.join(teach.getRoomId(), "sess-t", "Tom", null);
        Member teachObs = service.changeRole(teach.getRoomId(), teachMember.getMember().getMemberId(), Role.OBSERVER);
        assertThat(teachObs.getRole()).isEqualTo(Role.OBSERVER);
        assertThat(teachObs.isCanEdit()).isFalse();

        // Debug together
        setUp();
        Room debug = new Room("DebugRoom0000000000AA", RoomMode.PAIR, "debug_together", null,
                OwnerTokens.hash("secret"));
        store.put(debug);
        JoinOutcome debugMember = service.join(debug.getRoomId(), "sess-d", "Dan", null);
        assertThat(debugMember.getMember().isCanEdit()).isTrue();
        Member debugObs = service.changeRole(debug.getRoomId(), debugMember.getMember().getMemberId(), Role.OBSERVER);
        assertThat(debugObs.getRole()).isEqualTo(Role.OBSERVER);
        assertThat(debugObs.isCanEdit()).isFalse();
    }

    @Test
    void reconnectKeepsCurrentAccess_noResetToDefault() {
        // Teach/Interview: participant granted access keeps it on reconnect
        Room teach = pairRoom("teach_interview", "secret");
        JoinOutcome p1 = service.join(teach.getRoomId(), "sess-p1", "Pat", null);
        assertThat(p1.getMember().isCanEdit()).isFalse();
        service.setEditAccess(teach.getRoomId(), p1.getMember().getMemberId(), true);
        assertThat(p1.getMember().isCanEdit()).isTrue();

        JoinOutcome reconnectTeach = service.join(teach.getRoomId(), "sess-p1", "Pat", null);
        assertThat(reconnectTeach.isJoined()).isTrue();
        assertThat(reconnectTeach.getMember().isCanEdit()).isTrue();

        // Debug together: participant whose access was revoked keeps false on reconnect
        setUp();
        Room debug = new Room("DebugRoom0000000000AA", RoomMode.PAIR, "debug_together", null,
                OwnerTokens.hash("secret"));
        store.put(debug);
        JoinOutcome p2 = service.join(debug.getRoomId(), "sess-p2", "Dana", null);
        assertThat(p2.getMember().isCanEdit()).isTrue();
        service.setEditAccess(debug.getRoomId(), p2.getMember().getMemberId(), false);
        assertThat(p2.getMember().isCanEdit()).isFalse();

        JoinOutcome reconnectDebug = service.join(debug.getRoomId(), "sess-p2", "Dana", null);
        assertThat(reconnectDebug.isJoined()).isTrue();
        assertThat(reconnectDebug.getMember().isCanEdit()).isFalse();
    }

    @Test
    void us63_limitTwo_acceptsTwo_thenRefusesThird_andAllowsReconnect() {
        ReflectionTestUtils.setField(service, "maxMembersPerRoom", 2);
        Room room = pairRoom("teach_interview", "secret");
        JoinOutcome host = service.join(room.getRoomId(), "host-session", "Host", "secret");
        JoinOutcome guest = service.join(room.getRoomId(), "guest-session", "Guest", null);
        assertThat(host.isJoined()).isTrue();
        assertThat(guest.isJoined()).isTrue();

        JoinOutcome refused = service.join(room.getRoomId(), "other-session", "Other", null);
        assertThat(refused.isJoined()).isFalse();
        assertThat(refused.getRefusedReason()).isEqualTo("ROOM_FULL");
        assertThat(service.roster(room.getRoomId())).hasSize(2);

        JoinOutcome reconnect = service.join(room.getRoomId(), "guest-session", "Guest", null);
        assertThat(reconnect.isJoined()).isTrue();
        assertThat(reconnect.getMember().getMemberId()).isEqualTo(guest.getMember().getMemberId());
        assertThat(service.roster(room.getRoomId())).hasSize(2);
    }

    @Test
    void us63_limitTen_refusesEleventh_withoutAffectingOtherRoom() {
        ReflectionTestUtils.setField(service, "maxMembersPerRoom", 10);
        Room first = openRoom("secret");
        for (int i = 0; i < 10; i++) {
            assertThat(service.join(first.getRoomId(), "sess-" + i, "User" + i,
                    i == 0 ? "secret" : null).isJoined()).isTrue();
        }
        JoinOutcome extra = service.join(first.getRoomId(), "sess-10", "User10", null);
        assertThat(extra.isJoined()).isFalse();
        assertThat(extra.getRefusedReason()).isEqualTo("ROOM_FULL");

        Room second = pairRoom("teach_interview", "other-secret");
        assertThat(service.join(second.getRoomId(), "new-session", "New", "other-secret")
                .isJoined()).isTrue();
    }

    @Test
    void us63_hostWithValidOwnerTokenCanReturnAtCapacity() {
        ReflectionTestUtils.setField(service, "maxMembersPerRoom", 2);
        Room room = pairRoom("teach_interview", "secret");
        JoinOutcome originalHost = service.join(room.getRoomId(), "host-session", "Host", "secret");
        assertThat(originalHost.isJoined()).isTrue();
        assertThat(service.join(room.getRoomId(), "guest-1", "Guest1", null).isJoined()).isTrue();
        // Simulate Host losing its roster entry during absence without blocking.
        room.getMembers().remove(originalHost.getMember().getMemberId());
        assertThat(service.join(room.getRoomId(), "guest-2", "Guest2", null).isJoined()).isTrue();
        JoinOutcome returned = service.join(room.getRoomId(), "host-return", "Host", "secret");
        assertThat(returned.isJoined()).isTrue();
        assertThat(returned.getMember().getRole()).isEqualTo(Role.HOST);
        assertThat(service.roster(room.getRoomId())).hasSize(3);
    }
}
