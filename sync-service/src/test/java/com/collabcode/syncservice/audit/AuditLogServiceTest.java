package com.collabcode.syncservice.audit;

import com.collabcode.syncservice.config.AuditLogProperties;
import com.collabcode.syncservice.handler.ControlHandler;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditLogServiceTest {

    private RoomStore roomStore;
    private ControlHandler controlHandler;
    private AuditLogProperties properties;
    private AuditLogService auditLogService;
    private Room room;

    private final Member host = new Member("m_host", "s_host", "HostUser", Role.HOST, true, true);
    private final Member guest = new Member("m_guest", "s_guest", "GuestUser", Role.PARTICIPANT, false, true);

    @BeforeEach
    void setUp() {
        roomStore = new RoomStore();
        controlHandler = mock(ControlHandler.class);
        properties = new AuditLogProperties();
        properties.setMaxEntries(3); // Small bound to test dropping oldest
        auditLogService = new AuditLogService(roomStore, controlHandler, properties);

        room = new Room("testRoom123456789012", RoomMode.PAIR, "two_browsers", "python", "hash");
        room.getMembers().put(host.getMemberId(), host);
        room.getMembers().put(guest.getMemberId(), guest);
        roomStore.put(room);
    }

    @Test
    void record_createsEntry_andBroadcastsToControlHandler() {
        Room.AuditLogEntry entry = auditLogService.record(
                room.getRoomId(),
                "role_change",
                host,
                guest,
                guest.getDisplayName(),
                guest.getMemberId(),
                "role",
                "participant",
                "observer",
                null
        );

        assertThat(entry).isNotNull();
        assertThat(entry.id()).startsWith("aud_");
        assertThat(entry.action()).isEqualTo("role_change");
        assertThat(entry.actorDisplayName()).isEqualTo("HostUser");
        assertThat(entry.actorMemberId()).isEqualTo("m_host");
        assertThat(entry.targetDisplayName()).isEqualTo("GuestUser");
        assertThat(entry.targetMemberId()).isEqualTo("m_guest");
        assertThat(entry.property()).isEqualTo("role");
        assertThat(entry.oldValue()).isEqualTo("participant");
        assertThat(entry.newValue()).isEqualTo("observer");
        assertThat(entry.timestamp()).isNotNull();

        verify(controlHandler).broadcastAuditEntry(eq(room.getRoomId()), any(Room.AuditLogEntry.class));

        List<Room.AuditLogEntry> logs = room.getAuditLog();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).id()).isEqualTo(entry.id());
    }

    @Test
    void record_dropsOldestWhenCapacityExceeded() {
        // Capacity is 3
        auditLogService.record(room.getRoomId(), "action1", host, guest, "Guest", "m_g", "prop", "0", "1", null);
        auditLogService.record(room.getRoomId(), "action2", host, guest, "Guest", "m_g", "prop", "1", "2", null);
        auditLogService.record(room.getRoomId(), "action3", host, guest, "Guest", "m_g", "prop", "2", "3", null);

        List<Room.AuditLogEntry> logs = room.getAuditLog();
        assertThat(logs).hasSize(3);
        // Newest first
        assertThat(logs.get(0).action()).isEqualTo("action3");
        assertThat(logs.get(2).action()).isEqualTo("action1");

        // Add 4th entry; action1 should be dropped
        auditLogService.record(room.getRoomId(), "action4", host, guest, "Guest", "m_g", "prop", "3", "4", null);

        List<Room.AuditLogEntry> updatedLogs = room.getAuditLog();
        assertThat(updatedLogs).hasSize(3);
        assertThat(updatedLogs.get(0).action()).isEqualTo("action4");
        assertThat(updatedLogs.get(1).action()).isEqualTo("action3");
        assertThat(updatedLogs.get(2).action()).isEqualTo("action2");
    }

    @Test
    void record_returnsNullForUnknownOrClosedRoom() {
        Room.AuditLogEntry nullUnknown = auditLogService.record(
                "unknownRoom", "action", host, guest, "Guest", "m_g", "prop", "0", "1", null
        );
        assertThat(nullUnknown).isNull();

        room.close();
        Room.AuditLogEntry nullClosed = auditLogService.record(
                room.getRoomId(), "action", host, guest, "Guest", "m_g", "prop", "0", "1", null
        );
        assertThat(nullClosed).isNull();
    }
}
