package com.collabcode.syncservice.room;

import com.collabcode.syncservice.module.ModuleService;
import com.collabcode.syncservice.run.RunRecordStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/** US-56: one local teardown path, followed by non-blocking remote notification. */
@Component
public class RoomCleanupCoordinator {
    private final RoomStore rooms;
    private final RoomRegistry registry;
    private final ModuleService modules;
    private final RunRecordStore runs;
    private final RoomEndedNotifier notifier;

    public RoomCleanupCoordinator(RoomStore rooms, RoomRegistry registry, ModuleService modules,
                                  RunRecordStore runs, RoomEndedNotifier notifier) {
        this.rooms = rooms;
        this.registry = registry;
        this.modules = modules;
        this.runs = runs;
        this.notifier = notifier;
    }

    /** Idempotently end a room; retain an empty tombstone for WebSocket 4410. */
    public synchronized boolean endRoom(String roomId, String reason) {
        Room room = rooms.get(roomId);
        if (room == null) return false;
        if (room.isClosed()) return true;
        // Mark closed first, before changing any maps, to reject racing joins.
        room.close();
        for (WebSocketSession session : registry.getAllSessionsInRoom(roomId)) {
            try {
                session.close(new CloseStatus(4410, "Room is closed"));
            } catch (IOException ignored) {
                // Continue cleanup if a client disconnected mid-close.
            } finally {
                registry.remove(session);
            }
        }
        registry.clearRoom(roomId);
        modules.deleteByRoom(roomId);
        runs.deleteByRoom(roomId);
        room.clearSensitiveState();
        // Network traffic and retry never block local teardown.
        notifier.queueRoomEnded(roomId, reason);
        return true;
    }
}
