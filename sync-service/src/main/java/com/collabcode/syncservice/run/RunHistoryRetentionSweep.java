package com.collabcode.syncservice.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/** FR15 / US-30: scheduled, privacy-safe execution history retention. */
@Component
public class RunHistoryRetentionSweep {
    private static final Logger log = LoggerFactory.getLogger(RunHistoryRetentionSweep.class);
    private final RunRecordStore store;
    private final Clock clock;
    private final Duration retention;

    @Autowired
    public RunHistoryRetentionSweep(RunRecordStore store,
            @Value("${app.run-history.retention-seconds}") long retentionSeconds) {
        this(store, Clock.systemUTC(), Duration.ofSeconds(retentionSeconds));
    }

    /** Clock and duration are injectable so tests require no sleeping. */
    public RunHistoryRetentionSweep(RunRecordStore store, Clock clock, Duration retention) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("History retention must be positive");
        }
        this.store = java.util.Objects.requireNonNull(store);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.run-history.sweep-interval-ms}")
    public int sweep() {
        int deleted = store.deleteOlderThan(clock.instant().minus(retention));
        // Counts only: never log run contents, code, input hashes or identities (NFR8.2).
        log.info("Execution history retention sweep deleted {} records", deleted);
        return deleted;
    }
}
