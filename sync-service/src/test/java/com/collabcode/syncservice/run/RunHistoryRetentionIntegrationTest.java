package com.collabcode.syncservice.run;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring context wiring integration of configured retention and the real run store. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.run-history.retention-seconds=3600",
                "app.run-history.sweep-interval-ms=3600000"
        }
)
class RunHistoryRetentionIntegrationTest {
    @Autowired private RunRecordStore store;
    @Autowired private RunHistoryRetentionSweep sweep;

    @Test
    void configuredRetentionIsAppliedToRealStoreAndRoomDeletionStillWorks() {
        String room = "us30-integration-" + java.util.UUID.randomUUID();
        String hash = InputHasher.hash("integration");
        Instant now = Instant.now();
        store.record(new RunRecord(room, "test", now.minus(2, ChronoUnit.HOURS).toString(),
                "python", List.of(), List.of(), hash, 1.0, 1.0));
        store.record(new RunRecord(room, "test", now.minus(5, ChronoUnit.MINUTES).toString(),
                "python", List.of(), List.of(), hash, 1.0, 1.0));
        try {
            assertThat(sweep.sweep()).isGreaterThanOrEqualTo(1);
            assertThat(store.findByRoomAndInputHash(room, hash)).hasSize(1);
            assertThat(store.deleteByRoom(room)).isEqualTo(1);
            assertThat(store.findByRoom(room)).isEmpty();
        } finally {
            store.deleteByRoom(room);
        }
    }
}
