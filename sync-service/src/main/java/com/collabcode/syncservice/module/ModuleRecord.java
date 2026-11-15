package com.collabcode.syncservice.module;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Authoritative Open-workspace module state needed by the V2.5/V2.6 boundary. */
public final class ModuleRecord {
    private final String moduleId;
    private final String name;
    private final String ownerMemberId;
    private final Set<String> writers = ConcurrentHashMap.newKeySet();
    private volatile boolean pending = true;

    ModuleRecord(String moduleId, String name, String ownerMemberId) {
        this.moduleId = moduleId;
        this.name = name;
        this.ownerMemberId = ownerMemberId;
    }

    public String moduleId() { return moduleId; }
    public String name() { return name; }
    public String ownerMemberId() { return ownerMemberId; }
    public boolean pending() { return pending; }
    void approve() { pending = false; }
    public boolean canEdit(String memberId) {
        return !pending && (ownerMemberId.equals(memberId) || writers.contains(memberId));
    }
}
