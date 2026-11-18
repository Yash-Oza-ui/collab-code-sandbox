package com.collabcode.execution.model;

import java.util.Collections;
import java.util.List;

/**
 * The spec object passed to CodeRunner.execute(spec) – Backlog v2 §6.1, Decision 8 (T-19).
 *
 * Decouples CodeRunner from whether source files originated from inline client payloads
 * or a server-side saved module snapshot read.
 */
public class RunSpec {

    private final String language;
    private final List<RunFile> files;
    private final String entry;
    private final String stdin;

    public RunSpec(String language, List<RunFile> files, String entry, String stdin) {
        this.language = language;
        this.files = (files != null) ? List.copyOf(files) : Collections.emptyList();
        this.entry = entry;
        this.stdin = (stdin != null) ? stdin : "";
    }

    public String getLanguage() { return language; }
    public List<RunFile> getFiles() { return files; }
    public String getEntry() { return entry; }
    public String getStdin() { return stdin; }
}

