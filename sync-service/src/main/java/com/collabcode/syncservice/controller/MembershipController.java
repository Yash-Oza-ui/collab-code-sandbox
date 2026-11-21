package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.handler.ControlHandler;
import com.collabcode.syncservice.module.ModuleService;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMessages;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remove, re-admit and regenerate-link (US-28, V2.18; FR9.6, CR04).
 *
 * <p>Three Host-only controls keep a removed user out:
 * <ul>
 *   <li>{@code POST /v1/rooms/{room_id}/members/{member_id}/remove} — block the
 *       member by its private {@code session_id}, close its sockets with
 *       {@code 4403}, and drop it from the roster.</li>
 *   <li>{@code POST /v1/rooms/{room_id}/members/{member_id}/readmit} — lift the
 *       block so the person can join again (as a new {@code member_id}).</li>
 *   <li>{@code GET  /v1/rooms/{room_id}/members/blocked} — list removed members so
 *       the Host client can offer "re-admit" after a refresh (never the
 *       {@code session_id}).</li>
 *   <li>{@code POST /v1/rooms/{room_id}/regenerate-link} — move the room to a new
 *       unguessable id, clear the block list, and close every old socket with
 *       {@code 4411}.</li>
 * </ul>
 *
 * <p>Every action is Host-only (a valid {@code X-Owner-Token}, V2.6) via the
 * shared {@link HostGuard}; the private {@code session_id} is never put in a URL,
 * body, log or broadcast (V2.3).
 */
@RestController
@RequestMapping("/v1")
public class MembershipController {

    private static final Logger log = LoggerFactory.getLogger(MembershipController.class);

    private final RoomService roomService;
    private final RoomRegistry registry;
    private final ModuleService moduleService;
    private final HostGuard hostGuard;
    private final ControlHandler controlHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MembershipController(RoomService roomService, RoomRegistry registry,
                                ModuleService moduleService, HostGuard hostGuard,
                                ControlHandler controlHandler) {
        this.roomService = roomService;
        this.registry = registry;
        this.moduleService = moduleService;
        this.hostGuard = hostGuard;
        this.controlHandler = controlHandler;
    }

    // -----------------------------------------------------------------------
    // Remove a member (V2.18)
    // -----------------------------------------------------------------------

    @PostMapping("/rooms/{roomId}/members/{memberId}/remove")
    public ResponseEntity<?> remove(
            @PathVariable String roomId,
            @PathVariable String memberId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        HostGuard.Result g = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!g.ok()) {
            return g.error();
        }
        Room room = g.room();

        // The target must exist, and a Host cannot be removed (this also covers the
        // Host removing themselves — the Host is the only Host, V2.18 rule 1).
        Member target = room.getMembers().get(memberId);
        if (target == null) {
            return HostGuard.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }
        if (target.getRole() == Role.HOST) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "A Host cannot be removed.");
        }

        // Records the block FIRST (so a racing reconnect is already refused), then
        // drops the member from the roster (RoomService, under its lock).
        Member removed = roomService.removeMember(roomId, memberId);
        if (removed == null) {
            // Removed by another request between the lookup and here.
            return HostGuard.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }

        // Drop any pending module requests created by the removed member (US-40)
        List<com.collabcode.syncservice.module.ModuleRecord> removedPending = moduleService.removePendingRequestsForMember(roomId, memberId);
        for (com.collabcode.syncservice.module.ModuleRecord pendingMod : removedPending) {
            controlHandler.broadcastModuleUpdated(roomId, RoomMessages.moduleUpdated(pendingMod, true));
        }

        // Drop any pending edit-access requests created by the removed member (US-38)
        controlHandler.clearAndNotifyEditRequest(roomId, memberId, "removed");

        // Close the removed member's control AND document sockets (4403, V2.3/V2.5),
        // then broadcast the updated roster to whoever remains (V2.4).
        closeSessions(registry.findAllBySessionId(roomId, removed.getSessionId()),
                ControlHandler.CLOSE_REMOVED, "Removed by the Host");
        broadcastRoster(room);

        log.info("Membership: removed member={} from room (blocked)", removed.getMemberId());
        return ResponseEntity.ok(statusBody("member_id", removed.getMemberId(), "removed"));
    }

    // -----------------------------------------------------------------------
    // Re-admit a member (V2.18)
    // -----------------------------------------------------------------------

    @PostMapping("/rooms/{roomId}/members/{memberId}/readmit")
    public ResponseEntity<?> readmit(
            @PathVariable String roomId,
            @PathVariable String memberId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        HostGuard.Result g = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!g.ok()) {
            return g.error();
        }

        // member_id must be one currently in the block list (V2.18 rule 1).
        if (!roomService.readmit(roomId, memberId)) {
            return HostGuard.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND",
                    "That member is not in this room's block list.");
        }
        log.info("Membership: re-admitted member={} (block lifted)", memberId);
        return ResponseEntity.ok(statusBody("member_id", memberId, "readmitted"));
    }

    // -----------------------------------------------------------------------
    // List removed members (V2.18)
    // -----------------------------------------------------------------------

    @GetMapping("/rooms/{roomId}/members/blocked")
    public ResponseEntity<?> listBlocked(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        HostGuard.Result g = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!g.ok()) {
            return g.error();
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (Room.BlockedSession b : roomService.listBlocked(roomId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("member_id", b.memberId());        // public id only
            entry.put("display_name", b.displayName());
            entry.put("removed_at", b.removedAt().toString()); // RFC 3339 UTC
            list.add(entry);                               // session_id is NEVER included
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blocked", list);
        return ResponseEntity.ok(body);
    }

    // -----------------------------------------------------------------------
    // Regenerate the room link (V2.18)
    // -----------------------------------------------------------------------

    @PostMapping("/rooms/{roomId}/regenerate-link")
    public ResponseEntity<?> regenerateLink(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        HostGuard.Result g = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!g.ok()) {
            return g.error();
        }

        Room fresh = roomService.regenerateLink(roomId);
        if (fresh == null) {
            return HostGuard.error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }
        // Re-key the room's modules (ownership/writers preserved).
        moduleService.moveRoom(roomId, fresh.getRoomId());
        // Close every socket on the OLD id (4411) — clients reconnect with the new link.
        closeSessions(registry.getAllSessionsInRoom(roomId),
                ControlHandler.CLOSE_LINK_REGENERATED, "Room link regenerated");

        log.info("Membership: regenerated room link (old id retired, block list cleared)");
        return ResponseEntity.ok(statusBody("room_id", fresh.getRoomId(), "regenerated"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void closeSessions(List<WebSocketSession> sessions, int code, String reason) {
        for (WebSocketSession s : sessions) {
            try {
                if (s.isOpen()) {
                    s.close(new CloseStatus(code, reason));
                }
            } catch (IOException e) {
                log.warn("Membership: failed to close wsSession={}: {}", s.getId(), e.getMessage());
            }
        }
    }

    private void broadcastRoster(Room room) {
        String json;
        try {
            json = objectMapper.writeValueAsString(RoomMessages.roster(room));
        } catch (IOException e) {
            log.warn("Membership: failed to serialise roster: {}", e.getMessage());
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
                log.warn("Membership: roster send failed to wsSession={}: {}", s.getId(), e.getMessage());
            }
        }
    }

    private static Map<String, Object> statusBody(String idKey, String idValue, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(idKey, idValue);
        body.put("status", status);
        return body;
    }
}
