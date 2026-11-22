package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.audit.AuditLogService;
import com.collabcode.syncservice.handler.ControlHandler;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMessages;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request edit access in Pair mode (US-38, V2.22; FR19.3).
 *
 * <p>{@code POST /v1/rooms/{roomId}/edit-requests/{memberId}/approve} approves
 * a pending edit request, giving edit access via {@link RoomService#setEditAccess}.
 *
 * <p>{@code POST /v1/rooms/{roomId}/edit-requests/{memberId}/decline} declines
 * a pending edit request without granting edit access.
 *
 * <p>{@code GET /v1/rooms/{roomId}/edit-requests} lists pending edit requests
 * for the Host.
 */
@RestController
@RequestMapping("/v1")
public class EditRequestController {

    private final RoomStore store;
    private final RoomService roomService;
    private final ControlHandler controlHandler;
    private final HostGuard hostGuard;
    private final AuditLogService auditLogService;

    public EditRequestController(
            RoomStore store,
            RoomService roomService,
            ControlHandler controlHandler,
            HostGuard hostGuard) {
        this(store, roomService, controlHandler, hostGuard, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EditRequestController(
            RoomStore store,
            RoomService roomService,
            ControlHandler controlHandler,
            HostGuard hostGuard,
            @org.springframework.beans.factory.annotation.Autowired(required = false) AuditLogService auditLogService) {
        this.store = store;
        this.roomService = roomService;
        this.controlHandler = controlHandler;
        this.hostGuard = hostGuard;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/rooms/{roomId}/edit-requests/{memberId}/approve")
    public ResponseEntity<?> approve(
            @PathVariable String roomId,
            @PathVariable String memberId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        HostGuard.Result g = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!g.ok()) {
            return g.error();
        }
        Room room = g.room();

        if (room.getMode() != RoomMode.PAIR) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Edit-access requests apply only in Pair mode; Open mode uses module requests.");
        }

        Room.EditRequest req = room.getEditRequest(memberId);
        if (req == null) {
            return HostGuard.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }

        Member target = room.getMembers().get(memberId);
        if (target == null) {
            room.removeEditRequest(memberId);
            return HostGuard.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }

        if (target.getRole() == Role.OBSERVER) {
            room.removeEditRequest(memberId);
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Observers cannot have edit access; make them a participant first.");
        }

        if (target.getRole() == Role.HOST) {
            room.removeEditRequest(memberId);
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "The Host's edit access cannot be changed.");
        }

        boolean oldCanEdit = target.isCanEdit();
        Member updated = roomService.setEditAccess(roomId, memberId, true);
        room.removeEditRequest(memberId);

        if (auditLogService != null) {
            auditLogService.record(
                    roomId,
                    "edit_access",
                    g.caller(),
                    updated,
                    updated.getDisplayName(),
                    updated.getMemberId(),
                    "can_edit",
                    oldCanEdit,
                    true,
                    "Approved edit request");
        }

        controlHandler.broadcastRoster(roomId);
        controlHandler.sendToMemberControlSessions(roomId, target.getSessionId(),
                RoomMessages.editAccessResponded(memberId, true, "The Host approved your edit access request."));

        return ResponseEntity.ok(RoomMessages.memberEntry(room, updated));
    }

    @PostMapping("/rooms/{roomId}/edit-requests/{memberId}/decline")
    public ResponseEntity<?> decline(
            @PathVariable String roomId,
            @PathVariable String memberId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        HostGuard.Result g = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!g.ok()) {
            return g.error();
        }
        Room room = g.room();

        if (room.getMode() != RoomMode.PAIR) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Edit-access requests apply only in Pair mode; Open mode uses module requests.");
        }

        Room.EditRequest req = room.getEditRequest(memberId);
        if (req == null) {
            return HostGuard.error(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
        }

        room.removeEditRequest(memberId);

        Member target = room.getMembers().get(memberId);
        if (target != null) {
            controlHandler.sendToMemberControlSessions(roomId, target.getSessionId(),
                    RoomMessages.editAccessResponded(memberId, false, "The Host declined your edit access request."));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("member_id", memberId);
        resp.put("status", "declined");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/rooms/{roomId}/edit-requests")
    public ResponseEntity<?> listPending(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        HostGuard.Result g = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!g.ok()) {
            return g.error();
        }
        Room room = g.room();

        if (room.getMode() != RoomMode.PAIR) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Edit-access requests apply only in Pair mode; Open mode uses module requests.");
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Room.EditRequest req : room.getPendingEditRequests().values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("member_id", req.memberId());
            entry.put("display_name", req.displayName());
            entry.put("requested_at", req.requestedAt().toString());
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }
}
