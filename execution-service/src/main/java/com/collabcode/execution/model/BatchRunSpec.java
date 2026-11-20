package com.collabcode.execution.model;

import java.util.Collections;
import java.util.List;

/**
 * Specification object for running a batch of test inputs in one container (FR38.4, FR5.1, US-71).
 */
public class BatchRunSpec {

    private final String language;
    private final List<RunFile> files;
    private final String entry;
    private final List<String> inputs;
    private final List<String> expectedOutputs;

    public BatchRunSpec(String language, List<RunFile> files, String entry, List<String> inputs) {
        this(language, files, entry, inputs, null);
    }

    public BatchRunSpec(
            String language,
            List<RunFile> files,
            String entry,
            List<String> inputs,
            List<String> expectedOutputs) {
        this.language = language;
        this.files = (files != null) ? List.copyOf(files) : Collections.emptyList();
        this.entry = entry;
        this.inputs = (inputs != null) ? List.copyOf(inputs) : Collections.emptyList();
        this.expectedOutputs = (expectedOutputs != null) ? List.copyOf(expectedOutputs) : Collections.emptyList();
    }

    public String getLanguage() { return language; }
    public List<RunFile> getFiles() { return files; }
    public String getEntry() { return entry; }
    public List<String> getInputs() { return inputs; }
    public List<String> getExpectedOutputs() { return expectedOutputs; }
}
