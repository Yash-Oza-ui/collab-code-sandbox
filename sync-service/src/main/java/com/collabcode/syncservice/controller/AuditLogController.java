package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMessages;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Audit log of role and permission changes (US-58, V2.23; FR34).
 *
 * <p>{@code GET /v1/rooms/{roomId}/audit-log} returns the room's audit log entries,
 * sorted newest first. Accessible to any joined member in the room (Host, Participant, Observer).
 *
 * <p>Entries are strictly server-written: no endpoints exist to add, update, or remove entries.
 */
@RestController
@RequestMapping("/v1")
public class AuditLogController {

    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private final RoomStore roomStore;
    private final RoomService roomService;

    public AuditLogController(RoomStore roomStore, RoomService roomService) {
        this.roomStore = roomStore;
        this.roomService = roomService;
    }

    @GetMapping("/rooms/{roomId}/audit-log")
    public ResponseEntity<?> getAuditLog(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        if (sessionId == null || !UUID_V4.matcher(sessionId).matches()) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Header 'X-Session-Id' must be a UUID.");
        }

        Room room = roomStore.get(roomId);
        if (room == null || room.isClosed()) {
            return error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }

        Member caller = roomService.findMemberBySession(roomId, sessionId);
        if (caller == null) {
            return error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "You are not in this room.");
        }

        List<Map<String, Object>> entries = room.getAuditLog().stream()
                .map(RoomMessages::auditEntryData)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room_id", roomId);
        body.put("entries", entries);

        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retry_after_seconds", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", null);
        body.put("error", error);

        return ResponseEntity.status(status).body(body);
    }
}
