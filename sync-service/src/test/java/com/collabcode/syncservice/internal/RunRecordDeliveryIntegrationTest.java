package com.collabcode.syncservice.internal;

import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.collabcode.syncservice.run.InputHasher;
import com.collabcode.syncservice.run.RunRecord;
import com.collabcode.syncservice.run.RunRecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for US-52 run-record persistence on internal delivery
 * ({@code POST /internal/rooms/{room_id}/messages}), querying ({@code GET /internal/rooms/{room_id}/runs}),
 * deletion ({@code DELETE /internal/rooms/{room_id}/runs} and room close), and secret exclusion.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "INTERNAL_API_TOKEN=test-token-us52-run-record"
        }
)
class RunRecordDeliveryIntegrationTest {

    private static final String TOKEN = "test-token-us52-run-record";
    private static final String SESSION_1 = "11111111-1111-4111-8111-111111111111";

    @LocalServerPort
    private int port;

    @Autowired
    private RoomStore roomStore;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RunRecordStore runRecordStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Delivered V2.9 run-result is recorded with all FR29 fields and internal fields stripped from WS frame")
    void deliveredRunResult_isRecordedAndInternalFieldsStrippedFromWs() throws Exception {
        String roomId = "room-us52-delivery-ws";
        roomStore.put(new Room(roomId, RoomMode.PAIR, "debug_together", null, OwnerTokens.hash("tok-ws")));
        roomService.join(roomId, SESSION_1, "Ada", "tok-ws");

        String inputHash = InputHasher.hash("42\n");
        BlockingQueue<String> wsMessages = new LinkedBlockingDeque<>();
        WebSocketSession ws = connectControlSocket(roomId, SESSION_1, wsMessages);
        try {
            String body = """
                    {
                      "audience": { "kind": "room" },
                      "type": "run-result",
                      "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
                      "room_id": "%s",
                      "language": "python",
                      "stdout": "42\\n",
                      "stderr": "",
                      "exit_code": 0,
                      "execution_time_ms": 38.5,
                      "peak_memory_mb": 7.9,
                      "timed_out": false,
                      "oom_killed": false,
                      "failure_kind": null,
                      "output_truncated": false,
                      "message": null,
                      "compile_time_ms": null,
                      "runner": {
                        "member_id": "m_7a3c9f",
                        "display_name": "Ada"
                      },
                      "ran_at": "2026-11-01T10:20:05.087Z",
                      "visibility": "room",
                      "saved_versions": [],
                      "failure": null,
                      "input_hash": "%s",
                      "files": ["main.py"]
                    }
                    """.formatted(roomId, inputHash);

            HttpResponse<String> resp = postMessage(roomId, body, TOKEN);
            assertThat(resp.statusCode()).isEqualTo(200);

            // WebSocket client receives V2.9 run-result without internal-only metadata
            String frame = wsMessages.poll(5, TimeUnit.SECONDS);
            assertThat(frame).isNotNull();
            JsonNode wsJson = objectMapper.readTree(frame);
            assertThat(wsJson.get("type").asText()).isEqualTo("run-result");
            assertThat(wsJson.has("audience")).isFalse();
            assertThat(wsJson.has("input_hash")).isFalse();
            assertThat(wsJson.has("files")).isFalse();
            assertThat(wsJson.get("stdout").asText()).isEqualTo("42\n");

            // Store contains the recorded run with all FR29 fields
            List<RunRecord> stored = runRecordStore.findByRoomAndInputHash(roomId, inputHash);
            assertThat(stored).hasSize(1);
            RunRecord rec = stored.get(0);
            assertThat(rec.getRoomId()).isEqualTo(roomId);
            assertThat(rec.getRunnerDisplayName()).isEqualTo("Ada");
            assertThat(rec.getRanAt()).isEqualTo("2026-11-01T10:20:05.087Z");
            assertThat(rec.getLanguage()).isEqualTo("python");
            assertThat(rec.getFiles()).containsExactly("main.py");
            assertThat(rec.getSavedVersions()).isEmpty();
            assertThat(rec.getInputHash()).isEqualTo(inputHash);
            assertThat(rec.getExecutionTimeMs()).isEqualTo(38.5);
            assertThat(rec.getPeakMemoryMb()).isEqualTo(7.9);
        } finally {
            ws.close();
        }
    }

    @Test
    @DisplayName("Stored record never persists owner_token, runner.session_id, raw stdin, or source code")
    void deliveredRunResult_stripsSecretsAndSessionIdsFromRecord() throws Exception {
        String roomId = "room-us52-secrets";
        String ownerToken = "secret-owner-token-do-not-store";
        String runnerSessionId = "99999999-9999-4999-8999-999999999999";
        String rawStdin = "SUPER_SECRET_STDIN_VALUE";
        String sourceCode = "print('SUPER_SECRET_SOURCE_CODE')";

        roomStore.put(new Room(roomId, RoomMode.OPEN, null, "python", OwnerTokens.hash(ownerToken)));

        String body = """
                {
                  "audience": { "kind": "sessions", "session_ids": ["%s"] },
                  "type": "run-result",
                  "request_id": "d7a2f5c8-1e39-4b60-a4c2-9f8e1d7b3a06",
                  "room_id": "%s",
                  "owner_token": "%s",
                  "session_id": "%s",
                  "stdin": "%s",
                  "language": "python",
                  "stdout": "",
                  "stderr": "",
                  "exit_code": 0,
                  "execution_time_ms": 41.0,
                  "peak_memory_mb": 8.4,
                  "runner": {
                    "member_id": "m_4b19d2",
                    "display_name": "Lin",
                    "session_id": "%s"
                  },
                  "ran_at": "2026-11-01T10:15:30.412Z",
                  "visibility": "private",
                  "files": [
                    { "name": "main.py", "content": "%s" },
                    { "name": "helper.py", "content": "def helper(): return 1" }
                  ],
                  "saved_versions": [
                    {
                      "module_id": "mod_9Qx7",
                      "name": "main.py",
                      "version": 4,
                      "content": "%s",
                      "saved_by": {
                        "member_id": "m_7a3c9f",
                        "display_name": "Ada",
                        "session_id": "%s"
                      },
                      "saved_at": "2026-11-01T10:12:04.118Z"
                    }
                  ]
                }
                """.formatted(
                runnerSessionId, roomId, ownerToken, runnerSessionId, rawStdin,
                runnerSessionId, sourceCode, sourceCode, runnerSessionId);

        HttpResponse<String> postResp = postMessage(roomId, body, TOKEN);
        assertThat(postResp.statusCode()).isEqualTo(200);

        String expectedHash = InputHasher.hash(rawStdin);
        HttpResponse<String> getResp = getRuns(roomId, expectedHash, TOKEN);
        assertThat(getResp.statusCode()).isEqualTo(200);

        String responseBody = getResp.body();
        assertThat(responseBody).doesNotContain(ownerToken);
        assertThat(responseBody).doesNotContain(runnerSessionId);
        assertThat(responseBody).doesNotContain(rawStdin);
        assertThat(responseBody).doesNotContain(sourceCode);
        assertThat(responseBody).doesNotContain("session_id");
        assertThat(responseBody).doesNotContain("owner_token");
        assertThat(responseBody).doesNotContain("\"stdin\"");

        JsonNode json = objectMapper.readTree(responseBody);
        assertThat(json.get("runs")).hasSize(1);
        JsonNode rec = json.get("runs").get(0);
        assertThat(rec.get("runner_display_name").asText()).isEqualTo("Lin");
        assertThat(rec.get("input_hash").asText()).isEqualTo(expectedHash);
        assertThat(rec.get("files").size()).isEqualTo(2);
        assertThat(rec.get("files").get(0).asText()).isEqualTo("main.py");
        assertThat(rec.get("saved_versions")).hasSize(1);
        assertThat(rec.get("saved_versions").get(0).get("module_id").asText()).isEqualTo("mod_9Qx7");
        assertThat(rec.get("saved_versions").get(0).get("version").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("GET /internal/rooms/{roomId}/runs filters by input_hash newest first, and non-run-result/failed deliveries are not recorded")
    void queryRuns_filtersByInputHashNewestFirst_andIgnoresNonRunOrFailedDeliveries() throws Exception {
        String roomId = "room-us52-query";
        roomStore.put(new Room(roomId, RoomMode.PAIR, "debug_together", null, OwnerTokens.hash("tok-q")));

        String hash1 = InputHasher.hash("stdin-one");
        String hash2 = InputHasher.hash("stdin-two");

        // Unauthorized delivery must NOT record
        HttpResponse<String> unauth = postMessage(roomId, runResultJson(roomId, "Ada", "2026-11-01T10:00:00.000Z", hash1, 10.0), "wrong-token");
        assertThat(unauth.statusCode()).isEqualTo(401);

        // Non-run-result message must NOT record
        HttpResponse<String> nonRun = postMessage(roomId, """
                {"type":"module-updated","room_id":"%s","module_id":"mod_1"}
                """.formatted(roomId), TOKEN);
        assertThat(nonRun.statusCode()).isEqualTo(200);

        // Deliver two runs for hash1 and one run for hash2
        assertThat(postMessage(roomId, runResultJson(roomId, "Ada", "2026-11-01T10:00:01.000Z", hash1, 20.0), TOKEN).statusCode()).isEqualTo(200);
        assertThat(postMessage(roomId, runResultJson(roomId, "Lin", "2026-11-01T10:00:02.000Z", hash2, 99.0), TOKEN).statusCode()).isEqualTo(200);
        assertThat(postMessage(roomId, runResultJson(roomId, "Ada", "2026-11-01T10:00:05.000Z", hash1, 250.0), TOKEN).statusCode()).isEqualTo(200);

        // Auth check on GET /runs
        assertThat(getRuns(roomId, hash1, null).statusCode()).isEqualTo(401);
        assertThat(getRuns(roomId, hash1, "wrong-token").statusCode()).isEqualTo(401);

        // Query by hash1 returns only the 2 matching runs, newest first
        HttpResponse<String> filtered = getRuns(roomId, hash1, TOKEN);
        assertThat(filtered.statusCode()).isEqualTo(200);
        JsonNode filteredRuns = objectMapper.readTree(filtered.body()).get("runs");
        assertThat(filteredRuns).hasSize(2);
        assertThat(filteredRuns.get(0).get("ran_at").asText()).isEqualTo("2026-11-01T10:00:05.000Z");
        assertThat(filteredRuns.get(0).get("execution_time_ms").asDouble()).isEqualTo(250.0);
        assertThat(filteredRuns.get(1).get("ran_at").asText()).isEqualTo("2026-11-01T10:00:01.000Z");
        assertThat(filteredRuns.get(1).get("execution_time_ms").asDouble()).isEqualTo(20.0);

        // DELETE /runs clears all records for the room
        assertThat(deleteRuns(roomId, "wrong-token").statusCode()).isEqualTo(401);
        HttpResponse<String> delResp = deleteRuns(roomId, TOKEN);
        assertThat(delResp.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(delResp.body()).get("deleted").asInt()).isEqualTo(3);

        HttpResponse<String> afterDelete = getRuns(roomId, null, TOKEN);
        assertThat(afterDelete.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(afterDelete.body()).get("runs")).isEmpty();
    }

    @Test
    @DisplayName("Closing a room deletes its run records and GET /runs returns 404 ROOM_NOT_FOUND")
    void closingRoom_deletesRunRecordsAndRejectsFurtherQueries() throws Exception {
        String roomId = "room-us52-close";
        roomStore.put(new Room(roomId, RoomMode.PAIR, "debug_together", null, OwnerTokens.hash("tok-c")));
        String hash = InputHasher.hash("abc");

        assertThat(postMessage(roomId, runResultJson(roomId, "Ada", "2026-11-01T10:00:01.000Z", hash, 12.0), TOKEN).statusCode())
                .isEqualTo(200);
        assertThat(runRecordStore.count(roomId)).isEqualTo(1);

        roomService.closeRoom(roomId);
        assertThat(runRecordStore.count(roomId)).isZero();

        HttpResponse<String> getAfterClose = getRuns(roomId, hash, TOKEN);
        assertThat(getAfterClose.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(getAfterClose.body()).get("error").get("code").asText())
                .isEqualTo("ROOM_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String runResultJson(String roomId, String displayName, String ranAt, String inputHash, double execMs) {
        return """
                {
                  "type": "run-result",
                  "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
                  "room_id": "%s",
                  "language": "python",
                  "stdout": "ok\\n",
                  "stderr": "",
                  "exit_code": 0,
                  "execution_time_ms": %s,
                  "peak_memory_mb": 6.5,
                  "runner": { "member_id": "m_1", "display_name": "%s" },
                  "ran_at": "%s",
                  "visibility": "room",
                  "saved_versions": [],
                  "input_hash": "%s",
                  "files": ["main.py"]
                }
                """.formatted(roomId, execMs, displayName, ranAt, inputHash);
    }

    private HttpResponse<String> postMessage(String roomId, String body, String token) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/internal/rooms/" + roomId + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getRuns(String roomId, String inputHash, String token) throws Exception {
        String url = "http://localhost:" + port + "/internal/rooms/" + roomId + "/runs";
        if (inputHash != null) {
            url += "?input_hash=" + inputHash;
        }
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteRuns(String roomId, String token) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/internal/rooms/" + roomId + "/runs"))
                .DELETE();
        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private WebSocketSession connectControlSocket(
            String roomId, String sessionId, BlockingQueue<String> queue) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws/rooms/" + roomId + "?session_id=" + sessionId;
        return client.execute(new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                queue.add(message.getPayload());
            }
        }, url).get(5, TimeUnit.SECONDS);
    }
}
