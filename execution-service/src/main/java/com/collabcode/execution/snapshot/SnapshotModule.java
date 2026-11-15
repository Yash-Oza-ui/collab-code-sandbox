package com.collabcode.execution.snapshot;

/**
 * Module metadata and content returned in a snapshot read – T-17 V2.8.
 */
public class SnapshotModule {

    private String module_id;
    private String name;
    private String content;
    private int version;
    private MemberInfo owner;
    private MemberInfo saved_by;
    private String saved_at;

    public SnapshotModule() {}

    public SnapshotModule(
            String module_id,
            String name,
            String content,
            int version,
            MemberInfo owner,
            MemberInfo saved_by,
            String saved_at) {
        this.module_id = module_id;
        this.name = name;
        this.content = (content != null) ? content : "";
        this.version = version;
        this.owner = owner;
        this.saved_by = saved_by;
        this.saved_at = saved_at;
    }

    public String getModule_id() { return module_id; }
    public void setModule_id(String module_id) { this.module_id = module_id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = (content != null) ? content : ""; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public MemberInfo getOwner() { return owner; }
    public void setOwner(MemberInfo owner) { this.owner = owner; }

    public MemberInfo getSaved_by() { return saved_by; }
    public void setSaved_by(MemberInfo saved_by) { this.saved_by = saved_by; }

    public String getSaved_at() { return saved_at; }
    public void setSaved_at(String saved_at) { this.saved_at = saved_at; }
}

