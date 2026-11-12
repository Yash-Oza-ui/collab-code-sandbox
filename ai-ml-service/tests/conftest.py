"""Pytest configuration and shared test fixtures for ai-ml-service."""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.cache import clear_analysis_cache
from app.counters import reset_counters


@pytest.fixture(autouse=True)
def isolate_service_state():
    """Reset in-memory cache and usage counters before and after every test."""
    reset_counters()
    clear_analysis_cache()
    yield
    reset_counters()
    clear_analysis_cache()
