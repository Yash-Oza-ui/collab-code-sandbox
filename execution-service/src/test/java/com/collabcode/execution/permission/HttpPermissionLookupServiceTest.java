package com.collabcode.execution.permission;

import com.collabcode.execution.exception.RoomNotFoundException;
import com.collabcode.execution.exception.RoomServiceUnavailableException;
import com.collabcode.execution.exception.RunNotAllowedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HttpPermissionLookupService} against a fake sync-service (US-60, V2.11, FR41.4).
 * Uses the JDK's built-in HTTP server, so no extra test dependency is needed.
 */
class HttpPermissionLookupServiceTest {

    private static final String TOKEN = "test-internal-token";
    private static final String ROOM = "Zq8-Lw2Ef6Th0Yx4Rn7JbA";
    private static final String SESSION = "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getRawPath());
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpPermissionLookupService service() {
        return new HttpPermissionLookupService(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), TOKEN);
    }

    private void answer(int httpStatus, String json) {
        status.set(httpStatus);
        body.set(json);
    }

    @Test
    void allowedAnswer_isReturnedAsIs_andTheRequestIsAuthenticated() {
        answer(200, """
                {"member_id":"m_7a3c9f","display_name":"Ada","role":"participant","mode":"pair",
                 "language":null,"can_run":true,"run_denied_reason":null,"ai_enabled":true}""");

        PermissionLookupResult r = service().lookup(ROOM, SESSION);

        assertThat(r.isCan_run()).isTrue();
        assertThat(r.getRun_denied_reason()).isNull();
        assertThat(r.getMember_id()).isEqualTo("m_7a3c9f");
        assertThat(r.getDisplay_name()).isEqualTo("Ada");
        assertThat(r.getRole()).isEqualTo("participant");
        assertThat(r.getMode()).isEqualTo("pair");
        assertThat(r.getLanguage()).isNull();
        assertThat(r.isAi_enabled()).isTrue();
        assertThat(lastPath.get()).isEqualTo("/internal/rooms/" + ROOM + "/sessions/" + SESSION);
        assertThat(lastToken.get()).isEqualTo(TOKEN);
    }

    @Test
    void refusedAnswers_carryEachReason() {
        for (String reason : new String[]{"observer", "run_revoked", "host_only"}) {
            answer(200, """
                    {"member_id":"m_4b19d2","display_name":"Lin","role":"participant","mode":"open",
                     "language":"python","can_run":false,"run_denied_reason":"%s","ai_enabled":false}"""
                    .formatted(reason));

            PermissionLookupResult r = service().lookup(ROOM, SESSION);

            assertThat(r.isCan_run()).isFalse();
            assertThat(r.getRun_denied_reason()).isEqualTo(reason);
            assertThat(r.getLanguage()).isEqualTo("python");
        }
    }

    @Test
    void everyLookupAsksSyncService_nothingIsCached() {
        answer(200, """
                {"member_id":"m_1","display_name":"Ada","role":"participant","mode":"pair",
                 "language":null,"can_run":true,"run_denied_reason":null,"ai_enabled":false}""");
        HttpPermissionLookupService service = service();
        assertThat(service.lookup(ROOM, SESSION).isCan_run()).isTrue();

        // The Host takes Run away: the very next lookup must see it.
        answer(200, """
                {"member_id":"m_1","display_name":"Ada","role":"participant","mode":"pair",
                 "language":null,"can_run":false,"run_denied_reason":"run_revoked","ai_enabled":false}""");
        PermissionLookupResult after = service.lookup(ROOM, SESSION);

        assertThat(after.isCan_run()).isFalse();
        assertThat(after.getRun_denied_reason()).isEqualTo("run_revoked");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void roomNotFound_mapsToRoomNotFound() {
        answer(404, "{\"request_id\":null,\"error\":{\"code\":\"ROOM_NOT_FOUND\",\"message\":\"x\"}}");

        assertThatThrownBy(() -> service().lookup(ROOM, SESSION)).isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void sessionNotFound_mapsToNotInRoom() {
        answer(404, "{\"request_id\":null,\"error\":{\"code\":\"SESSION_NOT_FOUND\",\"message\":\"x\"}}");

        assertThatThrownBy(() -> service().lookup(ROOM, SESSION))
                .isInstanceOfSatisfying(RunNotAllowedException.class,
                        e -> assertThat(e.getReason()).isEqualTo("not_in_room"));
    }

    @Test
    void failsClosed_onAnythingThatIsNotAClearAnswer() {
        String[][] cases = {
                {"401", "{\"error\":{\"code\":\"UNAUTHORIZED\"}}"},
                {"500", "{}"},
                {"503", ""},
                {"404", "not json"},
                {"404", "{\"error\":{\"code\":\"SOMETHING_ELSE\"}}"},
                {"200", "not json"},
                {"200", "[]"},
                {"200", "{}"},
                {"200", "{\"can_run\":\"true\"}"},
                {"200", "{\"can_run\":null}"},
        };
        for (String[] c : cases) {
            answer(Integer.parseInt(c[0]), c[1]);
            assertThatThrownBy(() -> service().lookup(ROOM, SESSION))
                    .as("status %s body %s", c[0], c[1])
                    .isInstanceOf(RoomServiceUnavailableException.class);
        }
    }

    @Test
    void failsClosed_whenSyncServiceIsNotReachable() {
        int port = server.getAddress().getPort();
        server.stop(0);
        HttpPermissionLookupService down =
                new HttpPermissionLookupService(new ObjectMapper(), "http://127.0.0.1:" + port, TOKEN);

        assertThatThrownBy(() -> down.lookup(ROOM, SESSION)).isInstanceOf(RoomServiceUnavailableException.class);
    }

    @Test
    void roomIdIsEncoded_soItCannotReachAnotherPath() {
        answer(200, """
                {"member_id":"m_1","display_name":"Ada","role":"participant","mode":"pair",
                 "language":null,"can_run":true,"run_denied_reason":null,"ai_enabled":false}""");

        service().lookup("a/b?x=1", SESSION);

        assertThat(lastPath.get()).startsWith("/internal/rooms/a%2Fb%3F");
        assertThat(lastPath.get()).endsWith("/sessions/" + SESSION);
    }
}
