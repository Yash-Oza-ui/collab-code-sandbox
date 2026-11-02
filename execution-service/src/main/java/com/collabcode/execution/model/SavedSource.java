package com.collabcode.execution.model;

/**
 * Open mode saved source shape (T-17 V2.8):
 * One runnable module: {"kind":"saved","target":"module","module_id":"..."}
 * Through entry file:  {"kind":"saved","target":"entry","module_ids":["..."]} or "module_ids":"all"
 */
public class SavedSource extends RunSource {

    private String target;
    private String module_id;
    private Object module_ids;

    public SavedSource() {
        setKind("saved");
    }

    public SavedSource(String target, String moduleId) {
        setKind("saved");
        this.target = target;
        this.module_id = moduleId;
    }

    public SavedSource(String target, Object moduleIds, Void unused) {
        setKind("saved");
        this.target = target;
        this.module_ids = moduleIds;
    }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getModule_id() { return module_id; }
    public void setModule_id(String module_id) { this.module_id = module_id; }

    public Object getModule_ids() { return module_ids; }
    public void setModule_ids(Object module_ids) { this.module_ids = module_ids; }
}

