package com.collabcode.execution.controller;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RoomEndedControllerTest {
    @Test
    void authenticatesValidatesAndForwardsAiCleanupIdempotently() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer fakeAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeAi.createContext("/v1/rooms/roomX/state", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Token")).isEqualTo("secret");
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        fakeAi.start();
        try {
            RoomEndedController c = new RoomEndedController("secret",
                    "http://127.0.0.1:" + fakeAi.getAddress().getPort(), HttpClient.newHttpClient());
            Map<String, Object> valid = Map.of("room_id", "roomX", "reason", "closed");
            assertThat(c.roomEnded("roomX", null, valid).getStatusCode().value()).isEqualTo(401);
            assertThat(c.roomEnded("roomX", "bad", valid).getStatusCode().value()).isEqualTo(401);
            assertThat(c.roomEnded("roomX", "secret", Map.of("room_id", "other", "reason", "closed"))
                    .getStatusCode().value()).isEqualTo(422);
            assertThat(c.roomEnded("roomX", "secret", valid).getStatusCode().value()).isEqualTo(200);
            assertThat(c.roomEnded("roomX", "secret", valid).getStatusCode().value()).isEqualTo(200);
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            fakeAi.stop(0);
        }
    }

    @Test
    void cleansUpInMemRunResultStoreWhenRoomEnds() throws Exception {
        HttpServer fakeAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeAi.createContext("/v1/rooms/roomClear/state", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        fakeAi.start();
        try {
            com.collabcode.execution.store.RunResultStore store = new com.collabcode.execution.store.RunResultStore();
            String reqId = "11111111-1111-4111-8111-111111111111";
            store.save(new com.collabcode.execution.store.StoredRun(reqId, "roomClear", "sess-1", "private", new com.collabcode.execution.model.RunResponse(), java.time.Instant.now()));
            assertThat(store.get(reqId)).isPresent();

            RoomEndedController c = new RoomEndedController("secret",
                    "http://127.0.0.1:" + fakeAi.getAddress().getPort(), HttpClient.newHttpClient(), store);
            Map<String, Object> valid = Map.of("room_id", "roomClear", "reason", "closed");
            assertThat(c.roomEnded("roomClear", "secret", valid).getStatusCode().value()).isEqualTo(200);

            assertThat(store.get(reqId)).isEmpty();
        } finally {
            fakeAi.stop(0);
        }
    }

    @Test
    void remoteFailureIsReportedAs503ForSyncRetry() throws Exception {
        HttpServer fakeAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeAi.createContext("/v1/rooms/roomY/state", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        fakeAi.start();
        try {
            RoomEndedController c = new RoomEndedController("secret",
                    "http://127.0.0.1:" + fakeAi.getAddress().getPort(), HttpClient.newHttpClient());
            assertThat(c.roomEnded("roomY", "secret", Map.of("room_id", "roomY", "reason", "deleted"))
                    .getStatusCode().value()).isEqualTo(503);
        } finally {
            fakeAi.stop(0);
        }
    }
}
