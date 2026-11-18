package com.collabcode.syncservice.room;

import com.collabcode.syncservice.run.RunRecordStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Join and roster logic (T-17 V2.3, V2.4; FR42).
 *
 * <p>This is the server-side model only; wiring it onto the control socket is
 * done where the control/document sockets are split (the Bhavya part of T-20).
 * A member is identified publicly by its member_id; the private session_id
 * is never exposed.
 */
@Component
public class RoomService {

    private final RoomStore store;
    private final RunRecordStore runRecordStore;

    public RoomService(RoomStore store) {
        this(store, null);
    }

    @Autowired
    public RoomService(RoomStore store, @Autowired(required = false) RunRecordStore runRecordStore) {
        this.store = store;
        this.runRecordStore = runRecordStore;
    }

    /**
     * Apply a join to a room.
     *
     * @throws IllegalArgumentException if the room does not exist (an unknown
     *         room closes the socket with 4404 before this is reached)
     */
    public synchronized JoinOutcome join(String roomId, String sessionId,
                                         String displayName, String ownerToken) {
        Room room = store.get(roomId);
        if (room == null) {
            throw new IllegalArgumentException("unknown room: " + roomId);
        }

        // US-28 (V2.18, CR04): a removed session is blocked — a reconnect, refresh,
        // new tab or a different display name is refused at join with USER_BLOCKED.
        if (room.isBlocked(sessionId)) {
            return JoinOutcome.refused("USER_BLOCKED");
        }

        // Reconnect: the same session rejoining (refresh or a second tab) is
        // accepted, not refused (FR42 identifies a person by session id).
        Member existing = findBySession(room, sessionId);
        if (existing != null) {
            return JoinOutcome.joined(existing);
        }

        // FR9.3 / US-26: if the room requires Host approval, the join is held as
        // APPROVAL_PENDING. The control socket must remain open (V2.3 review 2).
        if (room.isRequiresApproval()) {
            return JoinOutcome.refused("APPROVAL_PENDING");
        }

        // A display name already used by a DIFFERENT session is refused (FR42).
        if (findByName(room, displayName) != null) {
            return JoinOutcome.refused("NAME_TAKEN");
        }

        Role role = isOwner(room, ownerToken) ? Role.HOST : Role.PARTICIPANT;
        boolean canRun = role != Role.OBSERVER;
        boolean canEdit = computeCanEdit(room, role);

        Member member = new Member(MemberIds.generate(), sessionId, displayName, role, canEdit, canRun);
        room.getMembers().put(member.getMemberId(), member);
        return JoinOutcome.joined(member);
    }

    /** A snapshot of the room's current members (empty if the room is unknown). */
    public List<Member> roster(String roomId) {
        Room room = store.get(roomId);
        if (room == null) {
            return List.of();
        }
        return new ArrayList<>(room.getMembers().values());
    }

    /**
     * Resolve a member by its private session id (null if the room or session
     * is unknown). Used by the edit-permission check (Bhavya) and the internal
     * session-lookup endpoint (Jay), so it is public.
     */
    public Member findMemberBySession(String roomId, String sessionId) {
        Room room = store.get(roomId);
        if (room == null) {
            return null;
        }
        return findBySession(room, sessionId);
    }

    /**
     * Change a member's role and recompute its permissions (US-27, V2.17).
     * Returns the updated member, or null if the room or member is unknown.
     */
    public synchronized Member changeRole(String roomId, String memberId, Role newRole) {
        Room room = store.get(roomId);
        if (room == null) {
            return null;
        }
        Member member = room.getMembers().get(memberId);
        if (member == null) {
            return null;
        }
        member.setRole(newRole);
        member.setCanEdit(computeCanEdit(room, newRole));
        member.setCanRun(newRole != Role.OBSERVER);
        return member;
    }

    /**
     * Remove a member (US-28, V2.18). Records the block FIRST — keyed by the
     * member's private session_id, with its member_id, display_name and the
     * removal time — so a racing reconnect (FR11) is already refused, THEN drops
     * the member from the roster. Returns the removed member (whose session_id the
     * caller uses to close its sockets), or null if the room or member is unknown.
     */
    public synchronized Member removeMember(String roomId, String memberId) {
        Room room = store.get(roomId);
        if (room == null) {
            return null;
        }
        Member member = room.getMembers().get(memberId);
        if (member == null) {
            return null;
        }
        room.block(member.getSessionId(), member.getMemberId(), member.getDisplayName(), Instant.now());
        room.getMembers().remove(memberId);
        return member;
    }

    /**
     * Re-admit a member (US-28, V2.18): remove the one block entry with this
     * member_id. Returns false if no block entry matches (404 MEMBER_NOT_FOUND).
     */
    public synchronized boolean readmit(String roomId, String memberId) {
        Room room = store.get(roomId);
        if (room == null) {
            return false;
        }
        return room.unblockByMemberId(memberId);
    }

    /** The blocked members of a room (US-28 list), or empty if the room is unknown. */
    public List<Room.BlockedSession> listBlocked(String roomId) {
        Room room = store.get(roomId);
        if (room == null) {
            return List.of();
        }
        return new ArrayList<>(room.blockedSessions());
    }

    /**
     * Regenerate the room link (US-28, V2.18): move the room's state to a new,
     * unguessable id (NFR16, US-62) — members keep their member_ids (V2.3) — leave
     * the block list behind (a new link is a fresh start) and make the old id stop
     * resolving. Modules are re-keyed by the caller (ModuleService). Returns the
     * new Room, or null if the room is unknown.
     */
    public synchronized Room regenerateLink(String roomId) {
        Room old = store.get(roomId);
        if (old == null) {
            return null;
        }
        Room fresh = new Room(RoomIds.generate(), old.getMode(), old.getPreset(),
                old.getLanguage(), old.getOwnerTokenHash());
        fresh.setRequiresApproval(old.isRequiresApproval());
        fresh.getMembers().putAll(old.getMembers());
        store.put(fresh);
        store.remove(roomId);
        return fresh;
    }

    // ----- helpers -----

    private boolean isOwner(Room room, String ownerToken) {
        return ownerToken != null && OwnerTokens.matches(ownerToken, room.getOwnerTokenHash());
    }

    /**
     * Base edit permission at join. In Pair mode the preset decides the default
     * (FR19.2). In Open mode edit rights are per module so this flag is not sent.
     */
    private boolean computeCanEdit(Room room, Role role) {
        if (role == Role.OBSERVER) {
            return false;
        }
        if (room.getMode() != RoomMode.PAIR) {
            return false;
        }
        if (role == Role.HOST) {
            return true;
        }
        return "debug_together".equals(room.getPreset());
    }

    private Member findBySession(Room room, String sessionId) {
        for (Member m : room.getMembers().values()) {
            if (m.getSessionId().equals(sessionId)) {
                return m;
            }
        }
        return null;
    }

    private Member findByName(Room room, String displayName) {
        for (Member m : room.getMembers().values()) {
            if (m.getDisplayName().equals(displayName)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Mark a room as closed / destroyed (FR31.2, T-17 V2.5).
     * Deletes associated run records from memory (FR32, US-52, US-56).
     * Returns false if the room is unknown.
     */
    public boolean closeRoom(String roomId) {
        Room room = store.get(roomId);
        if (room == null) return false;
        room.close();
        if (runRecordStore != null) {
            runRecordStore.deleteByRoom(roomId);
        }
        return true;
    }

    /**
     * Purges room metadata and its run records (FR32, US-56 room destruction).
     */
    public boolean deleteRoom(String roomId) {
        Room room = store.remove(roomId);
        if (runRecordStore != null) {
            runRecordStore.deleteByRoom(roomId);
        }
        return room != null;
    }
}
