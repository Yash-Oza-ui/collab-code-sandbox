package com.collabcode.execution.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket analysis-result message DTO conforming to T-18 Decision 6 and docs/schemas/analysis-result.json.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisResultMessage {

    @JsonProperty("type")
    private String type = "analysis-result";

    @JsonProperty("request_id")
    private String request_id;

    @JsonProperty("analysis_id")
    private String analysis_id;

    @JsonProperty("room_id")
    private String room_id;

    @JsonProperty("task")
    private String task;

    @JsonProperty("status")
    private String status; // "ok", "fallback", "error"

    @JsonProperty("ai_step")
    private AIStep ai_step;

    @JsonProperty("result")
    private Map<String, Object> result = new LinkedHashMap<>();

    @JsonProperty("message")
    private String message;

    public AnalysisResultMessage() {}

    public AnalysisResultMessage(
            String requestId,
            String analysisId,
            String roomId,
            String task,
            String status,
            AIStep aiStep,
            Map<String, Object> result,
            String message) {
        this.type = "analysis-result";
        this.request_id = requestId;
        this.analysis_id = analysisId;
        this.room_id = roomId;
        this.task = task;
        this.status = status;
        this.ai_step = aiStep;
        this.result = (result != null) ? result : new LinkedHashMap<>();
        this.message = message;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getRequest_id() { return request_id; }
    public void setRequest_id(String request_id) { this.request_id = request_id; }

    public String getAnalysis_id() { return analysis_id; }
    public void setAnalysis_id(String analysis_id) { this.analysis_id = analysis_id; }

    public String getRoom_id() { return room_id; }
    public void setRoom_id(String room_id) { this.room_id = room_id; }

    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public AIStep getAi_step() { return ai_step; }
    public void setAi_step(AIStep ai_step) { this.ai_step = ai_step; }

    public Map<String, Object> getResult() { return result; }
    public void setResult(Map<String, Object> result) { this.result = result; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    /**
     * Inner class representing ai_step.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class AIStep {
        @JsonProperty("status")
        private String status; // "ok", "skipped"

        @JsonProperty("error_code")
        private String error_code; // error code string or null

        public AIStep() {}

        public AIStep(String status, String errorCode) {
            this.status = status;
            this.error_code = errorCode;
        }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getError_code() { return error_code; }
        public void setError_code(String error_code) { this.error_code = error_code; }
    }
}
