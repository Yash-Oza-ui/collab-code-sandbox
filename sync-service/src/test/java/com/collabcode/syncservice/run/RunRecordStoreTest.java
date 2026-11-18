package com.collabcode.syncservice.run;

import com.collabcode.syncservice.room.OwnerTokens;
import com.collabcode.syncservice.room.Room;
import com.collabcode.syncservice.room.RoomMode;
import com.collabcode.syncservice.room.RoomService;
import com.collabcode.syncservice.room.RoomStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InputHasher}, {@link RunRecord}, and {@link RunRecordStore}
 * (FR29, FR32, US-52, US-56, US-68).
 */
class RunRecordStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -----------------------------------------------------------------------
    // 1. Input hashing (FR29, US-52 DoD)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Two runs with the same stdin produce the exact same SHA-256 input hash")
    void sameStdin_producesSameInputHash() {
        String h1 = InputHasher.hash("10 20 30\n");
        String h2 = InputHasher.hash("10 20 30\n");

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Different stdin values produce different SHA-256 input hashes")
    void differentStdin_producesDifferentInputHash() {
        String h1 = InputHasher.hash("10 20 30\n");
        String h2 = InputHasher.hash("10 20 31\n");

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("Empty or null stdin produces the canonical SHA-256 of empty bytes (matches ai-ml-contract.md)")
    void emptyAndNullStdin_matchCanonicalEmptySha256() {
        assertThat(InputHasher.hash("")).isEqualTo(InputHasher.EMPTY_SHA256);
        assertThat(InputHasher.hash(null)).isEqualTo(InputHasher.EMPTY_SHA256);
    }

    @Test
    @DisplayName("Optional input files are hashed deterministically independent of map insertion order")
    void inputFiles_hashedDeterministicallyRegardlessOfOrder() {
        Map<String, byte[]> orderA = new LinkedHashMap<>();
        orderA.put("a.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        orderA.put("b.txt", "beta".getBytes(StandardCharsets.UTF_8));

        Map<String, byte[]> orderB = new LinkedHashMap<>();
        orderB.put("b.txt", "beta".getBytes(StandardCharsets.UTF_8));
        orderB.put("a.txt", "alpha".getBytes(StandardCharsets.UTF_8));

        Map<String, byte[]> modified = new LinkedHashMap<>();
        modified.put("a.txt", "alpha-changed".getBytes(StandardCharsets.UTF_8));
        modified.put("b.txt", "beta".getBytes(StandardCharsets.UTF_8));

        String hashA = InputHasher.hash("in", orderA);
        String hashB = InputHasher.hash("in", orderB);
        String hashModified = InputHasher.hash("in", modified);

        assertThat(hashA).isEqualTo(hashB);
        assertThat(hashA).isNotEqualTo(hashModified);
        assertThat(hashA).isNotEqualTo(InputHasher.hash("in"));
    }

    // -----------------------------------------------------------------------
    // 2. Querying by room and input hash, newest first (US-68 baseline)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByRoomAndInputHash returns only matching room and input_hash, ordered newest first")
    void findByRoomAndInputHash_filtersAndSortsNewestFirst() {
        RunRecordStore store = new RunRecordStore();
        String hashA = InputHasher.hash("input-A");
        String hashB = InputHasher.hash("input-B");

        RunRecord r1 = new RunRecord("room-1", "Ada", "2026-11-01T10:00:01.000Z",
                "python", List.of("main.py"), List.of(), hashA, 25.0, 8.1);
        RunRecord r2 = new RunRecord("room-1", "Lin", "2026-11-01T10:00:05.000Z",
                "python", List.of("main.py"), List.of(), hashB, 50.0, 9.2);
        RunRecord r3 = new RunRecord("room-1", "Ada", "2026-11-01T10:00:10.000Z",
                "python", List.of("main.py"), List.of(), hashA, 420.0, 12.4);
        RunRecord rOtherRoom = new RunRecord("room-2", "Ada", "2026-11-01T10:00:20.000Z",
                "python", List.of("main.py"), List.of(), hashA, 15.0, 7.0);

        store.record(r1);
        store.record(r2);
        store.record(r3);
        store.record(rOtherRoom);

        List<RunRecord> matches = store.findByRoomAndInputHash("room-1", hashA);
        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).getRanAt()).isEqualTo("2026-11-01T10:00:10.000Z");
        assertThat(matches.get(0).getExecutionTimeMs()).isEqualTo(420.0);
        assertThat(matches.get(1).getRanAt()).isEqualTo("2026-11-01T10:00:01.000Z");
        assertThat(matches.get(1).getExecutionTimeMs()).isEqualTo(25.0);

        assertThat(store.findByRoomAndInputHash("room-1", hashB)).hasSize(1);
        assertThat(store.findByRoomAndInputHash("room-2", hashA)).hasSize(1);
        assertThat(store.findByRoomAndInputHash("room-unknown", hashA)).isEmpty();
    }

    @Test
    @DisplayName("Tie-breaker orders later-recorded run first when ran_at timestamps are equal")
    void findByRoomAndInputHash_sameTimestamp_latestRecordedFirst() {
        RunRecordStore store = new RunRecordStore();
        String hash = InputHasher.hash("same");
        String ts = "2026-11-01T10:00:00.000Z";

        store.record(new RunRecord("room-1", "Ada", ts, "python", List.of("main.py"), List.of(), hash, 10.0, 5.0));
        store.record(new RunRecord("room-1", "Lin", ts, "python", List.of("main.py"), List.of(), hash, 20.0, 6.0));

        List<RunRecord> matches = store.findByRoomAndInputHash("room-1", hash);
        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).getRunnerDisplayName()).isEqualTo("Lin");
        assertThat(matches.get(1).getRunnerDisplayName()).isEqualTo("Ada");
    }

    // -----------------------------------------------------------------------
    // 3. Deletion by room key & room close lifecycle (FR32, US-56)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("deleteByRoom deletes all records for target room and preserves other rooms")
    void deleteByRoom_deletesOnlyTargetRoom() {
        RunRecordStore store = new RunRecordStore();
        String hash = InputHasher.hash("x");

        store.record(new RunRecord("room-A", "Ada", "2026-11-01T10:00:01.000Z",
                "python", List.of("main.py"), List.of(), hash, 12.0, 7.0));
        store.record(new RunRecord("room-A", "Lin", "2026-11-01T10:00:02.000Z",
                "python", List.of("main.py"), List.of(), hash, 14.0, 7.2));
        store.record(new RunRecord("room-B", "Ada", "2026-11-01T10:00:03.000Z",
                "python", List.of("main.py"), List.of(), hash, 15.0, 7.5));

        int deleted = store.deleteByRoom("room-A");
        assertThat(deleted).isEqualTo(2);
        assertThat(store.findByRoom("room-A")).isEmpty();
        assertThat(store.findByRoomAndInputHash("room-A", hash)).isEmpty();
        assertThat(store.count("room-A")).isZero();

        assertThat(store.findByRoom("room-B")).hasSize(1);
        assertThat(store.deleteByRoom("room-nonexistent")).isZero();
    }

    @Test
    @DisplayName("Closing or deleting a room via RoomService purges its run records (FR32)")
    void roomServiceCloseAndDelete_purgesRunRecords() {
        RoomStore roomStore = new RoomStore();
        RunRecordStore runRecordStore = new RunRecordStore();
        RoomService roomService = new RoomService(roomStore, runRecordStore);

        String hash = InputHasher.hash("stdin-data");
        roomStore.put(new Room("room-close", RoomMode.PAIR, "debug_together", null, OwnerTokens.hash("tok1")));
        roomStore.put(new Room("room-delete", RoomMode.OPEN, null, "python", OwnerTokens.hash("tok2")));

        runRecordStore.record(new RunRecord("room-close", "Ada", "2026-11-01T10:00:01.000Z",
                "python", List.of("main.py"), List.of(), hash, 30.0, 8.0));
        runRecordStore.record(new RunRecord("room-delete", "Lin", "2026-11-01T10:00:02.000Z",
                "python", List.of("main.py"), List.of(), hash, 35.0, 8.5));

        assertThat(roomService.closeRoom("room-close")).isTrue();
        assertThat(runRecordStore.findByRoom("room-close")).isEmpty();
        assertThat(runRecordStore.findByRoom("room-delete")).hasSize(1);

        assertThat(roomService.deleteRoom("room-delete")).isTrue();
        assertThat(runRecordStore.findByRoom("room-delete")).isEmpty();
        assertThat(roomStore.exists("room-delete")).isFalse();
    }

    // -----------------------------------------------------------------------
    // 4. Privacy & secret exclusion (AC2, US-52 DoD)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Serialized RunRecord contains no owner token, session_id, raw stdin, or source code")
    void runRecord_containsNoSecretsOrSourceCode() throws Exception {
        String ownerTokenFixture = "owner-fixture-0000";
        String callerSessionId = "10000000-0000-4000-8000-000000000001";
        String rawStdin = "RAW_STDIN_INPUT_42";
        String sourceCode = "print('SOURCE_CODE_TEXT')";

        SavedVersionSummary sv = new SavedVersionSummary(
                "mod_1", "main.py", 3,
                new SavedVersionSummary.SavedBySummary("m_7a3c9f", "Ada"),
                "2026-11-01T10:12:04.118Z"
        );
        RunRecord record = new RunRecord(
                "room-privacy",
                "Ada",
                "2026-11-01T10:20:05.087Z",
                "python",
                List.of("main.py"),
                List.of(sv),
                InputHasher.hash(rawStdin),
                38.0,
                7.9
        );

        String json = objectMapper.writeValueAsString(record);
        assertThat(json).doesNotContain(ownerTokenFixture);
        assertThat(json).doesNotContain(callerSessionId);
        assertThat(json).doesNotContain(rawStdin);
        assertThat(json).doesNotContain(sourceCode);
        assertThat(json).doesNotContain("session_id");
        assertThat(json).doesNotContain("owner_token");
        assertThat(json).doesNotContain("\"stdin\"");
        assertThat(json).contains("\"runner_display_name\":\"Ada\"");
        assertThat(json).contains("\"input_hash\":\"" + InputHasher.hash(rawStdin) + "\"");
    }
}
