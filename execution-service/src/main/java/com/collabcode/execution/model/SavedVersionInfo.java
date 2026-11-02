package com.collabcode.execution.model;

import com.collabcode.execution.snapshot.MemberInfo;

/**
 * Saved version metadata for one module in the snapshot – T-17 V2.9 (Decision 9).
 */
public class SavedVersionInfo {

    private String module_id;
    private String name;
    private int version;
    private MemberInfo saved_by;
    private String saved_at;

    public SavedVersionInfo() {}

    public SavedVersionInfo(String module_id, String name, int version, MemberInfo saved_by, String saved_at) {
        this.module_id = module_id;
        this.name = name;
        this.version = version;
        this.saved_by = saved_by;
        this.saved_at = saved_at;
    }

    public String getModule_id() { return module_id; }
    public void setModule_id(String module_id) { this.module_id = module_id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public MemberInfo getSaved_by() { return saved_by; }
    public void setSaved_by(MemberInfo saved_by) { this.saved_by = saved_by; }

    public String getSaved_at() { return saved_at; }
    public void setSaved_at(String saved_at) { this.saved_at = saved_at; }
}

