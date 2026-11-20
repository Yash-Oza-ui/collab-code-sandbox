package com.collabcode.syncservice.run;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
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
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Clock clock;

    @Autowired
    public RunRecordStore() {
        this(Clock.systemUTC());
    }

    /** Injectable clock makes retention tests deterministic. */
    public RunRecordStore(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    /**
     * Stores a new run record for its room.
     */
    public void record(RunRecord record) {
        if (record == null || record.getRoomId() == null || record.getRoomId().isBlank()) {
            return;
        }
        long seq = sequenceGenerator.incrementAndGet();
        Instant ranAt = parseInstantOrNull(record.getRanAt());
        StoredEntry entry = new StoredEntry(record, ranAt != null ? ranAt : clock.instant(), seq);
        recordsByRoom.computeIfAbsent(record.getRoomId(), k -> new CopyOnWriteArrayList<>())
                .add(entry);
    }

    /**
     * Deletes all records for the given room (FR32, US-56 room lifecycle).
     *
     * @return number of deleted run records (0 if the room had no records)
     */
    public int deleteByRoom(String roomId) {
        return deleteMatching(roomId, entry -> true);
    }

    /**
     * Delete runs strictly older than {@code cutoff} across all rooms.
     * The same room-scoped deletion primitive is used by room deletion.
     * Expired runs immediately disappear from both general and baseline queries.
     */
    public int deleteOlderThan(Instant cutoff) {
        java.util.Objects.requireNonNull(cutoff, "cutoff");
        int total = 0;
        for (String roomId : recordsByRoom.keySet()) {
            total += deleteMatching(roomId, entry -> entry.parsedRanAt.isBefore(cutoff));
        }
        return total;
    }

    /** One atomic per-room deletion operation shared by the sweep and room lifecycle. */
    private int deleteMatching(String roomId, java.util.function.Predicate<StoredEntry> predicate) {
        if (roomId == null) {
            return 0;
        }
        AtomicInteger removed = new AtomicInteger();
        recordsByRoom.computeIfPresent(roomId, (key, entries) -> {
            entries.removeIf(entry -> {
                if (predicate.test(entry)) {
                    removed.incrementAndGet();
                    return true;
                }
                return false;
            });
            return entries.isEmpty() ? null : entries;
        });
        return removed.get();
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

    /**
     * Re-key a room's run records when its link is regenerated (US-28, V2.18): the
     * room id changes, so each immutable record is rebuilt with the new room_id and
     * the newest-first ordering (sequence + timestamp) is preserved. The old id is
     * left empty so a later US-56 deletion of it purges nothing stale.
     */
    public void moveRoom(String oldRoomId, String newRoomId) {
        if (oldRoomId == null || newRoomId == null || oldRoomId.equals(newRoomId)) {
            return;
        }
        List<StoredEntry> entries = recordsByRoom.remove(oldRoomId);
        if (entries == null || entries.isEmpty()) {
            return;
        }
        List<StoredEntry> moved = new CopyOnWriteArrayList<>();
        for (StoredEntry e : entries) {
            RunRecord r = e.record;
            RunRecord rekeyed = new RunRecord(newRoomId, r.getRunnerDisplayName(), r.getRanAt(),
                    r.getLanguage(), r.getFiles(), r.getSavedVersions(), r.getInputHash(),
                    r.getExecutionTimeMs(), r.getPeakMemoryMb());
            moved.add(new StoredEntry(rekeyed, e.parsedRanAt, e.sequence));
        }
        recordsByRoom.merge(newRoomId, moved, (existing, incoming) -> {
            existing.addAll(incoming);
            return existing;
        });
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
