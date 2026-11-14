package com.collabcode.execution.snapshot;

import com.collabcode.execution.model.SavedSource;

/**
 * Saved version reader interface – Backlog v2 §6.1, Decision 8 (T-19, Jay).
 *
 * One call returns all requested module versions together (consistent snapshot rule).
 * Stubbed until sync-service implements saved modules (US-39, US-45, US-47).
 */
public interface SavedVersionReader {

    /**
     * Reads all requested module versions for the run in one call.
     *
     * @param roomId room identifier
     * @param source saved source specification
     * @return a {@link SnapshotResult} containing entry and modules
     */
    SnapshotResult readSnapshot(String roomId, SavedSource source);
}

