package com.collabcode.execution.store;

import com.collabcode.execution.analysis.AnalysisResultMessage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory store of run results in execution-service (FR27, FR32, US-49, US-56).
 *
 * Requirements:
 * - Keeps run results and analysis messages while the run record exists so that a share can be sent (T-17 V2.10).
 * - Per-room isolation: cleared when a room ends via deleteByRoom (US-56).
 * - Bounded retention with TTL (24h) and max capacity to prevent unbounded memory growth.
 * - Strict privacy invariant: Never logs code, inputs, outputs, or session IDs (NFR8.2).
 */
@Component
public class RunResultStore {

    private static final int MAX_STORED_RUNS = 2000;
    private static final Duration RUN_TTL = Duration.ofHours(24);

    private final Map<String, StoredRun> runsByRequestId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> requestIdsByRoom = new ConcurrentHashMap<>();

    public void save(StoredRun run) {
        if (run == null || run.getRequestId() == null) return;

        evictIfNecessary();

        runsByRequestId.put(run.getRequestId(), run);
        if (run.getRoomId() != null) {
            requestIdsByRoom.computeIfAbsent(run.getRoomId(), k -> ConcurrentHashMap.newKeySet())
                    .add(run.getRequestId());
        }
    }

    public Optional<StoredRun> get(String requestId) {
        if (requestId == null) return Optional.empty();
        StoredRun run = runsByRequestId.get(requestId);
        if (run != null) {
            if (Duration.between(run.getCreatedAt(), Instant.now()).compareTo(RUN_TTL) > 0) {
                remove(requestId);
                return Optional.empty();
            }
            return Optional.of(run);
        }
        return Optional.empty();
    }

    public void deleteByRoom(String roomId) {
        if (roomId == null) return;
        Set<String> requestIds = requestIdsByRoom.remove(roomId);
        if (requestIds != null) {
            for (String reqId : requestIds) {
                runsByRequestId.remove(reqId);
            }
        }
    }

    public void remove(String requestId) {
        if (requestId == null) return;
        StoredRun removed = runsByRequestId.remove(requestId);
        if (removed != null && removed.getRoomId() != null) {
            Set<String> roomSet = requestIdsByRoom.get(removed.getRoomId());
            if (roomSet != null) {
                roomSet.remove(requestId);
                if (roomSet.isEmpty()) {
                    requestIdsByRoom.remove(removed.getRoomId());
                }
            }
        }
    }

    public void addAnalysisMessage(String requestId, AnalysisResultMessage message) {
        if (requestId == null || message == null) return;
        StoredRun run = runsByRequestId.get(requestId);
        if (run != null) {
            run.addAnalysisMessage(message);
        }
    }

    public String getVisibility(String requestId) {
        if (requestId == null) return null;
        StoredRun run = runsByRequestId.get(requestId);
        return run != null ? run.getVisibility() : null;
    }

    public int size() {
        return runsByRequestId.size();
    }

    public void clear() {
        runsByRequestId.clear();
        requestIdsByRoom.clear();
    }

    private void evictIfNecessary() {
        if (runsByRequestId.size() < MAX_STORED_RUNS) {
            return;
        }
        Instant cutoff = Instant.now().minus(RUN_TTL);
        runsByRequestId.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().getCreatedAt().isBefore(cutoff);
            if (expired && entry.getValue().getRoomId() != null) {
                Set<String> roomSet = requestIdsByRoom.get(entry.getValue().getRoomId());
                if (roomSet != null) {
                    roomSet.remove(entry.getKey());
                }
            }
            return expired;
        });

        // If still over capacity, remove oldest
        if (runsByRequestId.size() >= MAX_STORED_RUNS) {
            runsByRequestId.values().stream()
                    .min(Comparator.comparing(StoredRun::getCreatedAt))
                    .ifPresent(oldest -> remove(oldest.getRequestId()));
        }
    }
}
