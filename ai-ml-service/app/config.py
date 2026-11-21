import logging
import math
import os
from typing import Optional
from dotenv import load_dotenv

logger = logging.getLogger(__name__)

# Load environment variables from .env file if present
load_dotenv()


class Settings:
    """Service configuration loaded from environment variables.

    Every value is a DR3 configurable limit (US-16). No value is hard-coded;
    each is read from the environment with a sensible default.
    Override any value by setting the corresponding environment variable.
    See .env.example and docs/host-config.md for the full table.
    """

    def __init__(self) -> None:
        self.PORT: int = int(os.getenv("PORT", "8000"))
        self.EXECUTION_SERVICE_URL: str = os.getenv("EXECUTION_SERVICE_URL", "http://execution-service:8080")
        self.FAILING_INPUT_MAX_RUNS: int = max(1, min(10, int(os.getenv("FAILING_INPUT_MAX_RUNS", "8"))))
        self.FAILING_INPUT_BUDGET_SECONDS: float = min(20.0, max(1.0, float(os.getenv("FAILING_INPUT_BUDGET_SECONDS", "20"))))
        self.FAILING_INPUT_HTTP_TIMEOUT_SECONDS: float = max(1.0, float(os.getenv("FAILING_INPUT_HTTP_TIMEOUT_SECONDS", "35")))
        self.FAILING_INPUT_SCRIPT_MAX_CHARS: int = max(100, int(os.getenv("FAILING_INPUT_SCRIPT_MAX_CHARS", "4000")))
        self.LLM_API_KEY: Optional[str] = os.getenv("LLM_API_KEY") or os.getenv("GEMINI_API_KEY")
        self.LLM_MODEL: str = os.getenv("LLM_MODEL", "gemini-1.5-flash")
        self.LLM_BASE_URL: Optional[str] = os.getenv("LLM_BASE_URL")

        # FR40.4 – LLM request timeout in seconds (US-16)
        self.LLM_TIMEOUT_SECONDS: float = float(os.getenv("LLM_TIMEOUT_SECONDS", "10.0"))

        # FR37.2 – slowdown factor when AI is throttled (US-16 creates; US-77 uses it)
        self.AI_SLOWDOWN_FACTOR: float = float(os.getenv("AI_SLOWDOWN_FACTOR", "1.0"))

        # FR40.4 – maximum generation tokens the AI may produce per request (DR3, US-16, US-24, NFR7.2).
        # Canonical variable is AI_MAX_TOKENS_PER_REQUEST; LLM_MAX_OUTPUT_TOKENS is a supported legacy alias.
        # Both provider request bodies (Gemini maxOutputTokens, OpenAI max_tokens) and admission reservation
        # estimates use this exact unified value.
        canonical_tokens = os.getenv("AI_MAX_TOKENS_PER_REQUEST")
        legacy_tokens = os.getenv("LLM_MAX_OUTPUT_TOKENS")
        if canonical_tokens is not None and legacy_tokens is not None:
            if canonical_tokens != legacy_tokens:
                logger.warning(
                    "Both AI_MAX_TOKENS_PER_REQUEST (%s) and LLM_MAX_OUTPUT_TOKENS (%s) are set; "
                    "using canonical AI_MAX_TOKENS_PER_REQUEST.",
                    canonical_tokens,
                    legacy_tokens,
                )
            effective_max_tokens = int(canonical_tokens)
        elif canonical_tokens is not None:
            effective_max_tokens = int(canonical_tokens)
        elif legacy_tokens is not None:
            effective_max_tokens = int(legacy_tokens)
        else:
            effective_max_tokens = 4096

        if effective_max_tokens <= 0:
            raise ValueError(
                f"AI_MAX_TOKENS_PER_REQUEST must be a positive integer (> 0), got {effective_max_tokens}"
            )

        self.AI_MAX_TOKENS_PER_REQUEST: int = effective_max_tokens
        self.LLM_MAX_OUTPUT_TOKENS: int = effective_max_tokens

        # FR40.4 - maximum input prompt length in characters (US-77)
        self.AI_MAX_PROMPT_LENGTH: int = int(os.getenv("AI_MAX_PROMPT_LENGTH", os.getenv("AI_MAX_PROMPT_CHARS", "4000")))

        # FR40.3 - optional local model configuration (US-77)
        self.LOCAL_MODEL_ENABLED: bool = os.getenv("LOCAL_MODEL_ENABLED", "false").lower() in ("true", "1")
        self.LOCAL_MODEL_URL: Optional[str] = os.getenv("LOCAL_MODEL_URL")
        self.LOCAL_MODEL_NAME: str = os.getenv("LOCAL_MODEL_NAME", "local-model")

        # NFR5.3 – hourly per-room AI request limit (US-22 / US-16)
        self.ROOM_HOURLY_LIMIT: int = int(os.getenv("ROOM_HOURLY_LIMIT", "20"))

        # NFR7.2 – monthly spend cap in USD (DR3, US-16, US-24, NFR7.2).
        # Must be a finite positive number. Reaching the budget strictly blocks further paid calls.
        raw_budget = os.getenv("MONTHLY_BUDGET_USD", "10.0")
        try:
            budget_val = float(raw_budget)
        except (ValueError, TypeError) as exc:
            raise ValueError(f"MONTHLY_BUDGET_USD must be a valid float, got {raw_budget!r}") from exc
        if math.isnan(budget_val) or math.isinf(budget_val) or budget_val <= 0.0:
            raise ValueError(
                f"MONTHLY_BUDGET_USD must be a finite positive number (> 0), got {budget_val}"
            )
        self.MONTHLY_BUDGET_USD: float = budget_val

        # Cost rate used to estimate spend (FR40.4, US-16)
        self.TOKEN_COST_PER_TOKEN: float = float(os.getenv("TOKEN_COST_PER_TOKEN", "0.0000035"))
        self.ALERT_WEBHOOK_URL: Optional[str] = os.getenv("ALERT_WEBHOOK_URL")

        # Code section extraction limits (FR40.2, US-76, NFR8.2)
        self.SECTION_WINDOW_LINES: int = int(os.getenv("SECTION_WINDOW_LINES", "10"))
        self.SECTION_MAX_LINES: int = int(os.getenv("SECTION_MAX_LINES", "30"))
        self.SECTION_FALLBACK_LINES: int = int(os.getenv("SECTION_FALLBACK_LINES", "20"))

        # Rules-first AI call avoidance (FR36.4, US-67)
        self.RULES_FIRST_AI_AVOIDANCE: bool = os.getenv("RULES_FIRST_AI_AVOIDANCE", "false").lower() in ("true", "1")

        # FR37.2, DR3 – Slowdown detection configurable thresholds (US-68, §16.2)
        self.SLOWDOWN_FACTOR_THRESHOLD: float = float(os.getenv("SLOWDOWN_FACTOR_THRESHOLD", "8.0"))
        self.SLOWDOWN_OUTLIER_THRESHOLD: float = float(
            os.getenv("SLOWDOWN_OUTLIER_THRESHOLD", os.getenv("SLOWDOWN_MODIFIED_Z_THRESHOLD", "3.0"))
        )
        # Retain SLOWDOWN_MODIFIED_Z_THRESHOLD as alias for backwards compatibility
        self.SLOWDOWN_MODIFIED_Z_THRESHOLD: float = self.SLOWDOWN_OUTLIER_THRESHOLD
        self.SLOWDOWN_MIN_SAMPLES: int = int(os.getenv("SLOWDOWN_MIN_SAMPLES", "3"))
        self.SLOWDOWN_MAX_HISTORY: int = int(os.getenv("SLOWDOWN_MAX_HISTORY", "100"))

        # Time complexity measurement and internal run configuration (US-64, US-65, T-18 Decision 7)
        self.USE_STUB_RUNNER: bool = os.getenv("USE_STUB_RUNNER", "false").lower() in ("true", "1")
        self.EXECUTION_SERVICE_URL: str = os.getenv(
            "EXECUTION_SERVICE_URL", "http://execution-service:8082"
        ).rstrip("/")
        self.INTERNAL_API_TOKEN: str = os.getenv(
            "INTERNAL_API_TOKEN", os.getenv("INTERNAL_TOKEN", "")
        )
        self.COMPLEXITY_BASE_INPUT_SIZE: int = int(os.getenv("COMPLEXITY_BASE_INPUT_SIZE", "100"))
        self.COMPLEXITY_DEFAULT_REPEATS: int = int(os.getenv("COMPLEXITY_DEFAULT_REPEATS", "3"))
        self.COMPLEXITY_TIMEOUT_MS: float = float(os.getenv("COMPLEXITY_TIMEOUT_MS", "5000.0"))
        self.COMPLEXITY_R2_POOR_FIT_THRESHOLD: float = float(
            os.getenv("COMPLEXITY_R2_POOR_FIT_THRESHOLD", "0.85")
        )

    def __repr__(self) -> str:
        # Never print or log the actual API key (NFR8.2)
        key_status = "set" if self.LLM_API_KEY else "unset"
        return (
            f"<Settings PORT={self.PORT} LLM_MODEL={self.LLM_MODEL!r} "
            f"LLM_MAX_OUTPUT_TOKENS={self.LLM_MAX_OUTPUT_TOKENS} "
            f"AI_SLOWDOWN_FACTOR={self.AI_SLOWDOWN_FACTOR} "
            f"AI_MAX_TOKENS_PER_REQUEST={self.AI_MAX_TOKENS_PER_REQUEST} "
            f"ROOM_HOURLY_LIMIT={self.ROOM_HOURLY_LIMIT} "
            f"MONTHLY_BUDGET_USD={self.MONTHLY_BUDGET_USD} "
            f"SECTION_WINDOW_LINES={self.SECTION_WINDOW_LINES} SECTION_MAX_LINES={self.SECTION_MAX_LINES} "
            f"SLOWDOWN_FACTOR_THRESHOLD={self.SLOWDOWN_FACTOR_THRESHOLD} "
            f"SLOWDOWN_OUTLIER_THRESHOLD={self.SLOWDOWN_OUTLIER_THRESHOLD} "
            f"SLOWDOWN_MODIFIED_Z_THRESHOLD={self.SLOWDOWN_MODIFIED_Z_THRESHOLD} "
            f"SLOWDOWN_MIN_SAMPLES={self.SLOWDOWN_MIN_SAMPLES} "
            f"LLM_API_KEY={key_status}>"
        )

    def __str__(self) -> str:
        return self.__repr__()


settings = Settings()
