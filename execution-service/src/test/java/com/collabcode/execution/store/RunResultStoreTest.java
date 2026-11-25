package com.collabcode.execution.store;

import com.collabcode.execution.analysis.AnalysisResultMessage;
import com.collabcode.execution.model.RunResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RunResultStoreTest {

    private RunResultStore store;

    @BeforeEach
    void setUp() {
        store = new RunResultStore();
    }

    @Test
    @DisplayName("Saves and retrieves a stored run result")
    void saveAndGet() {
        String reqId = "11111111-1111-4111-8111-111111111111";
        RunResponse resp = new RunResponse();
        resp.setRequest_id(reqId);
        resp.setVisibility("private");

        StoredRun run = new StoredRun(reqId, "room-1", "sess-1", "private", resp, Instant.now());
        store.save(run);

        Optional<StoredRun> found = store.get(reqId);
        assertThat(found).isPresent();
        assertThat(found.get().getRequestId()).isEqualTo(reqId);
        assertThat(found.get().getRoomId()).isEqualTo("room-1");
        assertThat(found.get().getSessionId()).isEqualTo("sess-1");
        assertThat(found.get().getVisibility()).isEqualTo("private");
        assertThat(store.getVisibility(reqId)).isEqualTo("private");
    }

    @Test
    @DisplayName("deleteByRoom removes all runs for the specified room (US-56, FR32)")
    void deleteByRoom_removesOnlyTargetRoomRuns() {
        String req1 = "11111111-1111-4111-8111-111111111111";
        String req2 = "22222222-2222-4222-8222-222222222222";
        String req3 = "33333333-3333-4333-8333-333333333333";

        store.save(new StoredRun(req1, "room-A", "sess-1", "private", new RunResponse(), Instant.now()));
        store.save(new StoredRun(req2, "room-A", "sess-2", "room", new RunResponse(), Instant.now()));
        store.save(new StoredRun(req3, "room-B", "sess-3", "private", new RunResponse(), Instant.now()));

        assertThat(store.size()).isEqualTo(3);

        store.deleteByRoom("room-A");

        assertThat(store.get(req1)).isEmpty();
        assertThat(store.get(req2)).isEmpty();
        assertThat(store.get(req3)).isPresent();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("addAnalysisMessage appends analysis messages to stored run")
    void addAnalysisMessage_recordsMessages() {
        String reqId = "11111111-1111-4111-8111-111111111111";
        store.save(new StoredRun(reqId, "room-1", "sess-1", "private", new RunResponse(), Instant.now()));

        AnalysisResultMessage msg = new AnalysisResultMessage(
                reqId, "ana-1", "room-1", "complexity", "ok",
                new AnalysisResultMessage.AIStep("ok", null),
                Collections.emptyMap(), "Analysis done"
        );

        store.addAnalysisMessage(reqId, msg);

        StoredRun stored = store.get(reqId).orElseThrow();
        assertThat(stored.getAnalysisMessages()).hasSize(1);
        assertThat(stored.getAnalysisMessages().get(0).getAnalysis_id()).isEqualTo("ana-1");
    }

    @Test
    @DisplayName("Tracking roomBroadcastAnalysisIds prevents duplicate broadcast")
    void roomBroadcastAnalysisIds_preventsDuplicates() {
        String reqId = "11111111-1111-4111-8111-111111111111";
        StoredRun run = new StoredRun(reqId, "room-1", "sess-1", "private", new RunResponse(), Instant.now());
        store.save(run);

        assertThat(run.getRoomBroadcastAnalysisIds().add("ana-1")).isTrue();
        assertThat(run.getRoomBroadcastAnalysisIds().add("ana-1")).isFalse();
    }
}
