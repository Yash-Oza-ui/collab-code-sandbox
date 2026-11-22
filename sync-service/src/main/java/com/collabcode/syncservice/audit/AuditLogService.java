package com.collabcode.syncservice.audit;

import com.collabcode.syncservice.config.AuditLogProperties;
import com.collabcode.syncservice.handler.ControlHandler;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service managing room audit log entries for role and permission changes (US-58, V2.23; FR34).
 *
 * <p>Entries are server-written only and record successful mutations of roles,
 * edit access (Pair mode), module write access (Open mode), and run permissions.
 */
@Service
public class AuditLogService {

    private final RoomStore roomStore;
    private final ControlHandler controlHandler;
    private final AuditLogProperties properties;
    private final AtomicLong counter = new AtomicLong(0);

    @Autowired
    public AuditLogService(
            RoomStore roomStore,
            @Autowired(required = false) ControlHandler controlHandler,
            AuditLogProperties properties) {
        this.roomStore = roomStore;
        this.controlHandler = controlHandler;
        this.properties = properties;
    }

    /**
     * Record a successful role or permission change in the room's audit log and
     * broadcast an audit-entry message over the control socket (FR34).
     *
     * <p>Neither code, user text, tokens, nor private session_ids are ever recorded (NFR8.2).
     */
    public Room.AuditLogEntry record(
            String roomId,
            String action,
            Member actor,
            Member target,
            String targetDisplayName,
            String targetMemberId,
            String property,
            Object oldValue,
            Object newValue,
            String details) {

        Room room = roomStore.get(roomId);
        if (room == null || room.isClosed()) {
            return null;
        }

        String actorName = actor != null ? actor.getDisplayName() : "Host";
        String actorId = actor != null ? actor.getMemberId() : null;

        String tgtName = target != null ? target.getDisplayName() : targetDisplayName;
        String tgtId = target != null ? target.getMemberId() : targetMemberId;

        String id = "aud_" + System.currentTimeMillis() + "_" + counter.incrementAndGet();
        Room.AuditLogEntry entry = new Room.AuditLogEntry(
                id,
                Instant.now(),
                action,
                actorName,
                actorId,
                tgtName,
                tgtId,
                property,
                oldValue,
                newValue,
                details
        );

        int maxEntries = properties != null ? properties.getMaxEntries() : 100;
        room.addAuditEntry(entry, maxEntries);

        if (controlHandler != null) {
            controlHandler.broadcastAuditEntry(roomId, entry);
        }

        return entry;
    }
}
