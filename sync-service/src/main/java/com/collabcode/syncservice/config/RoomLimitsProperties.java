package com.collabcode.syncservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurable room-level limits for sync-service (DR3, US-16).
 *
 * <p>All values are read from application.properties under the
 * {@code app.room.limits} prefix. They are NEVER hard-coded in Java source
 * (DR3). Override any value with the corresponding environment variable
 * (Spring Boot automatically maps {@code APP_ROOM_LIMITS_<KEY>} to the
 * property {@code app.room.limits.<key>}).
 *
 * <p>Values whose upstream story is not yet implemented (US-63, US-55,
 * US-73, US-24) carry a placeholder default and are commented with the
 * story reference. This entry-point is created by US-16; the later stories
 * read from it.
 */
@Component
@ConfigurationProperties(prefix = "app.room.limits")
public class RoomLimitsProperties {

    /**
     * NFR15 – maximum number of members per room.
     * (US-16 creates this entry; a later story enforces it.)
     */
    private int maxMembersPerRoom;

    /**
     * FR31.2 – how long (ms) a host may remain absent after a WebSocket
     * disconnect before the room is force-closed.
     * (US-16 creates this entry; enforcement wired in a later story.)
     */
    private long hostLeavesTimeoutMs;

    /**
     * NFR14 – aggregate wall-clock time budget (ms) across all runs in one
     * room session. Zero means no aggregate cap (placeholder until US-63).
     */
    private long aggregateRunBudgetMs;

    /**
     * NFR14 – maximum number of run requests allowed in a single room
     * session. Zero means no cap (placeholder until US-63).
     */
    private int maxRunsPerSession;

    // ----- getters and setters -----

    public int getMaxMembersPerRoom()              { return maxMembersPerRoom; }
    public void setMaxMembersPerRoom(int v)        { this.maxMembersPerRoom = v; }

    public long getHostLeavesTimeoutMs()           { return hostLeavesTimeoutMs; }
    public void setHostLeavesTimeoutMs(long v)     { this.hostLeavesTimeoutMs = v; }

    public long getAggregateRunBudgetMs()          { return aggregateRunBudgetMs; }
    public void setAggregateRunBudgetMs(long v)    { this.aggregateRunBudgetMs = v; }

    public int getMaxRunsPerSession()              { return maxRunsPerSession; }
    public void setMaxRunsPerSession(int v)        { this.maxRunsPerSession = v; }
}
