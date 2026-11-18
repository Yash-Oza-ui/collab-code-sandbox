package com.collabcode.syncservice.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable summary of one Open-mode saved module version included in a {@link RunRecord}
 * (T-17 V2.9, FR26, FR29, US-52).
 *
 * <p>Persists only safe metadata ({@code module_id}, {@code name}, {@code version},
 * {@code saved_by} with public {@code member_id} and {@code display_name}, and {@code saved_at}).
 * Never stores source code, {@code session_id}, or {@code owner_token}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class SavedVersionSummary {

    @JsonProperty("module_id")
    private final String moduleId;

    @JsonProperty("name")
    private final String name;

    @JsonProperty("version")
    private final int version;

    @JsonProperty("saved_by")
    private final SavedBySummary savedBy;

    @JsonProperty("saved_at")
    private final String savedAt;

    public SavedVersionSummary(
            String moduleId,
            String name,
            int version,
            SavedBySummary savedBy,
            String savedAt) {
        this.moduleId = moduleId;
        this.name = name;
        this.version = version;
        this.savedBy = savedBy;
        this.savedAt = savedAt;
    }

    public String getModuleId() { return moduleId; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public SavedBySummary getSavedBy() { return savedBy; }
    public String getSavedAt() { return savedAt; }

    /**
     * Public saver identity (member_id and display_name only — never session_id).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static final class SavedBySummary {

        @JsonProperty("member_id")
        private final String memberId;

        @JsonProperty("display_name")
        private final String displayName;

        public SavedBySummary(String memberId, String displayName) {
            this.memberId = memberId;
            this.displayName = displayName;
        }

        public String getMemberId() { return memberId; }
        public String getDisplayName() { return displayName; }
    }
}
