package com.collabcode.syncservice.room;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
    private volatile String ownerTokenHash;

    /**
     * The room's AI switch default (FR35.2): off in a Teach/Interview room, on
     * in every other room. The Host can toggle it later (FR35.1).
     */
    private volatile boolean aiEnabled;

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

    /**
     * Host-only running (US-60, V2.20, FR41.3). While true, only the Host can run
     * code in this room; every other member is refused with {@code host_only}.
     * Off by default (FR41.1: any Participant can run unless the Host restricts Run).
     */
    private volatile boolean hostOnlyRun = false;

    /**
     * Per-room block list (US-28, V2.18, FR9.6/CR04), keyed by the private
     * {@code session_id}. A removed member is kept here so the Host can list and
     * re-admit them; the {@code session_id} is the key and is never exposed, while
     * {@code member_id}, {@code display_name} and {@code removed_at} are.
     */
    private final Map<String, BlockedSession> blocked = new ConcurrentHashMap<>();

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

    /**
     * Replace the stored owner-token hash (US-54 host transfer, V2.19): the
     * previous token stops matching at once (T-10). The plaintext token is
     * delivered to the new Host over its control socket, never stored here.
     */
    public void setOwnerTokenHash(String ownerTokenHash) { this.ownerTokenHash = ownerTokenHash; }
    public boolean isAiEnabled() { return aiEnabled; }
    public void setAiEnabled(boolean enabled) { this.aiEnabled = enabled; }
    public Map<String, Member> getMembers() { return members; }

    public boolean isClosed() { return closed; }
    public void close() { this.closed = true; }

    /** US-56: retain only the closed-room tombstone, not private member/Host state. */
    public void clearSensitiveState() {
        closed = true;
        ownerTokenHash = null;
        members.clear();
        blocked.clear();
        requiresApproval = false;
        hostOnlyRun = false;
        aiEnabled = false;
    }

    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean v) { this.requiresApproval = v; }

    // ----- Run permission (US-60, V2.20) -----

    public boolean isHostOnlyRun() { return hostOnlyRun; }
    public void setHostOnlyRun(boolean hostOnlyRun) { this.hostOnlyRun = hostOnlyRun; }

    /**
     * Why this member may not run, or {@code null} if they may (FR41.1 to FR41.4).
     * The one place the rule lives: the permission lookup, {@code joined} and
     * {@code roster} all ask here, so they can never disagree.
     *
     * <p>Order, most lasting first: the Host always runs; an Observer never runs
     * ({@code observer}); a participant the Host took Run from ({@code run_revoked},
     * kept in {@link Member#isCanRun()}); everyone but the Host while Host-only
     * running is on ({@code host_only}).
     */
    public String runDeniedReason(Member member) {
        if (member.getRole() == Role.HOST) {
            return null;
        }
        if (member.getRole() == Role.OBSERVER) {
            return "observer";
        }
        if (!member.isCanRun()) {
            return "run_revoked";
        }
        if (hostOnlyRun) {
            return "host_only";
        }
        return null;
    }

    /** True if this member may run right now (see {@link #runDeniedReason}). */
    public boolean canRun(Member member) {
        return runDeniedReason(member) == null;
    }

    /**
     * A copy of this room under a new id (US-28 regenerate-link, V2.18). Carries
     * every carried-over field — members, the closed and approval flags, the owner
     * token hash — so a field added later is not silently dropped on regenerate.
     * The block list is intentionally NOT copied (regenerate clears it, V2.18).
     */
    public Room withNewId(String newId) {
        Room copy = new Room(newId, mode, preset, language, ownerTokenHash);
        copy.closed = this.closed;
        copy.requiresApproval = this.requiresApproval;
        copy.hostOnlyRun = this.hostOnlyRun;
        copy.aiEnabled = this.aiEnabled;
        copy.members.putAll(this.members);
        return copy;
    }

    // ----- Block list (US-28, V2.18) -----

    /** A removed member, kept so the Host can list and re-admit them (V2.18). */
    public record BlockedSession(String sessionId, String memberId, String displayName, Instant removedAt) {}

    /** True if this session is currently blocked (checked at join, V2.3). */
    public boolean isBlocked(String sessionId) {
        return sessionId != null && blocked.containsKey(sessionId);
    }

    /** Record a block for a removed member (V2.18), keyed by the private session_id. */
    public void block(String sessionId, String memberId, String displayName, Instant removedAt) {
        if (sessionId == null) return;
        blocked.put(sessionId, new BlockedSession(sessionId, memberId, displayName, removedAt));
    }

    /** True if any block entry has this member_id (re-admit lookup, V2.18). */
    public boolean isMemberBlocked(String memberId) {
        return memberId != null && blocked.values().stream().anyMatch(b -> b.memberId().equals(memberId));
    }

    /** Remove the block entry whose member_id matches (re-admit, V2.18). True if one was removed. */
    public boolean unblockByMemberId(String memberId) {
        return memberId != null && blocked.values().removeIf(b -> b.memberId().equals(memberId));
    }

    /** A snapshot of the blocked members (V2.18 list). */
    public Collection<BlockedSession> blockedSessions() {
        return new ArrayList<>(blocked.values());
    }

}
