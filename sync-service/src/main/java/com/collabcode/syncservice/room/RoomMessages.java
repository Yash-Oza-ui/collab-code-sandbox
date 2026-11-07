package com.collabcode.syncservice.room;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the control-socket messages of T-17 V2.3 / V2.4 as plain maps, so the
 * socket layer can serialise and send them.
 *
 * <p>{@code member_id} is public and {@code session_id} is never included. In
 * Open mode {@code can_edit} is omitted, because edit rights there are per
 * module, not room-level.
 */
public final class RoomMessages {

    private RoomMessages() {
    }

    /** {@code joined} (sent to the one connection that joined). */
    public static Map<String, Object> joined(Room room, Member member) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "joined");
        m.put("member_id", member.getMemberId());
        m.put("role", member.getRole().value());
        m.put("mode", room.getMode().value());
        m.put("preset", room.getPreset());
        m.put("permissions", permissions(room, member));
        return m;
    }

    /** {@code join-refused} with a reason (for example {@code NAME_TAKEN}). */
    public static Map<String, Object> joinRefused(String reason, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "join-refused");
        m.put("reason", reason);
        m.put("message", message);
        return m;
    }

    /** {@code roster} (sent to the whole room after a join, leave or role change). */
    public static Map<String, Object> roster(Room room) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (Member member : room.getMembers().values()) {
            members.add(memberEntry(room, member));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "roster");
        m.put("members", members);
        return m;
    }

    /**
     * One member as it appears in {@code roster} (member_id, display_name, role,
     * permissions). Also the body of the role-change response (V2.17).
     */
    public static Map<String, Object> memberEntry(Room room, Member member) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("member_id", member.getMemberId());
        entry.put("display_name", member.getDisplayName());
        entry.put("role", member.getRole().value());
        entry.put("permissions", permissions(room, member));
        return entry;
    }

    /**
     * {@code can_run} is always present; {@code can_edit} is present only in
     * Pair mode (Open mode uses per-module write access, V2.6).
     */
    private static Map<String, Object> permissions(Room room, Member member) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (room.getMode() == RoomMode.PAIR) {
            p.put("can_edit", member.isCanEdit());
        }
        p.put("can_run", member.isCanRun());
        return p;
    }
}
