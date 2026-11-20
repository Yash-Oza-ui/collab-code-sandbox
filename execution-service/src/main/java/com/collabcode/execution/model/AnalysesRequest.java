package com.collabcode.execution.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request payload for POST /v1/analyses (Decision 4, §15; docs/schemas/analyses-request.json).
 */
public class AnalysesRequest {

    public static final String UUID_V4_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

    @NotBlank(message = "request_id is required")
    @Pattern(regexp = UUID_V4_REGEX, message = "request_id must be a UUID v4")
    private String request_id;

    @NotBlank(message = "room_id is required")
    private String room_id;

    @NotBlank(message = "task is required")
    private String task;

    @NotBlank(message = "language is required")
    private String language;

    @NotNull(message = "consent is required")
    private Boolean consent;

    private RunSource source;

    private String run_request_id;

    private String stdin;

    private String code;

    public String getRequest_id() { return request_id; }
    public void setRequest_id(String request_id) { this.request_id = request_id; }

    public String getRoom_id() { return room_id; }
    public void setRoom_id(String room_id) { this.room_id = room_id; }

    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Boolean getConsent() { return consent; }
    public void setConsent(Boolean consent) { this.consent = consent; }

    public RunSource getSource() { return source; }
    public void setSource(RunSource source) { this.source = source; }

    public String getRun_request_id() { return run_request_id; }
    public void setRun_request_id(String run_request_id) { this.run_request_id = run_request_id; }

    public String getStdin() { return stdin; }
    public void setStdin(String stdin) { this.stdin = stdin; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}
