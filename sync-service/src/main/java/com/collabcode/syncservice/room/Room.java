package com.collabcode.syncservice.room;

import java.util.Map;
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
    private final String preset;
    private final String language;
    private final String ownerTokenHash;

    /**
     * The room's AI switch default (FR35.2): off in a Teach/Interview room, on
     * in every other room. The Host can toggle it later (FR35.1).
     */
    private final boolean aiEnabled;

    /** memberId -> Member. Filled by join (V2.3); empty at creation. */
    private final Map<String, Member> members = new ConcurrentHashMap<>();

    /**
     * Flag indicating whether the room has been closed / destroyed (FR31.2, T-17 V2.5).
     * WebSocket connections to closed rooms are rejected with close code 4410.
     */
    private volatile boolean closed = false;

    /**
     * When true, a join attempt returns APPROVAL_PENDING (FR9.3 / US-26 stub for testing).
     */
    private volatile boolean requiresApproval = false;

    public Room(String roomId, RoomMode mode, String preset, String language, String ownerTokenHash) {
        this.roomId = roomId;
        this.mode = mode;
        this.preset = preset;
        this.language = language;
        this.ownerTokenHash = ownerTokenHash;
        this.aiEnabled = !"teach_interview".equals(preset);
    }

    public String getRoomId() { return roomId; }
    public RoomMode getMode() { return mode; }
    public String getPreset() { return preset; }
    public String getLanguage() { return language; }
    public String getOwnerTokenHash() { return ownerTokenHash; }
    public boolean isAiEnabled() { return aiEnabled; }
    public Map<String, Member> getMembers() { return members; }

    public boolean isClosed() { return closed; }
    public void close() { this.closed = true; }

    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean v) { this.requiresApproval = v; }

}
