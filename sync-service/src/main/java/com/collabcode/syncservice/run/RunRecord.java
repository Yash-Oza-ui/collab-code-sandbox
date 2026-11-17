package com.collabcode.syncservice.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Immutable run history record held by {@code sync-service} (FR29, US-52, T-18 Decision 9).
 *
 * <p>Written atomically with the result delivery call ({@code POST /internal/rooms/{room_id}/messages})
 * for every delivered {@code run-result}. Keyed by {@code room_id} so all records are purged when
 * the room is closed or deleted (FR32, US-56), and queryable by {@code (room_id, input_hash)}
 * newest-first for slowdown baseline calculation (US-68).
 *
 * <p>Security &amp; privacy invariants (AC2, NFR8.2):
 * <ul>
 *   <li>Contains NO {@code owner_token}.</li>
 *   <li>Contains NO {@code session_id} (even if present on an incoming {@code runner} object).</li>
 *   <li>Contains NO raw {@code stdin} — only its SHA-256 {@code input_hash}.</li>
 *   <li>Contains NO source code text — {@code files} stores filenames only.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class RunRecord {

    @JsonProperty("room_id")
    private final String roomId;

    @JsonProperty("runner_display_name")
    private final String runnerDisplayName;

    @JsonProperty("ran_at")
    private final String ranAt;

    @JsonProperty("language")
    private final String language;

    @JsonProperty("files")
    private final List<String> files;

    @JsonProperty("saved_versions")
    private final List<SavedVersionSummary> savedVersions;

    @JsonProperty("input_hash")
    private final String inputHash;

    @JsonProperty("execution_time_ms")
    private final double executionTimeMs;

    @JsonProperty("peak_memory_mb")
    private final double peakMemoryMb;

    public RunRecord(
            String roomId,
            String runnerDisplayName,
            String ranAt,
            String language,
            List<String> files,
            List<SavedVersionSummary> savedVersions,
            String inputHash,
            double executionTimeMs,
            double peakMemoryMb) {
        this.roomId = roomId;
        this.runnerDisplayName = runnerDisplayName != null ? runnerDisplayName : "";
        this.ranAt = ranAt != null ? ranAt : "";
        this.language = language != null ? language : "";
        this.files = files != null ? List.copyOf(files) : Collections.emptyList();
        this.savedVersions = savedVersions != null ? List.copyOf(savedVersions) : Collections.emptyList();
        this.inputHash = inputHash != null ? inputHash : InputHasher.EMPTY_SHA256;
        this.executionTimeMs = executionTimeMs;
        this.peakMemoryMb = peakMemoryMb;
    }

    public String getRoomId() { return roomId; }
    public String getRunnerDisplayName() { return runnerDisplayName; }
    public String getRanAt() { return ranAt; }
    public String getLanguage() { return language; }
    public List<String> getFiles() { return files; }
    public List<SavedVersionSummary> getSavedVersions() { return savedVersions; }
    public String getInputHash() { return inputHash; }
    public double getExecutionTimeMs() { return executionTimeMs; }
    public double getPeakMemoryMb() { return peakMemoryMb; }
}
