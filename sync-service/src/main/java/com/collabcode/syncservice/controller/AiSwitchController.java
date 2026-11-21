package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMessages;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;

/** US-59 (FR35): Host-only AI switch. Server controls state and broadcasts room changes. */
@RestController
@RequestMapping("/v1")
public class AiSwitchController {
    private final HostGuard hostGuard;
    private final RoomService roomService;
    private final RoomRegistry registry;
    private final ObjectMapper mapper;

    public AiSwitchController(HostGuard hostGuard, RoomService roomService,
                              RoomRegistry registry, ObjectMapper mapper) {
        this.hostGuard = hostGuard;
        this.roomService = roomService;
        this.registry = registry;
        this.mapper = mapper;
    }

    @PatchMapping("/rooms/{roomId}/ai-switch")
    public ResponseEntity<?> setAiSwitch(
            @PathVariable String roomId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-Owner-Token", required = false) String ownerToken,
            @RequestBody(required = false) String rawBody) {
        HostGuard.Result guard = hostGuard.requireHost(roomId, sessionId, ownerToken);
        if (!guard.ok()) return guard.error();

        JsonNode root;
        try {
            root = rawBody == null ? null : mapper.readTree(rawBody);
        } catch (IOException ex) {
            root = null;
        }
        if (root == null || !root.isObject() || root.size() != 1
                || !root.has("ai_enabled") || !root.get("ai_enabled").isBoolean()) {
            return HostGuard.error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
                    "Body must contain exactly one boolean field 'ai_enabled'.");
        }
        boolean enabled = root.get("ai_enabled").booleanValue();
        Room room = roomService.setAiEnabled(roomId, enabled);
        if (room == null) {
            return HostGuard.error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND",
                    "Room not found or already closed.");
        }

        // Send only to joined control sockets; no client decides the value locally.
        try {
            String message = mapper.writeValueAsString(RoomMessages.roomState(room));
            for (WebSocketSession target : registry.getJoinedControlSessions(roomId)) {
                try {
                    synchronized (target) {
                        if (target.isOpen()) target.sendMessage(new TextMessage(message));
                    }
                } catch (IOException ignored) {
                    // A disconnected client will receive the current value on its next join.
                }
            }
        } catch (IOException ignored) {
            // The state is authoritative even if notifications are temporarily unavailable.
        }
        return ResponseEntity.ok(Map.of("ai_enabled", room.isAiEnabled()));
    }
}
