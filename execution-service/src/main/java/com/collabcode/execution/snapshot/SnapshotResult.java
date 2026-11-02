package com.collabcode.execution.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snapshot read response object – T-17 V2.8 (Decision 8).
 * Represents all module versions needed for one run read together in one call.
 */
public class SnapshotResult {

    private String room_id;
    private String entry;
    private List<SnapshotModule> modules;

    public SnapshotResult() {
        this.modules = new ArrayList<>();
    }

    public SnapshotResult(String room_id, String entry, List<SnapshotModule> modules) {
        this.room_id = room_id;
        this.entry = entry;
        this.modules = (modules != null) ? new ArrayList<>(modules) : new ArrayList<>();
    }

    public String getRoom_id() { return room_id; }
    public void setRoom_id(String room_id) { this.room_id = room_id; }

    public String getEntry() { return entry; }
    public void setEntry(String entry) { this.entry = entry; }

    public List<SnapshotModule> getModules() { return Collections.unmodifiableList(modules); }
    public void setModules(List<SnapshotModule> modules) {
        this.modules = (modules != null) ? new ArrayList<>(modules) : new ArrayList<>();
    }
}

