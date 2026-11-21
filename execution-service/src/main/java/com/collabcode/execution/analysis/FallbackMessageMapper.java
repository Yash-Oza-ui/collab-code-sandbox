package com.collabcode.execution.analysis;

import java.util.Map;

/**
 * Centralized fallback message mapper (US-78, T-18 Decision 10, FR40.5).
 *
 * Maps error codes from ai-ml-service (or service failure conditions) into
 * short, student-understandable, safe user-facing fallback messages.
 *
 * Privacy & Security Invariant:
 * Never exposes raw internal errors, stack traces, provider secrets, token counts,
 * budgets, or internal URLs to the user or client.
 */
public final class FallbackMessageMapper {

    public static final String MSG_CONSENT_MISSING =
            "AI analysis is unavailable because AI consent is turned off.";

    public static final String MSG_AI_DISABLED =
            "AI analysis is currently disabled.";

    public static final String MSG_COST_CAP_REACHED =
            "AI analysis is temporarily unavailable because the monthly AI limit has been reached.";

    public static final String MSG_ROOM_RATE_LIMITED =
            "AI analysis is temporarily unavailable for this room. Please try again later.";

    public static final String MSG_RATE_LIMITED_PROVIDER =
            "AI analysis is temporarily unavailable. Please try again later.";

    public static final String MSG_PROVIDER_ERROR =
            "AI analysis could not be completed right now.";

    public static final String MSG_PROVIDER_TIMEOUT =
            "AI analysis took too long and was skipped.";

    public static final String MSG_SERVICE_UNAVAILABLE =
            "AI analysis service is temporarily unavailable.";

    public static final String MSG_GENERIC_FALLBACK =
            "AI analysis could not be completed right now.";

    private static final Map<String, String> CODE_TO_MESSAGE = Map.of(
            "CONSENT_MISSING", MSG_CONSENT_MISSING,
            "AI_DISABLED", MSG_AI_DISABLED,
            "COST_CAP_REACHED", MSG_COST_CAP_REACHED,
            "ROOM_RATE_LIMITED", MSG_ROOM_RATE_LIMITED,
            "RATE_LIMITED_PROVIDER", MSG_RATE_LIMITED_PROVIDER,
            "PROVIDER_ERROR", MSG_PROVIDER_ERROR,
            "PROVIDER_TIMEOUT", MSG_PROVIDER_TIMEOUT,
            "SERVICE_UNAVAILABLE", MSG_SERVICE_UNAVAILABLE
    );

    private FallbackMessageMapper() {
        // utility class
    }

    /**
     * Map an error code or failure condition to its safe human-readable fallback message.
     *
     * @param errorCode the error code from ai_step or system failure (e.g. SERVICE_UNAVAILABLE)
     * @return a plain language, student-friendly message without internal details
     */
    public static String getFallbackMessage(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return MSG_GENERIC_FALLBACK;
        }
        return CODE_TO_MESSAGE.getOrDefault(errorCode.trim().toUpperCase(), MSG_GENERIC_FALLBACK);
    }
}
