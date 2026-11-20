package com.collabcode.execution.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackMessageMapperTest {

    @ParameterizedTest
    @CsvSource({
            "CONSENT_MISSING, AI analysis is unavailable because AI consent is turned off.",
            "AI_DISABLED, AI analysis is currently disabled.",
            "COST_CAP_REACHED, AI analysis is temporarily unavailable because the monthly AI limit has been reached.",
            "ROOM_RATE_LIMITED, AI analysis is temporarily unavailable for this room. Please try again later.",
            "RATE_LIMITED_PROVIDER, AI analysis is temporarily unavailable. Please try again later.",
            "PROVIDER_ERROR, AI analysis could not be completed right now.",
            "PROVIDER_TIMEOUT, AI analysis took too long and was skipped.",
            "SERVICE_UNAVAILABLE, AI analysis service is temporarily unavailable."
    })
    @DisplayName("Each known error code maps to its expected safe human-readable message")
    void testKnownErrorCodes(String code, String expectedMessage) {
        String message = FallbackMessageMapper.getFallbackMessage(code);
        assertThat(message).isEqualTo(expectedMessage);
    }

    @Test
    @DisplayName("Unknown or null error code produces a safe generic fallback without leaking details")
    void testUnknownAndNullErrorCodes() {
        assertThat(FallbackMessageMapper.getFallbackMessage(null))
                .isEqualTo("AI analysis could not be completed right now.");
        assertThat(FallbackMessageMapper.getFallbackMessage(""))
                .isEqualTo("AI analysis could not be completed right now.");
        assertThat(FallbackMessageMapper.getFallbackMessage("   "))
                .isEqualTo("AI analysis could not be completed right now.");
        assertThat(FallbackMessageMapper.getFallbackMessage("SOME_RANDOM_INTERNAL_CODE_SQL_TIMEOUT"))
                .isEqualTo("AI analysis could not be completed right now.");
    }

    @Test
    @DisplayName("Case insensitive error code mapping")
    void testCaseInsensitivity() {
        assertThat(FallbackMessageMapper.getFallbackMessage("consent_missing"))
                .isEqualTo("AI analysis is unavailable because AI consent is turned off.");
        assertThat(FallbackMessageMapper.getFallbackMessage("  cost_cap_reached  "))
                .isEqualTo("AI analysis is temporarily unavailable because the monthly AI limit has been reached.");
    }
}
