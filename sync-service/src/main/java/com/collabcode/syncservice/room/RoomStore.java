package com.collabcode.syncservice.room;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of {@link Room} records (T-05 default: platform state in
 * sync-service). Held behind this component so a durable store can replace it
 * later without touching callers (platform-home section 5).
 */
@Component
public class RoomStore {

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public void put(Room room) {
        rooms.put(room.getRoomId(), room);
    }

    public Room get(String roomId) {
        return rooms.get(roomId);
    }

    public boolean exists(String roomId) {
        return rooms.containsKey(roomId);
    }
}
