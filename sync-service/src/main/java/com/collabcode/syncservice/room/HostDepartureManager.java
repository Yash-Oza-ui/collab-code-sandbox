package com.collabcode.syncservice.room;

import com.collabcode.syncservice.config.RoomLimitsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** US-55: the single place for Host departure, return and room closure. */
@Component
public class HostDepartureManager {
    private final RoomStore store;
    private final RoomRegistry registry;
    private final RoomLimitsProperties limits;
    private final Clock clock;
    private final Map<String, PendingDeparture> pending = new HashMap<>();

    @Autowired
    public HostDepartureManager(RoomStore store, RoomRegistry registry, RoomLimitsProperties limits) {
        this(store, registry, limits, Clock.systemUTC());
    }

    // Package-private for deterministic tests with a mutable clock.
    HostDepartureManager(RoomStore store, RoomRegistry registry,
                         RoomLimitsProperties limits, Clock clock) {
        this.store = store;
        this.registry = registry;
        this.limits = limits;
        this.clock = clock;
    }

    /** Called after removing a control connection from the registry, including transport errors. */
    public synchronized void hostDisconnected(String roomId, String sessionId) {
        if (roomId == null || sessionId == null) return;
        Room room = store.get(roomId);
        if (room == null || room.isClosed()) return;
        boolean disconnectedHost = room.getMembers().values().stream()
                .anyMatch(m -> m.getRole() == Role.HOST && sessionId.equals(m.getSessionId()));
        if (disconnectedHost && !anyHostConnected(roomId, room)) {
            pending.putIfAbsent(roomId, new PendingDeparture(
                    clock.instant().plusMillis(limits.getHostLeavesTimeoutMs())));
        }
    }

    /** Successful Host join (including an owner-token reconnect) cancels the deadline. */
    public synchronized void hostReturned(String roomId, Member member) {
        if (member != null && member.getRole() == Role.HOST) pending.remove(roomId);
    }

    /** US-54: any pending departure from the old Host no longer applies. */
    public synchronized void hostTransferred(String roomId) {
        pending.remove(roomId);
    }

    /** Periodic check; the clock is injectable and tests call this directly. */
    @Scheduled(fixedDelayString = "${app.room.limits.host-leaves-check-interval-ms:1000}")
    public synchronized void checkExpiredDepartures() {
        Instant now = clock.instant();
        for (Map.Entry<String, PendingDeparture> entry : new HashMap<>(pending).entrySet()) {
            String roomId = entry.getKey();
            PendingDeparture departure = entry.getValue();
            if (!departure.deadline().isAfter(now)) {
                Room room = store.get(roomId);
                if (room != null && !room.isClosed() && hasHost(room)
                        && !anyHostConnected(roomId, room)) {
                    closeRoom(roomId);
                }
                pending.remove(roomId);
            }
        }
    }

    /** Single closure entry point for US-56 to extend with cross-service data deletion. */
    public synchronized void closeRoom(String roomId) {
        pending.remove(roomId);
        Room room = store.get(roomId);
        if (room == null || room.isClosed()) return;
        // Retain the closed tombstone so subsequent WebSocket connections get 4410.
        // US-56 will decide how tombstones survive actual room-data deletion.
        room.close();
        for (WebSocketSession session : registry.getAllSessionsInRoom(roomId)) {
            try {
                session.close(new CloseStatus(4410, "Room is closed"));
            } catch (IOException ignored) {
                // A connection can disappear during closure; finish closing the others.
            } finally {
                registry.remove(session);
            }
        }
    }

    private boolean hasHost(Room room) {
        return room.getMembers().values().stream().anyMatch(m -> m.getRole() == Role.HOST);
    }

    private boolean anyHostConnected(String roomId, Room room) {
        return registry.getJoinedControlSessions(roomId).stream().anyMatch(s ->
                room.getMembers().values().stream().anyMatch(m ->
                        m.getRole() == Role.HOST && m.getSessionId().equals(s.getAttributes().get("sessionId"))));
    }

    private record PendingDeparture(Instant deadline) { }
}
