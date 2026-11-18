package com.collabcode.execution.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base polymorphic source object for a run request (T-17 V2.8, decision 8).
 * Exactly one source object is carried in RunRequest.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true,
        defaultImpl = UnknownSource.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = InlineSource.class, name = "inline"),
        @JsonSubTypes.Type(value = SavedSource.class, name = "saved")
})
public abstract class RunSource {

    private String kind;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
}

