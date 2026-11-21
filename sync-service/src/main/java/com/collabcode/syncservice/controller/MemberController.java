package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMessages;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.collabcode.syncservice.handler.ControlHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Member role change (US-27, V2.17; FR9.4) and edit access in Pair mode
 * (US-36, V2.21; FR19.1).
 *
 * <p>{@code PATCH /v1/rooms/{room_id}/members/{member_id}} lets the Host make a
 * member an {@code observer} (read-only) or a {@code participant} again.
 *
 * <p>{@code PATCH /v1/rooms/{room_id}/members/{member_id}/edit-access} lets the
 * Host grant or revoke edit access for a participant in Pair mode.
 *
 * <p>Both are Host-only actions: the caller sends its private {@code X-Session-Id} and a
 * valid {@code X-Owner-Token} (V2.6); without the token the answer is
 * {@code 403 NOT_OWNER}. Permissions take effect on the server, so a hidden
 * button is never the enforcement (NFR13).
 */
@RestController
@RequestMapping("/v1")
public class MemberController {

    /** session_id is a client-generated UUID v4 (contract §3.3). */
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private final RoomStore store;
    private final RoomService roomService;
    private final ControlHandler controlHandler;
    private final HostGuard hostGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemberController(
            RoomStore store,
            RoomService roomService,
            ControlHandler controlHandler,
            HostGuard hostGuard) {
        this.store = store;
        this.roomService = roomService;
        this.controlHandler = controlHandler;
        this.hostGuard = hostGuard;
    }

    @PatchMapping("/rooms/{roomId}/members/{memberId}")
    public ResponseEntity<?> changeRole(
            @PathVariable String roomId,
            @PathVariable String memberId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken,
            @RequestBody(required = false) UpdateMemberRequest body) {

        // The caller identity must be a well-formed UUID v4 (contract §3.3, V2.6);
        // null, blank or malformed is a client error (422), distinct from a valid
        // session that has simply not joined the room (404 SESSION_NOT_FOUND below).
        if (sessionId == null || !UUID_V4.matcher(sessionId).matches()) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Header 'X-Session-Id' must be a UUID.");
        }

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

        // A Host's role cannot be changed here — not the caller's own, and not a
        // second Host session (V2.17, rule 1). Transferring or removing the Host
        // is FR31.1, a separate story.
        Member target = room.getMembers().get(memberId);
        if (target == null) {
            return error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }
        if (target.getRole() == Role.HOST) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "The Host's role cannot be changed here.");
        }

        Member updated = roomService.changeRole(roomId, memberId, newRole);
        if (updated == null) {
            // Removed between the lookup and the change.
            return error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }
        controlHandler.broadcastRoster(roomId);
        // The updated role is now part of the server-authoritative roster.
        // Broadcast it to all connected control sockets (V2.17 rule 4).
        return ResponseEntity.ok(RoomMessages.memberEntry(room, updated));
    }

    /**
     * Give or take back edit access for a participant in Pair mode (US-36, V2.21; FR19.1).
     *
     * <p>{@code PATCH /v1/rooms/{roomId}/members/{memberId}/edit-access} with
     * {@code {"can_edit": true|false}} grants or withdraws edit access for a participant.
     */
    @PatchMapping("/rooms/{roomId}/members/{memberId}/edit-access")
    public ResponseEntity<?> setEditAccess(
            @PathVariable String roomId,
            @PathVariable String memberId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken,
            @RequestBody(required = false) String rawBody) {

        HostGuard.Result g = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!g.ok()) {
            return g.error();
        }
        Room room = g.room();

        // 2. Open mode room refuses the call (write access there is per module, US-43).
        if (room.getMode() != RoomMode.PAIR) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Edit access controls apply only in Pair mode; Open mode uses per-module write access.");
        }

        // 3. Body validation: can_edit must be a JSON boolean.
        Boolean canEdit = readBoolean(rawBody, "can_edit");
        if (canEdit == null) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Field 'can_edit' must be true or false.");
        }

        // 4. Target member validation.
        Member target = room.getMembers().get(memberId);
        if (target == null) {
            return error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }

        // Host cannot withdraw or change the Host's own access (FR19.1).
        if (target.getRole() == Role.HOST) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "The Host's edit access cannot be changed.");
        }

        // Observer's role wins (FR9.4) until made a participant (US-27).
        if (target.getRole() == Role.OBSERVER) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Observers cannot have edit access; make them a participant first.");
        }

        boolean changed = target.isCanEdit() != canEdit;
        Member updated = roomService.setEditAccess(roomId, memberId, canEdit);
        if (updated == null) {
            return error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }

        if (changed) {
            controlHandler.broadcastRoster(roomId);
        }

        return ResponseEntity.ok(RoomMessages.memberEntry(room, updated));
    }

    private Boolean readBoolean(String rawBody, String field) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode value = root.get(field);
        if (value == null || !value.isBoolean()) {
            return null;
        }
        return value.asBoolean();
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
