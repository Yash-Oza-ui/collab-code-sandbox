package com.collabcode.syncservice.module;

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

    /**
     * US-42: Transfer ownership of an existing module.
     * Does not change its pending status, content,
     * writers, runnable status, entry status or version.
     */
    public synchronized void transferOwnership(String newOwnerMemberId) {
        if (newOwnerMemberId == null || newOwnerMemberId.isBlank()) {
            throw new IllegalArgumentException("INVALID_OWNER");
        }
        this.ownerMemberId = newOwnerMemberId;
    }

    public boolean canEdit(String memberId) {
        return !pending && memberId != null
                && (ownerMemberId.equals(memberId) || writers.contains(memberId));
    }
}
