package com.collabcode.syncservice.module;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for Open-mode modules (FR20.1, DR4, US-39).
 *
 * <p>Owns the workspace module state and lifecycle: creation with strict language/extension
 * validation, opaque server-generated {@code module_id}s, Host approval, and active document state.
 */
@Component
public class ModuleService {
    // Provisional NFR15 default; Spring overrides from application.properties.
    @Value("${app.room.limits.max-modules-per-room:10}")
    private int maxModulesPerRoom = 10;

    private final RoomStore rooms;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Map<String, ModuleRecord>> byRoom = new ConcurrentHashMap<>();

    public ModuleService(RoomStore rooms) {
        this.rooms = rooms;
    }

    /**
     * Centralized module creation function (US-39, FR20.1, FR21.3, DR4).
     *
     * <p>Both Host direct creation and participant request-and-approve flows (US-40),
     * as well as future module limits (US-63), build through this single entrypoint.
     *
     * @param roomId room identifier
     * @param ownerMemberId owner of the module (every module has exactly one owner from inception)
     * @param name filename with valid extension for room's language
     * @param pending true if awaiting Host approval, false if approved/created by Host
     * @return the created {@link ModuleRecord}
     */
    public synchronized ModuleRecord createModule(String roomId, String ownerMemberId, String name, boolean pending) {
        Room room = requireOpenRoom(roomId);
        if (ownerMemberId == null || ownerMemberId.isBlank()) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }

        Member owner = room.getMembers().get(ownerMemberId);
        if (owner != null && owner.getRole() == Role.OBSERVER) {
            throw new IllegalArgumentException("OBSERVERS_CANNOT_REQUEST_MODULES");
        }

        // Validate language-appropriate extension and plain file name rules (DR4)
        ModuleFileNameValidator.validate(name, room.getLanguage());

        Map<String, ModuleRecord> modules = byRoom.computeIfAbsent(roomId, ignored -> new ConcurrentHashMap<>());
        if (modules.values().stream().anyMatch(module -> module.name().equals(name))) {
            throw new IllegalStateException("NAME_TAKEN");
        }

        // Pending requests are not approved modules. They may remain pending
        // when the room is full; capacity is checked again at Host approval.
        if (!pending && maxModulesPerRoom > 0
                && modules.values().stream().filter(m -> !m.pending()).count() >= maxModulesPerRoom) {
            throw new IllegalStateException("LIMIT_REACHED");
        }

        String moduleId = generateId();
        ModuleRecord module = new ModuleRecord(moduleId, name, ownerMemberId, pending);
        modules.put(moduleId, module);
        return module;
    }

    /**
     * Request creation of a module by a participant (US-40 / V2.6).
     * Module is created with {@code pending = true} awaiting Host approval.
     */
    public synchronized ModuleRecord requestCreate(String roomId, Member requester, String name) {
        if (requester == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        return createModule(roomId, requester.getMemberId(), name, true);
    }

    /**
     * Direct creation of an approved module by the Host (US-39 / V2.6).
     * Module is created with {@code pending = false} and immediately active.
     */
    public synchronized ModuleRecord createApprovedModule(String roomId, Member host, String name) {
        if (host == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        return createModule(roomId, host.getMemberId(), name, false);
    }

    /**
     * Host approves a pending module (FR21.2, V2.6).
     */
    public synchronized ModuleRecord approve(String roomId, String moduleId, Member caller, String ownerToken) {
        return approve(roomId, moduleId, caller, ownerToken, null);
    }

    /**
     * Host approves a pending module with optional new owner reassignment (FR21.2).
     */
    public synchronized ModuleRecord approve(
            String roomId,
            String moduleId,
            Member caller,
            String ownerToken,
            String optionalNewOwnerId) {

        Room room = requireOpenRoom(roomId);
        if (caller == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        if (caller.getRole() != Role.HOST) {
            throw new SecurityException("NOT_OWNER");
        }
        if (!OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
            throw new SecurityException("NOT_OWNER");
        }
        ModuleRecord module = find(roomId, moduleId);
        if (module == null) {
            throw new IllegalStateException("MODULE_NOT_FOUND");
        }

        String targetOwnerId = (optionalNewOwnerId != null && !optionalNewOwnerId.isBlank())
                ? optionalNewOwnerId
                : module.ownerMemberId();

        Member target = room.getMembers().get(targetOwnerId);
        if (target == null && !room.getMembers().isEmpty()) {
            throw new IllegalArgumentException("MEMBER_NOT_FOUND");
        }
        if (target != null && target.getRole() == Role.OBSERVER) {
            throw new IllegalArgumentException("OBSERVER_CANNOT_OWN");
        }

        // Refuse approval when all active slots are occupied, without changing
        // the pending request or creating an approved module.
        if (module.pending() && maxModulesPerRoom > 0) {
            Map<String, ModuleRecord> modules = byRoom.get(roomId);
            long approved = modules == null ? 0
                    : modules.values().stream().filter(m -> !m.pending()).count();
            if (approved >= maxModulesPerRoom) {
                throw new IllegalStateException("LIMIT_REACHED");
            }
        }

        module.approve(targetOwnerId);
        return module;
    }

    /**
     * Host declines a pending module request (US-40, FR21.2).
     */
    public synchronized ModuleRecord decline(String roomId, String moduleId, Member caller, String ownerToken) {
        Room room = requireOpenRoom(roomId);
        if (caller == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        if (caller.getRole() != Role.HOST) {
            throw new SecurityException("NOT_OWNER");
        }
        if (!OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
            throw new SecurityException("NOT_OWNER");
        }
        ModuleRecord module = find(roomId, moduleId);
        if (module == null) {
            throw new IllegalStateException("MODULE_NOT_FOUND");
        }
        if (!module.pending()) {
            throw new IllegalArgumentException("MODULE_NOT_PENDING");
        }
        Map<String, ModuleRecord> modules = byRoom.get(roomId);
        if (modules != null) {
            modules.remove(moduleId);
        }
        return module;
    }

    /**
     * Removes all pending module requests authored by the specified member (US-40).
     * Used when a requester leaves or is removed from the room.
     */
    public synchronized List<ModuleRecord> removePendingRequestsForMember(String roomId, String memberId) {
        Map<String, ModuleRecord> modules = byRoom.get(roomId);
        if (modules == null || memberId == null) {
            return List.of();
        }
        List<ModuleRecord> removed = new java.util.ArrayList<>();
        modules.entrySet().removeIf(entry -> {
            ModuleRecord m = entry.getValue();
            if (m.pending() && memberId.equals(m.ownerMemberId())) {
                removed.add(m);
                return true;
            }
            return false;
        });
        return removed;
    }

    /**
     * US-42: Transfer approved modules owned by a Host-removed member to the
     * current Host, and remove the former member from every approved module's
     * writer list. Returns changed modules for module-updated broadcasts.
     */
    public synchronized List<ModuleRecord> transferOwnedModules(
            String roomId, String removedMemberId, String hostMemberId) {
        Room room = requireOpenRoom(roomId);
        if (removedMemberId == null || removedMemberId.isBlank()
                || hostMemberId == null || hostMemberId.isBlank()
                || removedMemberId.equals(hostMemberId)) {
            throw new IllegalArgumentException("INVALID_MEMBER_ID");
        }
        Member host = room.getMembers().get(hostMemberId);
        if (host == null || host.getRole() != Role.HOST) {
            throw new IllegalArgumentException("HOST_NOT_FOUND");
        }
        Map<String, ModuleRecord> modules = byRoom.get(roomId);
        if (modules == null) {
            return List.of();
        }
        List<ModuleRecord> updated = new java.util.ArrayList<>();
        for (ModuleRecord module : modules.values()) {
            if (module.pending()) {
                continue;
            }
            boolean changed = false;
            if (removedMemberId.equals(module.ownerMemberId())) {
                module.transferOwnership(hostMemberId);
                changed = true;
            }
            if (module.removeWriter(removedMemberId)) {
                changed = true;
            }
            if (changed) {
                updated.add(module);
            }
        }
        updated.sort(Comparator.comparing(ModuleRecord::moduleId));
        return updated;
    }

    /** Outcome of the single US-42 removal step. {@code removed} is null if the member was unknown. */
    public record MemberRemovalResult(Member removed,
                                      List<ModuleRecord> transferred,
                                      List<ModuleRecord> removedPending) {}

    /**
     * US-42 (FR21.5, FR21.3): remove a member and settle everything they hold in
     * the module workspace in ONE step.
     *
     * <p>The roster removal ({@code removeFromRoom}, the US-28
     * {@code RoomService.removeMember} path) runs while this service's lock is held,
     * followed in the same critical section by the ownership transfer to the
     * current Host, the writer revocation and the pending-request cleanup. Every
     * module operation (approve, grant/revoke, list, ...) is synchronized on this
     * service, so none of them can observe the member gone from the room while
     * still owning or writing a module, or the reverse.
     *
     * <p>Lock order is always ModuleService -> RoomService; RoomService never
     * calls into ModuleService, so this cannot deadlock.
     *
     * <p>The current Host is resolved before anything changes, so a missing Host
     * fails the whole step without a half-done removal.
     */
    public synchronized MemberRemovalResult removeMemberAndReassignModules(
            String roomId, String memberId, java.util.function.Supplier<Member> removeFromRoom) {
        Room room = rooms.get(roomId);
        boolean hasModules = room != null && !room.isClosed() && room.getMode() == RoomMode.OPEN;
        String hostMemberId = null;
        if (hasModules) {
            hostMemberId = room.getMembers().values().stream()
                    .filter(m -> m.getRole() == Role.HOST && !m.getMemberId().equals(memberId))
                    .map(Member::getMemberId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("HOST_NOT_FOUND"));
        }

        Member removed = removeFromRoom.get();
        if (removed == null) {
            return new MemberRemovalResult(null, List.of(), List.of());
        }

        // Pair mode has no modules: nothing to transfer (FR21.5 applies to Open mode).
        List<ModuleRecord> transferred = hasModules
                ? transferOwnedModules(roomId, memberId, hostMemberId)
                : List.of();
        List<ModuleRecord> removedPending = removePendingRequestsForMember(roomId, memberId);
        return new MemberRemovalResult(removed, transferred, removedPending);
    }

    /** Returns pending module requests awaiting Host approval (US-40). */
    public synchronized List<ModuleRecord> listPending(String roomId, Member requester) {
        requireOpenRoom(roomId);
        if (requester == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        Map<String, ModuleRecord> modules = byRoom.get(roomId);
        if (modules == null) {
            return List.of();
        }
        return modules.values().stream()
                .filter(ModuleRecord::pending)
                .sorted(Comparator.comparing(ModuleRecord::moduleId))
                .toList();
    }

    /** Returns a stable snapshot of approved modules for a joined room member. */
    public synchronized List<ModuleRecord> listApproved(String roomId, Member requester) {
        requireOpenRoom(roomId);
        if (requester == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        Map<String, ModuleRecord> modules = byRoom.get(roomId);
        if (modules == null) {
            return List.of();
        }
        return modules.values().stream()
                .filter(module -> !module.pending())
                .sorted(Comparator.comparing(ModuleRecord::moduleId))
                .toList();
    }

    public record WriteAccessResult(ModuleRecord module, boolean changed) {}

    /**
     * Grant write access to a member for a module (US-43, FR22.1).
     *
     * <p>Only the module owner or the Host may grant write access.
     * When called by the Host, a valid {@code X-Owner-Token} is required.
     */
    public synchronized WriteAccessResult grantWriteAccessResult(
            String roomId,
            String moduleId,
            Member caller,
            String ownerToken,
            String targetMemberId) {

        Room room = requireOpenRoom(roomId);
        if (caller == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        if (targetMemberId == null || targetMemberId.isBlank()) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }

        ModuleRecord module = find(roomId, moduleId);
        if (module == null) {
            throw new IllegalStateException("MODULE_NOT_FOUND");
        }

        boolean isOwner = caller.getMemberId().equals(module.ownerMemberId());
        boolean isHost = caller.getRole() == Role.HOST;

        if (!isOwner && !isHost) {
            throw new SecurityException("NOT_MODULE_OWNER");
        }
        if (!isOwner && isHost) {
            if (!OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
                throw new SecurityException("NOT_OWNER");
            }
        }

        Member target = room.getMembers().get(targetMemberId);
        if (target == null) {
            throw new IllegalArgumentException("MEMBER_NOT_FOUND");
        }

        boolean changed = module.addWriter(targetMemberId);
        return new WriteAccessResult(module, changed);
    }

    public synchronized ModuleRecord grantWriteAccess(
            String roomId,
            String moduleId,
            Member caller,
            String ownerToken,
            String targetMemberId) {
        return grantWriteAccessResult(roomId, moduleId, caller, ownerToken, targetMemberId).module();
    }

    /**
     * Revoke write access from a member for a module (US-43, FR22.1).
     *
     * <p>Only the module owner or the Host may revoke write access.
     * The module owner always keeps write access (cannot be revoked).
     */
    public synchronized WriteAccessResult revokeWriteAccessResult(
            String roomId,
            String moduleId,
            Member caller,
            String ownerToken,
            String targetMemberId) {

        Room room = requireOpenRoom(roomId);
        if (caller == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        if (targetMemberId == null || targetMemberId.isBlank()) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }

        ModuleRecord module = find(roomId, moduleId);
        if (module == null) {
            throw new IllegalStateException("MODULE_NOT_FOUND");
        }

        boolean isOwner = caller.getMemberId().equals(module.ownerMemberId());
        boolean isHost = caller.getRole() == Role.HOST;

        if (!isOwner && !isHost) {
            throw new SecurityException("NOT_MODULE_OWNER");
        }
        if (!isOwner && isHost) {
            if (!OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
                throw new SecurityException("NOT_OWNER");
            }
        }

        if (targetMemberId.equals(module.ownerMemberId())) {
            throw new IllegalArgumentException("OWNER_CANNOT_LOSE_WRITE_ACCESS");
        }

        Member target = room.getMembers().get(targetMemberId);
        if (target == null) {
            throw new IllegalArgumentException("MEMBER_NOT_FOUND");
        }

        boolean changed = module.removeWriter(targetMemberId);
        return new WriteAccessResult(module, changed);
    }

    public synchronized ModuleRecord revokeWriteAccess(
            String roomId,
            String moduleId,
            Member caller,
            String ownerToken,
            String targetMemberId) {
        return revokeWriteAccessResult(roomId, moduleId, caller, ownerToken, targetMemberId).module();
    }

    /** A socket may address only an approved module. */
    public boolean isActive(String roomId, String moduleId) {
        ModuleRecord module = find(roomId, moduleId);
        return module != null && !module.pending();
    }

    public boolean canEdit(String roomId, String moduleId, Member member) {
        if (member == null || member.getRole() == Role.OBSERVER) {
            return false;
        }
        ModuleRecord module = find(roomId, moduleId);
        return module != null && module.canEdit(member.getMemberId());
    }

    /**
     * Re-key a room's modules when its link is regenerated (US-28, V2.18): the
     * room id changes but module ownership and writers are preserved.
     */
    public synchronized void moveRoom(String oldRoomId, String newRoomId) {
        Map<String, ModuleRecord> modules = byRoom.remove(oldRoomId);
        if (modules != null) {
            byRoom.merge(newRoomId, modules, (existing, incoming) -> {
                existing.putAll(incoming);
                return existing;
            });
        }
    }

    /** US-56: drop all module metadata, saved versions and pending requests for a room. */
    public synchronized void deleteByRoom(String roomId) {
        byRoom.remove(roomId);
    }

    public ModuleRecord find(String roomId, String moduleId) {
        Map<String, ModuleRecord> modules = byRoom.get(roomId);
        return modules == null ? null : modules.get(moduleId);
    }

    /**
     * Manually saves the module's current text as a new immutable version (US-45, FR24.1).
     * Kept in one function so US-63 size limits can plug in cleanly.
     */
    public synchronized ModuleRecord saveModule(String roomId, String moduleId, Member caller, String text) {
        requireOpenRoom(roomId);
        if (caller == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        ModuleRecord module = find(roomId, moduleId);
        if (module == null) {
            throw new IllegalStateException("MODULE_NOT_FOUND");
        }
        if (module.pending()) {
            throw new IllegalStateException("MODULE_PENDING");
        }
        if (caller.getRole() == Role.OBSERVER || !module.canEdit(caller.getMemberId())) {
            throw new SecurityException("NOT_MODULE_WRITER");
        }
        if (text == null) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }
        module.save(caller, text, Instant.now());
        return module;
    }

    /** Marks an approved module dirty after an accepted edit frame (US-45, FR24.2). */
    public boolean markDirty(String roomId, String moduleId) {
        ModuleRecord module = find(roomId, moduleId);
        return module != null && !module.pending() && module.markDirty();
    }

    /** Result of updating entry file designation. */
    public record EntryChangeResult(ModuleRecord updated, ModuleRecord replaced) {}

    /**
     * Mark or unmark a module as runnable (FR20.2, V2.6).
     * Callable only by the module owner.
     */
    public synchronized ModuleRecord setRunnable(String roomId, String moduleId, Member caller, boolean runnable) {
        requireOpenRoom(roomId);
        if (caller == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        ModuleRecord module = find(roomId, moduleId);
        if (module == null) {
            throw new IllegalStateException("MODULE_NOT_FOUND");
        }
        if (module.pending()) {
            throw new IllegalStateException("MODULE_PENDING");
        }
        if (!caller.getMemberId().equals(module.ownerMemberId())) {
            throw new SecurityException("NOT_MODULE_OWNER");
        }
        module.setRunnable(runnable);
        return module;
    }

    /**
     * Designate or un-designate a module as the workspace entry file (FR20.3, V2.6).
     * Callable only by the room Host with a valid owner token.
     * Setting a new entry file replaces any previously designated entry file.
     */
    public synchronized EntryChangeResult setEntry(String roomId, String moduleId, Member caller, String ownerToken, boolean isEntry) {
        Room room = requireOpenRoom(roomId);
        if (caller == null) {
            throw new IllegalArgumentException("SESSION_NOT_FOUND");
        }
        if (caller.getRole() != Role.HOST) {
            throw new SecurityException("NOT_OWNER");
        }
        if (!OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
            throw new SecurityException("NOT_OWNER");
        }
        ModuleRecord module = find(roomId, moduleId);
        if (module == null) {
            throw new IllegalStateException("MODULE_NOT_FOUND");
        }
        if (module.pending()) {
            throw new IllegalStateException("MODULE_PENDING");
        }

        ModuleRecord replaced = null;
        if (isEntry) {
            Map<String, ModuleRecord> modules = byRoom.get(roomId);
            if (modules != null) {
                for (ModuleRecord other : modules.values()) {
                    if (other.isEntry() && !other.moduleId().equals(moduleId)) {
                        other.setEntry(false);
                        replaced = other;
                    }
                }
            }
        }
        module.setEntry(isEntry);
        return new EntryChangeResult(module, replaced);
    }

    private Room requireOpenRoom(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null || room.isClosed()) {
            throw new IllegalArgumentException("ROOM_NOT_FOUND");
        }
        if (room.getMode() != RoomMode.OPEN) {
            throw new IllegalArgumentException("PAIR_MODE_NO_MODULES");
        }
        return room;
    }

    /**
     * Generates a server-authoritative opaque module ID of at least 16 URL-safe characters (decision 7).
     * Format: {@code "mod_" + 16 base64url characters} = 20 URL-safe characters total.
     */
    public String generateId() {
        byte[] bytes = new byte[12]; // 96 bits = 16 base64url characters
        random.nextBytes(bytes);
        return "mod_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Returns all approved (non-pending) modules for an open room. */
    public synchronized List<ModuleRecord> getApprovedModules(String roomId) {
        requireOpenRoom(roomId);
        Map<String, ModuleRecord> modules = byRoom.get(roomId);
        if (modules == null) {
            return List.of();
        }
        return modules.values().stream()
                .filter(m -> !m.pending())
                .sorted(Comparator.comparing(ModuleRecord::moduleId))
                .toList();
    }

    /** Returns the entry module for an open room, or null if none is designated. */
    public synchronized ModuleRecord findEntryModule(String roomId) {
        requireOpenRoom(roomId);
        Map<String, ModuleRecord> modules = byRoom.get(roomId);
        if (modules == null) {
            return null;
        }
        return modules.values().stream()
                .filter(m -> !m.pending() && m.isEntry())
                .findFirst()
                .orElse(null);
    }
}
