package com.collabcode.syncservice.module;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ModuleService} (US-39, FR20.1, FR21.1-21.3, DR4).
 */
class ModuleServiceTest {

    private RoomStore roomStore;
    private ModuleService moduleService;

    private static final String OPEN_ROOM_ID = "openRoom1234567890123";
    private static final String PAIR_ROOM_ID = "pairRoom1234567890123";
    private static final String OWNER_TOKEN = "test-secret-token";

    private final Member host = new Member("m_host", "s_host", "Host", Role.HOST, false, true);
    private final Member participant = new Member("m_part", "s_part", "Participant", Role.PARTICIPANT, false, true);

    @BeforeEach
    void setUp() {
        roomStore = new RoomStore();
        moduleService = new ModuleService(roomStore);

        // Open mode room in Python
        Room openRoom = new Room(OPEN_ROOM_ID, RoomMode.OPEN, null, "python", OwnerTokens.hash(OWNER_TOKEN));
        openRoom.getMembers().put(host.getMemberId(), host);
        openRoom.getMembers().put(participant.getMemberId(), participant);
        roomStore.put(openRoom);

        // Pair mode room
        Room pairRoom = new Room(PAIR_ROOM_ID, RoomMode.PAIR, "teach_interview", null, OwnerTokens.hash(OWNER_TOKEN));
        roomStore.put(pairRoom);
    }

    @Test
    @DisplayName("Host direct creation creates approved module (pending=false, owner=host)")
    void createApprovedModule_createsImmediatelyActiveModule() {
        ModuleRecord module = moduleService.createApprovedModule(OPEN_ROOM_ID, host, "main.py");

        assertThat(module).isNotNull();
        assertThat(module.moduleId()).matches("^mod_[A-Za-z0-9_-]{16}$");
        assertThat(module.name()).isEqualTo("main.py");
        assertThat(module.ownerMemberId()).isEqualTo("m_host");
        assertThat(module.pending()).isFalse();
        assertThat(module.runnable()).isTrue();
        assertThat(module.isEntry()).isFalse();
        assertThat(module.version()).isEqualTo(0);

        // Is immediately active and listable
        assertThat(moduleService.isActive(OPEN_ROOM_ID, module.moduleId())).isTrue();
        List<ModuleRecord> list = moduleService.listApproved(OPEN_ROOM_ID, host);
        assertThat(list).extracting(ModuleRecord::moduleId).containsExactly(module.moduleId());
    }

    @Test
    @DisplayName("Participant creation request creates pending module (pending=true, owner=requester)")
    void requestCreate_createsPendingModule() {
        ModuleRecord module = moduleService.requestCreate(OPEN_ROOM_ID, participant, "solver.py");

        assertThat(module).isNotNull();
        assertThat(module.moduleId()).matches("^mod_[A-Za-z0-9_-]{16}$");
        assertThat(module.name()).isEqualTo("solver.py");
        assertThat(module.ownerMemberId()).isEqualTo("m_part");
        assertThat(module.pending()).isTrue();

        // Pending module is not active for document sockets and not in approved list
        assertThat(moduleService.isActive(OPEN_ROOM_ID, module.moduleId())).isFalse();
        assertThat(moduleService.listApproved(OPEN_ROOM_ID, participant)).isEmpty();
    }

    @Test
    @DisplayName("Host approves pending module, activating it for document sockets and listing")
    void approve_activatesPendingModule() {
        ModuleRecord pending = moduleService.requestCreate(OPEN_ROOM_ID, participant, "algo.py");
        assertThat(pending.pending()).isTrue();

        ModuleRecord approved = moduleService.approve(OPEN_ROOM_ID, pending.moduleId(), host, OWNER_TOKEN);
        assertThat(approved.pending()).isFalse();
        assertThat(approved.moduleId()).isEqualTo(pending.moduleId());
        assertThat(moduleService.isActive(OPEN_ROOM_ID, approved.moduleId())).isTrue();

        List<ModuleRecord> list = moduleService.listApproved(OPEN_ROOM_ID, participant);
        assertThat(list).extracting(ModuleRecord::moduleId).containsExactly(pending.moduleId());
    }

    @Test
    @DisplayName("Host approval can optionally reassign owner")
    void approve_withNewOwner_reassignsOwner() {
        ModuleRecord pending = moduleService.requestCreate(OPEN_ROOM_ID, participant, "algo.py");
        ModuleRecord approved = moduleService.approve(OPEN_ROOM_ID, pending.moduleId(), host, OWNER_TOKEN, "m_host");
        assertThat(approved.ownerMemberId()).isEqualTo("m_host");
    }

    @Test
    @DisplayName("Late joiner sees approved modules via listApproved")
    void lateJoiner_seesApprovedModules() {
        moduleService.createApprovedModule(OPEN_ROOM_ID, host, "main.py");
        moduleService.createApprovedModule(OPEN_ROOM_ID, host, "utils.py");

        Member lateJoiner = new Member("m_late", "s_late", "LateJoiner", Role.PARTICIPANT, false, true);
        List<ModuleRecord> modules = moduleService.listApproved(OPEN_ROOM_ID, lateJoiner);

        assertThat(modules).hasSize(2);
        assertThat(modules).extracting(ModuleRecord::name).containsExactlyInAnyOrder("main.py", "utils.py");
    }

    @Test
    @DisplayName("Duplicate file name in workspace rejected with NAME_TAKEN")
    void duplicateName_rejected() {
        moduleService.createApprovedModule(OPEN_ROOM_ID, host, "main.py");

        assertThatThrownBy(() -> moduleService.createApprovedModule(OPEN_ROOM_ID, host, "main.py"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NAME_TAKEN");

        assertThatThrownBy(() -> moduleService.requestCreate(OPEN_ROOM_ID, participant, "main.py"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NAME_TAKEN");
    }

    @Test
    @DisplayName("Wrong extension for workspace language rejected with INVALID_REQUEST")
    void wrongExtension_rejected() {
        // OPEN_ROOM_ID is Python
        assertThatThrownBy(() -> moduleService.createApprovedModule(OPEN_ROOM_ID, host, "Main.java"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> moduleService.createApprovedModule(OPEN_ROOM_ID, host, "script.js"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> moduleService.createApprovedModule(OPEN_ROOM_ID, host, "main.cpp"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Directory traversal characters in file name rejected")
    void directoryTraversal_rejected() {
        assertThatThrownBy(() -> moduleService.createApprovedModule(OPEN_ROOM_ID, host, "../main.py"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> moduleService.createApprovedModule(OPEN_ROOM_ID, host, "sub/main.py"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> moduleService.createApprovedModule(OPEN_ROOM_ID, host, "sub\\main.py"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Pair mode room refuses all module calls")
    void pairModeRoom_refusesModuleCalls() {
        assertThatThrownBy(() -> moduleService.createApprovedModule(PAIR_ROOM_ID, host, "main.py"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PAIR_MODE_NO_MODULES");

        assertThatThrownBy(() -> moduleService.requestCreate(PAIR_ROOM_ID, participant, "main.py"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PAIR_MODE_NO_MODULES");

        assertThatThrownBy(() -> moduleService.listApproved(PAIR_ROOM_ID, host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PAIR_MODE_NO_MODULES");
    }

    @Test
    @DisplayName("Unknown room returns ROOM_NOT_FOUND")
    void unknownRoom_returnsRoomNotFound() {
        assertThatThrownBy(() -> moduleService.createApprovedModule("unknownRoomId0000000000", host, "main.py"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ROOM_NOT_FOUND");
    }

    @Test
    @DisplayName("Null or non-joined session returns SESSION_NOT_FOUND")
    void nullSession_returnsSessionNotFound() {
        assertThatThrownBy(() -> moduleService.createApprovedModule(OPEN_ROOM_ID, null, "main.py"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SESSION_NOT_FOUND");

        assertThatThrownBy(() -> moduleService.listApproved(OPEN_ROOM_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SESSION_NOT_FOUND");
    }

    @Test
    @DisplayName("Edit permission check follows ownership and writer access")
    void canEdit_enforcesOwnership() {
        ModuleRecord module = moduleService.createApprovedModule(OPEN_ROOM_ID, host, "main.py");

        assertThat(moduleService.canEdit(OPEN_ROOM_ID, module.moduleId(), host)).isTrue();
        assertThat(moduleService.canEdit(OPEN_ROOM_ID, module.moduleId(), participant)).isFalse();

        // Grant write to participant
        module.writers().add(participant.getMemberId());
        assertThat(moduleService.canEdit(OPEN_ROOM_ID, module.moduleId(), participant)).isTrue();
    }

    @Test
    @DisplayName("Pending module cannot be edited by anyone")
    void pendingModule_cannotBeEdited() {
        ModuleRecord pending = moduleService.requestCreate(OPEN_ROOM_ID, participant, "solver.py");
        assertThat(moduleService.canEdit(OPEN_ROOM_ID, pending.moduleId(), participant)).isFalse();
        assertThat(moduleService.canEdit(OPEN_ROOM_ID, pending.moduleId(), host)).isFalse();
    }

    @Test
    @DisplayName("Server-generated module IDs are opaque, distinct, and at least 16 URL-safe chars")
    void generateId_uniqueAndSecure() {
        String id1 = moduleService.generateId();
        String id2 = moduleService.generateId();

        assertThat(id1).isNotEqualTo(id2);
        assertThat(id1).startsWith("mod_");
        assertThat(id1.length()).isGreaterThanOrEqualTo(20);
        assertThat(id1).matches("^mod_[A-Za-z0-9_-]{16}$");
    }

    @Test
    @DisplayName("Observer cannot request a module (FR21.1)")
    void observerCannotRequestModule() {
        Member observer = new Member("m_obs", "s_obs", "Observer", Role.OBSERVER, false, false);
        Room room = roomStore.get(OPEN_ROOM_ID);
        room.getMembers().put("m_obs", observer);

        assertThatThrownBy(() -> moduleService.requestCreate(OPEN_ROOM_ID, observer, "obs.py"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Host approval rejects non-existent member as owner with MEMBER_NOT_FOUND (FR21.2)")
    void approve_unknownMember_throwsMemberNotFound() {
        ModuleRecord pending = moduleService.requestCreate(OPEN_ROOM_ID, participant, "unknown-owner.py");
        assertThatThrownBy(() -> moduleService.approve(OPEN_ROOM_ID, pending.moduleId(), host, OWNER_TOKEN, "m_ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MEMBER_NOT_FOUND");
    }

    @Test
    @DisplayName("Host approval rejects observer as owner with OBSERVER_CANNOT_OWN (FR21.2)")
    void approve_observer_throwsObserverCannotOwn() {
        Member observer = new Member("m_obs2", "s_obs2", "Observer2", Role.OBSERVER, false, false);
        Room room = roomStore.get(OPEN_ROOM_ID);
        room.getMembers().put("m_obs2", observer);

        ModuleRecord pending = moduleService.requestCreate(OPEN_ROOM_ID, participant, "obs-owner.py");
        assertThatThrownBy(() -> moduleService.approve(OPEN_ROOM_ID, pending.moduleId(), host, OWNER_TOKEN, "m_obs2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OBSERVER_CANNOT_OWN");
    }

    @Test
    @DisplayName("Host declines pending module, removing it from workspace (US-40)")
    void decline_removesPendingModule() {
        ModuleRecord pending = moduleService.requestCreate(OPEN_ROOM_ID, participant, "declined.py");
        ModuleRecord declined = moduleService.decline(OPEN_ROOM_ID, pending.moduleId(), host, OWNER_TOKEN);

        assertThat(declined.moduleId()).isEqualTo(pending.moduleId());
        assertThat(moduleService.find(OPEN_ROOM_ID, pending.moduleId())).isNull();
    }

    @Test
    @DisplayName("Declining an already approved module throws MODULE_NOT_PENDING")
    void decline_alreadyApproved_throws() {
        ModuleRecord approved = moduleService.createApprovedModule(OPEN_ROOM_ID, host, "approved.py");
        assertThatThrownBy(() -> moduleService.decline(OPEN_ROOM_ID, approved.moduleId(), host, OWNER_TOKEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODULE_NOT_PENDING");
    }

    @Test
    @DisplayName("removePendingRequestsForMember removes pending modules for requester and preserves approved")
    void removePendingRequestsForMember_dropsPendingOnly() {
        ModuleRecord p1 = moduleService.requestCreate(OPEN_ROOM_ID, participant, "pending1.py");
        ModuleRecord p2 = moduleService.createApprovedModule(OPEN_ROOM_ID, participant, "approved1.py");

        List<ModuleRecord> removed = moduleService.removePendingRequestsForMember(OPEN_ROOM_ID, participant.getMemberId());
        assertThat(removed).extracting(ModuleRecord::moduleId).containsExactly(p1.moduleId());
        assertThat(moduleService.find(OPEN_ROOM_ID, p1.moduleId())).isNull();
        assertThat(moduleService.find(OPEN_ROOM_ID, p2.moduleId())).isNotNull();
    }

    @Test
    @DisplayName("listPending returns pending module requests for Host")
    void listPending_returnsPendingModules() {
        moduleService.createApprovedModule(OPEN_ROOM_ID, host, "main.py");
        ModuleRecord p = moduleService.requestCreate(OPEN_ROOM_ID, participant, "pending2.py");

        List<ModuleRecord> pendingList = moduleService.listPending(OPEN_ROOM_ID, host);
        assertThat(pendingList).extracting(ModuleRecord::moduleId).containsExactly(p.moduleId());
    }

    @Test
    @DisplayName("Concurrent approvals preserve exactly one owner (FR21.3)")
    void concurrentApprovals_preserveSingleOwner() throws Exception {
        ModuleRecord pending = moduleService.requestCreate(OPEN_ROOM_ID, participant, "race.py");
        int threads = 8;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final String targetOwner = (i % 2 == 0) ? "m_host" : "m_part";
            executor.submit(() -> {
                try {
                    moduleService.approve(OPEN_ROOM_ID, pending.moduleId(), host, OWNER_TOKEN, targetOwner);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        ModuleRecord result = moduleService.find(OPEN_ROOM_ID, pending.moduleId());
        assertThat(result).isNotNull();
        assertThat(result.pending()).isFalse();
        assertThat(result.ownerMemberId()).isIn("m_host", "m_part");
    }

    @Test
    @DisplayName("Owner can grant and revoke write access (FR22.1)")
    void owner_canGrantAndRevokeWriteAccess() {
        ModuleRecord module = moduleService.createApprovedModule(OPEN_ROOM_ID, host, "owner-control.py");
        assertThat(moduleService.canEdit(OPEN_ROOM_ID, module.moduleId(), participant)).isFalse();

        // Grant write access to participant
        moduleService.grantWriteAccess(OPEN_ROOM_ID, module.moduleId(), host, null, participant.getMemberId());
        assertThat(module.writers()).contains(participant.getMemberId());
        assertThat(moduleService.canEdit(OPEN_ROOM_ID, module.moduleId(), participant)).isTrue();

        // Revoke write access
        moduleService.revokeWriteAccess(OPEN_ROOM_ID, module.moduleId(), host, null, participant.getMemberId());
        assertThat(module.writers()).doesNotContain(participant.getMemberId());
        assertThat(moduleService.canEdit(OPEN_ROOM_ID, module.moduleId(), participant)).isFalse();
    }

    @Test
    @DisplayName("Host with valid token can grant write access to participant and to Host (FR22.1)")
    void host_withOwnerToken_canGrantWriteAccess() {
        // Module owned by participant
        ModuleRecord module = moduleService.createModule(OPEN_ROOM_ID, participant.getMemberId(), "part-owned.py", false);
        assertThat(moduleService.canEdit(OPEN_ROOM_ID, module.moduleId(), host)).isFalse();

        // Host grants write access to Host
        moduleService.grantWriteAccess(OPEN_ROOM_ID, module.moduleId(), host, OWNER_TOKEN, host.getMemberId());
        assertThat(module.writers()).contains(host.getMemberId());
        assertThat(moduleService.canEdit(OPEN_ROOM_ID, module.moduleId(), host)).isTrue();
    }

    @Test
    @DisplayName("Non-owner non-host cannot grant or revoke write access (NOT_MODULE_OWNER)")
    void nonOwnerNonHost_cannotChangeWriteAccess() {
        ModuleRecord module = moduleService.createApprovedModule(OPEN_ROOM_ID, host, "host-mod.py");

        assertThatThrownBy(() -> moduleService.grantWriteAccess(
                OPEN_ROOM_ID, module.moduleId(), participant, null, participant.getMemberId()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("NOT_MODULE_OWNER");

        module.addWriter(participant.getMemberId());

        assertThatThrownBy(() -> moduleService.revokeWriteAccess(
                OPEN_ROOM_ID, module.moduleId(), participant, null, participant.getMemberId()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("NOT_MODULE_OWNER");
    }

    @Test
    @DisplayName("Module owner cannot lose write access")
    void owner_cannotLoseWriteAccess() {
        ModuleRecord module = moduleService.createApprovedModule(OPEN_ROOM_ID, host, "permanent-owner.py");

        assertThatThrownBy(() -> moduleService.revokeWriteAccess(
                OPEN_ROOM_ID, module.moduleId(), host, null, host.getMemberId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OWNER_CANNOT_LOSE_WRITE_ACCESS");

        assertThat(moduleService.canEdit(OPEN_ROOM_ID, module.moduleId(), host)).isTrue();
    }

    @Test
    @DisplayName("Observer cannot edit even if write access recorded (reason observer)")
    void observer_cannotEditEvenIfInWriters() {
        Member observer = new Member("m_obs", "s_obs", "Observer", Role.OBSERVER, false, false);
        Room room = roomStore.get(OPEN_ROOM_ID);
        room.getMembers().put(observer.getMemberId(), observer);

        ModuleRecord module = moduleService.createApprovedModule(OPEN_ROOM_ID, host, "obs-test.py");
        moduleService.grantWriteAccess(OPEN_ROOM_ID, module.moduleId(), host, null, observer.getMemberId());

        assertThat(module.writers()).contains(observer.getMemberId());
        // must be false because member is OBSERVER
        assertThat(moduleService.canEdit(OPEN_ROOM_ID, module.moduleId(), observer)).isFalse();
    }
}
