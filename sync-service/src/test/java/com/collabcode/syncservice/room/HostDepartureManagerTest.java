package com.collabcode.syncservice.room;

import com.collabcode.syncservice.config.RoomLimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HostDepartureManagerTest {
    private static final String ROOM_ID = "Us55HostLeftRoom000000AA";
    private RoomStore store;
    private RoomRegistry registry;
    private RoomService service;
    private Room room;
    private MutableClock clock;
    private RoomLimitsProperties config;
    private HostDepartureManager manager;
    private final Map<String, CloseStatus> closedSockets = new HashMap<>();

    @BeforeEach
    void setup() {
        store = new RoomStore();
        registry = new RoomRegistry();
        room = new Room(ROOM_ID, RoomMode.PAIR, "debug_together", null, OwnerTokens.hash("owner"));
        store.put(room);
        service = new RoomService(store);
        config = new RoomLimitsProperties();
        config.setHostLeavesTimeoutMs(30_000);
        clock = new MutableClock();
        manager = new HostDepartureManager(store, registry, config, clock);
        service.setHostDepartureManager(manager);
        service.join(ROOM_ID, "host", "Host", "owner");
        service.join(ROOM_ID, "guest", "Guest", null);
    }

    @Test
    void closingAfterTimeoutClosesControlAndDocumentSocketsAndRefusesJoin() throws Exception {
        WebSocketSession host = socket("ws-host", "host", true);
        WebSocketSession guest = socket("ws-guest", "guest", true);
        WebSocketSession doc = socket("ws-doc", "guest", false);
        registry.add(ROOM_ID, host);
        registry.add(ROOM_ID, guest);
        registry.add(ROOM_ID, doc);
        registry.remove(host);
        manager.hostDisconnected(ROOM_ID, "host");
        clock.advanceMillis(29_999);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isFalse();
        clock.advanceMillis(1);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isTrue();
        assertThat(closedSockets.get("ws-guest").getCode()).isEqualTo(4410);
        assertThat(closedSockets.get("ws-doc").getCode()).isEqualTo(4410);
        assertThat(service.join(ROOM_ID, "new", "New", null).isJoined()).isFalse();
        assertThat(service.join(ROOM_ID, "new", "New", null).getRefusedReason()).isEqualTo("ROOM_CLOSED");
    }

    @Test
    void returningHostCancelsDeadline() {
        WebSocketSession host = socket("ws-host", "host", true);
        registry.add(ROOM_ID, host);
        registry.remove(host);
        manager.hostDisconnected(ROOM_ID, "host");
        registry.add(ROOM_ID, socket("ws-returned", "host", true));
        manager.hostReturned(ROOM_ID, service.join(ROOM_ID, "host", "Host", "owner").getMember());
        clock.advanceMillis(60_000);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isFalse();
    }

    @Test
    void multipleHostTabsMustBothDisconnect() {
        WebSocketSession first = socket("ws-1", "host", true);
        WebSocketSession second = socket("ws-2", "host", true);
        registry.add(ROOM_ID, first);
        registry.add(ROOM_ID, second);
        registry.remove(first);
        manager.hostDisconnected(ROOM_ID, "host");
        clock.advanceMillis(40_000);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isFalse();
        registry.remove(second);
        manager.hostDisconnected(ROOM_ID, "host");
        clock.advanceMillis(30_000);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isTrue();
    }

    @Test
    void multipleOwnerTokenSessionsDoNotCloseUntilLastHostDisconnects() {
        service.join(ROOM_ID, "host2", "Host2", "owner");
        WebSocketSession first = socket("ws-1", "host", true);
        WebSocketSession second = socket("ws-2", "host2", true);
        registry.add(ROOM_ID, first);
        registry.add(ROOM_ID, second);
        registry.remove(first);
        manager.hostDisconnected(ROOM_ID, "host");
        clock.advanceMillis(40_000);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isFalse();
        registry.remove(second);
        manager.hostDisconnected(ROOM_ID, "host2");
        clock.advanceMillis(30_000);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isTrue();
    }

    @Test
    void transferCancelsOldHostTimeoutNewHostDepartureStartsAnother() {
        WebSocketSession oldHost = socket("ws-host", "host", true);
        WebSocketSession newHost = socket("ws-guest", "guest", true);
        registry.add(ROOM_ID, oldHost);
        registry.add(ROOM_ID, newHost);
        registry.remove(oldHost);
        manager.hostDisconnected(ROOM_ID, "host");
        String guestMemberId = service.findMemberBySession(ROOM_ID, "guest").getMemberId();
        service.transferHost(ROOM_ID, guestMemberId, OwnerTokens.hash("new-owner"));
        clock.advanceMillis(60_000);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isFalse();
        registry.remove(newHost);
        manager.hostDisconnected(ROOM_ID, "guest");
        clock.advanceMillis(30_000);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isTrue();
    }

    @Test
    void changedConfigurationControlsDeadline() {
        config.setHostLeavesTimeoutMs(5_000);
        manager.hostDisconnected(ROOM_ID, "host");
        clock.advanceMillis(4_999);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isFalse();
        clock.advanceMillis(1);
        manager.checkExpiredDepartures();
        assertThat(room.isClosed()).isTrue();
    }

    /** A JDK proxy avoids Mockito / Byte Buddy instrumentation of WebSocketSession.
     *  This keeps the tests compatible with newer JDKs used in CI.
     */
    private WebSocketSession socket(String id, String clientSessionId, boolean control) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sessionId", clientSessionId);
        attributes.put("joined", control);
        if (!control) attributes.put("docId", "module-1");
        return (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getId": return id;
                        case "getAttributes": return attributes;
                        case "isOpen": return !closedSockets.containsKey(id);
                        case "close":
                            closedSockets.put(id, args != null && args.length > 0
                                    ? (CloseStatus) args[0] : CloseStatus.NORMAL);
                            return null;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "TestWebSocketSession(" + id + ")";
                        default: throw new UnsupportedOperationException(
                                "Unexpected WebSocketSession method in test: " + method.getName());
                    }
                });
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T10:00:00Z");
        void advanceMillis(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
