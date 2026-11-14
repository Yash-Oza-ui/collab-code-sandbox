package com.collabcode.execution.model;

import com.collabcode.execution.snapshot.MemberInfo;

/**
 * Failing module details for an Open mode run – T-17 V2.9 (Decision 9, FR28).
 */
public class FailureInfo {

    private String module_id;
    private String name;
    private MemberInfo owner;
    private MemberInfo last_saved_by;

    public FailureInfo() {}

    public FailureInfo(String module_id, String name, MemberInfo owner, MemberInfo last_saved_by) {
        this.module_id = module_id;
        this.name = name;
        this.owner = owner;
        this.last_saved_by = last_saved_by;
    }

    public String getModule_id() { return module_id; }
    public void setModule_id(String module_id) { this.module_id = module_id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public MemberInfo getOwner() { return owner; }
    public void setOwner(MemberInfo owner) { this.owner = owner; }

    public MemberInfo getLast_saved_by() { return last_saved_by; }
    public void setLast_saved_by(MemberInfo last_saved_by) { this.last_saved_by = last_saved_by; }
}

