package com.collabcode.syncservice.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
