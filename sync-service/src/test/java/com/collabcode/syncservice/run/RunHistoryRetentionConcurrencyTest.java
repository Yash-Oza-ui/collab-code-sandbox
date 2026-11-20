package com.collabcode.syncservice.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression for insertion racing with an expiry sweep on the same room key. */
class RunHistoryRetentionConcurrencyTest {
    @Test
    void concurrentSweepDoesNotDropFreshRecords() throws Exception {
        RunRecordStore store = new RunRecordStore();
        String roomId = "retention-race";
        String hash = InputHasher.hash("same-input");
        Instant cutoff = Instant.parse("2026-11-20T00:00:00Z");
        int freshRuns = 500;
        // Keep creating/removing the room's map entry while fresh runs arrive.
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> sweeper = pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < freshRuns; i++) {
                        store.record(new RunRecord(roomId, "old", cutoff.minusSeconds(1).toString(),
                                "python", List.of("main.py"), List.of(), hash, 10.0, 1.0));
                        store.deleteOlderThan(cutoff);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            Future<?> writer = pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < freshRuns; i++) {
                        store.record(new RunRecord(roomId, "fresh", cutoff.plusSeconds(1).toString(),
                                "python", List.of("main.py"), List.of(), hash, 10.0, 1.0));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            start.countDown();
            sweeper.get(15, TimeUnit.SECONDS);
            writer.get(15, TimeUnit.SECONDS);
            store.deleteOlderThan(cutoff);
            assertThat(store.findByRoom(roomId)).hasSize(freshRuns)
                    .allSatisfy(run -> assertThat(run.getRunnerDisplayName()).isEqualTo("fresh"));
        } finally {
            pool.shutdownNow();
        }
    }
}
