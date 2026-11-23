package com.collabcode.syncservice.module;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaveModuleTest {

    private RoomStore roomStore;
    private ModuleService moduleService;

    private static final String OPEN_ROOM_ID = "openRoom1234567890123";
    private static final String PAIR_ROOM_ID = "pairRoom1234567890123";
    private static final String OWNER_TOKEN = "test-secret-token";

    private final Member host = new Member("m_host", "s_host", "Host", Role.HOST, false, true);
    private final Member owner = new Member("m_owner", "s_owner", "Alice", Role.PARTICIPANT, false, true);
    private final Member writer = new Member("m_writer", "s_writer", "Bob", Role.PARTICIPANT, false, true);
    private final Member reader = new Member("m_reader", "s_reader", "Carol", Role.PARTICIPANT, false, true);
    private final Member observer = new Member("m_obs", "s_obs", "Dan", Role.OBSERVER, false, false);

    @BeforeEach
    void setUp() {
        roomStore = new RoomStore();
        moduleService = new ModuleService(roomStore);

        Room openRoom = new Room(OPEN_ROOM_ID, RoomMode.OPEN, null, "python", OwnerTokens.hash(OWNER_TOKEN));
        openRoom.getMembers().put(host.getMemberId(), host);
        openRoom.getMembers().put(owner.getMemberId(), owner);
        openRoom.getMembers().put(writer.getMemberId(), writer);
        openRoom.getMembers().put(reader.getMemberId(), reader);
        openRoom.getMembers().put(observer.getMemberId(), observer);
        roomStore.put(openRoom);

        Room pairRoom = new Room(PAIR_ROOM_ID, RoomMode.PAIR, "debug_together", "python", OwnerTokens.hash(OWNER_TOKEN));
        pairRoom.getMembers().put(host.getMemberId(), host);
        roomStore.put(pairRoom);
    }

    @Test
    void ownerAndWriterCanSaveAndVersionsRiseMonotonically() {
        ModuleRecord mod = moduleService.createModule(OPEN_ROOM_ID, owner.getMemberId(), "solver.py", false);
        moduleService.grantWriteAccess(OPEN_ROOM_ID, mod.moduleId(), owner, null, writer.getMemberId());

        assertThat(mod.version()).isZero();
        assertThat(mod.dirty()).isFalse();

        assertThat(moduleService.markDirty(OPEN_ROOM_ID, mod.moduleId())).isTrue();
        assertThat(moduleService.markDirty(OPEN_ROOM_ID, mod.moduleId())).isFalse();
        assertThat(mod.dirty()).isTrue();

        ModuleRecord v1 = moduleService.saveModule(OPEN_ROOM_ID, mod.moduleId(), owner, "print(1)\n");
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.dirty()).isFalse();
        assertThat(v1.savedText()).isEqualTo("print(1)\n");
        assertThat(v1.savedByMemberId()).isEqualTo(owner.getMemberId());
        assertThat(v1.savedByDisplayName()).isEqualTo("Alice");
        assertThat(v1.savedAt()).isNotNull();

        assertThat(moduleService.markDirty(OPEN_ROOM_ID, mod.moduleId())).isTrue();
        ModuleRecord v2 = moduleService.saveModule(OPEN_ROOM_ID, mod.moduleId(), writer, "print(2)\n");
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.dirty()).isFalse();
        assertThat(v2.savedText()).isEqualTo("print(2)\n");
        assertThat(v2.savedByMemberId()).isEqualTo(writer.getMemberId());
        assertThat(v2.savedByDisplayName()).isEqualTo("Bob");
        assertThat(v2.savedVersions()).hasSize(2);
    }

    @Test
    void readerObserverAndHostWithoutWriteAccessAreRefused() {
        ModuleRecord mod = moduleService.createModule(OPEN_ROOM_ID, owner.getMemberId(), "main.py", false);

        assertThatThrownBy(() -> moduleService.saveModule(OPEN_ROOM_ID, mod.moduleId(), reader, "x"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("NOT_MODULE_WRITER");

        assertThatThrownBy(() -> moduleService.saveModule(OPEN_ROOM_ID, mod.moduleId(), observer, "x"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("NOT_MODULE_WRITER");

        assertThatThrownBy(() -> moduleService.saveModule(OPEN_ROOM_ID, mod.moduleId(), host, "x"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("NOT_MODULE_WRITER");

        assertThat(mod.version()).isZero();
    }

    @Test
    void missingModuleAndPairModeAreRefusedAndRoomDeleteClearsSavedVersions() {
        assertThatThrownBy(() -> moduleService.saveModule(OPEN_ROOM_ID, "mod_does_not_exist", owner, "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MODULE_NOT_FOUND");

        assertThatThrownBy(() -> moduleService.saveModule(PAIR_ROOM_ID, "mod_any", host, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PAIR_MODE_NO_MODULES");

        ModuleRecord mod = moduleService.createModule(OPEN_ROOM_ID, owner.getMemberId(), "app.py", false);
        moduleService.saveModule(OPEN_ROOM_ID, mod.moduleId(), owner, "saved");
        assertThat(mod.savedVersions()).hasSize(1);

        moduleService.deleteByRoom(OPEN_ROOM_ID);
        assertThat(moduleService.find(OPEN_ROOM_ID, mod.moduleId())).isNull();
    }
}
