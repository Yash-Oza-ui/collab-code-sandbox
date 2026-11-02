package com.collabcode.execution.model;

/**
 * A file included in a run specification or inline source (T-17 V2.8, DR4).
 */
public class RunFile {

    /**
     * Plain file name with language extension (DR4): no '/', no '\', no '..'.
     */
    private String name;

    /**
     * File content.
     */
    private String content;

    public RunFile() {}

    public RunFile(String name, String content) {
        this.name = name;
        this.content = (content != null) ? content : "";
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = (content != null) ? content : ""; }
}

