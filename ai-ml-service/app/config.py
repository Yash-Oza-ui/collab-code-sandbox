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

        # Room rate limiting (NFR5.3, US-22, DR3)
        self.ROOM_HOURLY_LIMIT: int = int(os.getenv("ROOM_HOURLY_LIMIT", "20"))

    def __repr__(self) -> str:
        # Never print or log the actual API key (NFR8.2)
        key_status = "set" if self.LLM_API_KEY else "unset"
        return (
            f"<Settings PORT={self.PORT} LLM_MODEL={self.LLM_MODEL!r} "
            f"ROOM_HOURLY_LIMIT={self.ROOM_HOURLY_LIMIT} LLM_API_KEY={key_status}>"
        )

    def __str__(self) -> str:
        return self.__repr__()


settings = Settings()
