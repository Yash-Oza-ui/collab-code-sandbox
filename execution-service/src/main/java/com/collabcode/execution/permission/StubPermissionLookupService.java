package com.collabcode.execution.permission;

import com.collabcode.execution.exception.RoomServiceUnavailableException;
import com.collabcode.execution.exception.RunNotAllowedException;
import org.springframework.stereotype.Component;

/**
 * Deterministic in-memory stub for PermissionLookupService (T-19, Jay / T-17 V2.11).
 *
 * Provides member identity and permissions until sync-service implements session lookup (T-20).
 * Throws ROOM_SERVICE_UNAVAILABLE (503) when simulated failure is active.
 */
@Component
public class StubPermissionLookupService implements PermissionLookupService {

    private volatile boolean available = true;
    private volatile PermissionLookupResult customLookup = null;

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void setCustomLookup(PermissionLookupResult customLookup) {
        this.customLookup = customLookup;
    }

    public void reset() {
        this.available = true;
        this.customLookup = null;
    }

    @Override
    public PermissionLookupResult lookup(String roomId, String sessionId) {
        if (!available || "service-unavailable".equalsIgnoreCase(roomId)) {
            throw new RoomServiceUnavailableException(null);
        }

        if (customLookup != null) {
            return customLookup;
        }

        if (sessionId != null && sessionId.toLowerCase().contains("not_in_room")) {
            throw new RunNotAllowedException(null, "not_in_room");
        }

        String mode = (roomId != null && (roomId.toLowerCase().contains("open") || roomId.equals("k3Yc1t9Qx7mBvA2nR0sLpQ")))
                ? "open" : "pair";
        String language = "open".equals(mode) ? "python" : null;

        if (sessionId != null && sessionId.toLowerCase().contains("observer")) {
            return new PermissionLookupResult(
                    "m_4b19d2", "Lin", "observer", mode, language, false, "observer", false
            );
        }

        if (sessionId != null && sessionId.toLowerCase().contains("revoked")) {
            return new PermissionLookupResult(
                    "m_4b19d2", "Lin", "participant", mode, language, false, "run_revoked", false
            );
        }

        if (sessionId != null && sessionId.toLowerCase().contains("host_only")) {
            return new PermissionLookupResult(
                    "m_4b19d2", "Lin", "participant", mode, language, false, "host_only", false
            );
        }

        return new PermissionLookupResult(
                "m_7a3c9f", "Ada", "participant", mode, language, true, null, false
        );
    }
}

