package com.collabcode.execution.model;

import java.util.List;

/**
 * Pair mode inline source shape (T-17 V2.8):
 * {"kind":"inline","files":[{"name":"main.py","content":"..."}],"entry":"main.py"}
 */
public class InlineSource extends RunSource {

    private List<RunFile> files;
    private String entry;

    public InlineSource() {
        setKind("inline");
    }

    public InlineSource(List<RunFile> files, String entry) {
        setKind("inline");
        this.files = files;
        this.entry = entry;
    }

    public List<RunFile> getFiles() { return files; }
    public void setFiles(List<RunFile> files) { this.files = files; }

    public String getEntry() { return entry; }
    public void setEntry(String entry) { this.entry = entry; }
}

