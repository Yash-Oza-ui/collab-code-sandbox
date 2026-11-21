package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.handler.ControlHandler;
import com.collabcode.syncservice.module.ModuleRecord;
import com.collabcode.syncservice.module.ModuleService;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMessages;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for workspace module operations (T-17 V2.6, FR20.1, DR4, US-39).
 *
 * All endpoints identify the caller via the {@code X-Session-Id} header.
 * Host-only operations (direct creation, approval) additionally require {@code X-Owner-Token}.
 */
@RestController
@RequestMapping("/v1")
public class ModuleController {

    private final RoomStore store;
    private final RoomService roomService;
    private final ModuleService moduleService;
    private final ControlHandler controlHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ModuleController(
            RoomStore store,
            RoomService roomService,
            ModuleService moduleService,
            ControlHandler controlHandler) {
        this.store = store;
        this.roomService = roomService;
        this.moduleService = moduleService;
        this.controlHandler = controlHandler;
    }

    /**
     * List approved Open-mode modules (V2.6).
     * Pair mode rooms refuse the call with 422 INVALID_REQUEST.
     */
    @GetMapping("/rooms/{roomId}/modules")
    public ResponseEntity<?> listModules(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken,
            @RequestParam(value = "pending", required = false) Boolean pending) {

        Room room = store.get(roomId);
        if (room == null) {
            return moduleError(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }
        if (room.getMode() != RoomMode.OPEN) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return moduleError(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Header 'X-Session-Id' is required.");
        }
        Member member = roomService.findMemberBySession(roomId, sessionId);
        if (member == null) {
            return moduleError(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session has not joined the room.");
        }

        if (Boolean.TRUE.equals(pending)) {
            if (member.getRole() != Role.HOST) {
                return moduleError(HttpStatus.FORBIDDEN, "NOT_OWNER", "Only the Host can list pending requests.");
            }
            if (ownerToken != null && !ownerToken.isBlank() && !OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
                return moduleError(HttpStatus.FORBIDDEN, "NOT_OWNER", "Invalid owner token.");
            }
            List<Map<String, Object>> modules = moduleService.listPending(roomId, member).stream()
                    .map(this::moduleBody)
                    .toList();
            return ResponseEntity.ok(modules);
        }

        try {
            List<Map<String, Object>> modules = moduleService.listApproved(roomId, member).stream()
                    .map(this::moduleBody)
                    .toList();
            return ResponseEntity.ok(modules);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("PAIR_MODE_NO_MODULES".equals(msg)) {
                return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
            }
            return moduleError(HttpStatus.NOT_FOUND, msg, msg);
        }
    }

    /**
     * Create or request a module (V2.6, US-39, US-40).
     *
     * <p>When called by the Host with a valid {@code X-Owner-Token}, the module is created
     * directly with {@code pending = false} (HTTP 201 Created).
     * When requested without {@code X-Owner-Token}, it is created with {@code pending = true}
     * awaiting Host approval (HTTP 202 Accepted).
     */
    @PostMapping("/rooms/{roomId}/modules")
    public ResponseEntity<?> createModule(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken,
            @RequestBody(required = false) Map<String, Object> body) {

        Room room = store.get(roomId);
        if (room == null) {
            return moduleError(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }
        if (room.getMode() != RoomMode.OPEN) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Header 'X-Session-Id' is required.");
        }
        Member member = roomService.findMemberBySession(roomId, sessionId);
        if (member == null) {
            return moduleError(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session has not joined the room.");
        }
        if (member.getRole() == Role.OBSERVER) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Observers cannot request modules.");
        }

        Object rawName = body == null ? null : body.get("name");
        String name = rawName instanceof String s ? s : null;
        if (name == null || name.isBlank()) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Field 'name' is missing or invalid.");
        }

        boolean isHostDirectCreate = ownerToken != null && !ownerToken.isBlank();
        if (isHostDirectCreate) {
            if (member.getRole() != Role.HOST || !OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
                return moduleError(HttpStatus.FORBIDDEN, "NOT_OWNER", "Only the Host can directly create modules.");
            }
        }

        try {
            boolean pending = !isHostDirectCreate;
            ModuleRecord module = moduleService.createModule(roomId, member.getMemberId(), name, pending);

            // Broadcast module-updated to all connected control sockets
            controlHandler.broadcastModuleUpdated(roomId, moduleUpdatedPayload(module, false));

            HttpStatus status = isHostDirectCreate ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
            return ResponseEntity.status(status).body(moduleBody(module));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("PAIR_MODE_NO_MODULES".equals(msg)) {
                return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
            }
            if ("SESSION_NOT_FOUND".equals(msg) || "ROOM_NOT_FOUND".equals(msg)) {
                return moduleError(HttpStatus.NOT_FOUND, msg, msg);
            }
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", msg != null ? msg : "Invalid request.");
        } catch (IllegalStateException e) {
            if ("NAME_TAKEN".equals(e.getMessage())) {
                return moduleError(HttpStatus.CONFLICT, "NAME_TAKEN", "Module name '" + name + "' is already taken in this workspace.");
            }
            return moduleError(HttpStatus.CONFLICT, e.getMessage(), e.getMessage());
        }
    }

    /**
     * Host approves a pending module (V2.6, FR21.2).
     */
    @PostMapping("/rooms/{roomId}/modules/{moduleId}/approve")
    public ResponseEntity<?> approveModule(
            @PathVariable String roomId,
            @PathVariable String moduleId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken,
            HttpEntity<String> httpEntity) {

        Room room = store.get(roomId);
        if (room == null) {
            return moduleError(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }
        if (room.getMode() != RoomMode.OPEN) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Header 'X-Session-Id' is required.");
        }
        Member member = roomService.findMemberBySession(roomId, sessionId);
        if (member == null) {
            return moduleError(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session has not joined the room.");
        }
        if (ownerToken == null || ownerToken.isBlank() || member.getRole() != Role.HOST
                || !OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
            return moduleError(HttpStatus.FORBIDDEN, "NOT_OWNER", "Only the Host can approve modules.");
        }

        try {
            String newOwnerId = null;
            if (httpEntity != null && httpEntity.getBody() != null && !httpEntity.getBody().isBlank()) {
                try {
                    JsonNode json = objectMapper.readTree(httpEntity.getBody());
                    if (json.has("owner_member_id") && !json.get("owner_member_id").isNull()) {
                        newOwnerId = json.get("owner_member_id").asText();
                    }
                } catch (Exception ignored) {
                }
            }

            ModuleRecord existingModule = moduleService.find(roomId, moduleId);
            if (existingModule == null) {
                return moduleError(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "Module not found.");
            }
            String targetOwnerId = (newOwnerId != null && !newOwnerId.isBlank()) ? newOwnerId : existingModule.ownerMemberId();
            Member target = room.getMembers().get(targetOwnerId);
            if (target == null) {
                return moduleError(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
            }
            if (target.getRole() == Role.OBSERVER) {
                return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Observers cannot own modules.");
            }

            ModuleRecord module = moduleService.approve(roomId, moduleId, member, ownerToken, newOwnerId);

            // Broadcast module-updated to all connected control sockets
            controlHandler.broadcastModuleUpdated(roomId, RoomMessages.moduleUpdated(module, false));

            return ResponseEntity.ok(moduleBody(module));
        } catch (SecurityException e) {
            return moduleError(HttpStatus.FORBIDDEN, "NOT_OWNER", e.getMessage());
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("PAIR_MODE_NO_MODULES".equals(msg)) {
                return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
            }
            if ("MEMBER_NOT_FOUND".equals(msg)) {
                return moduleError(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Member not found in this room.");
            }
            if ("OBSERVER_CANNOT_OWN".equals(msg) || "OBSERVERS_CANNOT_REQUEST_MODULES".equals(msg)) {
                return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Observers cannot own modules.");
            }
            HttpStatus status = ("SESSION_NOT_FOUND".equals(msg) || "ROOM_NOT_FOUND".equals(msg))
                    ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
            return moduleError(status, msg, msg);
        } catch (IllegalStateException e) {
            return moduleError(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "Module not found.");
        }
    }

    /**
     * Host declines a pending module (US-40, FR21.2).
     */
    @PostMapping("/rooms/{roomId}/modules/{moduleId}/decline")
    public ResponseEntity<?> declineModule(
            @PathVariable String roomId,
            @PathVariable String moduleId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        Room room = store.get(roomId);
        if (room == null) {
            return moduleError(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }
        if (room.getMode() != RoomMode.OPEN) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Header 'X-Session-Id' is required.");
        }
        Member member = roomService.findMemberBySession(roomId, sessionId);
        if (member == null) {
            return moduleError(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session has not joined the room.");
        }
        if (ownerToken == null || ownerToken.isBlank() || member.getRole() != Role.HOST
                || !OwnerTokens.matches(ownerToken, room.getOwnerTokenHash())) {
            return moduleError(HttpStatus.FORBIDDEN, "NOT_OWNER", "Only the Host can decline modules.");
        }

        try {
            ModuleRecord module = moduleService.decline(roomId, moduleId, member, ownerToken);

            // Broadcast module-updated with deleted=true to all connected control sockets
            controlHandler.broadcastModuleUpdated(roomId, RoomMessages.moduleUpdated(module, true));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("module_id", module.moduleId());
            body.put("status", "declined");
            return ResponseEntity.ok(body);
        } catch (SecurityException e) {
            return moduleError(HttpStatus.FORBIDDEN, "NOT_OWNER", e.getMessage());
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("PAIR_MODE_NO_MODULES".equals(msg)) {
                return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
            }
            if ("MODULE_NOT_PENDING".equals(msg)) {
                return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Module is not pending approval.");
            }
            HttpStatus status = ("SESSION_NOT_FOUND".equals(msg) || "ROOM_NOT_FOUND".equals(msg))
                    ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
            return moduleError(status, msg, msg);
        } catch (IllegalStateException e) {
            return moduleError(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "Module not found.");
        }
    }

    /**
     * Grant write access to a member for a module (US-43, FR22.1, V2.6).
     *
     * <p>Allowed callers: module owner or the Host (with valid X-Owner-Token).
     */
    @PostMapping("/rooms/{roomId}/modules/{moduleId}/writers")
    public ResponseEntity<?> grantWriteAccess(
            @PathVariable String roomId,
            @PathVariable String moduleId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken,
            @RequestBody(required = false) Map<String, Object> body) {

        Room room = store.get(roomId);
        if (room == null) {
            return moduleError(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }
        if (room.getMode() != RoomMode.OPEN) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Header 'X-Session-Id' is required.");
        }
        Member member = roomService.findMemberBySession(roomId, sessionId);
        if (member == null) {
            return moduleError(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session has not joined the room.");
        }

        Object rawTarget = body == null ? null : body.get("member_id");
        String targetMemberId = rawTarget instanceof String s ? s : null;
        if (targetMemberId == null || targetMemberId.isBlank()) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Field 'member_id' is required.");
        }

        try {
            ModuleRecord module = moduleService.grantWriteAccess(roomId, moduleId, member, ownerToken, targetMemberId);

            // Broadcast module-updated to all connected control sockets
            controlHandler.broadcastModuleUpdated(roomId, RoomMessages.moduleUpdated(module, false));

            return ResponseEntity.ok(moduleBody(module));
        } catch (SecurityException e) {
            String code = "NOT_OWNER".equals(e.getMessage()) ? "NOT_OWNER" : "NOT_MODULE_OWNER";
            String msg = "NOT_OWNER".equals(code)
                    ? "Invalid owner token."
                    : "Only the module owner or the Host can change write access.";
            return moduleError(HttpStatus.FORBIDDEN, code, msg);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("PAIR_MODE_NO_MODULES".equals(msg)) {
                return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
            }
            if ("MEMBER_NOT_FOUND".equals(msg)) {
                return moduleError(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Target member not found in this room.");
            }
            HttpStatus status = ("SESSION_NOT_FOUND".equals(msg) || "ROOM_NOT_FOUND".equals(msg))
                    ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
            return moduleError(status, msg, msg);
        } catch (IllegalStateException e) {
            return moduleError(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "Module not found.");
        }
    }

    /**
     * Revoke write access from a member for a module (US-43, FR22.1, V2.6).
     *
     * <p>Allowed callers: module owner or the Host (with valid X-Owner-Token).
     */
    @DeleteMapping("/rooms/{roomId}/modules/{moduleId}/writers/{memberId}")
    public ResponseEntity<?> revokeWriteAccess(
            @PathVariable String roomId,
            @PathVariable String moduleId,
            @PathVariable String memberId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken) {

        Room room = store.get(roomId);
        if (room == null) {
            return moduleError(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Room not found.");
        }
        if (room.getMode() != RoomMode.OPEN) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Header 'X-Session-Id' is required.");
        }
        Member member = roomService.findMemberBySession(roomId, sessionId);
        if (member == null) {
            return moduleError(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session has not joined the room.");
        }

        if (memberId == null || memberId.isBlank()) {
            return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Path parameter 'member_id' is required.");
        }

        try {
            ModuleRecord module = moduleService.revokeWriteAccess(roomId, moduleId, member, ownerToken, memberId);

            // Broadcast module-updated to all connected control sockets
            controlHandler.broadcastModuleUpdated(roomId, RoomMessages.moduleUpdated(module, false));

            return ResponseEntity.ok(moduleBody(module));
        } catch (SecurityException e) {
            String code = "NOT_OWNER".equals(e.getMessage()) ? "NOT_OWNER" : "NOT_MODULE_OWNER";
            String msg = "NOT_OWNER".equals(code)
                    ? "Invalid owner token."
                    : "Only the module owner or the Host can change write access.";
            return moduleError(HttpStatus.FORBIDDEN, code, msg);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if ("PAIR_MODE_NO_MODULES".equals(msg)) {
                return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Pair mode rooms do not have modules.");
            }
            if ("OWNER_CANNOT_LOSE_WRITE_ACCESS".equals(msg)) {
                return moduleError(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "Module owner always keeps write access.");
            }
            if ("MEMBER_NOT_FOUND".equals(msg)) {
                return moduleError(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Target member not found in this room.");
            }
            HttpStatus status = ("SESSION_NOT_FOUND".equals(msg) || "ROOM_NOT_FOUND".equals(msg))
                    ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
            return moduleError(status, msg, msg);
        } catch (IllegalStateException e) {
            return moduleError(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "Module not found.");
        }
    }

    private ResponseEntity<Map<String, Object>> moduleError(HttpStatus status, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retry_after_seconds", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", null);
        body.put("error", error);
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> moduleBody(ModuleRecord module) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("module_id", module.moduleId());
        body.put("name", module.name());
        body.put("owner_member_id", module.ownerMemberId());
        body.put("writers", new ArrayList<>(module.writers()));
        body.put("runnable", module.runnable());
        body.put("is_entry", module.isEntry());
        body.put("version", module.version());
        body.put("pending", module.pending());
        return body;
    }

    private Map<String, Object> moduleUpdatedPayload(ModuleRecord module, boolean deleted) {
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
}
