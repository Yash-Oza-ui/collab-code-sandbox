package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMessages;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Control who can run code (US-60, V2.20; FR41.1 to FR41.4).
 *
 * <ul>
 *   <li>{@code PATCH /v1/rooms/{room_id}/members/{member_id}/run-permission} with
 *       {@code {"can_run": false|true}} takes Run away from a participant or gives
 *       it back (FR41.2).</li>
 *   <li>{@code PATCH /v1/rooms/{room_id}/run-policy} with
 *       {@code {"host_only_run": true|false}} switches the room to Host-only
 *       running and back (FR41.3).</li>
 * </ul>
 *
 * <p>Both are Host-only: the caller's {@code X-Session-Id} plus a valid
 * {@code X-Owner-Token} (V2.6), checked by the shared {@link HostGuard}; anyone
 * else gets {@code 403 NOT_OWNER}. The decision lives on the server. execution-service
 * asks the session lookup on every run request, so a change is effective for the
 * member's next run at once, and a new {@code roster} goes to the room so the Run
 * button follows {@code can_run}. Nothing is logged except member ids and the
 * on/off state (no names, tokens or code).
 */
@RestController
@RequestMapping("/v1")
public class RunPermissionController {

    private static final Logger log = LoggerFactory.getLogger(RunPermissionController.class);

    private final RoomService roomService;
    private final RoomRegistry registry;
    private final HostGuard hostGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RunPermissionController(RoomService roomService, RoomRegistry registry, HostGuard hostGuard) {
        this.roomService = roomService;
        this.registry = registry;
        this.hostGuard = hostGuard;
    }

    // -----------------------------------------------------------------------
    // Per-member: take Run away / give it back (FR41.2)
    // -----------------------------------------------------------------------

    @PatchMapping("/rooms/{roomId}/members/{memberId}/run-permission")
    public ResponseEntity<?> setMemberRunPermission(
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

        Boolean canRun = readBoolean(rawBody, "can_run");
        if (canRun == null) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Field 'can_run' must be true or false.");
        }

        Member target = room.getMembers().get(memberId);
        if (target == null) {
            return HostGuard.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }
        // Run is restricted per participant only (V2.20 rule 2): the Host can always run,
        // and an Observer cannot run whatever the Host sets (FR41.1).
        if (target.getRole() == Role.HOST) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "The Host can always run; Run cannot be taken from the Host.");
        }
        if (target.getRole() == Role.OBSERVER) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "An Observer cannot run. Make the member a participant first.");
        }

        boolean changed = target.isCanRun() != canRun;
        Member updated = roomService.setMemberRunAllowed(roomId, memberId, canRun);
        if (updated == null) {
            // Removed between the lookup and the change.
            return HostGuard.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }
        if (changed) {
            log.info("RunPermission: member={} run {} by the Host", memberId, canRun ? "given back" : "taken away");
            broadcastRoster(room);
        }
        // The member as it appears in roster (same shape as V2.17).
        return ResponseEntity.ok(RoomMessages.memberEntry(room, updated));
    }

    // -----------------------------------------------------------------------
    // Room-wide: Host-only running on / off (FR41.3)
    // -----------------------------------------------------------------------

    @PatchMapping("/rooms/{roomId}/run-policy")
    public ResponseEntity<?> setRunPolicy(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken,
            @RequestBody(required = false) String rawBody) {

        HostGuard.Result g = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!g.ok()) {
            return g.error();
        }
        Room room = g.room();

        Boolean hostOnly = readBoolean(rawBody, "host_only_run");
        if (hostOnly == null) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Field 'host_only_run' must be true or false.");
        }

        boolean changed = room.isHostOnlyRun() != hostOnly;
        Room updated = roomService.setHostOnlyRun(roomId, hostOnly);
        if (updated == null) {
            return HostGuard.error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }
        if (changed) {
            log.info("RunPermission: room host-only running {}", hostOnly ? "on" : "off");
            broadcastRoster(updated);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("host_only_run", updated.isHostOnlyRun());
        return ResponseEntity.ok(body);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Read a required JSON boolean field from the body, or null if the body is
     * missing, is not a JSON object, or the field is missing or not a real boolean
     * (a string such as "false" is refused, so a typo can never flip a permission).
     */
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
        return value.booleanValue();
    }

    /** Send a fresh {@code roster} to every connection in the room (V2.4). Never logs the payload. */
    private void broadcastRoster(Room room) {
        String json;
        try {
            json = objectMapper.writeValueAsString(RoomMessages.roster(room));
        } catch (IOException e) {
            log.warn("RunPermission: failed to serialise roster: {}", e.getMessage());
            return;
        }
        for (WebSocketSession s : registry.getAll(room.getRoomId())) {
            try {
                if (s.isOpen()) {
                    synchronized (s) {
                        s.sendMessage(new TextMessage(json));
                    }
                }
            } catch (IOException e) {
                log.warn("RunPermission: roster send failed to wsSession={}: {}", s.getId(), e.getMessage());
            }
        }
    }
}
