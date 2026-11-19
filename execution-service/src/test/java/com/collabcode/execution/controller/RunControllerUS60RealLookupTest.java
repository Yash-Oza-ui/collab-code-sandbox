package com.collabcode.execution.controller;

import com.collabcode.execution.broadcast.RoomBroadcastService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-60 (FR41.1 to FR41.4) end to end inside execution-service: POST /v1/runs with the
 * REAL permission lookup (no {@code stub-permissions} profile) against a fake sync-service.
 * Checks the refusal and its reason for each case, and that a change made by the Host is
 * seen by the very next run (the lookup is never cached).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("stub-runner")
class RunControllerUS60RealLookupTest {

    private static final String TOKEN = "us60-internal-token";
    private static final String REQUEST_ID = "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10";
    private static final String ROOM_ID = "room-pair-us60";
    private static final String SESSION = "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90";
    private static final String STRANGER = "11111111-1111-4111-8111-111111111111";

    /** What the fake sync-service answers per session id: a JSON body (200) or null for SESSION_NOT_FOUND. */
    private static final Map<String, String> ANSWERS = new ConcurrentHashMap<>();
    private static final HttpServer SYNC;

    static {
        try {
            SYNC = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SYNC.createContext("/internal/rooms/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status;
            String body;
            if (!TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"))) {
                status = 401;
                body = "{\"error\":{\"code\":\"UNAUTHORIZED\"}}";
            } else if (path.endsWith("/messages")) {
                status = 200; // result broadcast, not under test
                body = "{\"delivered\":0}";
            } else if (path.contains("/rooms/room-missing/")) {
                status = 404;
                body = "{\"error\":{\"code\":\"ROOM_NOT_FOUND\"}}";
            } else {
                String session = path.substring(path.lastIndexOf('/') + 1);
                String answer = ANSWERS.get(session);
                if (answer == null) {
                    status = 404;
                    body = "{\"error\":{\"code\":\"SESSION_NOT_FOUND\"}}";
                } else {
                    status = 200;
                    body = answer;
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        SYNC.start();
    }

    @DynamicPropertySource
    static void syncService(DynamicPropertyRegistry registry) {
        registry.add("app.sync-service.internal-url", () -> "http://127.0.0.1:" + SYNC.getAddress().getPort());
        registry.add("app.sync-service.internal-token", () -> TOKEN);
    }

    @AfterAll
    static void stopSync() {
        SYNC.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoomBroadcastService broadcastService;

    private static String lookupAnswer(boolean canRun, String reason, String role) {
        return "{\"member_id\":\"m_4b19d2\",\"display_name\":\"Lin\",\"role\":\"" + role + "\","
                + "\"mode\":\"pair\",\"language\":null,\"can_run\":" + canRun + ","
                + "\"run_denied_reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + ","
                + "\"ai_enabled\":false}";
    }

    @BeforeEach
    void reset() {
        ANSWERS.clear();
    }

    private org.springframework.test.web.servlet.ResultActions run(String roomId, String sessionId)
            throws Exception {
        String body = """
                {
                  "request_id": "%s",
                  "room_id":    "%s",
                  "session_id": "%s",
                  "language":   "python",
                  "source": {
                    "kind": "inline",
                    "files": [{ "name": "main.py", "content": "print(1)" }],
                    "entry": "main.py"
                  }
                }
                """.formatted(REQUEST_ID, roomId, sessionId);
        return mockMvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("Allowed participant runs: 200 with the runner from the lookup")
    void allowed_runs() throws Exception {
        ANSWERS.put(SESSION, lookupAnswer(true, null, "participant"));

        run(ROOM_ID, SESSION)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runner.member_id").value("m_4b19d2"));
    }

    @Test
    @DisplayName("Each refusal reason becomes 403 RUN_NOT_ALLOWED with error.reason (FR41.4)")
    void refusals_carryTheReason() throws Exception {
        String[][] cases = {
                {"observer", "observer", "Observers cannot run code."},
                {"run_revoked", "participant", "The Host has turned off running for you."},
                {"host_only", "participant", "Only the Host can run code in this room right now."},
        };
        for (String[] c : cases) {
            ANSWERS.put(SESSION, lookupAnswer(false, c[0], c[1]));

            run(ROOM_ID, SESSION)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.request_id").value(REQUEST_ID))
                    .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.error.reason").value(c[0]))
                    .andExpect(jsonPath("$.error.message").value(c[2]));
        }
    }

    @Test
    @DisplayName("A change by the Host is seen by the next run: no caching")
    void change_takesEffectAtOnce_andBack() throws Exception {
        ANSWERS.put(SESSION, lookupAnswer(true, null, "participant"));
        run(ROOM_ID, SESSION).andExpect(status().isOk());

        ANSWERS.put(SESSION, lookupAnswer(false, "run_revoked", "participant"));
        run(ROOM_ID, SESSION)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.reason").value("run_revoked"));

        ANSWERS.put(SESSION, lookupAnswer(true, null, "participant"));
        run(ROOM_ID, SESSION).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Session not in the room: 403 RUN_NOT_ALLOWED reason not_in_room")
    void sessionNotInRoom() throws Exception {
        run(ROOM_ID, STRANGER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.reason").value("not_in_room"));
    }

    @Test
    @DisplayName("Unknown room: 404 ROOM_NOT_FOUND")
    void unknownRoom() throws Exception {
        run("room-missing", SESSION)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    @DisplayName("A lookup that is not a clear answer refuses the run: 503 ROOM_SERVICE_UNAVAILABLE")
    void notAClearAnswer_isUnavailable() throws Exception {
        ANSWERS.put(SESSION, "{\"member_id\":\"m_1\",\"display_name\":\"Ada\",\"mode\":\"pair\"}"); // no can_run

        run(ROOM_ID, SESSION)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ROOM_SERVICE_UNAVAILABLE"));
    }
}
