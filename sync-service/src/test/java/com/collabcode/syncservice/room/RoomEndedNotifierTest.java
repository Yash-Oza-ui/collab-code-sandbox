package com.collabcode.syncservice.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RoomEndedNotifierTest {
    @Test
    void retriesTransientFailureAndStopsAfterSuccessWithoutDuplicatingPayload() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/rooms/testRoom/ended", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Token")).isEqualTo("secret");
            int n = calls.incrementAndGet();
            exchange.sendResponseHeaders(n == 1 ? 503 : 200, -1);
            exchange.close();
        });
        server.start();
        try {
            RoomEndedNotifier notifier = new RoomEndedNotifier(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "secret", 0, new ObjectMapper(), HttpClient.newHttpClient());
            notifier.queueRoomEnded("testRoom", "closed");
            notifier.retryPending();
            notifier.retryPending();
            notifier.retryPending();
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }
}
