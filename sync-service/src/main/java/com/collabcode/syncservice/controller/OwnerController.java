package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owner check (T-10, FR9.5; contract section 8, V2.1).
 *
 * <p>{@code GET /v1/rooms/{room_id}/owner} with the header
 * {@code X-Owner-Token} tells the caller whether it holds the room's owner
 * token, so ownership survives a refresh or reconnect without any login.
 *
 * <p>The token is compared against the stored SHA-256 hash in constant time
 * ({@link OwnerTokens#matches}); it is never logged, and the {@code 403}
 * message does not reveal whether the token was missing or wrong. An unknown
 * room answers {@code 404 ROOM_NOT_FOUND}, like every other REST call on a
 * room that does not exist (V2.12).
 */
@RestController
@RequestMapping("/v1")
public class OwnerController {

    private final RoomStore store;

    public OwnerController(RoomStore store) {
        this.store = store;
    }

    @GetMapping("/rooms/{roomId}/owner")
    public ResponseEntity<?> checkOwner(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        Room room = store.get(roomId);
        if (room == null) {
            return error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }
        if (!OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
            return error(HttpStatus.FORBIDDEN, "NOT_OWNER", "Missing or invalid owner token.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room_id", roomId);
        body.put("is_owner", true);
        return ResponseEntity.ok(body);
    }

    /** Section 7 error envelope; the owner check carries no request_id. */
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
