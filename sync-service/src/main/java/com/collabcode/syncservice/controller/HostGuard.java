package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared Host-only authorization and the section-7 error envelope for the room
 * REST controllers (V2.6 caller identity). Extracted so US-27, US-28 and US-54 do
 * not each re-copy the UUID check, the owner-token check and the error shape.
 */
@Component
public class HostGuard {

    /** session_id is a client-generated UUID v4 (contract §3.3). */
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private final RoomStore store;
    private final RoomService roomService;

    public HostGuard(RoomStore store, RoomService roomService) {
        this.store = store;
        this.roomService = roomService;
    }

    /** Outcome of the guard: either an error response, or the resolved room and caller. */
    public record Result(ResponseEntity<Map<String, Object>> error, Room room, Member caller) {
        public Result(ResponseEntity<Map<String, Object>> error, Room room) {
            this(error, room, null);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Validate a Host-only caller in the V2.6 order: a well-formed session id
     * (422 INVALID_REQUEST), an existing room (404 ROOM_NOT_FOUND), the caller
     * having joined it (404 SESSION_NOT_FOUND), and a valid owner token
     * (403 NOT_OWNER). On success {@link Result#ok()} is true and
     * {@link Result#room()} is the room.
     */
    public Result requireHost(String roomId, String sessionId, String ownerToken) {
        if (!isUuid(sessionId)) {
            return new Result(error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Header 'X-Session-Id' must be a UUID."), null, null);
        }
        Room room = store.get(roomId);
        if (room == null) {
            return new Result(error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found."), null, null);
        }
        Member caller = roomService.findMemberBySession(roomId, sessionId);
        if (caller == null) {
            return new Result(error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "You are not in this room."), null, null);
        }
        if (!OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
            return new Result(error(HttpStatus.FORBIDDEN, "NOT_OWNER", "Missing or invalid owner token."), null, null);
        }
        return new Result(null, room, caller);
    }

    /** True if the value is a well-formed UUID v4 (contract §3.3). */
    public static boolean isUuid(String sessionId) {
        return sessionId != null && UUID_V4.matcher(sessionId).matches();
    }

    /** The section-7 error envelope ({@code request_id} is null; these calls carry none). */
    public static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
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
