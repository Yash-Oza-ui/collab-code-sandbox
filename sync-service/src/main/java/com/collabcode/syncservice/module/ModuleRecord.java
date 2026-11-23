package com.collabcode.syncservice.module;
import com.collabcode.syncservice.room.Member;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Authoritative Open-workspace module state needed by the V2.5/V2.6 boundary (FR20.1, DR4, US-39). */
public final class ModuleRecord {
    private final String moduleId;
    private final String name;
    private volatile String ownerMemberId;
    private final Set<String> writers = ConcurrentHashMap.newKeySet();
    private volatile boolean runnable = true;
    private volatile boolean isEntry = false;
    private volatile int version = 0;
    private volatile boolean pending;

    public record SavedVersion(
            int version,
            String text,
            String savedByMemberId,
            String savedByDisplayName,
            Instant savedAt) {}

    private volatile boolean dirty = false;
    private volatile String savedByMemberId;
    private volatile String savedByDisplayName;
    private volatile Instant savedAt;
    private volatile String savedText = "";
    private final List<SavedVersion> savedVersions = new ArrayList<>();

    ModuleRecord(String moduleId, String name, String ownerMemberId) {
        this(moduleId, name, ownerMemberId, true);
    }

    ModuleRecord(String moduleId, String name, String ownerMemberId, boolean pending) {
        this.moduleId = moduleId;
        this.name = name;
        this.ownerMemberId = ownerMemberId;
        this.pending = pending;
    }

    public String moduleId() { return moduleId; }
    public String name() { return name; }
    public String ownerMemberId() { return ownerMemberId; }
    public Set<String> writers() { return writers; }
    public synchronized boolean addWriter(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            return false;
        }
        return writers.add(memberId);
    }
    public synchronized boolean removeWriter(String memberId) {
        if (memberId == null) {
            return false;
        }
        return writers.remove(memberId);
    }
    public boolean runnable() { return runnable; }
    public void setRunnable(boolean runnable) { this.runnable = runnable; }
    public boolean isEntry() { return isEntry; }
    public void setEntry(boolean entry) { this.isEntry = entry; }
    public int version() { return version; }
    public void setVersion(int version) { this.version = version; }
    public boolean pending() { return pending; }

    synchronized void approve() {
        this.pending = false;
    }

    synchronized void approve(String newOwnerMemberId) {
        this.pending = false;
        if (newOwnerMemberId != null && !newOwnerMemberId.isBlank()) {
            this.ownerMemberId = newOwnerMemberId;
        }
    }

    public boolean canEdit(String memberId) {
        return !pending && memberId != null && (ownerMemberId.equals(memberId) || writers.contains(memberId));
    }

    public boolean dirty() { return dirty; }
    public String savedByMemberId() { return savedByMemberId; }
    public String savedByDisplayName() { return savedByDisplayName; }
    public Instant savedAt() { return savedAt; }
    public String savedText() { return savedText; }

    public synchronized List<SavedVersion> savedVersions() {
        return List.copyOf(savedVersions);
    }

    public synchronized boolean markDirty() {
        if (dirty) {
            return false;
        }
        dirty = true;
        return true;
    }

    public synchronized SavedVersion save(Member saver, String text, Instant when) {
        this.version += 1;
        this.savedText = text;
        this.savedByMemberId = saver.getMemberId();
        this.savedByDisplayName = saver.getDisplayName();
        this.savedAt = when;
        this.dirty = false;
        SavedVersion snapshot = new SavedVersion(
                this.version,
                text,
                this.savedByMemberId,
                this.savedByDisplayName,
                when);
        this.savedVersions.add(snapshot);
        return snapshot;
    }
}
