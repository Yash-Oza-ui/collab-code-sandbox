package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomIds;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Room creation (T-17 V2.1, decisions 1 and 2).
 *
 * <p>{@code POST /v1/rooms} creates a room and issues its owner token. The
 * server generates the room id ({@code >=128} bits, base64url, no padding), so
 * the client cannot choose it (NFR16). Only the SHA-256 hash of the owner token
 * is stored. A body that breaks the mode rules is rejected with
 * {@code INVALID_REQUEST} (422) in the section 7 error envelope.
 */
@RestController
@RequestMapping("/v1")
public class RoomController {

    /** DR1: the five supported languages (contract section 2). */
    private static final Set<String> LANGUAGES = Set.of("cpp", "python", "c", "java", "javascript");

    /** The two Pair-mode presets (FR19.2). */
    private static final Set<String> PRESETS = Set.of("teach_interview", "debug_together");

    private final RoomStore store;
    private final RoomService roomService;

    public RoomController(RoomStore store, RoomService roomService) {
        this.store = store;
        this.roomService = roomService;
    }

    @PostMapping("/rooms")
    public ResponseEntity<?> createRoom(@RequestBody(required = false) CreateRoomRequest body) {
        if (body == null) {
            return invalid("mode");
        }

        RoomMode mode = RoomMode.fromValue(body.mode());
        if (mode == null) {
            return invalid("mode");
        }

        String preset = null;
        String language = null;

        if (mode == RoomMode.PAIR) {
            if (body.language() != null) {
                return invalid("language"); // language is Open-only
            }
            preset = body.preset();
            if (preset == null || !PRESETS.contains(preset)) {
                return invalid("preset");
            }
        } else { // OPEN
            if (body.preset() != null) {
                return invalid("preset"); // preset is Pair-only
            }
            language = body.language();
            if (language == null) {
                return invalid("language"); // required field missing
            }
            if (!LANGUAGES.contains(language)) {
                return unsupportedLanguage(language); // present but not one of the five (DR1)
            }
        }

        String roomId = RoomIds.generate();
        String ownerToken = OwnerTokens.generate();
        store.put(new Room(roomId, mode, preset, language, OwnerTokens.hash(ownerToken)));

        CreateRoomResponse response =
                new CreateRoomResponse(roomId, ownerToken, mode.value(), preset, language);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * {@code 422 INVALID_REQUEST} in the section 7 error envelope. Room
     * creation carries no {@code request_id}, so it is {@code null}.
     */
    private ResponseEntity<Map<String, Object>> invalid(String field) {
        return envelope("INVALID_REQUEST", "Field '" + field + "' is missing or invalid.");
    }

    /**
     * {@code 422 UNSUPPORTED_LANGUAGE} (§7.1, V2.1/V2.12): a {@code language}
     * value that is present but not one of the five DR1 ids.
     */
    private ResponseEntity<Map<String, Object>> unsupportedLanguage(String language) {
        return envelope("UNSUPPORTED_LANGUAGE",
                "Language '" + language + "' is not supported. Use one of: cpp, python, c, java, javascript.");
    }

    /**
     * Register an Open-mode module id in a room (V2.6, partial implementation).
     * {@code POST /v1/rooms/{room_id}/modules} with body {@code {"module_id":".."}}`r
     * returns {@code 201} with the module_id on success, or 404 if the room is unknown.
     * Full module creation (pending, approval, etc.) is a later story; this endpoint
     * exists so the document socket (4405 validation) has a source of truth.
     */
    @PostMapping("/rooms/{roomId}/modules")
    public ResponseEntity<?> registerModule(@PathVariable String roomId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        Room room = store.get(roomId);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }
        if (room.getMode() != RoomMode.OPEN) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "modules are only valid in Open mode"));
        }
        String moduleId = (body != null && body.containsKey("module_id"))
                ? String.valueOf(body.get("module_id")) : null;
        if (moduleId == null || moduleId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "module_id is required"));
        }
        roomService.registerModule(roomId, moduleId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("module_id", moduleId));
    }

    private ResponseEntity<Map<String, Object>> envelope(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retry_after_seconds", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", null);
        body.put("error", error);

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }
}
