package com.collabcode.syncservice.room;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Join and roster logic (T-17 V2.3, V2.4; FR42).
 *
 * <p>This is the server-side model only; wiring it onto the control socket is
 * done where the control/document sockets are split (the Bhavya part of T-20).
 * A member is identified publicly by its {@code member_id}; the private
 * {@code session_id} is never exposed.
 */
@Component
public class RoomService {

    private final RoomStore store;

    public RoomService(RoomStore store) {
        this.store = store;
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

        // Reconnect: the same session rejoining (refresh or a second tab) is
        // accepted, not refused (FR42 identifies a person by session id).
        Member existing = findBySession(room, sessionId);
        if (existing != null) {
            return JoinOutcome.joined(existing);
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
     * An Observer gets {@code can_edit:false, can_run:false}; a participant gets
     * the preset's default edit access (FR19.2). Returns the updated member, or
     * {@code null} if the room or member is unknown.
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

    // ----- helpers -----

    private boolean isOwner(Room room, String ownerToken) {
        return ownerToken != null && OwnerTokens.matches(ownerToken, room.getOwnerTokenHash());
    }

    /**
     * Base edit permission at join. In Pair mode the preset decides the default
     * (FR19.2): Teach/Interview lets only the Host edit; Debug together lets
     * everyone edit. The Host can change this later (US-35). In Open mode edit
     * rights are per module, so this room-level flag is not used and the
     * join/roster message omits {@code can_edit} (see {@link RoomMessages}).
     */
    private boolean computeCanEdit(Room room, Role role) {
        if (role == Role.OBSERVER) {
            return false; // an Observer never edits, in either mode (FR9.4)
        }
        if (room.getMode() != RoomMode.PAIR) {
            return false; // Open: per-module; value is not sent
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
}
