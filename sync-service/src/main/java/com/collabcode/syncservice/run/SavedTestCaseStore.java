package com.collabcode.syncservice.run;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Room-owned, member-private saved tests. Never stores submitted program code. */
@Component
public class SavedTestCaseStore {
    public record SavedTestCase(String id, String input,
                                @JsonProperty("failure_type") String failureType,
                                String language,
                                @JsonProperty("expected_output") String expectedOutput) { }

    private final Map<String, Map<String, List<SavedTestCase>>> byRoom = new ConcurrentHashMap<>();
    private static final int MAX_PER_MEMBER = 100;

    public SavedTestCase save(String roomId, String memberId, String input,
                              String failureType, String language, String expectedOutput) {
        return save(roomId, memberId, input, failureType, language, expectedOutput, () -> true);
    }

    /** Atomic admission guard runs under the same lock as deletion. */
    public SavedTestCase save(String roomId, String memberId, String input,
                              String failureType, String language, String expectedOutput,
                              BooleanSupplier stillAuthorised) {
        SavedTestCase value = new SavedTestCase(UUID.randomUUID().toString(),
                input, failureType, language, expectedOutput);
        // Synchronization serializes saves/deletions within the JVM to avoid
        // reviving a room's records across a concurrent room deletion.
        synchronized (byRoom) {
            if (!stillAuthorised.getAsBoolean()) {
                throw new IllegalStateException("ROOM_UNAVAILABLE");
            }
            List<SavedTestCase> entries = byRoom.computeIfAbsent(roomId, unused -> new ConcurrentHashMap<>())
                    .computeIfAbsent(memberId, unused -> new ArrayList<>());
            if (entries.size() >= MAX_PER_MEMBER) {
                throw new IllegalStateException("SAVED_TEST_LIMIT");
            }
            entries.add(value);
        }
        return value;
    }

    public List<SavedTestCase> list(String roomId, String memberId) {
        synchronized (byRoom) {
            Map<String, List<SavedTestCase>> entries = byRoom.get(roomId);
            if (entries == null) return List.of();
            return List.copyOf(entries.getOrDefault(memberId, List.of()));
        }
    }

    public void deleteByRoom(String roomId) {
        synchronized (byRoom) {
            byRoom.remove(roomId);
        }
    }
}
