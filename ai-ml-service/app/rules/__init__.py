"""Rules and deterministic analysis package (US-67, FR12.3)."""

from app.rules.patterns import (
    detect_slow_patterns,
    run_rule_based_analysis_with_ai_fallback,
)

__all__ = [
    "detect_slow_patterns",
    "run_rule_based_analysis_with_ai_fallback",
]
