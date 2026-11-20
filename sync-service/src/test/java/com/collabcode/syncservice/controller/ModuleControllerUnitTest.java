package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.handler.ControlHandler;
import com.collabcode.syncservice.module.ModuleRecord;
import com.collabcode.syncservice.module.ModuleService;
import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Role;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomRegistry;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fast in-memory unit tests for {@link ModuleController} (US-39, FR20.1, DR4).
 */
class ModuleControllerUnitTest {

    private MockMvc mvc;
    private RoomStore roomStore;
    private RoomRegistry roomRegistry;
    private RoomService roomService;
    private ModuleService moduleService;
    private final List<Map<String, Object>> broadcasts = new ArrayList<>();

    private static final String OPEN_ROOM_ID = "openRoom1234567890123";
    private static final String PAIR_ROOM_ID = "pairRoom1234567890123";
    private static final String OWNER_TOKEN = "secret-owner-token";

    private static final String HOST_SESSION = "11111111-1111-4111-8111-111111111111";
    private static final String GUEST_SESSION = "22222222-2222-4222-8222-222222222222";

    @BeforeEach
    void setUp() {
        broadcasts.clear();
        roomStore = new RoomStore();
        roomRegistry = new RoomRegistry();
        roomService = new RoomService(roomStore);
        moduleService = new ModuleService(roomStore);

        Room openRoom = new Room(OPEN_ROOM_ID, RoomMode.OPEN, null, "python", OwnerTokens.hash(OWNER_TOKEN));
        roomStore.put(openRoom);

        Room pairRoom = new Room(PAIR_ROOM_ID, RoomMode.PAIR, "teach_interview", null, OwnerTokens.hash(OWNER_TOKEN));
        roomStore.put(pairRoom);

        // Join host and guest to open room
        roomService.join(OPEN_ROOM_ID, HOST_SESSION, "Host", OWNER_TOKEN);
        roomService.join(OPEN_ROOM_ID, GUEST_SESSION, "Guest", null);

        // Join host to pair room
        roomService.join(PAIR_ROOM_ID, HOST_SESSION, "Host", OWNER_TOKEN);

        ControlHandler stubControlHandler = new ControlHandler(roomStore, roomService, roomRegistry) {
            @Override
            public void broadcastModuleUpdated(String roomId, Map<String, Object> moduleUpdatedMsg) {
                broadcasts.add(moduleUpdatedMsg);
            }
        };

        ModuleController controller = new ModuleController(roomStore, roomService, moduleService, stubControlHandler);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Host direct creation creates approved module (201 Created, pending=false) and broadcasts module-updated")
    void hostDirectCreate_returns201_andBroadcasts() throws Exception {
        mvc.perform(post("/v1/rooms/" + OPEN_ROOM_ID + "/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Session-Id", HOST_SESSION)
                        .header("X-Owner-Token", OWNER_TOKEN)
                        .content("{\"name\":\"main.py\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("main.py"))
                .andExpect(jsonPath("$.pending").value(false))
                .andExpect(jsonPath("$.runnable").value(true))
                .andExpect(jsonPath("$.is_entry").value(false))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.module_id").isString());

        assertThat(broadcasts).hasSize(1);
        assertThat(broadcasts.get(0).get("type")).isEqualTo("module-updated");
        assertThat(broadcasts.get(0).get("name")).isEqualTo("main.py");
        assertThat(broadcasts.get(0).get("pending")).isEqualTo(false);
        assertThat(broadcasts.get(0).get("deleted")).isEqualTo(false);
    }

    @Test
    @DisplayName("Participant creation request without owner token returns 202 Accepted (pending=true)")
    void participantRequest_returns202() throws Exception {
        mvc.perform(post("/v1/rooms/" + OPEN_ROOM_ID + "/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Session-Id", GUEST_SESSION)
                        .content("{\"name\":\"solver.py\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.name").value("solver.py"))
                .andExpect(jsonPath("$.pending").value(true));

        assertThat(broadcasts).hasSize(1);
        assertThat(broadcasts.get(0).get("pending")).isEqualTo(true);
    }

    @Test
    @DisplayName("Host approval transitions pending module to active (200 OK) and broadcasts")
    void hostApprove_returns200() throws Exception {
        Member guest = roomService.findMemberBySession(OPEN_ROOM_ID, GUEST_SESSION);
        ModuleRecord pending = moduleService.requestCreate(OPEN_ROOM_ID, guest, "solver.py");

        mvc.perform(post("/v1/rooms/" + OPEN_ROOM_ID + "/modules/" + pending.moduleId() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Session-Id", HOST_SESSION)
                        .header("X-Owner-Token", OWNER_TOKEN)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.module_id").value(pending.moduleId()))
                .andExpect(jsonPath("$.pending").value(false));

        assertThat(broadcasts).hasSize(1);
        assertThat(broadcasts.get(0).get("module_id")).isEqualTo(pending.moduleId());
        assertThat(broadcasts.get(0).get("pending")).isEqualTo(false);
    }

    @Test
    @DisplayName("List modules returns approved modules and excludes pending ones")
    void listModules_returnsApprovedOnly() throws Exception {
        Member host = roomService.findMemberBySession(OPEN_ROOM_ID, HOST_SESSION);
        Member guest = roomService.findMemberBySession(OPEN_ROOM_ID, GUEST_SESSION);

        moduleService.createApprovedModule(OPEN_ROOM_ID, host, "main.py");
        moduleService.requestCreate(OPEN_ROOM_ID, guest, "pending.py");

        mvc.perform(get("/v1/rooms/" + OPEN_ROOM_ID + "/modules")
                        .header("X-Session-Id", HOST_SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("main.py"))
                .andExpect(jsonPath("$[0].pending").value(false));
    }

    @Test
    @DisplayName("Wrong extension for room language returns 422 INVALID_REQUEST")
    void wrongExtension_returns422() throws Exception {
        mvc.perform(post("/v1/rooms/" + OPEN_ROOM_ID + "/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Session-Id", HOST_SESSION)
                        .content("{\"name\":\"Main.java\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    @DisplayName("Duplicate name returns 409 NAME_TAKEN")
    void duplicateName_returns409() throws Exception {
        Member host = roomService.findMemberBySession(OPEN_ROOM_ID, HOST_SESSION);
        moduleService.createApprovedModule(OPEN_ROOM_ID, host, "main.py");

        mvc.perform(post("/v1/rooms/" + OPEN_ROOM_ID + "/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Session-Id", HOST_SESSION)
                        .content("{\"name\":\"main.py\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NAME_TAKEN"));
    }

    @Test
    @DisplayName("Directory traversal characters in name return 422 INVALID_REQUEST")
    void directoryTraversal_returns422() throws Exception {
        mvc.perform(post("/v1/rooms/" + OPEN_ROOM_ID + "/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Session-Id", HOST_SESSION)
                        .content("{\"name\":\"../evil.py\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("Pair mode room refuses list, create, and approve with 422 INVALID_REQUEST")
    void pairModeRoom_refusesCalls() throws Exception {
        // List
        mvc.perform(get("/v1/rooms/" + PAIR_ROOM_ID + "/modules")
                        .header("X-Session-Id", HOST_SESSION))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Pair mode rooms do not have modules."));

        // Create
        mvc.perform(post("/v1/rooms/" + PAIR_ROOM_ID + "/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Session-Id", HOST_SESSION)
                        .content("{\"name\":\"main.py\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        // Approve
        mvc.perform(post("/v1/rooms/" + PAIR_ROOM_ID + "/modules/mod_1234567890123456/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Session-Id", HOST_SESSION)
                        .header("X-Owner-Token", OWNER_TOKEN)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("Missing or non-joined session returns 404 SESSION_NOT_FOUND")
    void nonJoinedSession_returns404() throws Exception {
        mvc.perform(get("/v1/rooms/" + OPEN_ROOM_ID + "/modules"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        mvc.perform(get("/v1/rooms/" + OPEN_ROOM_ID + "/modules")
                        .header("X-Session-Id", "99999999-9999-4999-8999-999999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("Unknown room returns 404 ROOM_NOT_FOUND")
    void unknownRoom_returns404() throws Exception {
        mvc.perform(get("/v1/rooms/nonExistentRoom00000000/modules")
                        .header("X-Session-Id", HOST_SESSION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
    }
}
