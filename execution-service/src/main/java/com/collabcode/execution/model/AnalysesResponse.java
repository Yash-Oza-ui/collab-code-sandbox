package com.collabcode.execution.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response payload for POST /v1/analyses (Decision 4, §15.2; docs/schemas/analyses-response.json).
 */
public class AnalysesResponse {

    @JsonProperty("request_id")
    private String request_id;

    @JsonProperty("analysis_id")
    private String analysis_id;

    @JsonProperty("status")
    private String status = "accepted";

    @JsonProperty("message")
    private String message = "Analysis started in background. Results will arrive via WebSocket.";

    public AnalysesResponse() {}

    public AnalysesResponse(String requestId, String analysisId) {
        this.request_id = requestId;
        this.analysis_id = analysisId;
        this.status = "accepted";
        this.message = "Analysis started in background. Results will arrive via WebSocket.";
    }

    public AnalysesResponse(String requestId, String analysisId, String status, String message) {
        this.request_id = requestId;
        this.analysis_id = analysisId;
        this.status = status;
        this.message = message;
    }

    public String getRequest_id() { return request_id; }
    public void setRequest_id(String request_id) { this.request_id = request_id; }

    public String getAnalysis_id() { return analysis_id; }
    public void setAnalysis_id(String analysis_id) { this.analysis_id = analysis_id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
