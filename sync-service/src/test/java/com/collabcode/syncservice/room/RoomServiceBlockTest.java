package com.collabcode.syncservice.room;

import com.collabcode.syncservice.run.RunRecord;
import com.collabcode.syncservice.run.RunRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the US-28 (V2.18) block list: remove, re-admit, regenerate-link
 * and the USER_BLOCKED join refusal (FR9.6, CR04). No Spring context is needed.
 */
class RoomServiceBlockTest {

    private RoomStore store;
    private RoomService service;

    @BeforeEach
    void setUp() {
        store = new RoomStore();
        service = new RoomService(store);
    }

    private Room pairRoom(String ownerToken) {
        Room room = new Room("Zq8-Lw2Ef6Th0Yx4Rn7JbA", RoomMode.PAIR, "teach_interview", null,
                OwnerTokens.hash(ownerToken));
        store.put(room);
        return room;
    }

    @Test
    void removeMember_recordsBlock_dropsFromRoster_andReturnsMember() {
        Room room = pairRoom("secret");
        service.join(room.getRoomId(), "host-sess", "Host", "secret");
        String guestId = service.join(room.getRoomId(), "guest-sess", "Lin", null).getMember().getMemberId();

        Member removed = service.removeMember(room.getRoomId(), guestId);

        assertThat(removed).isNotNull();
        assertThat(removed.getMemberId()).isEqualTo(guestId);
        assertThat(removed.getSessionId()).isEqualTo("guest-sess");
        assertThat(room.getMembers()).doesNotContainKey(guestId);     // gone from the roster
        assertThat(room.isBlocked("guest-sess")).isTrue();            // blocked by session id
        assertThat(room.isMemberBlocked(guestId)).isTrue();
    }

    @Test
    void removedSession_cannotRejoin_refusedUserBlocked() {
        Room room = pairRoom("secret");
        String guestId = service.join(room.getRoomId(), "guest-sess", "Lin", null).getMember().getMemberId();
        service.removeMember(room.getRoomId(), guestId);

        JoinOutcome out = service.join(room.getRoomId(), "guest-sess", "Lin", null);
        assertThat(out.isJoined()).isFalse();
        assertThat(out.getRefusedReason()).isEqualTo("USER_BLOCKED");
    }

    @Test
    void blockIsBySession_notByName_differentNameStillBlocked() {
        Room room = pairRoom("secret");
        String guestId = service.join(room.getRoomId(), "guest-sess", "Lin", null).getMember().getMemberId();
        service.removeMember(room.getRoomId(), guestId);

        // same session, a completely different display name -> still blocked (CR04)
        JoinOutcome out = service.join(room.getRoomId(), "guest-sess", "A New Name", null);
        assertThat(out.getRefusedReason()).isEqualTo("USER_BLOCKED");
    }

    @Test
    void readmit_liftsBlock_allowsRejoin_withFreshMemberId() {
        Room room = pairRoom("secret");
        String guestId = service.join(room.getRoomId(), "guest-sess", "Lin", null).getMember().getMemberId();
        service.removeMember(room.getRoomId(), guestId);

        assertThat(service.readmit(room.getRoomId(), guestId)).isTrue();
        assertThat(room.isBlocked("guest-sess")).isFalse();

        JoinOutcome out = service.join(room.getRoomId(), "guest-sess", "Lin", null);
        assertThat(out.isJoined()).isTrue();
        assertThat(out.getMember().getMemberId()).isNotEqualTo(guestId); // retired id, fresh one (V2.3)
    }

    @Test
    void readmit_unknownMember_returnsFalse() {
        Room room = pairRoom("secret");
        assertThat(service.readmit(room.getRoomId(), "m_nope")).isFalse();
    }

    @Test
    void readmit_onlyTargetSessionIsFreed_othersStayBlocked() {
        Room room = pairRoom("secret");
        String g1 = service.join(room.getRoomId(), "s1", "Lin", null).getMember().getMemberId();
        String g2 = service.join(room.getRoomId(), "s2", "Max", null).getMember().getMemberId();
        service.removeMember(room.getRoomId(), g1);
        service.removeMember(room.getRoomId(), g2);

        service.readmit(room.getRoomId(), g1);

        assertThat(room.isBlocked("s1")).isFalse();
        assertThat(room.isBlocked("s2")).isTrue();
    }

    @Test
    void removeMember_unknownMember_returnsNull() {
        Room room = pairRoom("secret");
        assertThat(service.removeMember(room.getRoomId(), "m_nope")).isNull();
    }

    @Test
    void removeMember_unknownRoom_returnsNull() {
        assertThat(service.removeMember("NoSuchRoom0000000000AA", "m_x")).isNull();
    }

    @Test
    void listBlocked_returnsRemovedMembers() {
        Room room = pairRoom("secret");
        String g1 = service.join(room.getRoomId(), "s1", "Lin", null).getMember().getMemberId();
        String g2 = service.join(room.getRoomId(), "s2", "Max", null).getMember().getMemberId();
        service.removeMember(room.getRoomId(), g1);
        service.removeMember(room.getRoomId(), g2);

        List<Room.BlockedSession> blocked = service.listBlocked(room.getRoomId());
        assertThat(blocked).hasSize(2);
        assertThat(blocked).extracting(Room.BlockedSession::memberId).containsExactlyInAnyOrder(g1, g2);
        assertThat(blocked).allSatisfy(b -> {
            assertThat(b.displayName()).isNotBlank();
            assertThat(b.removedAt()).isNotNull();
        });
    }

    @Test
    void regenerateLink_newId_carriesMembers_clearsBlock_oldIdGone() {
        Room room = pairRoom("secret");
        String hostId = service.join(room.getRoomId(), "host-sess", "Host", "secret").getMember().getMemberId();
        String guestId = service.join(room.getRoomId(), "guest-sess", "Lin", null).getMember().getMemberId();
        String goneId = service.join(room.getRoomId(), "gone-sess", "Gone", null).getMember().getMemberId();
        service.removeMember(room.getRoomId(), goneId); // a block that must be cleared
        String oldId = room.getRoomId();

        Room fresh = service.regenerateLink(oldId);

        assertThat(fresh).isNotNull();
        assertThat(fresh.getRoomId()).isNotEqualTo(oldId);
        assertThat(fresh.getRoomId()).hasSize(22);                       // NFR16 / US-62
        assertThat(store.get(oldId)).isNull();                           // old id stops resolving
        assertThat(store.get(fresh.getRoomId())).isSameAs(fresh);
        assertThat(fresh.getMembers()).containsKeys(hostId, guestId);    // member_ids carried over (V2.3)
        assertThat(fresh.isMemberBlocked(goneId)).isFalse();             // block list cleared
        assertThat(fresh.isBlocked("gone-sess")).isFalse();
        assertThat(fresh.getOwnerTokenHash()).isEqualTo(room.getOwnerTokenHash()); // same owner token
    }

    @Test
    void regenerateLink_unknownRoom_returnsNull() {
        assertThat(service.regenerateLink("NoSuchRoom0000000000AA")).isNull();
    }

    @Test
    void regenerateLink_closedRoom_staysClosed_andCarriesFlags() {
        Room room = pairRoom("secret");
        room.setRequiresApproval(true);
        room.close();
        Room fresh = service.regenerateLink(room.getRoomId());
        assertThat(fresh.isClosed()).isTrue();            // a closed room is not resurrected (V2.18)
        assertThat(fresh.isRequiresApproval()).isTrue();  // flags carried via Room.withNewId
    }

    @Test
    void regenerateLink_movesRunRecords_toNewIdWithRebuiltRoomId() {
        RunRecordStore runs = new RunRecordStore();
        RoomService svc = new RoomService(store, runs);
        Room room = pairRoom("secret");
        String oldId = room.getRoomId();
        runs.record(new RunRecord(oldId, "Ada", "2026-11-18T10:00:00Z", "python",
                List.of("main.py"), List.of(), "abc123", 12.0, 3.0));

        Room fresh = svc.regenerateLink(oldId);

        assertThat(runs.findByRoom(oldId)).isEmpty();                 // old id purged
        assertThat(runs.findByRoom(fresh.getRoomId())).hasSize(1);    // moved to the new id
        assertThat(runs.findByRoom(fresh.getRoomId()).get(0).getRoomId())
                .isEqualTo(fresh.getRoomId());                        // room_id rebuilt
    }
}
