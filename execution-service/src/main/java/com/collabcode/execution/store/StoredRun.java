package com.collabcode.execution.store;

import com.collabcode.execution.analysis.AnalysisResultMessage;
import com.collabcode.execution.model.RunResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory representation of an executed run result held for sharing (T-17 V2.10, FR27).
 *
 * Coordinates thread-safe, atomic private-to-room visibility transitions and per-run
 * analysis message registration and room-delivery claiming without duplicate broadcasts.
 */
public class StoredRun {

    public enum AnalysisBroadcastAction {
        BROADCAST_PRIVATE,
        BROADCAST_ROOM,
        NONE
    }

    public static class ShareResult {
        private final boolean newlyShared;
        private final List<AnalysisResultMessage> analysisMessagesToBroadcast;

        public ShareResult(boolean newlyShared, List<AnalysisResultMessage> analysisMessagesToBroadcast) {
            this.newlyShared = newlyShared;
            this.analysisMessagesToBroadcast = analysisMessagesToBroadcast != null
                    ? Collections.unmodifiableList(analysisMessagesToBroadcast)
                    : Collections.emptyList();
        }

        public boolean isNewlyShared() {
            return newlyShared;
        }

        public List<AnalysisResultMessage> getAnalysisMessagesToBroadcast() {
            return analysisMessagesToBroadcast;
        }
    }

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

    public synchronized void setVisibility(String visibility) {
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

    /**
     * Atomically transitions visibility from private to room, claiming any analysis messages
     * registered so far for room broadcast.
     *
     * If the run was already shared (or in Pair mode with room visibility), returns newlyShared = false
     * and an empty list of messages, guaranteeing strictly idempotent sharing.
     */
    public synchronized ShareResult share() {
        if ("room".equalsIgnoreCase(this.visibility)) {
            return new ShareResult(false, Collections.emptyList());
        }
        this.visibility = "room";
        if (this.runResponse != null) {
            this.runResponse.setVisibility("room");
        }
        List<AnalysisResultMessage> toBroadcast = new ArrayList<>();
        for (AnalysisResultMessage msg : this.analysisMessages) {
            if (msg != null && msg.getAnalysis_id() != null) {
                if (this.roomBroadcastAnalysisIds.add(msg.getAnalysis_id())) {
                    toBroadcast.add(msg);
                }
            }
        }
        return new ShareResult(true, toBroadcast);
    }

    /**
     * Registers an analysis message produced for this run and decides the delivery audience.
     *
     * If visibility is currently "private", stores the message and returns BROADCAST_PRIVATE.
     * If visibility is currently "room", claims room delivery via roomBroadcastAnalysisIds and returns
     * BROADCAST_ROOM (or NONE if already claimed by a concurrent share).
     */
    public synchronized AnalysisBroadcastAction registerAnalysisMessage(AnalysisResultMessage message) {
        if (message == null) {
            return AnalysisBroadcastAction.NONE;
        }
        this.analysisMessages.add(message);
        if ("room".equalsIgnoreCase(this.visibility)) {
            String aid = message.getAnalysis_id();
            if (aid != null && this.roomBroadcastAnalysisIds.add(aid)) {
                return AnalysisBroadcastAction.BROADCAST_ROOM;
            } else {
                return AnalysisBroadcastAction.NONE;
            }
        } else {
            return AnalysisBroadcastAction.BROADCAST_PRIVATE;
        }
    }

    public synchronized void addAnalysisMessage(AnalysisResultMessage msg) {
        registerAnalysisMessage(msg);
    }
}
