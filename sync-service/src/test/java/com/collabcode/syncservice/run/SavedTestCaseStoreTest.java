package com.collabcode.syncservice.run;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SavedTestCaseStoreTest {
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
