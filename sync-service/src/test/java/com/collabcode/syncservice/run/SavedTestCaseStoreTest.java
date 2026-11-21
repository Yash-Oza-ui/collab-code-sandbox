package com.collabcode.syncservice.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SavedTestCaseStoreTest {
    @Test
    void savedTestResponseUsesSnakeCaseAndDoesNotContainCode() throws Exception {
        var saved = new SavedTestCaseStore().save("room", "member", "1 2", "wrong_output", "python", "3");
        String json = new ObjectMapper().writeValueAsString(saved);
        assertThat(json).contains("\"failure_type\"", "\"expected_output\"");
        assertThat(json).doesNotContain("failureType", "expectedOutput", "\"code\"");
    }

    @Test
    void saveGuardRefusesInsertionAfterRoomHasClosed() {
        SavedTestCaseStore store = new SavedTestCaseStore();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                store.save("room", "member", "BUG", "crash", "python", null, () -> false));
        assertThat(store.list("room", "member")).isEmpty();
    }

    @Test
    void savesOnlyDataAndRemovesItWithRoom() {
        SavedTestCaseStore store = new SavedTestCaseStore();
        var saved = store.save("room-A", "member-A", "1 2 3", "wrong_output", "python", "6");
        assertThat(saved.input()).isEqualTo("1 2 3");
        assertThat(saved.expectedOutput()).isEqualTo("6");
        assertThat(store.list("room-A", "member-A")).hasSize(1);
        assertThat(store.list("room-A", "member-B")).isEmpty();
        store.deleteByRoom("room-A");
        assertThat(store.list("room-A", "member-A")).isEmpty();
    }
}
