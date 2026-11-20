package com.collabcode.syncservice.module;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomStore;
import org.springframework.stereotype.Component;

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

        // Validate language-appropriate extension and plain file name rules (DR4)
        ModuleFileNameValidator.validate(name, room.getLanguage());

        Map<String, ModuleRecord> modules = byRoom.computeIfAbsent(roomId, ignored -> new ConcurrentHashMap<>());
        if (modules.values().stream().anyMatch(module -> module.name().equals(name))) {
            throw new IllegalStateException("NAME_TAKEN");
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
        module.approve(optionalNewOwnerId);
        return module;
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

    /** A socket may address only an approved module. */
    public boolean isActive(String roomId, String moduleId) {
        ModuleRecord module = find(roomId, moduleId);
        return module != null && !module.pending();
    }

    public boolean canEdit(String roomId, String moduleId, Member member) {
        ModuleRecord module = find(roomId, moduleId);
        return member != null && module != null && module.canEdit(member.getMemberId());
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

    public ModuleRecord find(String roomId, String moduleId) {
        Map<String, ModuleRecord> modules = byRoom.get(roomId);
        return modules == null ? null : modules.get(moduleId);
    }

    private Room requireOpenRoom(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null) {
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
}
