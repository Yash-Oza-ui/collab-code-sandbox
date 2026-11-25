package com.collabcode.syncservice.internal;

import com.collabcode.syncservice.module.ModuleRecord;
import com.collabcode.syncservice.module.ModuleService;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance and contract integration tests for POST /internal/rooms/{roomId}/snapshot (US-47, T-17 V2.8).
 *
 * Covers:
 *   1. Target module returns saved version, never live text (FR25.1, FR25.2).
 *   2. Module not marked runnable returns 422 MODULE_NOT_RUNNABLE.
 *   3. Never-saved module returns 422 MODULE_NOT_SAVED.
 *   4. Non-existent module returns 404 MODULE_NOT_FOUND.
 *   5. Missing/invalid internal token returns 401 UNAUTHORIZED.
 *   6. Pair mode room returns 422 INVALID_REQUEST.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "INTERNAL_API_TOKEN=test-snapshot-token"
        }
)
class SnapshotEndpointIntegrationTest {

    private static final String TOKEN = "test-snapshot-token";

    @LocalServerPort
    private int port;

    @Autowired
    private RoomStore roomStore;

    @Autowired
    private ModuleService moduleService;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String OPEN_ROOM_ID = "openRoomSnap123456789";
    private static final String PAIR_ROOM_ID = "pairRoomSnap123456789";

    private final Member host = new Member("m_host", "s_host", "Host", Role.HOST, false, true);
    private final Member alice = new Member("m_alice", "s_alice", "Alice", Role.PARTICIPANT, false, true);

    @BeforeEach
    void setUp() {
        Room openRoom = new Room(OPEN_ROOM_ID, RoomMode.OPEN, null, "python", OwnerTokens.hash("owner-tok"));
        openRoom.getMembers().put(host.getMemberId(), host);
        openRoom.getMembers().put(alice.getMemberId(), alice);
        roomStore.put(openRoom);

        Room pairRoom = new Room(PAIR_ROOM_ID, RoomMode.PAIR, "debug_together", "python", OwnerTokens.hash("owner-tok"));
        pairRoom.getMembers().put(host.getMemberId(), host);
        roomStore.put(pairRoom);
    }

    private HttpResponse<String> postSnapshot(String roomId, String body, String token) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/internal/rooms/" + roomId + "/snapshot"))
                .header("Content-Type", "application/json");

        if (token != null) {
            builder.header("X-Internal-Token", token);
        }
        if (body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }

        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("US-47: Snapshot for runnable and saved module returns 200, entry, saved content and metadata (FR25.1, FR25.2)")
    void snapshot_targetModule_runnableAndSaved_returnsSavedContent() throws Exception {
        ModuleRecord mod = moduleService.createModule(OPEN_ROOM_ID, alice.getMemberId(), "solver.py", false);
        moduleService.saveModule(OPEN_ROOM_ID, mod.moduleId(), alice, "print('saved v1')\n");
        moduleService.setRunnable(OPEN_ROOM_ID, mod.moduleId(), alice, true);

        // Mark dirty (unsaved text)
        moduleService.markDirty(OPEN_ROOM_ID, mod.moduleId());

        String requestBody = """
                {
                  "kind": "saved",
                  "target": "module",
                  "module_id": "%s"
                }
                """.formatted(mod.moduleId());

        HttpResponse<String> resp = postSnapshot(OPEN_ROOM_ID, requestBody, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("room_id").asText()).isEqualTo(OPEN_ROOM_ID);
        assertThat(json.get("entry").asText()).isEqualTo("solver.py");
        assertThat(json.get("modules").isArray()).isTrue();
        assertThat(json.get("modules")).hasSize(1);

        JsonNode modNode = json.get("modules").get(0);
        assertThat(modNode.get("module_id").asText()).isEqualTo(mod.moduleId());
        assertThat(modNode.get("name").asText()).isEqualTo("solver.py");
        assertThat(modNode.get("content").asText()).isEqualTo("print('saved v1')\n");
        assertThat(modNode.get("version").asInt()).isEqualTo(1);
        assertThat(modNode.get("saved_by").get("member_id").asText()).isEqualTo(alice.getMemberId());
        assertThat(modNode.get("saved_by").get("display_name").asText()).isEqualTo("Alice");
        assertThat(modNode.get("saved_at").asText()).isNotBlank();
        assertThat(modNode.get("owner").get("member_id").asText()).isEqualTo(alice.getMemberId());
    }

    @Test
    @DisplayName("US-47: Snapshot for module not marked runnable returns 422 MODULE_NOT_RUNNABLE")
    void snapshot_targetModule_notRunnable_returns422ModuleNotRunnable() throws Exception {
        ModuleRecord mod = moduleService.createModule(OPEN_ROOM_ID, alice.getMemberId(), "calc.py", false);
        moduleService.saveModule(OPEN_ROOM_ID, mod.moduleId(), alice, "print('saved v1')\n");
        moduleService.setRunnable(OPEN_ROOM_ID, mod.moduleId(), alice, false);

        String requestBody = """
                {
                  "kind": "saved",
                  "target": "module",
                  "module_id": "%s"
                }
                """.formatted(mod.moduleId());

        HttpResponse<String> resp = postSnapshot(OPEN_ROOM_ID, requestBody, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(422);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("MODULE_NOT_RUNNABLE");
        assertThat(json.get("error").get("message").asText()).contains("calc.py").contains("is not marked runnable");
    }

    @Test
    @DisplayName("US-47: Snapshot for module that has never been saved returns 422 MODULE_NOT_SAVED")
    void snapshot_targetModule_neverSaved_returns422ModuleNotSaved() throws Exception {
        ModuleRecord mod = moduleService.createModule(OPEN_ROOM_ID, alice.getMemberId(), "unsaved.py", false);
        moduleService.setRunnable(OPEN_ROOM_ID, mod.moduleId(), alice, true);
        // never saved: version == 0, savedAt == null

        String requestBody = """
                {
                  "kind": "saved",
                  "target": "module",
                  "module_id": "%s"
                }
                """.formatted(mod.moduleId());

        HttpResponse<String> resp = postSnapshot(OPEN_ROOM_ID, requestBody, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(422);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("MODULE_NOT_SAVED");
        assertThat(json.get("error").get("message").asText()).isEqualTo("Module 'unsaved.py' has never been saved. Save it first.");
    }

    @Test
    @DisplayName("US-47: Snapshot for non-existent module returns 404 MODULE_NOT_FOUND")
    void snapshot_targetModule_notFound_returns404() throws Exception {
        String requestBody = """
                {
                  "kind": "saved",
                  "target": "module",
                  "module_id": "mod_non_existent"
                }
                """;

        HttpResponse<String> resp = postSnapshot(OPEN_ROOM_ID, requestBody, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(404);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("MODULE_NOT_FOUND");
    }

    @Test
    @DisplayName("Snapshot with missing or invalid token returns 401 UNAUTHORIZED")
    void snapshot_invalidToken_returns401() throws Exception {
        String requestBody = """
                {
                  "kind": "saved",
                  "target": "module",
                  "module_id": "mod_1"
                }
                """;

        HttpResponse<String> resp = postSnapshot(OPEN_ROOM_ID, requestBody, "wrong-token");
        assertThat(resp.statusCode()).isEqualTo(401);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("Snapshot in Pair mode room returns 422 INVALID_REQUEST")
    void snapshot_pairMode_returns422() throws Exception {
        String requestBody = """
                {
                  "kind": "saved",
                  "target": "module",
                  "module_id": "mod_1"
                }
                """;

        HttpResponse<String> resp = postSnapshot(PAIR_ROOM_ID, requestBody, TOKEN);
        assertThat(resp.statusCode()).isEqualTo(422);

        JsonNode json = objectMapper.readTree(resp.body());
        assertThat(json.get("error").get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(json.get("error").get("message").asText()).contains("Open mode rooms");
    }
}
