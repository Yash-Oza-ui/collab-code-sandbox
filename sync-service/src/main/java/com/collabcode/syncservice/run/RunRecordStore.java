package com.collabcode.syncservice.run;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store for {@link RunRecord} entries held by {@code sync-service} (FR29, US-52, T-18 Decision 9).
 *
 * <p>Keyed by room so records can be deleted atomically with the room (FR32, US-56).
 * Supports queries by room and {@code input_hash} with newest-first ordering
 * for slowdown baseline calculation (US-68).
 */
@Component
public class RunRecordStore {

    private static final Comparator<StoredEntry> NEWEST_FIRST = (a, b) -> {
        int timeCmp = compareTimestampsDescending(a.parsedRanAt, a.record.getRanAt(),
                b.parsedRanAt, b.record.getRanAt());
        if (timeCmp != 0) {
            return timeCmp;
        }
        return Long.compare(b.sequence, a.sequence);
    };

    private final Map<String, List<StoredEntry>> recordsByRoom = new ConcurrentHashMap<>();
    private final AtomicLong sequenceGenerator = new AtomicLong(0);

    /**
     * Stores a new run record for its room.
     */
    public void record(RunRecord record) {
        if (record == null || record.getRoomId() == null || record.getRoomId().isBlank()) {
            return;
        }
        long seq = sequenceGenerator.incrementAndGet();
        StoredEntry entry = new StoredEntry(record, parseInstantOrNull(record.getRanAt()), seq);
        recordsByRoom.computeIfAbsent(record.getRoomId(), k -> new CopyOnWriteArrayList<>())
                .add(entry);
    }

    /**
     * Deletes all records for the given room (FR32, US-56 room lifecycle).
     *
     * @return number of deleted run records (0 if the room had no records)
     */
    public int deleteByRoom(String roomId) {
        if (roomId == null) {
            return 0;
        }
        List<StoredEntry> removed = recordsByRoom.remove(roomId);
        return removed != null ? removed.size() : 0;
    }

    /** Alias for {@link #deleteByRoom(String)}. */
    public int deleteByRoomId(String roomId) {
        return deleteByRoom(roomId);
    }

    /**
     * Returns runs for the given room matching {@code input_hash}, sorted newest first (US-68).
     */
    public List<RunRecord> findByRoomAndInputHash(String roomId, String inputHash) {
        if (roomId == null || inputHash == null || inputHash.isBlank()) {
            return Collections.emptyList();
        }
        List<StoredEntry> roomRecords = recordsByRoom.get(roomId);
        if (roomRecords == null || roomRecords.isEmpty()) {
            return Collections.emptyList();
        }

        List<StoredEntry> matches = new ArrayList<>();
        for (StoredEntry entry : roomRecords) {
            if (inputHash.equalsIgnoreCase(entry.record.getInputHash())) {
                matches.add(entry);
            }
        }
        matches.sort(NEWEST_FIRST);
        return matches.stream().map(e -> e.record).toList();
    }

    /** Alias for {@link #findByRoomAndInputHash(String, String)}. */
    public List<RunRecord> getRuns(String roomId, String inputHash) {
        return findByRoomAndInputHash(roomId, inputHash);
    }

    /**
     * Returns all runs for the given room, sorted newest first.
     */
    public List<RunRecord> findByRoom(String roomId) {
        if (roomId == null) {
            return Collections.emptyList();
        }
        List<StoredEntry> roomRecords = recordsByRoom.get(roomId);
        if (roomRecords == null || roomRecords.isEmpty()) {
            return Collections.emptyList();
        }
        List<StoredEntry> copy = new ArrayList<>(roomRecords);
        copy.sort(NEWEST_FIRST);
        return copy.stream().map(e -> e.record).toList();
    }

    /**
     * Number of stored records for the room.
     */
    public int count(String roomId) {
        if (roomId == null) {
            return 0;
        }
        List<StoredEntry> list = recordsByRoom.get(roomId);
        return list != null ? list.size() : 0;
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int compareTimestampsDescending(
            Instant aInstant, String aRaw, Instant bInstant, String bRaw) {
        if (aInstant != null && bInstant != null) {
            return bInstant.compareTo(aInstant);
        }
        if (aInstant != null) {
            return -1;
        }
        if (bInstant != null) {
            return 1;
        }
        String safeA = aRaw != null ? aRaw : "";
        String safeB = bRaw != null ? bRaw : "";
        return safeB.compareTo(safeA);
    }

    private record StoredEntry(RunRecord record, Instant parsedRanAt, long sequence) {
    }
}
