package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.audit.AuditLogService;
import com.collabcode.syncservice.handler.ControlHandler;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transfer the Host role (US-54, V2.19; FR31.1).
 *
 * <p>{@code POST /v1/rooms/{room_id}/members/{member_id}/make-host} hands the Host
 * role to a participant. The server reissues the owner token (the previous one
 * stops granting Host rights at once, T-10), keeps exactly one Host, and delivers
 * the new token to the new Host <strong>only</strong>, on their control socket, in
 * a {@code host-granted} message — never in this HTTP response, in {@code roster},
 * or in a log. A fresh {@code roster} tells the whole room about the change (V2.4).
 *
 * <p>Caller identity (V2.6: the Host's {@code X-Session-Id} plus a valid
 * {@code X-Owner-Token}) is checked by the shared {@link HostGuard}.
 */
@RestController
@RequestMapping("/v1")
public class HostTransferController {

    private static final Logger log = LoggerFactory.getLogger(HostTransferController.class);

    private final RoomService roomService;
    private final RoomRegistry registry;
    private final HostGuard hostGuard;
    private final ControlHandler controlHandler;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HostTransferController(RoomService roomService, RoomRegistry registry, HostGuard hostGuard) {
        this(roomService, registry, hostGuard, null, null);
    }

    public HostTransferController(RoomService roomService, RoomRegistry registry, HostGuard hostGuard, ControlHandler controlHandler) {
        this(roomService, registry, hostGuard, controlHandler, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HostTransferController(
            RoomService roomService,
            RoomRegistry registry,
            HostGuard hostGuard,
            ControlHandler controlHandler,
            @org.springframework.beans.factory.annotation.Autowired(required = false) AuditLogService auditLogService) {
        this.roomService = roomService;
        this.registry = registry;
        this.hostGuard = hostGuard;
        this.controlHandler = controlHandler;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/rooms/{roomId}/members/{memberId}/make-host")
    public ResponseEntity<?> makeHost(
            @PathVariable String roomId,
            @PathVariable String memberId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        HostGuard.Result g = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!g.ok()) {
            return g.error();
        }
        Room room = g.room();

        // The target must be a current participant (V2.19 rule 2).
        Member target = room.getMembers().get(memberId);
        if (target == null) {
            return HostGuard.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }
        if (target.getRole() == Role.HOST) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "You cannot transfer the Host role to yourself or another Host.");
        }
        if (target.getRole() == Role.OBSERVER) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Make the Observer a participant before transferring the Host role.");
        }

        // Reissue the owner token and swap the roles atomically (exactly one Host).
        String newOwnerToken = OwnerTokens.generate();
        Member newHost = roomService.transferHost(roomId, memberId, OwnerTokens.hash(newOwnerToken));
        if (newHost == null) {
            // Removed between the lookup and here.
            return HostGuard.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }

        // Deliver the new owner token to the new Host ONLY, on their control socket(s),
        // then tell the whole room about the role change (V2.4).
        sendHostGranted(roomId, newHost.getSessionId(), newHost.getMemberId(), newOwnerToken);
        if (controlHandler != null) {
            controlHandler.clearAndNotifyEditRequest(roomId, newHost.getMemberId(), "role_changed");
        }
        if (auditLogService != null) {
            auditLogService.record(
                    roomId,
                    "host_transfer",
                    g.caller(),
                    newHost,
                    newHost.getDisplayName(),
                    newHost.getMemberId(),
                    "role",
                    "participant",
                    "host",
                    null);
        }
        broadcastRoster(room);

        log.info("HostTransfer: host role transferred to member={} (owner token reissued)", newHost.getMemberId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("host_member_id", newHost.getMemberId());
        body.put("status", "transferred");
        return ResponseEntity.ok(body);
    }

    /** Send {@code host-granted} with the new token to the new Host's control socket(s). Never logs the token. */
    private void sendHostGranted(String roomId, String newHostSessionId, String memberId, String ownerToken) {
        String json;
        try {
            json = objectMapper.writeValueAsString(RoomMessages.hostGranted(memberId, ownerToken));
        } catch (IOException e) {
            log.warn("HostTransfer: failed to serialise host-granted: {}", e.getMessage());
            return;
        }
        for (WebSocketSession s : registry.getBySessionIds(roomId, List.of(newHostSessionId))) {
            try {
                if (s.isOpen()) {
                    synchronized (s) {
                        s.sendMessage(new TextMessage(json));
                    }
                }
            } catch (IOException e) {
                log.warn("HostTransfer: host-granted send failed to wsSession={}: {}", s.getId(), e.getMessage());
            }
        }
    }

    private void broadcastRoster(Room room) {
        String json;
        try {
            json = objectMapper.writeValueAsString(RoomMessages.roster(room));
        } catch (IOException e) {
            log.warn("HostTransfer: failed to serialise roster: {}", e.getMessage());
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
                log.warn("HostTransfer: roster send failed to wsSession={}: {}", s.getId(), e.getMessage());
            }
        }
    }
}
