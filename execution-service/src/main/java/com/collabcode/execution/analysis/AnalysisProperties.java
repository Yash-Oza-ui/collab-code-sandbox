package com.collabcode.execution.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for AI/ML analysis integration (US-78, NFR9, T-18).
 *
 * Configures the target ai-ml-service URL and the bounded wait timeout.
 * Read from application.yml under app.ai-ml-service.
 */
@Component
@ConfigurationProperties(prefix = "app.ai-ml-service")
public class AnalysisProperties {

    /** Base URL of ai-ml-service, default http://localhost:8000. */
    private String url = "http://localhost:8000";

    /** Maximum wall-clock seconds to wait for ai-ml-service before falling back. */
    private int timeoutSeconds = 5;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = (url != null && !url.isBlank()) ? url : "http://localhost:8000";
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = (timeoutSeconds > 0) ? timeoutSeconds : 5;
    }
}
