package com.collabcode.syncservice.module;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-42 (FR21.5, FR21.3): removing a member and moving their modules to the current
 * Host is ONE step. These tests prove the step is atomic for every module operation,
 * including a removal that races a Host approval (US-40) or a write-access grant (US-43).
 */
class ModuleServiceMemberRemovalTest {

    private static final String TOKEN = "us42-owner-token";
    private static final int RACE_ROUNDS = 200;

    private RoomStore store;
    private ModuleService modules;
    private RoomService roomService;

    @BeforeEach
    void setUp() {
        store = new RoomStore();
        modules = new ModuleService(store);
        roomService = new RoomService(store);
    }

    // ----------------------------------------------------------------- helpers

    private Room openRoom(String roomId, Member... members) {
        Room room = new Room(roomId, RoomMode.OPEN, null, "python", OwnerTokens.hash(TOKEN));
        for (Member m : members) {
            room.getMembers().put(m.getMemberId(), m);
        }
        store.put(room);
        return room;
    }

    private static Member member(String id, Role role) {
        return new Member(id, "s_" + id, id, role, false, true);
    }

    private ModuleService.MemberRemovalResult remove(String roomId, String memberId) {
        return modules.removeMemberAndReassignModules(
                roomId, memberId, () -> roomService.removeMember(roomId, memberId));
    }

    /** Every approved module has exactly one owner, and that owner is a current member. */
    private List<String> ownershipViolations(String roomId, Member reader) {
        List<String> violations = new ArrayList<>();
        synchronized (modules) {
            Room room = store.get(roomId);
            for (ModuleRecord m : modules.listApproved(roomId, reader)) {
                String owner = m.ownerMemberId();
                if (owner == null || owner.isBlank() || !room.getMembers().containsKey(owner)) {
                    violations.add(m.moduleId() + " owner=" + owner);
                }
            }
        }
        return violations;
    }

    // ------------------------------------------------------------ basic step

    @Test
    void removingOwnerOfSeveralModules_hostOwnsAll_writerAndPendingGone_inOneStep() {
        Member host = member("m_host", Role.HOST);
        Member guest = member("m_guest", Role.PARTICIPANT);
        Member other = member("m_other", Role.PARTICIPANT);
        Room room = openRoom("us42RoomBasic000000000", host, guest, other);

        ModuleRecord a = modules.createModule(room.getRoomId(), guest.getMemberId(), "a.py", false);
        ModuleRecord b = modules.createModule(room.getRoomId(), guest.getMemberId(), "b.py", false);
        a.addWriter(other.getMemberId());
        ModuleRecord hostOwned = modules.createModule(room.getRoomId(), host.getMemberId(), "h.py", false);
        hostOwned.addWriter(guest.getMemberId());
        ModuleRecord pending = modules.createModule(room.getRoomId(), guest.getMemberId(), "p.py", true);

        ModuleService.MemberRemovalResult r = remove(room.getRoomId(), guest.getMemberId());

        assertThat(r.removed()).isSameAs(guest);
        assertThat(room.getMembers()).doesNotContainKey(guest.getMemberId());
        assertThat(room.isBlocked(guest.getSessionId())).isTrue();                 // US-28 block kept
        assertThat(a.ownerMemberId()).isEqualTo(host.getMemberId());
        assertThat(b.ownerMemberId()).isEqualTo(host.getMemberId());
        assertThat(a.writers()).containsExactly(other.getMemberId());             // other writers kept
        assertThat(hostOwned.writers()).doesNotContain(guest.getMemberId());       // write access gone
        assertThat(r.transferred()).extracting(ModuleRecord::moduleId)
                .containsExactlyInAnyOrder(a.moduleId(), b.moduleId(), hostOwned.moduleId());
        assertThat(r.removedPending()).extracting(ModuleRecord::moduleId).containsExactly(pending.moduleId());
        assertThat(modules.find(room.getRoomId(), pending.moduleId())).isNull();
        assertThat(ownershipViolations(room.getRoomId(), host)).isEmpty();
    }

    @Test
    void rosterRemovalRunsInsideTheModuleLock_noModuleReadCanSeeTheGap() throws Exception {
        Member host = member("m_host", Role.HOST);
        Member guest = member("m_guest", Role.PARTICIPANT);
        Room room = openRoom("us42RoomLock0000000000", host, guest);
        modules.createModule(room.getRoomId(), guest.getMemberId(), "g.py", false);

        AtomicReference<List<String>> ownersSeen = new AtomicReference<>();
        AtomicBoolean readerRanDuringRemoval = new AtomicBoolean(true);
        Thread[] reader = new Thread[1];

        remove0(room, guest, reader, ownersSeen, readerRanDuringRemoval);
        reader[0].join(5000);

        // The concurrent reader was blocked for the whole step, then saw only the result.
        assertThat(readerRanDuringRemoval.get()).isFalse();
        assertThat(ownersSeen.get()).containsExactly(host.getMemberId());
    }

    private void remove0(Room room, Member guest, Thread[] reader,
                         AtomicReference<List<String>> ownersSeen, AtomicBoolean readerRanDuringRemoval) {
        Member host = room.getMembers().get("m_host");
        modules.removeMemberAndReassignModules(room.getRoomId(), guest.getMemberId(), () -> {
            // A module read issued in the middle of the step must wait for the whole step.
            reader[0] = new Thread(() -> ownersSeen.set(modules.listApproved(room.getRoomId(), host).stream()
                    .map(ModuleRecord::ownerMemberId).toList()));
            reader[0].start();
            try {
                reader[0].join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerRanDuringRemoval.set(!reader[0].isAlive());
            return roomService.removeMember(room.getRoomId(), guest.getMemberId());
        });
    }

    @Test
    void missingHost_failsBeforeAnythingChanges() {
        Member guest = member("m_guest", Role.PARTICIPANT);
        Member other = member("m_other", Role.PARTICIPANT);
        Room room = openRoom("us42RoomNoHost00000000", guest, other);
        ModuleRecord owned = modules.createModule(room.getRoomId(), guest.getMemberId(), "g.py", false);

        assertThatThrownBy(() -> remove(room.getRoomId(), guest.getMemberId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("HOST_NOT_FOUND");

        // No half-done step: still a member, not blocked, still the owner.
        assertThat(room.getMembers()).containsKey(guest.getMemberId());
        assertThat(room.isBlocked(guest.getSessionId())).isFalse();
        assertThat(owned.ownerMemberId()).isEqualTo(guest.getMemberId());
    }

    @Test
    void unknownMember_changesNothing() {
        Member host = member("m_host", Role.HOST);
        Room room = openRoom("us42RoomUnknown0000000", host);
        ModuleRecord owned = modules.createModule(room.getRoomId(), host.getMemberId(), "h.py", false);

        ModuleService.MemberRemovalResult r = remove(room.getRoomId(), "m_nope");

        assertThat(r.removed()).isNull();
        assertThat(r.transferred()).isEmpty();
        assertThat(r.removedPending()).isEmpty();
        assertThat(owned.ownerMemberId()).isEqualTo(host.getMemberId());
    }

    @Test
    void pairMode_removesTheMember_andDoesNoModuleWork() {
        Member host = member("m_host", Role.HOST);
        Member guest = member("m_guest", Role.PARTICIPANT);
        Room pair = new Room("us42RoomPair0000000000", RoomMode.PAIR, "teach_interview", null, OwnerTokens.hash(TOKEN));
        pair.getMembers().put(host.getMemberId(), host);
        pair.getMembers().put(guest.getMemberId(), guest);
        store.put(pair);

        ModuleService.MemberRemovalResult r = remove(pair.getRoomId(), guest.getMemberId());

        assertThat(r.removed()).isSameAs(guest);
        assertThat(r.transferred()).isEmpty();
        assertThat(r.removedPending()).isEmpty();
        assertThat(pair.getMembers()).doesNotContainKey(guest.getMemberId());
    }

    // -------------------------------------------------------- concurrent cases

    /** Runs the removal and the other operation together, with an observer checking the invariant throughout. */
    private List<String> race(Room room, Member host, String removeId, Runnable otherOperation) throws Exception {
        List<String> violations = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);

        Thread observer = new Thread(() -> {
            while (running.get()) {
                violations.addAll(ownershipViolations(room.getRoomId(), host));
            }
        });
        Thread remover = new Thread(() -> {
            awaitQuietly(start);
            remove(room.getRoomId(), removeId);
        });
        Thread other = new Thread(() -> {
            awaitQuietly(start);
            try {
                otherOperation.run();
            } catch (IllegalArgumentException | IllegalStateException expected) {
                // The removal won the race: the target/module is already gone. That is fine.
            }
        });
        observer.start();
        remover.start();
        other.start();
        start.countDown();
        remover.join(5000);
        other.join(5000);
        running.set(false);
        observer.join(5000);
        violations.addAll(ownershipViolations(room.getRoomId(), host));
        return violations;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void removalRacingHostApprovalOfTheRemovedMembersRequest_neverLeavesThemOwner() throws Exception {
        for (int i = 0; i < RACE_ROUNDS; i++) {
            Member host = member("m_host", Role.HOST);
            Member guest = member("m_guest", Role.PARTICIPANT);
            Room room = openRoom(String.format("us42ApproveA%010d", i), host, guest);
            ModuleRecord request = modules.createModule(room.getRoomId(), guest.getMemberId(), "req.py", true);

            List<String> violations = race(room, host, guest.getMemberId(),
                    () -> modules.approve(room.getRoomId(), request.moduleId(), host, TOKEN));

            assertThat(violations).as("round " + i).isEmpty();
            ModuleRecord after = modules.find(room.getRoomId(), request.moduleId());
            if (after != null) { // approval won: the removal then handed it to the Host
                assertThat(after.pending()).isFalse();
                assertThat(after.ownerMemberId()).as("round " + i).isEqualTo(host.getMemberId());
            }
        }
    }

    @Test
    void removalRacingApprovalThatAssignsTheRemovedMemberAsOwner_neverLeavesThemOwner() throws Exception {
        for (int i = 0; i < RACE_ROUNDS; i++) {
            Member host = member("m_host", Role.HOST);
            Member guest = member("m_guest", Role.PARTICIPANT);
            Member other = member("m_other", Role.PARTICIPANT);
            Room room = openRoom(String.format("us42ApproveB%010d", i), host, guest, other);
            ModuleRecord request = modules.createModule(room.getRoomId(), other.getMemberId(), "req.py", true);

            List<String> violations = race(room, host, guest.getMemberId(),
                    () -> modules.approve(room.getRoomId(), request.moduleId(), host, TOKEN, guest.getMemberId()));

            assertThat(violations).as("round " + i).isEmpty();
            assertThat(request.ownerMemberId()).as("round " + i).isNotEqualTo(guest.getMemberId());
        }
    }

    @Test
    void removalRacingWriteAccessGrant_neverLeavesAStaleWriter() throws Exception {
        for (int i = 0; i < RACE_ROUNDS; i++) {
            Member host = member("m_host", Role.HOST);
            Member guest = member("m_guest", Role.PARTICIPANT);
            Room room = openRoom(String.format("us42GrantCc%010d", i), host, guest);
            ModuleRecord hostOwned = modules.createModule(room.getRoomId(), host.getMemberId(), "h.py", false);

            List<String> violations = race(room, host, guest.getMemberId(),
                    () -> modules.grantWriteAccess(room.getRoomId(), hostOwned.moduleId(), host, TOKEN,
                            guest.getMemberId()));

            assertThat(violations).as("round " + i).isEmpty();
            assertThat(hostOwned.writers()).as("round " + i).doesNotContain(guest.getMemberId());
        }
    }
}
