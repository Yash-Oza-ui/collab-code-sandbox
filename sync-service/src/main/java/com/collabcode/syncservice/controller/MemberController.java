package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMessages;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Member role change (US-27, V2.17; FR9.4).
 *
 * <p>{@code PATCH /v1/rooms/{room_id}/members/{member_id}} lets the Host make a
 * member an {@code observer} (read-only) or a {@code participant} again. It is
 * a Host-only action: the caller sends its private {@code X-Session-Id} and a
 * valid {@code X-Owner-Token} (V2.6); without the token the answer is
 * {@code 403 NOT_OWNER}. The role takes effect on the server, so a hidden
 * button is never the enforcement (NFR13).
 */
@RestController
@RequestMapping("/v1")
public class MemberController {

    private final RoomStore store;
    private final RoomService roomService;

    public MemberController(RoomStore store, RoomService roomService) {
        this.store = store;
        this.roomService = roomService;
    }

    @PatchMapping("/rooms/{roomId}/members/{memberId}")
    public ResponseEntity<?> changeRole(
            @PathVariable String roomId,
            @PathVariable String memberId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken,
            @RequestBody(required = false) UpdateMemberRequest body) {

        Room room = store.get(roomId);
        if (room == null) {
            return error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }

        // Caller must have joined this room (its private session id).
        Member caller = roomService.findMemberBySession(roomId, sessionId);
        if (caller == null) {
            return error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "You are not in this room.");
        }

        // Host-only: a valid owner token is required (V2.17).
        if (!OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
            return error(HttpStatus.FORBIDDEN, "NOT_OWNER", "Missing or invalid owner token.");
        }

        // Only participant/observer; "host" and anything else is invalid.
        Role newRole = parseRole(body == null ? null : body.role());
        if (newRole == null) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Field 'role' must be 'participant' or 'observer'.");
        }

        // The Host cannot change their own role (V2.17, rule 1).
        if (memberId.equals(caller.getMemberId())) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "The Host cannot change their own role.");
        }

        if (!room.getMembers().containsKey(memberId)) {
            return error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }

        Member updated = roomService.changeRole(roomId, memberId, newRole);
        // The new roster is pushed to the room by the control-socket layer; the
        // response returns the member as it appears in the roster (V2.17).
        return ResponseEntity.ok(RoomMessages.memberEntry(room, updated));
    }

    private Role parseRole(String role) {
        if ("participant".equals(role)) {
            return Role.PARTICIPANT;
        }
        if ("observer".equals(role)) {
            return Role.OBSERVER;
        }
        return null; // null, "host", or anything else
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
