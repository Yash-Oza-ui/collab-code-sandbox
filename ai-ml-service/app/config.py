import os
from typing import Optional
from dotenv import load_dotenv

# Load environment variables from .env file if present
load_dotenv()


class Settings:
    """Service configuration loaded from environment variables."""

    def __init__(self) -> None:
        self.PORT: int = int(os.getenv("PORT", "8000"))
        self.LLM_API_KEY: Optional[str] = os.getenv("LLM_API_KEY") or os.getenv("GEMINI_API_KEY")
        self.LLM_MODEL: str = os.getenv("LLM_MODEL", "gemini-1.5-flash")
        self.LLM_BASE_URL: Optional[str] = os.getenv("LLM_BASE_URL")
        self.LLM_TIMEOUT_SECONDS: float = float(os.getenv("LLM_TIMEOUT_SECONDS", "10.0"))
        # Maximum generation tokens sent in every outbound request body (US-24, NFR7.2).
        # This value MUST match the maxOutputTokens / max_tokens field sent in the request body.
        # Used by the admission reservation formula so reserved tokens correctly cover the
        # maximum billable output for each outbound call.
        self.LLM_MAX_OUTPUT_TOKENS: int = int(os.getenv("LLM_MAX_OUTPUT_TOKENS", "256"))
        self.MONTHLY_BUDGET_USD: float = float(os.getenv("MONTHLY_BUDGET_USD", "10.0"))
        self.TOKEN_COST_PER_TOKEN: float = float(os.getenv("TOKEN_COST_PER_TOKEN", "0.0000035"))
        self.ALERT_WEBHOOK_URL: Optional[str] = os.getenv("ALERT_WEBHOOK_URL")

        # Code section extraction limits (FR40.2, US-76, NFR8.2)
        self.SECTION_WINDOW_LINES: int = int(os.getenv("SECTION_WINDOW_LINES", "10"))
        self.SECTION_MAX_LINES: int = int(os.getenv("SECTION_MAX_LINES", "30"))
        self.SECTION_FALLBACK_LINES: int = int(os.getenv("SECTION_FALLBACK_LINES", "20"))

    def __repr__(self) -> str:
        # Never print or log the actual API key (NFR8.2)
        key_status = "set" if self.LLM_API_KEY else "unset"
        return (
            f"<Settings PORT={self.PORT} LLM_MODEL={self.LLM_MODEL!r} "
            f"SECTION_WINDOW_LINES={self.SECTION_WINDOW_LINES} SECTION_MAX_LINES={self.SECTION_MAX_LINES} "
            f"LLM_API_KEY={key_status}>"
        )

    def __str__(self) -> str:
        return self.__repr__()


settings = Settings()
