package com.collabcode.syncservice.room;

import com.collabcode.syncservice.module.ModuleRecord;
import com.collabcode.syncservice.module.ModuleService;
import com.collabcode.syncservice.run.InputHasher;
import com.collabcode.syncservice.run.RunRecord;
import com.collabcode.syncservice.run.RunRecordStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** US-56: local teardown must succeed independently of the remote endpoint. */
class RoomCleanupCoordinatorTest {

    @Test
    void hostClosePurgesModulesRunRecordsMembersBlocksAndTokenAndPreserves4410Tombstone() {
        RoomStore rooms = new RoomStore();
        RoomRegistry registry = new RoomRegistry();
        RunRecordStore runs = new RunRecordStore();
        ModuleService modules = new ModuleService(rooms);
        RoomEndedNotifier remote = new RoomEndedNotifier("http://localhost:1", "", 10L, new ObjectMapper());
        RoomCleanupCoordinator cleanup = new RoomCleanupCoordinator(rooms, registry, modules, runs, remote);
        RoomService service = new RoomService(rooms, runs);
        service.setRoomCleanupCoordinator(cleanup);

        Room room = new Room("roomUS56A", RoomMode.OPEN, null, "python", OwnerTokens.hash("secret"));
        rooms.put(room);
        room.getMembers().put("memberA", new Member("memberA", "sessionA", "Host", Role.HOST, true, true));
        room.block("blocked-session", "blocked-member", "Removed", Instant.now());
        ModuleRecord module = modules.createModule(room.getRoomId(), "memberA", "main.py", false);
        runs.record(new RunRecord(room.getRoomId(), "Host", "2026-11-21T10:00:00Z", "python",
                List.of("main.py"), List.of(), InputHasher.hash("stdin"), 12.0, 4.0));
        assertThat(modules.find(room.getRoomId(), module.moduleId())).isNotNull();
        assertThat(runs.count(room.getRoomId())).isEqualTo(1);

        assertThat(service.closeRoom(room.getRoomId())).isTrue();
        assertThat(room.isClosed()).isTrue();
        assertThat(room.getMembers()).isEmpty();
        assertThat(room.blockedSessions()).isEmpty();
        assertThat(room.getOwnerTokenHash()).isNull();
        assertThat(modules.find(room.getRoomId(), module.moduleId())).isNull();
        assertThat(runs.count(room.getRoomId())).isZero();
        assertThat(service.join(room.getRoomId(), "new", "New", null).getRefusedReason()).isEqualTo("ROOM_CLOSED");
        assertThat(service.closeRoom(room.getRoomId())).isTrue(); // idempotent
    }

    @Test
    void deletingUnknownRoomIsHarmlessAndOtherRoomsRemainIntact() {
        RoomStore rooms = new RoomStore();
        RoomRegistry registry = new RoomRegistry();
        RunRecordStore runs = new RunRecordStore();
        ModuleService modules = new ModuleService(rooms);
        RoomEndedNotifier remote = new RoomEndedNotifier("http://localhost:1", "", 10L, new ObjectMapper());
        RoomCleanupCoordinator cleanup = new RoomCleanupCoordinator(rooms, registry, modules, runs, remote);
        RoomService service = new RoomService(rooms, runs);
        service.setRoomCleanupCoordinator(cleanup);
        Room keep = new Room("keep-US56", RoomMode.PAIR, "debug_together", null, OwnerTokens.hash("secret"));
        rooms.put(keep);
        assertThat(service.deleteRoom("unknown-US56")).isFalse();
        assertThat(rooms.get("keep-US56")).isSameAs(keep);
        assertThat(keep.isClosed()).isFalse();
    }
}
