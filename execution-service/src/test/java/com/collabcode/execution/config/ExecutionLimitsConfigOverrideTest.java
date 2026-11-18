package com.collabcode.execution.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-16 (DR3) acceptance test: verifies that every execution-service limit
 * can be overridden at deploy time via configuration (environment variable /
 * profile file) without any code change.
 *
 * <p>Each test sets a property to a non-default value and asserts the bean
 * reflects it, proving no value is hard-coded in Java source.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.execution.limits.wall-time-seconds=2",
        "app.execution.limits.memory-mb=64",
        "app.execution.limits.pids-limit=16",
        "app.execution.limits.max-output-bytes=512",
        "app.execution.limits.compile-timeout-seconds=3",
        "app.execution.limits.max-request-body-bytes=256",
        "app.execution.limits.max-queue-length=2",
        "app.execution.limits.queue-retry-after-seconds=1",
        "app.execution.limits.aggregate-time-budget-ms=4500",
        "app.execution.limits.max-runs-per-request=4",
        "app.execution.limits.budget-retry-after-seconds=12"
})
class ExecutionLimitsConfigOverrideTest {

    @Autowired
    private ExecutionLimitsProperties limits;

    @Test
    @DisplayName("wall-time-seconds can be overridden at deploy time (NFR4.1, US-16)")
    void wallTimeSeconds_isOverridable() {
        assertThat(limits.getWallTimeSeconds()).isEqualTo(2);
    }

    @Test
    @DisplayName("memory-mb can be overridden at deploy time (NFR4.2, US-16)")
    void memoryMb_isOverridable() {
        assertThat(limits.getMemoryMb()).isEqualTo(64);
    }

    @Test
    @DisplayName("pids-limit can be overridden at deploy time (NFR4.3, US-16)")
    void pidsLimit_isOverridable() {
        assertThat(limits.getPidsLimit()).isEqualTo(16);
    }

    @Test
    @DisplayName("max-output-bytes can be overridden at deploy time (NFR4.4, US-16)")
    void maxOutputBytes_isOverridable() {
        assertThat(limits.getMaxOutputBytes()).isEqualTo(512L);
    }

    @Test
    @DisplayName("compile-timeout-seconds can be overridden at deploy time (NFR4.5, US-16)")
    void compileTimeoutSeconds_isOverridable() {
        assertThat(limits.getCompileTimeoutSeconds()).isEqualTo(3);
    }

    @Test
    @DisplayName("max-request-body-bytes can be overridden at deploy time (DR5, US-16)")
    void maxRequestBodyBytes_isOverridable() {
        assertThat(limits.getMaxRequestBodyBytes()).isEqualTo(256L);
    }

    @Test
    @DisplayName("max-queue-length can be overridden at deploy time (FR16, US-16)")
    void maxQueueLength_isOverridable() {
        assertThat(limits.getMaxQueueLength()).isEqualTo(2);
    }

    @Test
    @DisplayName("queue-retry-after-seconds can be overridden at deploy time (FR16, US-16)")
    void queueRetryAfterSeconds_isOverridable() {
        assertThat(limits.getQueueRetryAfterSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("aggregate-time-budget-ms can be overridden at deploy time (NFR14, US-64)")
    void aggregateTimeBudgetMs_isOverridable() {
        assertThat(limits.getAggregateTimeBudgetMs()).isEqualTo(4500L);
    }

    @Test
    @DisplayName("max-runs-per-request can be overridden at deploy time (NFR14, US-64)")
    void maxRunsPerRequest_isOverridable() {
        assertThat(limits.getMaxRunsPerRequest()).isEqualTo(4);
    }

    @Test
    @DisplayName("budget-retry-after-seconds can be overridden at deploy time (NFR14, US-64)")
    void budgetRetryAfterSeconds_isOverridable() {
        assertThat(limits.getBudgetRetryAfterSeconds()).isEqualTo(12);
    }
}
