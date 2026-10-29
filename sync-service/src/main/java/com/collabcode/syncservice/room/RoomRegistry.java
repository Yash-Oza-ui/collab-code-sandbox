package com.collabcode.syncservice.room;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry that maps room IDs to their active WebSocket sessions.
 *
 * <p>Sprint 1: no document state is kept here. The relay stores nothing
 * except which sessions are currently open in which room.
 *
 * <p>Sprint 2 note: each connection stores its stable {@code session_id}
 * (supplied by the client) as a session attribute under the key
 * {@code "sessionId"}. FR9.6 will use that value to block a removed user
 * without requiring it to be stored here.
 */
@Component
public class RoomRegistry {

    /** roomId -> live sessions in that room. */
    private final Map<String, List<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    /** WebSocket session ID -> room ID, for O(1) removal on disconnect. */
    private final Map<String, String> sessionToRoom = new ConcurrentHashMap<>();

    /**
     * Register a new connection in a room.
     *
     * @param roomId  the validated room id from the URL path
     * @param session the newly established WebSocket session
     */
    public void add(String roomId, WebSocketSession session) {
        rooms.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(session);
        sessionToRoom.put(session.getId(), roomId);
    }

    /**
     * Remove a disconnected or errored session from its room.
     * Safe to call more than once for the same session.
     *
     * @param session the session to remove
     */
    public void remove(WebSocketSession session) {
        String roomId = sessionToRoom.remove(session.getId());
        if (roomId == null) {
            return; // already removed or never registered (e.g. rejected in afterConnectionEstablished)
        }
        List<WebSocketSession> sessions = rooms.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                rooms.remove(roomId, sessions); // only remove if still empty
            }
        }
    }

    /**
     * Return all open sessions in the room <em>except</em> the sender.
     * Used for normal binary relay (contract section 3.5 rule 1).
     *
     * @param roomId room to look up
     * @param sender session to exclude from the result
     * @return snapshot list; may be empty but never null
     */
    public List<WebSocketSession> getOthers(String roomId, WebSocketSession sender) {
        List<WebSocketSession> raw = rooms.getOrDefault(roomId, Collections.emptyList());
        List<WebSocketSession> result = new ArrayList<>(raw.size());
        for (WebSocketSession s : raw) {
            if (s.isOpen() && !s.getId().equals(sender.getId())) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * Return all open sessions in a room, including any that might be the
     * originator. Used by the internal broadcast endpoint (section 5) which
     * must deliver to <em>every</em> connection including the caller's.
     *
     * @param roomId room to look up
     * @return snapshot list; empty (not null) when the room has no connections
     */
    public List<WebSocketSession> getAll(String roomId) {
        List<WebSocketSession> raw = rooms.getOrDefault(roomId, Collections.emptyList());
        List<WebSocketSession> result = new ArrayList<>(raw.size());
        for (WebSocketSession s : raw) {
            if (s.isOpen()) {
                result.add(s);
            }
        }
        return result;
    }
}
