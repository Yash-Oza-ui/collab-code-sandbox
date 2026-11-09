"""Usage counters and telemetry tracking module (FR40.1, US-75, NFR8.2, T-18 v2).

Provides thread-safe in-memory usage counting for:
- Model calls
- Token usage (extracted from provider response, not estimated from user text)
- Cache hits
- Fallback activations

Maintains per-room counters and system total aggregates.
Strict Privacy Invariant (NFR8.2):
The counters hold metadata, room identifiers, and numbers ONLY.
Never stores raw code, stderr, prompts, or other user-written text.
Supports per-room teardown (clear_room) for room lifecycle management (FR32, US-56).
"""

import threading
from typing import Any, Optional


class UsageTracker:
    """Thread-safe counter tracker for AI/ML service usage metrics.

    Stores numbers and room IDs only. Never stores raw code or user text (NFR8.2).
    """

    def __init__(
        self,
        monthly_budget_usd: float = 10.0,
        token_cost_per_token: float = 0.0000035,
    ) -> None:
        self._lock = threading.Lock()
        self.monthly_budget_usd = monthly_budget_usd
        self.token_cost_per_token = token_cost_per_token

        # Platform totals across all rooms and operations
        self._total_model_calls: int = 0
        self._total_tokens_used: int = 0
        self._total_cache_hits: int = 0
        self._total_fallback_activations: int = 0

        # Per-room counter mapping: room_id (str) -> dict of numeric metrics
        self._room_counters: dict[str, dict[str, int]] = {}

    def _ensure_room(self, room_id: str) -> dict[str, int]:
        """Ensure an entry exists for the given room_id (must be called inside self._lock)."""
        if room_id not in self._room_counters:
            self._room_counters[room_id] = {
                "model_calls": 0,
                "tokens_used": 0,
                "cache_hits": 0,
                "fallback_activations": 0,
            }
        return self._room_counters[room_id]

    def record_model_call(self, room_id: str, tokens: int = 0) -> None:
        """Record an outbound LLM model call and token consumption (FR40.1, US-75).

        Args:
            room_id: The room identifier where the call originated.
            tokens: The exact token count extracted from the LLM provider response.
                    Must NOT be estimated from user-written text (FR40.1).
        """
        room_key = str(room_id)
        token_val = max(0, int(tokens)) if isinstance(tokens, (int, float)) else 0

        with self._lock:
            self._total_model_calls += 1
            self._total_tokens_used += token_val

            room = self._ensure_room(room_key)
            room["model_calls"] += 1
            room["tokens_used"] += token_val

    def record_cache_hit(self, room_id: str) -> None:
        """Record a cache hit for an analysis task (FR40.1, US-75).

        Args:
            room_id: The room identifier where the cache hit occurred.
        """
        room_key = str(room_id)
        with self._lock:
            self._total_cache_hits += 1
            room = self._ensure_room(room_key)
            room["cache_hits"] += 1

    def record_fallback(self, room_id: str) -> None:
        """Record a fallback / skipped AI step activation (FR40.1, US-75).

        Args:
            room_id: The room identifier where the fallback occurred.
        """
        room_key = str(room_id)
        with self._lock:
            self._total_fallback_activations += 1
            room = self._ensure_room(room_key)
            room["fallback_activations"] += 1

    def clear_room(self, room_id: str) -> bool:
        """Remove counters for a specific room when closed/deleted (US-56, FR32).

        Leaves total platform aggregates and other rooms intact.

        Args:
            room_id: Room identifier to clear.

        Returns:
            True if room was found and deleted, False otherwise.
        """
        room_key = str(room_id)
        with self._lock:
            if room_key in self._room_counters:
                del self._room_counters[room_key]
                return True
            return False

    def get_room_usage(self, room_id: str) -> dict[str, Any]:
        """Return usage metrics for a specific room.

        Args:
            room_id: Room identifier.

        Returns:
            Dictionary with room_id and numeric metrics only.
        """
        room_key = str(room_id)
        with self._lock:
            if room_key in self._room_counters:
                r = self._room_counters[room_key]
                return {
                    "room_id": room_key,
                    "model_calls": r["model_calls"],
                    "tokens_used": r["tokens_used"],
                    "cache_hits": r["cache_hits"],
                    "fallback_activations": r["fallback_activations"],
                }
            return {
                "room_id": room_key,
                "model_calls": 0,
                "tokens_used": 0,
                "cache_hits": 0,
                "fallback_activations": 0,
            }

    def get_total_usage(self) -> dict[str, Any]:
        """Return overall system usage counters formatted per T-18 contract §16.5.

        Returns:
            Dictionary matching docs/schemas/usage-response specifications:
            - total_model_calls (int)
            - total_tokens_used (int)
            - total_cache_hits (int)
            - total_fallback_activations (int)
            - monthly_spend_usd (float)
            - monthly_budget_usd (float)
            - alert_80_percent_triggered (bool)
            - cost_cap_reached (bool)
            - rooms_tracked (int)
        """
        with self._lock:
            spend_usd = round(self._total_tokens_used * self.token_cost_per_token, 4)
            budget = float(self.monthly_budget_usd)
            return {
                "total_model_calls": self._total_model_calls,
                "total_tokens_used": self._total_tokens_used,
                "total_cache_hits": self._total_cache_hits,
                "total_fallback_activations": self._total_fallback_activations,
                "monthly_spend_usd": spend_usd,
                "monthly_budget_usd": budget,
                "alert_80_percent_triggered": spend_usd >= (0.8 * budget),
                "cost_cap_reached": spend_usd >= budget,
                "rooms_tracked": len(self._room_counters),
            }

    def reset(self) -> None:
        """Reset all counters and tracked rooms (for test isolation)."""
        with self._lock:
            self._total_model_calls = 0
            self._total_tokens_used = 0
            self._total_cache_hits = 0
            self._total_fallback_activations = 0
            self._room_counters.clear()


# Default global tracker instance
_default_tracker = UsageTracker()


def get_tracker() -> UsageTracker:
    """Retrieve the global default usage tracker."""
    return _default_tracker


def set_tracker(tracker: UsageTracker) -> None:
    """Configure the global default usage tracker (useful for testing)."""
    global _default_tracker
    _default_tracker = tracker


def record_model_call(room_id: str, tokens: int = 0) -> None:
    """Record an outbound LLM call and tokens used (FR40.1, US-75)."""
    _default_tracker.record_model_call(room_id=room_id, tokens=tokens)


def record_cache_hit(room_id: str) -> None:
    """Record a cache hit (FR40.1, US-75)."""
    _default_tracker.record_cache_hit(room_id=room_id)


def record_fallback(room_id: str) -> None:
    """Record a fallback / skipped AI step activation (FR40.1, US-75)."""
    _default_tracker.record_fallback(room_id=room_id)


def clear_room(room_id: str) -> bool:
    """Remove room-scoped counter state (US-56, FR32)."""
    return _default_tracker.clear_room(room_id=room_id)


def get_room_usage(room_id: str) -> dict[str, Any]:
    """Retrieve usage counters for a specific room."""
    return _default_tracker.get_room_usage(room_id=room_id)


def get_total_usage() -> dict[str, Any]:
    """Retrieve overall system usage telemetry matching T-18 contract §16.5."""
    return _default_tracker.get_total_usage()


def reset_counters() -> None:
    """Reset all counters in the default tracker."""
    _default_tracker.reset()
