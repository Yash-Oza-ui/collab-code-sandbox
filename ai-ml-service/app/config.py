import os
from typing import Optional
from dotenv import load_dotenv

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
        self.LLM_API_KEY: Optional[str] = os.getenv("LLM_API_KEY") or os.getenv("GEMINI_API_KEY")
        self.LLM_MODEL: str = os.getenv("LLM_MODEL", "gemini-1.5-flash")
        self.LLM_BASE_URL: Optional[str] = os.getenv("LLM_BASE_URL")

        # FR40.4 – LLM request timeout in seconds (US-16)
        self.LLM_TIMEOUT_SECONDS: float = float(os.getenv("LLM_TIMEOUT_SECONDS", "10.0"))
        # Maximum generation tokens sent in every outbound request body (US-24, NFR7.2).
        # This value MUST match the maxOutputTokens / max_tokens field sent in the request body.
        # Used by the admission reservation formula so reserved tokens correctly cover the
        # maximum billable output for each outbound call.
        self.LLM_MAX_OUTPUT_TOKENS: int = int(os.getenv("LLM_MAX_OUTPUT_TOKENS", "256"))

        # FR37.2 – slowdown factor when AI is throttled (US-16 creates; US-77 uses it)
        self.AI_SLOWDOWN_FACTOR: float = float(os.getenv("AI_SLOWDOWN_FACTOR", "1.0"))

        # FR40.4 – maximum tokens the AI may generate per request (US-16 creates; US-24 enforces)
        self.AI_MAX_TOKENS_PER_REQUEST: int = int(os.getenv("AI_MAX_TOKENS_PER_REQUEST", "4096"))

        # NFR5.3 – hourly per-room AI request limit (US-22 / US-16)
        self.ROOM_HOURLY_LIMIT: int = int(os.getenv("ROOM_HOURLY_LIMIT", "20"))

        # NFR7.2 – monthly spend cap in USD (US-16 creates; US-77 enforces). 0.0 = no cap
        self.MONTHLY_BUDGET_USD: float = float(os.getenv("MONTHLY_BUDGET_USD", "10.0"))

        # Cost rate used to estimate spend (FR40.4, US-16)
        self.TOKEN_COST_PER_TOKEN: float = float(os.getenv("TOKEN_COST_PER_TOKEN", "0.0000035"))
        self.ALERT_WEBHOOK_URL: Optional[str] = os.getenv("ALERT_WEBHOOK_URL")

        # Code section extraction limits (FR40.2, US-76, NFR8.2)
        self.SECTION_WINDOW_LINES: int = int(os.getenv("SECTION_WINDOW_LINES", "10"))
        self.SECTION_MAX_LINES: int = int(os.getenv("SECTION_MAX_LINES", "30"))
        self.SECTION_FALLBACK_LINES: int = int(os.getenv("SECTION_FALLBACK_LINES", "20"))

        # Rules-first AI call avoidance (FR36.4, US-67)
        self.RULES_FIRST_AI_AVOIDANCE: bool = os.getenv("RULES_FIRST_AI_AVOIDANCE", "false").lower() in ("true", "1")

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
            f"LLM_API_KEY={key_status}>"
        )

    def __str__(self) -> str:
        return self.__repr__()


settings = Settings()
