package com.collabcode.syncservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies room-level resource-limit configuration (DR3, NFR15, FR31.2, NFR14, US-16).
 *
 * <p>Test 1: default values are loaded from application.properties.
 * <p>Test 2: changing a property value via @TestPropertySource is picked up,
 *            proving that configuration (not hard-coded constants) governs behaviour.
 */
class RoomLimitsPropertiesTest {

    // -----------------------------------------------------------------------
    // 1. Default values are loaded from application.properties
    // -----------------------------------------------------------------------

    @SpringBootTest
    static class DefaultsTest {

        @Autowired
        private RoomLimitsProperties limits;

        @Test
        @DisplayName("RoomLimitsProperties binds from application.properties (US-16, DR3)")
        void roomLimits_areLoadedFromConfig() {
            assertThat(limits).isNotNull();
        }

        @Test
        @DisplayName("max-members-per-room has a positive default (NFR15)")
        void maxMembersPerRoom_hasPositiveDefault() {
            assertThat(limits.getMaxMembersPerRoom()).isGreaterThan(0);
        }

        @Test
        @DisplayName("host-leaves-timeout-ms has a positive default (FR31.2)")
        void hostLeavesTimeoutMs_hasPositiveDefault() {
            assertThat(limits.getHostLeavesTimeoutMs()).isGreaterThan(0);
        }

        @Test
        @DisplayName("aggregate-run-budget-ms has a non-negative default (NFR14 placeholder)")
        void aggregateRunBudgetMs_hasNonNegativeDefault() {
            assertThat(limits.getAggregateRunBudgetMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("max-runs-per-session has a non-negative default (NFR14 placeholder)")
        void maxRunsPerSession_hasNonNegativeDefault() {
            assertThat(limits.getMaxRunsPerSession()).isGreaterThanOrEqualTo(0);
        }
    }

    // -----------------------------------------------------------------------
    // 2. Configuration overrides are honoured (DR3 key requirement: no hard-coding)
    // -----------------------------------------------------------------------

    @SpringBootTest
    @TestPropertySource(properties = {
            "app.room.limits.max-members-per-room=3",
            "app.room.limits.host-leaves-timeout-ms=500",
            "app.room.limits.aggregate-run-budget-ms=60000",
            "app.room.limits.max-runs-per-session=5"
    })
    static class OverrideTest {

        @Autowired
        private RoomLimitsProperties limits;

        @Test
        @DisplayName("max-members-per-room can be overridden at deploy time (US-16, DR3)")
        void maxMembersPerRoom_isOverridable() {
            assertThat(limits.getMaxMembersPerRoom()).isEqualTo(3);
        }

        @Test
        @DisplayName("host-leaves-timeout-ms can be overridden at deploy time (FR31.2, US-16)")
        void hostLeavesTimeoutMs_isOverridable() {
            assertThat(limits.getHostLeavesTimeoutMs()).isEqualTo(500L);
        }

        @Test
        @DisplayName("aggregate-run-budget-ms can be overridden at deploy time (NFR14, US-16)")
        void aggregateRunBudgetMs_isOverridable() {
            assertThat(limits.getAggregateRunBudgetMs()).isEqualTo(60_000L);
        }

        @Test
        @DisplayName("max-runs-per-session can be overridden at deploy time (NFR14, US-16)")
        void maxRunsPerSession_isOverridable() {
            assertThat(limits.getMaxRunsPerSession()).isEqualTo(5);
        }
    }
}
