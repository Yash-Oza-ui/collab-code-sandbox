package com.collabcode.syncservice.module;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link ModuleService#moveRoom(String, String)} (US-28 regenerate-link):
 * the move must MERGE, not overwrite, so a module created on the new id in the gap
 * before the move runs is not silently lost (Bhavya review, item 4).
 */
class ModuleServiceMoveRoomTest {

    private Room openRoom(RoomStore store, String id) {
        Room room = new Room(id, RoomMode.OPEN, null, "python", OwnerTokens.hash("secret"));
        store.put(room);
        return room;
    }

    @Test
    void moveRoom_mergesAndKeepsAModuleCreatedOnTheNewIdInTheGap() {
        RoomStore store = new RoomStore();
        openRoom(store, "oldRoom0000000000000A");
        openRoom(store, "newRoom0000000000000B");
        ModuleService modules = new ModuleService(store);

        Member host = new Member("m_host", "s-host", "Host", Role.HOST, false, true);
        Member guest = new Member("m_guest", "s-guest", "Guest", Role.PARTICIPANT, false, true);

        // A module on the OLD id (the one being moved).
        ModuleRecord moving = modules.requestCreate("oldRoom0000000000000A", guest, "a.py");
        modules.approve("oldRoom0000000000000A", moving.moduleId(), host, "secret");
        // A module created on the NEW id in the gap before moveRoom runs.
        ModuleRecord gap = modules.requestCreate("newRoom0000000000000B", guest, "b.py");
        modules.approve("newRoom0000000000000B", gap.moduleId(), host, "secret");

        modules.moveRoom("oldRoom0000000000000A", "newRoom0000000000000B");

        List<String> ids = modules.listApproved("newRoom0000000000000B", guest).stream()
                .map(ModuleRecord::moduleId).toList();
        assertThat(ids).containsExactlyInAnyOrder(moving.moduleId(), gap.moduleId());
    }

    @Test
    void moveRoom_movesModulesAndEmptiesTheOldId() {
        RoomStore store = new RoomStore();
        openRoom(store, "oldRoom0000000000000A");
        openRoom(store, "newRoom0000000000000B");
        ModuleService modules = new ModuleService(store);

        Member host = new Member("m_host", "s-host", "Host", Role.HOST, false, true);
        Member guest = new Member("m_guest", "s-guest", "Guest", Role.PARTICIPANT, false, true);
        ModuleRecord m = modules.requestCreate("oldRoom0000000000000A", guest, "a.py");
        modules.approve("oldRoom0000000000000A", m.moduleId(), host, "secret");

        modules.moveRoom("oldRoom0000000000000A", "newRoom0000000000000B");

        assertThat(modules.listApproved("oldRoom0000000000000A", guest)).isEmpty();
        assertThat(modules.listApproved("newRoom0000000000000B", guest))
                .extracting(ModuleRecord::moduleId).containsExactly(m.moduleId());
    }
}
