package com.collabcode.syncservice.room;

import java.time.Instant;
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
        m.put("ai_enabled", room.isAiEnabled());
        if (room.getLanguage() != null) {
            m.put("language", room.getLanguage());
        }
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

    /**
     * {@code host-granted} (US-54, V2.19): the new owner token, delivered to the new
     * Host only, on their control socket. Never put in an HTTP response, in
     * {@code roster}, or in a log (same confidentiality as room creation, V2.1).
     */
    public static Map<String, Object> hostGranted(String memberId, String ownerToken) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "host-granted");
        m.put("member_id", memberId);
        m.put("owner_token", ownerToken);
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
        // Room-wide Run setting (US-60, V2.20): lets the Host client show the switch.
        m.put("host_only_run", room.isHostOnlyRun());
        m.put("ai_enabled", room.isAiEnabled());
        return m;
    }

    /** Server-authoritative room-wide AI switch change (US-59). */
    public static Map<String, Object> roomState(Room room) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "room-state");
        m.put("ai_enabled", room.isAiEnabled());
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
     * {@code can_run} and {@code run_denied_reason} are always present; {@code can_edit}
     * is present only in Pair mode (Open mode uses per-module write access, V2.6).
     * {@code can_run} is the effective answer (US-60, V2.20), the same one the
     * permission lookup gives execution-service: it includes Host-only running.
     * {@code run_denied_reason} is {@code null} when the member may run.
     */
    private static Map<String, Object> permissions(Room room, Member member) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (room.getMode() == RoomMode.PAIR) {
            p.put("can_edit", member.isCanEdit());
        }
        p.put("can_run", room.canRun(member));
        p.put("run_denied_reason", room.runDeniedReason(member));
        return p;
    }

    /**
     * {@code module-updated} (T-17 V2.6): sent to all connections when a module
     * is created, approved, updated, or removed/declined.
     */
    public static Map<String, Object> moduleUpdated(com.collabcode.syncservice.module.ModuleRecord module, boolean deleted) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "module-updated");
        payload.put("module_id", module.moduleId());
        payload.put("name", module.name());
        payload.put("owner_member_id", module.ownerMemberId());
        payload.put("writers", new ArrayList<>(module.writers()));
        payload.put("runnable", module.runnable());
        payload.put("is_entry", module.isEntry());
        payload.put("version", module.version());
        payload.put("pending", module.pending());
        payload.put("deleted", deleted);
        return payload;
    }

    /**
     * {@code edit-access-requested} (US-38, V2.22): pushed to all joined control
     * sockets of the Host when a participant without edit access asks for it.
     */
    public static Map<String, Object> editAccessRequested(String memberId, String displayName, Instant requestedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "edit-access-requested");
        m.put("member_id", memberId);
        m.put("display_name", displayName);
        m.put("requested_at", requestedAt.toString());
        return m;
    }

    /**
     * {@code edit-access-responded} (US-38, V2.22): sent to the requester's control
     * sockets when the Host approves or declines their request.
     */
    public static Map<String, Object> editAccessResponded(String memberId, boolean approved, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "edit-access-responded");
        m.put("member_id", memberId);
        m.put("approved", approved);
        m.put("message", message);
        return m;
    }

    /**
     * {@code edit-access-request-cleared} (US-38, V2.22): sent to Host control sockets
     * when a pending request is automatically cleared (granted, role_changed, removed, left).
     */
    public static Map<String, Object> editAccessRequestCleared(String memberId, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "edit-access-request-cleared");
        m.put("member_id", memberId);
        m.put("reason", reason);
        return m;
    }

    /**
     * Generic error frame on the control socket (V2.5, V2.22).
     */
    public static Map<String, Object> controlError(String code, String message) {
        Map<String, Object> errObj = new LinkedHashMap<>();
        errObj.put("code", code);
        errObj.put("message", message);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("code", code);
        m.put("message", message);
        m.put("error", errObj);
        return m;
    }
}
