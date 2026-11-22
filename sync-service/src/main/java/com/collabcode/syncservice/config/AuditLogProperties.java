package com.collabcode.syncservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurable audit log limits for sync-service (DR3, FR34, US-58).
 *
 * <p>Values are read from application.properties under the {@code app.audit-log} prefix.
 * Override with the environment variable {@code APP_AUDIT_LOG_MAX_ENTRIES}.
 */
@Component
@ConfigurationProperties(prefix = "app.audit-log")
public class AuditLogProperties {

    /**
     * Maximum audit log entries kept in memory per room.
     * When capacity is reached, the oldest entries are dropped.
     */
    private int maxEntries = 100;

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }
}
