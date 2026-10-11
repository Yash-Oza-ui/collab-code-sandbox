import os
from typing import Optional
from dotenv import load_dotenv

# Load environment variables from .env file if present
load_dotenv()


class Settings:
    """Service configuration loaded from environment variables."""

    def __init__(self) -> None:
        self.LLM_API_KEY: Optional[str] = os.getenv("LLM_API_KEY")
        self.LLM_MODEL: str = os.getenv("LLM_MODEL", "gemini-1.5-flash")

    def __repr__(self) -> str:
        # Never print or log the actual API key (NFR8.2)
        key_status = "set" if self.LLM_API_KEY else "unset"
        return f"<Settings LLM_MODEL={self.LLM_MODEL!r} LLM_API_KEY={key_status}>"

    def __str__(self) -> str:
        return self.__repr__()


settings = Settings()
