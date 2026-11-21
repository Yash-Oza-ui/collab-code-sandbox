"""Pytest configuration and shared test fixtures for ai-ml-service."""

import sys
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.cache import clear_analysis_cache
from app.counters import reset_counters
from app.internal_runner import StubMeasurementRunner, set_measurement_runner
from app.limiter import clear_room_rate_limiter


@pytest.fixture(autouse=True)
def isolate_service_state():
    """Reset in-memory cache, usage counters, rate limits, and inject test stub runner."""
    reset_counters()
    clear_analysis_cache()
    clear_room_rate_limiter()
    # Explicitly inject test stub runner for unit test runs
    set_measurement_runner(
        StubMeasurementRunner(
            growth_type="linear",
            base_time_ms=1.0,
            code_overrides={
                "for j in range": {
                    "growth_type": "quadratic",
                    "base_time_ms": 1.12,
                },
            },
        )
    )
    yield
    set_measurement_runner(None)
    reset_counters()
    clear_analysis_cache()
    clear_room_rate_limiter()
