package com.collabcode.syncservice.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RoomService#transferHost(String, String, String)} (US-54,
 * V2.19; FR31.1): owner-token reissue, the single-Host invariant and permission
 * recompute. No Spring context is needed.
 */
class RoomServiceTransferHostTest {

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

    private long hostCount(Room room) {
        return room.getMembers().values().stream().filter(m -> m.getRole() == Role.HOST).count();
    }

    @Test
    void transferHost_promotesTarget_demotesOldHost_andReissuesToken() {
        Room room = pairRoom("old-secret");
        String hostId = service.join(room.getRoomId(), "host-sess", "Host", "old-secret").getMember().getMemberId();
        String guestId = service.join(room.getRoomId(), "guest-sess", "Lin", null).getMember().getMemberId();

        Member newHost = service.transferHost(room.getRoomId(), guestId, OwnerTokens.hash("new-secret"));

        assertThat(newHost).isNotNull();
        assertThat(newHost.getMemberId()).isEqualTo(guestId);
        assertThat(room.getMembers().get(guestId).getRole()).isEqualTo(Role.HOST);        // target promoted
        assertThat(room.getMembers().get(hostId).getRole()).isEqualTo(Role.PARTICIPANT);  // old Host demoted
        assertThat(hostCount(room)).isEqualTo(1);                                          // exactly one Host
        assertThat(OwnerTokens.matches("old-secret", room.getOwnerTokenHash())).isFalse(); // old token retired
        assertThat(OwnerTokens.matches("new-secret", room.getOwnerTokenHash())).isTrue();  // new token matches
    }

    @Test
    void transferHost_newHostGetsHostPermissions_inPair() {
        Room room = pairRoom("secret"); // teach_interview: the Host edits the shared file
        service.join(room.getRoomId(), "host-sess", "Host", "secret");
        String guestId = service.join(room.getRoomId(), "guest-sess", "Lin", null).getMember().getMemberId();

        service.transferHost(room.getRoomId(), guestId, OwnerTokens.hash("new"));

        Member newHost = room.getMembers().get(guestId);
        assertThat(newHost.isCanRun()).isTrue();
        assertThat(newHost.isCanEdit()).isTrue();
    }

    @Test
    void transferHost_leavesExactlyOneHost_evenWithMultipleOwnerSessions() {
        Room room = pairRoom("secret");
        service.join(room.getRoomId(), "host-1", "Host1", "secret"); // two sessions with the owner token
        service.join(room.getRoomId(), "host-2", "Host2", "secret"); // -> two Hosts before transfer
        String guestId = service.join(room.getRoomId(), "guest-sess", "Lin", null).getMember().getMemberId();
        assertThat(hostCount(room)).isEqualTo(2);

        service.transferHost(room.getRoomId(), guestId, OwnerTokens.hash("new"));

        assertThat(hostCount(room)).isEqualTo(1);
        assertThat(room.getMembers().get(guestId).getRole()).isEqualTo(Role.HOST);
    }

    @Test
    void transferHost_unknownRoom_returnsNull() {
        assertThat(service.transferHost("NoSuchRoom0000000000AA", "m_x", OwnerTokens.hash("n"))).isNull();
    }

    @Test
    void transferHost_unknownTarget_returnsNull() {
        Room room = pairRoom("secret");
        assertThat(service.transferHost(room.getRoomId(), "m_nope", OwnerTokens.hash("n"))).isNull();
    }
}
