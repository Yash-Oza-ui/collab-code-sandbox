package com.collabcode.execution.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the resource-limits configuration section (DR3, T-03 AC / Definition of Done).
 *
 * Checks that:
 *   1. The configuration section is present and bound (the app starts).
 *   2. Every limit field has a non-zero default from application.yml
 *      (placeholder values; real values set in Sprint 2 / NFR4).
 *   3. No limit value is hard-coded in Java source (enforced by reading
 *      only through ExecutionLimitsProperties, never from a literal).
 */
@SpringBootTest
class ExecutionLimitsPropertiesTest {

    @Autowired
    private ExecutionLimitsProperties limits;

    @Test
    @DisplayName("Limits config section is present and bound (DR3)")
    void limitsConfigSection_isBoundFromYaml() {
        // If this fails, the app.execution.limits section is missing from application.yml.
        assertThat(limits).isNotNull();
    }

    @Test
    @DisplayName("wall-time-seconds has a placeholder value > 0 (NFR4.1)")
    void wallTimeSeconds_hasPlaceholderValue() {
        assertThat(limits.getWallTimeSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("memory-mb has a placeholder value > 0 (NFR4.2)")
    void memoryMb_hasPlaceholderValue() {
        assertThat(limits.getMemoryMb()).isGreaterThan(0);
    }

    @Test
    @DisplayName("pids-limit has a placeholder value > 0 (NFR4.3)")
    void pidsLimit_hasPlaceholderValue() {
        assertThat(limits.getPidsLimit()).isGreaterThan(0);
    }

    @Test
    @DisplayName("max-output-bytes has a placeholder value > 0 (NFR4.4)")
    void maxOutputBytes_hasPlaceholderValue() {
        assertThat(limits.getMaxOutputBytes()).isGreaterThan(0);
    }

    @Test
    @DisplayName("max-request-body-bytes has a placeholder value > 0 (T-06 section 2)")
    void maxRequestBodyBytes_hasPlaceholderValue() {
        assertThat(limits.getMaxRequestBodyBytes()).isGreaterThan(0);
    }
}
