package com.collabcode.syncservice.handler;

import com.collabcode.syncservice.module.ModuleService;
import com.collabcode.syncservice.room.JoinOutcome;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Us03RegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static WebSocketSession mockSession(String wsId, String roomId, String sessionId,
                                                String docId, boolean joined) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("roomId", roomId);
        attrs.put("sessionId", sessionId);
        if (docId != null) {
            attrs.put("docId", docId);
        }
        attrs.put("joined", joined);
        when(s.getId()).thenReturn(wsId);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(attrs);
        return s;
    }

    @Test
    void reconnectPreservesObserverRoleAndRestrictedRunPermissionAcrossTabs() throws Exception {
        RoomStore store = new RoomStore();
        Room room = new Room("room1", RoomMode.PAIR, "default", "python", "hash");
        store.put(room);
        RoomService roomService = new RoomService(store);
        RoomRegistry registry = new RoomRegistry();
        ControlHandler control = new ControlHandler(store, roomService, registry, MAPPER);

        String obsSessionId = "11111111-1111-4111-8111-111111111111";
        JoinOutcome firstJoin = roomService.join("room1", obsSessionId, "Alice", null);
        Member original = firstJoin.getMember();
        roomService.changeRole("room1", original.getMemberId(), Role.OBSERVER);

        String restrictedSessionId = "22222222-2222-4222-8222-222222222222";
        Member restricted = roomService.join("room1", restrictedSessionId, "Bob", null).getMember();
        restricted.setCanRun(false);

        WebSocketSession tab1 = mockSession("ws-1", "room1", obsSessionId, null, true);
        WebSocketSession tab2 = mockSession("ws-2", "room1", obsSessionId, null, true);
        registry.add("room1", tab1);
        registry.add("room1", tab2);

        control.afterConnectionClosed(tab1, CloseStatus.NORMAL);
        assertThat(registry.getJoinedControlSessions("room1")).containsExactly(tab2);

        control.afterConnectionClosed(tab2, CloseStatus.NORMAL);
        assertThat(registry.getJoinedControlSessions("room1")).isEmpty();

        // Reconnect must preserve member_id, OBSERVER role, and restricted canRun
        JoinOutcome obsReconnect = roomService.join("room1", obsSessionId, "Alice", null);
        assertThat(obsReconnect.isJoined()).isTrue();
        assertThat(obsReconnect.getMember().getMemberId()).isEqualTo(original.getMemberId());
        assertThat(obsReconnect.getMember().getRole()).isEqualTo(Role.OBSERVER);
        assertThat(obsReconnect.getMember().isCanEdit()).isFalse();
        assertThat(obsReconnect.getMember().isCanRun()).isFalse();

        JoinOutcome bobReconnect = roomService.join("room1", restrictedSessionId, "Bob", null);
        assertThat(bobReconnect.getMember().getMemberId()).isEqualTo(restricted.getMemberId());
        assertThat(bobReconnect.getMember().isCanRun()).isFalse();
    }

    @Test
    void broadcastRosterExcludesUnjoinedAndApprovalPendingSockets() throws Exception {
        RoomStore store = new RoomStore();
        Room room = new Room("room2", RoomMode.PAIR, "default", "python", "hash");
        store.put(room);
        RoomService roomService = new RoomService(store);
        RoomRegistry registry = new RoomRegistry();
        ControlHandler control = new ControlHandler(store, roomService, registry, MAPPER);

        roomService.join("room2", "11111111-1111-4111-8111-111111111111", "Host", null);

        WebSocketSession joined = mockSession("ws-joined", "room2", "11111111-1111-4111-8111-111111111111", null, true);
        WebSocketSession unjoined = mockSession("ws-unjoined", "room2", "22222222-2222-4222-8222-222222222222", null, false);
        WebSocketSession pending = mockSession("ws-pending", "room2", "33333333-3333-4333-8333-333333333333", null, false);

        registry.add("room2", joined);
        registry.add("room2", unjoined);
        registry.add("room2", pending);

        control.broadcastRoster("room2");

        verify(joined, times(1)).sendMessage(any(TextMessage.class));
        verify(unjoined, never()).sendMessage(any(TextMessage.class));
        verify(pending, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void documentHandlerRewritesForgedAwarenessIdentityAndDropsHijackedClientId() throws Exception {
        RoomStore store = new RoomStore();
        Room room = new Room("room3", RoomMode.PAIR, "default", "python", "hash");
        store.put(room);
        RoomService roomService = new RoomService(store);
        RoomRegistry registry = new RoomRegistry();
        DocumentHandler docHandler = new DocumentHandler(
                store, roomService, mock(ModuleService.class), registry);

        String hostSession = "11111111-1111-4111-8111-111111111111";
        String attackerSession = "22222222-2222-4222-8222-222222222222";
        Member host = roomService.join("room3", hostSession, "Real Host", null).getMember();
        Member attacker = roomService.join("room3", attackerSession, "Attacker", null).getMember();

        WebSocketSession hostWs = mockSession("ws-host", "room3", hostSession, "main", true);
        WebSocketSession attackerWs = mockSession("ws-attacker", "room3", attackerSession, "main", true);
        registry.add("room3", hostWs);
        registry.add("room3", attackerWs);

        String forgedJson = String.format(
                "{\"user\":{\"name\":\"Real Host\",\"color\":\"#2563eb\",\"member_id\":\"%s\",\"role\":\"HOST\"}}",
                host.getMemberId());
        byte[] forgedFrame = buildAwarenessFrame(42L, 1L, forgedJson);

        docHandler.handleBinaryMessage(attackerWs, new BinaryMessage(forgedFrame));

        ArgumentCaptor<BinaryMessage> captor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(hostWs, times(1)).sendMessage(captor.capture());

        String relayedJson = extractFirstAwarenessJson(captor.getValue().getPayload());
        JsonNode userNode = MAPPER.readTree(relayedJson).get("user");
        assertThat(userNode.get("member_id").asText()).isEqualTo(attacker.getMemberId());
        assertThat(userNode.get("name").asText()).isEqualTo("Attacker");
        assertThat(userNode.has("role")).isFalse();

        // Host trying to hijack Attacker's already-bound clientId=42 must be dropped
        byte[] hijackFrame = buildAwarenessFrame(42L, 2L, "{\"user\":{\"name\":\"Hijack\"}}");
        docHandler.handleBinaryMessage(hostWs, new BinaryMessage(hijackFrame));
        verify(attackerWs, times(1)).sendMessage(any(BinaryMessage.class));
    }

    private static byte[] buildAwarenessFrame(long clientId, long clock, String json) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarUint(payload, 1);
        writeVarUint(payload, clientId);
        writeVarUint(payload, clock);
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        writeVarUint(payload, utf8.length);
        payload.write(utf8);

        byte[] p = payload.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarUint(frame, 1);
        writeVarUint(frame, p.length);
        frame.write(p);
        return frame.toByteArray();
    }

    private static String extractFirstAwarenessJson(ByteBuffer buf) {
        byte[] b = new byte[buf.remaining()];
        buf.duplicate().get(b);
        int[] pos = new int[] { 0 };
        readVarUint(b, pos); // type=1
        readVarUint(b, pos); // payloadLen
        readVarUint(b, pos); // count
        readVarUint(b, pos); // clientId
        readVarUint(b, pos); // clock
        int len = (int) readVarUint(b, pos);
        return new String(b, pos[0], len, StandardCharsets.UTF_8);
    }

    private static long readVarUint(byte[] buf, int[] pos) {
        long num = 0;
        int mult = 1;
        while (pos[0] < buf.length) {
            int v = buf[pos[0]++] & 0xff;
            num += (long) (v & 0x7f) * mult;
            if (v < 0x80) return num;
            mult *= 128;
        }
        return num;
    }

    private static void writeVarUint(ByteArrayOutputStream out, long num) {
        while (num > 0x7f) {
            out.write((int) (0x80 | (num & 0x7f)));
            num >>>= 7;
        }
        out.write((int) num);
    }
}