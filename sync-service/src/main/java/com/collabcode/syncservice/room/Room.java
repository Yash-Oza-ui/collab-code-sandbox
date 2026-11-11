package com.collabcode.syncservice.room;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory room record (T-17 V2.1).
 *
 * <p>Per the T-05 decision (platform-home.md), room state lives in
 * sync-service. For now it is held in memory behind {@link RoomStore}; a
 * durable store is a later decision (platform-home section 5).
 *
 * <p>Only the SHA-256 hash of the owner token is stored; the plaintext token
 * is returned once at creation and never kept (T-10).
 */
public class Room {

    private final String roomId;
    private final RoomMode mode;
    private final String preset;     // Pair only; null in Open
    private final String language;   // Open only; null in Pair
    private final String ownerTokenHash;

    /**
     * The room's AI switch default (FR35.2): off in a Teach/Interview room, on
     * in every other room. The Host can toggle it later (FR35.1); the session
     * lookup (V2.11) reads this value.
     */
    private final boolean aiEnabled;

    /** memberId -&gt; Member. Filled by join (V2.3); empty at creation. */
    private final Map<String, Member> members = new ConcurrentHashMap<>();

    public Room(String roomId, RoomMode mode, String preset, String language, String ownerTokenHash) {
        this.roomId = roomId;
        this.mode = mode;
        this.preset = preset;
        this.language = language;
        this.ownerTokenHash = ownerTokenHash;
        this.aiEnabled = !"teach_interview".equals(preset); // FR35.2
    }

    /**
     * Open-mode module IDs registered in this room (V2.6).
     * In Pair mode this set is always empty; the only valid doc_id is "main".
     * In Open mode, {@link #registerModule(String)} adds an id here and
     * {@link #hasModule(String)} lets the document socket validate the doc_id (4405).
     */
    private final Set<String> moduleIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public String getRoomId() { return roomId; }
    public RoomMode getMode() { return mode; }
    public String getPreset() { return preset; }
    public String getLanguage() { return language; }
    public String getOwnerTokenHash() { return ownerTokenHash; }
    public boolean isAiEnabled() { return aiEnabled; }
    public Map<String, Member> getMembers() { return members; }

    /**
     * Register an Open-mode module id so the document socket can validate it (4405).
     * No-op in Pair mode (the caller should not call this in Pair mode).()
     */
    public void registerModule(String moduleId) {
        moduleIds.add(moduleId);
    }

    /**
     * Returns {@code true} if {@code moduleId} has been registered in this Open-mode room.
     * Always returns {@code false} in Pair mode (Pair has no modules).
     */
    public boolean hasModule(String moduleId) {
        return moduleIds.contains(moduleId);
    }

    /** Unmodifiable view of module ids (for testing / REST listing). */
    public Set<String> getModuleIds() {
        return Collections.unmodifiableSet(moduleIds);
    }
}
