"""
Tests for ai-ml-service configurable limits (US-16, DR3).

Verifies that:
1. All DR3 values owned by ai-ml-service are present in Settings with positive/non-negative defaults.
2. Each value can be overridden via environment variable (no hard-coding).
"""

import os
import importlib
import pytest


def _fresh_settings(**overrides):
    """Re-import config with the given environment variable overrides."""
    for k, v in overrides.items():
        os.environ[k] = str(v)
    try:
        import app.config as cfg_module
        importlib.reload(cfg_module)
        return cfg_module.Settings()
    finally:
        for k in overrides:
            os.environ.pop(k, None)


class TestConfigDefaults:
    """All DR3 values have sensible defaults (never zero or missing, unless 0 is intentional)."""

    def setup_method(self):
        from app.config import Settings
        self.settings = Settings()

    def test_llm_timeout_has_positive_default(self):
        assert self.settings.LLM_TIMEOUT_SECONDS > 0, "FR40.4 LLM timeout must be > 0"

    def test_ai_slowdown_factor_has_default(self):
        assert self.settings.AI_SLOWDOWN_FACTOR >= 1.0, "FR37.2 slowdown factor must be >= 1.0"

    def test_ai_max_tokens_has_positive_default(self):
        assert self.settings.AI_MAX_TOKENS_PER_REQUEST > 0, "FR40.4 max tokens must be > 0"

    def test_room_hourly_limit_has_positive_default(self):
        assert self.settings.ROOM_HOURLY_LIMIT > 0, "NFR5.3 room hourly limit must be > 0"

    def test_monthly_budget_has_non_negative_default(self):
        assert self.settings.MONTHLY_BUDGET_USD >= 0.0, "NFR7.2 monthly budget must be >= 0"

    def test_token_cost_has_positive_default(self):
        assert self.settings.TOKEN_COST_PER_TOKEN > 0.0, "FR40.4 token cost must be > 0"

    def test_section_window_lines_positive(self):
        assert self.settings.SECTION_WINDOW_LINES > 0

    def test_section_max_lines_positive(self):
        assert self.settings.SECTION_MAX_LINES > 0

    def test_slowdown_factor_threshold_has_default(self):
        assert self.settings.SLOWDOWN_FACTOR_THRESHOLD >= 1.0, "FR37.2 slowdown factor threshold must be >= 1.0"

    def test_slowdown_outlier_threshold_has_default(self):
        assert self.settings.SLOWDOWN_OUTLIER_THRESHOLD == pytest.approx(3.0), "§16.2 outlier threshold default is 3.0"

    def test_slowdown_modified_z_threshold_positive(self):
        assert self.settings.SLOWDOWN_MODIFIED_Z_THRESHOLD > 0.0

    def test_slowdown_min_samples_positive(self):
        assert self.settings.SLOWDOWN_MIN_SAMPLES >= 1


class TestConfigOverrides:
    """Every DR3 value can be overridden via environment variable (US-16, DR3)."""

    def test_llm_timeout_overridable(self):
        s = _fresh_settings(LLM_TIMEOUT_SECONDS="3.5")
        assert s.LLM_TIMEOUT_SECONDS == pytest.approx(3.5)

    def test_ai_slowdown_factor_overridable(self):
        s = _fresh_settings(AI_SLOWDOWN_FACTOR="2.0")
        assert s.AI_SLOWDOWN_FACTOR == pytest.approx(2.0)

    def test_ai_max_tokens_overridable(self):
        s = _fresh_settings(AI_MAX_TOKENS_PER_REQUEST="512")
        assert s.AI_MAX_TOKENS_PER_REQUEST == 512

    def test_room_hourly_limit_overridable(self):
        s = _fresh_settings(ROOM_HOURLY_LIMIT="5")
        assert s.ROOM_HOURLY_LIMIT == 5

    def test_monthly_budget_overridable(self):
        s = _fresh_settings(MONTHLY_BUDGET_USD="0.5")
        assert s.MONTHLY_BUDGET_USD == pytest.approx(0.5)

    def test_token_cost_overridable(self):
        s = _fresh_settings(TOKEN_COST_PER_TOKEN="0.000001")
        assert s.TOKEN_COST_PER_TOKEN == pytest.approx(0.000001)

    def test_slowdown_factor_threshold_overridable(self):
        s = _fresh_settings(SLOWDOWN_FACTOR_THRESHOLD="4.0")
        assert s.SLOWDOWN_FACTOR_THRESHOLD == pytest.approx(4.0)

    def test_slowdown_modified_z_threshold_overridable(self):
        s = _fresh_settings(SLOWDOWN_MODIFIED_Z_THRESHOLD="2.5")
        assert s.SLOWDOWN_MODIFIED_Z_THRESHOLD == pytest.approx(2.5)

    def test_slowdown_outlier_threshold_overridable(self):
        s = _fresh_settings(SLOWDOWN_OUTLIER_THRESHOLD="2.8")
        assert s.SLOWDOWN_OUTLIER_THRESHOLD == pytest.approx(2.8)
        assert s.SLOWDOWN_MODIFIED_Z_THRESHOLD == pytest.approx(2.8)

    def test_slowdown_min_samples_overridable(self):
        s = _fresh_settings(SLOWDOWN_MIN_SAMPLES="5")
        assert s.SLOWDOWN_MIN_SAMPLES == 5
