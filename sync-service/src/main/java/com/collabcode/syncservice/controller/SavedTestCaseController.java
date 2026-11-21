package com.collabcode.syncservice.controller;

import com.collabcode.syncservice.room.Member;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.collabcode.syncservice.run.SavedTestCaseStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/** Proposed US-72 room-owned Save as Test Case endpoint (FR38.2). */
@RestController
@RequestMapping("/v1/rooms/{roomId}/saved-tests")
public class SavedTestCaseController {
    private static final Set<String> KINDS = Set.of("wrong_output", "crash", "timeout");
    private static final Set<String> LANGUAGES = Set.of("python", "cpp", "c", "java", "javascript");
    private static final Set<String> FIELDS = Set.of("input", "failure_type", "language", "expected_output");
    private final RoomStore rooms;
    private final RoomService roomService;
    private final SavedTestCaseStore tests;

    public SavedTestCaseController(RoomStore rooms, RoomService roomService,
                                   SavedTestCaseStore tests) {
        this.rooms = rooms;
        this.roomService = roomService;
        this.tests = tests;
    }

    @PostMapping
    public ResponseEntity<?> save(@PathVariable String roomId,
                                  @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                  @RequestBody(required = false) JsonNode body) {
        Member member = authorised(roomId, sessionId);
        if (member == null) return forbidden();
        if (body == null || !body.isObject()) return invalid();
        var fields = body.fieldNames();
        while (fields.hasNext()) if (!FIELDS.contains(fields.next())) return invalid();
        if (!isText(body, "input") || !isText(body, "failure_type") || !isText(body, "language"))
            return invalid();
        String input = body.get("input").asText();
        String kind = body.get("failure_type").asText();
        String language = body.get("language").asText();
        if (input.length() > 4096 || !KINDS.contains(kind) || !LANGUAGES.contains(language))
            return invalid();
        JsonNode expectedNode = body.get("expected_output");
        if (expectedNode != null && !expectedNode.isNull() && !expectedNode.isTextual()) return invalid();
        String expected = expectedNode == null || expectedNode.isNull() ? null : expectedNode.asText();
        if (expected != null && expected.length() > 8192) return invalid();
        if (kind.equals("wrong_output") && expected == null) return invalid();
        try {
            // Validate against room closure while holding the same lock as deletion.
            var stored = tests.save(roomId, member.getMemberId(), input, kind, language, expected,
                    () -> {
                        Member active = authorised(roomId, sessionId);
                        return active != null && active.getMemberId().equals(member.getMemberId());
                    });
            return ResponseEntity.status(HttpStatus.CREATED).body(stored);
        } catch (IllegalStateException exceeded) {
            if ("ROOM_UNAVAILABLE".equals(exceeded.getMessage())) return forbidden();
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "SAVED_TEST_LIMIT"));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable String roomId,
                                  @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Member member = authorised(roomId, sessionId);
        if (member == null) return forbidden();
        // Each member sees only their own saved tests. No private run data
        // is ever broadcast to a different session in Open mode (US-49).
        return ResponseEntity.ok(tests.list(roomId, member.getMemberId()));
    }

    private Member authorised(String roomId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Room room = rooms.get(roomId);
        if (room == null || room.isClosed()) return null;
        return roomService.findMemberBySession(roomId, sessionId);
    }

    private static boolean isText(JsonNode body, String field) {
        return body.has(field) && body.get(field).isTextual();
    }

    private static ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "NOT_AUTHORIZED"));
    }
}
