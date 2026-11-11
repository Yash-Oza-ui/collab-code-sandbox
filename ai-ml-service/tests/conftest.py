"""Pytest configuration and shared test fixtures for ai-ml-service."""

import sys
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


@pytest.fixture(autouse=True)
def _reset_ai_control_state():
    """Reset shared cache and rate-limiter state before and after each test."""
    from app.cache import clear_analysis_cache
    from app.limiter import clear_room_rate_limiter

    clear_analysis_cache()
    clear_room_rate_limiter()
    yield
    clear_analysis_cache()
    clear_room_rate_limiter()
