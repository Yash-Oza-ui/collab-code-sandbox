package com.collabcode.syncservice.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

}
