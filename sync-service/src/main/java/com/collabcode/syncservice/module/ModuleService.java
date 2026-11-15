package com.collabcode.syncservice.module;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomStore;
import com.collabcode.syncservice.room.Role;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for Open-mode modules.  It deliberately owns module
 * state rather than storing a second, socket-only collection on {@link Room}.
 */
@Component
public class ModuleService {
    private final RoomStore rooms;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Map<String, ModuleRecord>> byRoom = new ConcurrentHashMap<>();

    public ModuleService(RoomStore rooms) {
        this.rooms = rooms;
    }

    public synchronized ModuleRecord requestCreate(String roomId, Member requester, String name) {
        Room room = requireOpenRoom(roomId);
        if (requester == null) throw new IllegalArgumentException("SESSION_NOT_FOUND");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("INVALID_REQUEST");
        Map<String, ModuleRecord> modules = byRoom.computeIfAbsent(roomId, ignored -> new ConcurrentHashMap<>());
        if (modules.values().stream().anyMatch(module -> module.name().equals(name))) {
            throw new IllegalStateException("NAME_TAKEN");
        }
        ModuleRecord module = new ModuleRecord(generateId(), name, requester.getMemberId());
        modules.put(module.moduleId(), module);
        return module;
    }

    public synchronized ModuleRecord approve(String roomId, String moduleId, Member caller, String ownerToken) {
        Room room = requireOpenRoom(roomId);
        if (caller == null) throw new IllegalArgumentException("SESSION_NOT_FOUND");
        if (caller.getRole() != Role.HOST) throw new SecurityException("NOT_OWNER");
        if (!OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) throw new SecurityException("NOT_OWNER");
        ModuleRecord module = find(roomId, moduleId);
        if (module == null) throw new IllegalStateException("MODULE_NOT_FOUND");
        module.approve();
        return module;
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

    private Room requireOpenRoom(String roomId) {
        Room room = rooms.get(roomId);
        if (room == null || room.getMode() != RoomMode.OPEN) throw new IllegalArgumentException("ROOM_NOT_FOUND");
        return room;
    }

    private ModuleRecord find(String roomId, String moduleId) {
        Map<String, ModuleRecord> modules = byRoom.get(roomId);
        return modules == null ? null : modules.get(moduleId);
    }

    private String generateId() {
        byte[] bytes = new byte[12]; // 96 bits, 16 base64url characters
        random.nextBytes(bytes);
        return "mod_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
