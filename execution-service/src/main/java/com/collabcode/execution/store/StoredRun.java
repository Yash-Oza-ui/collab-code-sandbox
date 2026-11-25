package com.collabcode.execution.store;

import com.collabcode.execution.analysis.AnalysisResultMessage;
import com.collabcode.execution.model.RunResponse;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory representation of an executed run result held for sharing (T-17 V2.10, FR27).
 */
public class StoredRun {
    private final String requestId;
    private final String roomId;
    private final String sessionId;
    private volatile String visibility;
    private final RunResponse runResponse;
    private final List<AnalysisResultMessage> analysisMessages = new CopyOnWriteArrayList<>();
    private final Set<String> roomBroadcastAnalysisIds = ConcurrentHashMap.newKeySet();
    private final Instant createdAt;

    public StoredRun(
            String requestId,
            String roomId,
            String sessionId,
            String visibility,
            RunResponse runResponse,
            Instant createdAt) {
        this.requestId = requestId;
        this.roomId = roomId;
        this.sessionId = sessionId;
        this.visibility = visibility;
        this.runResponse = runResponse;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public RunResponse getRunResponse() {
        return runResponse;
    }

    public List<AnalysisResultMessage> getAnalysisMessages() {
        return analysisMessages;
    }

    public Set<String> getRoomBroadcastAnalysisIds() {
        return roomBroadcastAnalysisIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void addAnalysisMessage(AnalysisResultMessage msg) {
        if (msg != null) {
            analysisMessages.add(msg);
        }
    }
}
