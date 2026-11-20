package com.collabcode.syncservice.run;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunHistoryRetentionSweepTest {
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH = InputHasher.hash("same-input");

    private static RunRecord run(String room, Instant at) {
        return new RunRecord(room, "runner", at.toString(), "python",
                List.of("main.py"), List.of(), HASH, 10.0, 2.0);
    }

    @Test
    void sweepDeletesOldKeepsNewAndPreservesOtherRoom() {
        RunRecordStore store = new RunRecordStore(CLOCK);
        store.record(run("a", NOW.minus(Duration.ofHours(25))));
        store.record(run("a", NOW.minus(Duration.ofHours(24)))); // boundary must remain
        store.record(run("a", NOW.minus(Duration.ofHours(1))));
        store.record(run("b", NOW.minus(Duration.ofMinutes(5))));

        int deleted = new RunHistoryRetentionSweep(store, CLOCK, Duration.ofHours(24)).sweep();

        assertThat(deleted).isEqualTo(1);
        assertThat(store.findByRoom("a")).hasSize(2);
        assertThat(store.findByRoomAndInputHash("a", HASH)).hasSize(2);
        assertThat(store.findByRoom("b")).hasSize(1);
        assertThat(new RunHistoryRetentionSweep(store, CLOCK, Duration.ofHours(24)).sweep()).isZero();
    }

    @Test
    void changingRetentionChangesResultWithoutCodeChange() {
        RunRecordStore store = new RunRecordStore(CLOCK);
        store.record(run("a", NOW.minus(Duration.ofHours(3))));
        assertThat(new RunHistoryRetentionSweep(store, CLOCK, Duration.ofHours(24)).sweep()).isZero();
        assertThat(new RunHistoryRetentionSweep(store, CLOCK, Duration.ofHours(2)).sweep()).isEqualTo(1);
        assertThat(store.findByRoomAndInputHash("a", HASH)).isEmpty();
    }

    @Test
    void roomDeletionUsesSamePrimitiveAndRemovesEvenRecentRecords() {
        RunRecordStore store = new RunRecordStore(CLOCK);
        store.record(run("a", NOW.minusSeconds(1)));
        store.record(run("a", NOW.minusSeconds(2)));
        store.record(run("b", NOW.minusSeconds(3)));
        assertThat(store.deleteByRoom("a")).isEqualTo(2);
        assertThat(store.findByRoom("a")).isEmpty();
        assertThat(store.findByRoomAndInputHash("a", HASH)).isEmpty();
        assertThat(store.findByRoom("b")).hasSize(1);
    }

    @Test
    void timestampWithNoValidDateIsStillEventuallyExpired() {
        RunRecordStore store = new RunRecordStore(CLOCK);
        store.record(new RunRecord("a", "runner", "invalid-time", "python",
                List.of("main.py"), List.of(), HASH, 10.0, 2.0));
        Clock later = Clock.fixed(NOW.plus(Duration.ofHours(25)), ZoneOffset.UTC);
        assertThat(new RunHistoryRetentionSweep(store, later, Duration.ofHours(24)).sweep()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveRetention() {
        RunRecordStore store = new RunRecordStore(CLOCK);
        assertThatThrownBy(() -> new RunHistoryRetentionSweep(store, CLOCK, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunHistoryRetentionSweep(store, CLOCK, Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
