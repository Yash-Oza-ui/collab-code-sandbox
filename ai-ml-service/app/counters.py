"""Usage counters and telemetry tracking module (FR40.1, US-75, US-24, NFR7.1, NFR7.2, NFR7.4, NFR7.5, NFR8.2, T-18 v2).

Provides thread-safe in-memory usage counting for:
- Model calls
- Token usage (extracted from provider response, not estimated from user text)
- Cache hits
- Fallback activations
- Monthly external AI expenditure tracking and budget cap enforcement (US-24, NFR7.1, NFR7.2)
- 80% budget threshold alert deduplication per UTC calendar month (US-24, NFR7.4)
- Calendar month rollover handling (including December to January) in UTC (US-24, NFR7.1)
- Concurrent request reservation logic preventing stampedes over the budget cap (US-24, NFR7.2)

Maintains per-room counters and system total aggregates.
Strict Privacy Invariant (NFR8.2):
The counters hold metadata, room identifiers, and numbers ONLY.
Never stores raw code, stderr, prompts, or other user-written text.
Supports per-room teardown (clear_room) for room lifecycle management (FR32, US-56).
"""

from datetime import datetime, timezone
from decimal import Decimal
import logging
import threading
from typing import Any, Callable, Optional

from app.config import settings

logger = logging.getLogger(__name__)


class CostCapReachedError(Exception):
    """Raised when an external paid AI call is blocked because the monthly budget cap has been reached (NFR7.2, US-24)."""

    def __init__(
        self,
        message: str = "Monthly AI budget cap reached. Paid AI calls are blocked.",
        status_code: int = 402,
        code: str = "COST_CAP_REACHED",
        retry_after_seconds: Optional[int] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.code = code
        self.retry_after_seconds = retry_after_seconds


class CallReservation:
    """Context manager representing an active in-flight LLM call reservation (US-24, NFR7.2)."""

    def __init__(self, tracker: "UsageTracker", reservation_id: int, reserved_tokens: int) -> None:
        self._tracker = tracker
        self._reservation_id = reservation_id
        self._reserved_tokens = reserved_tokens
        self._committed = False

    @property
    def reservation_id(self) -> int:
        return self._reservation_id

    @property
    def reserved_tokens(self) -> int:
        return self._reserved_tokens

    @property
    def is_committed(self) -> bool:
        return self._committed

    def commit(
        self,
        tokens: int,
        room_id: Optional[str] = None,
        monthly_tokens: Optional[int] = None,
    ) -> None:
        """Commit actual tokens consumed upon successful LLM provider response.

        Safe accounting policy (US-24):
        - If tokens > 0: actual provider tokens are recorded for both room usage and monthly spend.
        - If tokens <= 0 (missing or null usage metadata, or unparseable HTTP 200 response):
          0 tokens are recorded for room usage (FR40.1: tokens extracted from provider response),
          while monthly_tokens falls back to reserved_tokens (e.g. 100) to ensure the paid call
          is never zero-cost against the monthly AI budget.
        """
        if not self._committed:
            self._committed = True
            token_val = max(0, int(tokens)) if isinstance(tokens, (int, float)) else 0
            if monthly_tokens is not None:
                m_tokens = max(0, int(monthly_tokens))
            else:
                m_tokens = token_val if token_val > 0 else self._reserved_tokens
            self._tracker._commit_reservation(
                self._reservation_id,
                tokens=token_val,
                monthly_tokens=m_tokens,
                room_id=room_id,
            )

    def commit_uncertain(self, room_id: Optional[str] = None) -> None:
        """Commit conservative token count for uncertain post-dispatch outcomes.

        Safe conservative accounting (US-24):
        When an outbound request has been dispatched over the wire but the response
        is lost (e.g. ReadTimeout, ReadError, or CancelledError after network dispatch),
        the provider may have processed and billed the call. We conservatively commit
        the reserved token count (e.g. 100 tokens) to monthly spend so expenditure
        is never undercounted.
        Room-level telemetry records 0 tokens because no response was received.
        """
        if not self._committed:
            self._committed = True
            self._tracker._commit_reservation(
                self._reservation_id,
                tokens=0,
                monthly_tokens=self._reserved_tokens,
                room_id=room_id,
            )

    def release(self) -> None:
        """Release reservation without recording tokens (e.g. on unbilled error, timeout before dispatch)."""
        if not self._committed:
            self._committed = True
            self._tracker._release_reservation(self._reservation_id)

    def __enter__(self) -> "CallReservation":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        if not self._committed:
            self.release()


class UsageTracker:
    """Thread-safe counter tracker for AI/ML service usage metrics and monthly budget enforcement.

    Stores numbers and room IDs only. Never stores raw code or user text (NFR8.2).
    """

    def __init__(
        self,
        monthly_budget_usd: Optional[float] = None,
        token_cost_per_token: Optional[float] = None,
        clock: Optional[Callable[[], datetime]] = None,
    ) -> None:
        self._lock = threading.Lock()
        self._monthly_budget_override = monthly_budget_usd
        self._token_cost_override = token_cost_per_token
        self._clock_fn = clock

        # Platform lifetime totals across all rooms and operations
        self._total_model_calls: int = 0
        self._total_tokens_used: int = 0
        self._total_cache_hits: int = 0
        self._total_fallback_activations: int = 0

        # Per-room counter mapping: room_id (str) -> dict of numeric metrics
        self._room_counters: dict[str, dict[str, int]] = {}

        # Monthly accounting (US-24, NFR7.1, NFR7.2, NFR7.4)
        now = self._get_utc_now()
        self._current_month_key: tuple[int, int] = (now.year, now.month)
        self._monthly_tokens_used: int = 0
        self._alert_80_percent_triggered: bool = False

        # In-flight call reservations for concurrency protection (US-24, NFR7.2)
        self._active_reservations_tokens: int = 0
        self._next_reservation_id: int = 1
        self._reservations: dict[int, int] = {}

    def set_clock(self, clock: Optional[Callable[[], datetime]]) -> None:
        """Configure custom clock function for testing UTC month transitions."""
        with self._lock:
            self._clock_fn = clock
            self._check_month_rollover()

    def _get_utc_now(self) -> datetime:
        """Return the current datetime in UTC (using custom clock if configured)."""
        if self._clock_fn is not None:
            dt = self._clock_fn()
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt.astimezone(timezone.utc)
        return datetime.now(timezone.utc)

    def _check_month_rollover(self) -> None:
        """Check if the UTC calendar month has transitioned and reset monthly accounting (must be called inside self._lock)."""
        now = self._get_utc_now()
        current_key = (now.year, now.month)
        if current_key != self._current_month_key:
            old_year, old_month = self._current_month_key
            new_year, new_month = current_key
            logger.info(
                "UTC calendar month rollover: transitioning from %04d-%02d to %04d-%02d. "
                "Resetting monthly spend counters and 80%% alert state (US-24, NFR7.1, NFR7.4).",
                old_year,
                old_month,
                new_year,
                new_month,
            )
            self._current_month_key = current_key
            self._monthly_tokens_used = 0
            self._alert_80_percent_triggered = False
            # In-flight reservations from the previous month are preserved across rollover.
            # They represent active requests that crossed the midnight boundary and must not
            # be leaked or lost. Their spend will be attributed upon completion (US-24).

    @property
    def monthly_budget_usd(self) -> float:
        if self._monthly_budget_override is not None:
            return float(self._monthly_budget_override)
        return float(getattr(settings, "MONTHLY_BUDGET_USD", 10.0))

    @monthly_budget_usd.setter
    def monthly_budget_usd(self, value: float) -> None:
        trigger_alert = False
        alert_args: tuple[float, float, float, str] = (0.0, 0.0, 0.0, "")
        with self._lock:
            self._monthly_budget_override = float(value)
            self._check_month_rollover()
            cost_dec = Decimal(str(self.token_cost_per_token))
            budget_dec = Decimal(str(value))
            spend_dec = Decimal(self._monthly_tokens_used) * cost_dec
            threshold_dec = Decimal("0.8") * budget_dec
            if spend_dec >= threshold_dec and not self._alert_80_percent_triggered:
                self._alert_80_percent_triggered = True
                trigger_alert = True
                year, month = self._current_month_key
                alert_args = (
                    round(float(spend_dec), 4),
                    round(float(budget_dec), 4),
                    round(float(threshold_dec), 4),
                    f"{year:04d}-{month:02d}",
                )
            # Deduplication invariant: Once triggered in a UTC month, config changes
            # must not reset its deduplication state (US-24).

        if trigger_alert:
            self._send_80_percent_alert(*alert_args)

    @property
    def token_cost_per_token(self) -> float:
        if self._token_cost_override is not None:
            return float(self._token_cost_override)
        return float(getattr(settings, "TOKEN_COST_PER_TOKEN", 0.0000035))

    @token_cost_per_token.setter
    def token_cost_per_token(self, value: float) -> None:
        trigger_alert = False
        alert_args: tuple[float, float, float, str] = (0.0, 0.0, 0.0, "")
        with self._lock:
            self._token_cost_override = float(value)
            self._check_month_rollover()
            cost_dec = Decimal(str(value))
            budget_dec = Decimal(str(self.monthly_budget_usd))
            spend_dec = Decimal(self._monthly_tokens_used) * cost_dec
            threshold_dec = Decimal("0.8") * budget_dec
            if spend_dec >= threshold_dec and not self._alert_80_percent_triggered:
                self._alert_80_percent_triggered = True
                trigger_alert = True
                year, month = self._current_month_key
                alert_args = (
                    round(float(spend_dec), 4),
                    round(float(budget_dec), 4),
                    round(float(threshold_dec), 4),
                    f"{year:04d}-{month:02d}",
                )
            # Deduplication invariant: Once triggered in a UTC month, config changes
            # must not reset its deduplication state (US-24).

        if trigger_alert:
            self._send_80_percent_alert(*alert_args)

    @property
    def monthly_tokens_used(self) -> int:
        with self._lock:
            self._check_month_rollover()
            return self._monthly_tokens_used

    @property
    def alert_80_percent_triggered(self) -> bool:
        with self._lock:
            self._check_month_rollover()
            return self._alert_80_percent_triggered

    @property
    def cost_cap_reached(self) -> bool:
        with self._lock:
            self._check_month_rollover()
            cost_dec = Decimal(str(self.token_cost_per_token))
            budget_dec = Decimal(str(self.monthly_budget_usd))
            return (Decimal(self._monthly_tokens_used) * cost_dec) >= budget_dec

    @property
    def current_month(self) -> str:
        with self._lock:
            self._check_month_rollover()
            year, month = self._current_month_key
            return f"{year:04d}-{month:02d}"

    def get_monthly_spend_usd(self) -> float:
        """Return the current external AI spending in USD for the current UTC month."""
        with self._lock:
            self._check_month_rollover()
            spend_dec = Decimal(self._monthly_tokens_used) * Decimal(str(self.token_cost_per_token))
            return round(float(spend_dec), 4)

    def is_cost_cap_reached(self, include_reservations: bool = False) -> bool:
        """Check if monthly budget cap has been reached (NFR7.2).

        Args:
            include_reservations: When True, factors in active in-flight call reservations.
        """
        with self._lock:
            self._check_month_rollover()
            tokens = self._monthly_tokens_used
            if include_reservations:
                tokens += self._active_reservations_tokens
            cost_dec = Decimal(str(self.token_cost_per_token))
            budget_dec = Decimal(str(self.monthly_budget_usd))
            spend_dec = Decimal(tokens) * cost_dec
            return spend_dec >= budget_dec

    def _send_80_percent_alert(self, current_spend: float, budget: float, threshold: float, month_str: str) -> None:
        """Deliver the 80% budget alert via structured logging and optional webhook (US-24, NFR7.4).

        Synchronous network I/O is performed without holding self._lock.
        Best-effort: errors never break analysis or accounting.
        """
        logger.warning(
            "AI_COST_CAP_ALERT: Monthly external AI spending has reached or exceeded 80%% of budget. "
            "spend_usd=%.4f budget_usd=%.4f threshold_usd=%.4f month=%s",
            current_spend,
            budget,
            threshold,
            month_str,
        )
        webhook_url = getattr(settings, "ALERT_WEBHOOK_URL", None)
        if webhook_url:
            try:
                import httpx

                payload = {
                    "event": "AI_COST_CAP_ALERT",
                    "spend_usd": current_spend,
                    "budget_usd": budget,
                    "threshold_usd": threshold,
                    "month": month_str,
                }
                with httpx.Client(timeout=2.0) as client:
                    resp = client.post(webhook_url, json=payload)
                    if resp.status_code >= 400:
                        logger.warning(
                            "Alert webhook dispatch failed with HTTP status %d",
                            resp.status_code,
                        )
                    else:
                        logger.info("Alert webhook dispatched successfully (HTTP %d)", resp.status_code)
            except Exception as exc:
                # Strictly safe logging: do not log webhook URL, secrets, or prompt content (NFR8.2)
                logger.warning("Failed to dispatch alert webhook: %s", type(exc).__name__)

    def reserve_call(self, estimated_tokens: int = 100) -> CallReservation:
        """Atomically check cost cap and reserve budget headroom for an outbound call (US-24, NFR7.2).

        Calculates projected spend including current usage, active reservations, and the new reservation.
        Rejects reservation if projected spend would reach or exceed the configured budget.
        All checks and reservation insertions occur atomically under self._lock.

        Raises:
            CostCapReachedError: If projected spending reaches or exceeds the budget.
        """
        token_est = max(1, int(estimated_tokens))
        with self._lock:
            self._check_month_rollover()
            budget_dec = Decimal(str(self.monthly_budget_usd))
            cost_dec = Decimal(str(self.token_cost_per_token))

            # Atomic reservation check: includes current monthly tokens used,
            # all active in-flight reservations, PLUS the newly requested reservation
            projected_tokens = self._monthly_tokens_used + self._active_reservations_tokens + token_est
            projected_spend = Decimal(projected_tokens) * cost_dec

            # Reject reservation if projected spend would reach or exceed the configured budget
            if projected_spend >= budget_dec:
                current_spend = Decimal(self._monthly_tokens_used) * cost_dec
                effective_spend = Decimal(self._monthly_tokens_used + self._active_reservations_tokens) * cost_dec
                logger.info(
                    "Cost cap reached: projected spend $%.6f (current=$%.6f, active_res=$%.6f, new_res=%d tokens) "
                    "reaches or exceeds budget $%.6f. Blocking paid call (NFR7.2).",
                    float(projected_spend),
                    float(current_spend),
                    float(effective_spend),
                    token_est,
                    float(budget_dec),
                )
                raise CostCapReachedError()

            res_id = self._next_reservation_id
            self._next_reservation_id += 1
            self._reservations[res_id] = token_est
            self._active_reservations_tokens += token_est

            return CallReservation(self, res_id, token_est)

    def _commit_reservation(
        self,
        reservation_id: int,
        tokens: int,
        monthly_tokens: Optional[int] = None,
        room_id: Optional[str] = None,
    ) -> None:
        """Commit actual token usage from provider response and release reservation (must be thread-safe)."""
        token_val = max(0, int(tokens)) if isinstance(tokens, (int, float)) else 0
        m_tokens = max(0, int(monthly_tokens)) if monthly_tokens is not None else token_val
        trigger_alert = False
        alert_args: tuple[float, float, float, str] = (0.0, 0.0, 0.0, "")

        with self._lock:
            self._check_month_rollover()
            if reservation_id in self._reservations:
                reserved = self._reservations.pop(reservation_id)
                self._active_reservations_tokens = max(0, self._active_reservations_tokens - reserved)

            self._total_model_calls += 1
            self._total_tokens_used += token_val
            self._monthly_tokens_used += m_tokens

            if room_id:
                room = self._ensure_room(str(room_id))
                room["model_calls"] += 1
                room["tokens_used"] += token_val

            cost_dec = Decimal(str(self.token_cost_per_token))
            budget_dec = Decimal(str(self.monthly_budget_usd))
            spend_dec = Decimal(self._monthly_tokens_used) * cost_dec
            threshold_dec = Decimal("0.8") * budget_dec

            if spend_dec >= threshold_dec and not self._alert_80_percent_triggered:
                self._alert_80_percent_triggered = True
                trigger_alert = True
                year, month = self._current_month_key
                alert_args = (
                    round(float(spend_dec), 4),
                    round(float(budget_dec), 4),
                    round(float(threshold_dec), 4),
                    f"{year:04d}-{month:02d}",
                )

        if trigger_alert:
            self._send_80_percent_alert(*alert_args)

    def _release_reservation(self, reservation_id: int) -> None:
        """Release reservation without recording tokens (must be thread-safe)."""
        with self._lock:
            if reservation_id in self._reservations:
                reserved = self._reservations.pop(reservation_id)
                self._active_reservations_tokens = max(0, self._active_reservations_tokens - reserved)

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

    def record_model_call(self, room_id: Optional[str] = None, tokens: int = 0) -> None:
        """Record an outbound LLM model call and token consumption (FR40.1, US-75, US-24).

        Args:
            room_id: The room identifier where the call originated. When None or empty,
                     updates platform totals without creating a synthetic room entry.
            tokens: The exact token count extracted from the LLM provider response.
                    Must NOT be estimated from user-written text (FR40.1).
        """
        token_val = max(0, int(tokens)) if isinstance(tokens, (int, float)) else 0
        trigger_alert = False
        alert_args: tuple[float, float, float, str] = (0.0, 0.0, 0.0, "")

        with self._lock:
            self._check_month_rollover()
            self._total_model_calls += 1
            self._total_tokens_used += token_val
            self._monthly_tokens_used += token_val

            if room_id:
                room = self._ensure_room(str(room_id))
                room["model_calls"] += 1
                room["tokens_used"] += token_val

            cost_dec = Decimal(str(self.token_cost_per_token))
            budget_dec = Decimal(str(self.monthly_budget_usd))
            spend_dec = Decimal(self._monthly_tokens_used) * cost_dec
            threshold_dec = Decimal("0.8") * budget_dec

            if spend_dec >= threshold_dec and not self._alert_80_percent_triggered:
                self._alert_80_percent_triggered = True
                trigger_alert = True
                year, month = self._current_month_key
                alert_args = (
                    round(float(spend_dec), 4),
                    round(float(budget_dec), 4),
                    round(float(threshold_dec), 4),
                    f"{year:04d}-{month:02d}",
                )

        if trigger_alert:
            self._send_80_percent_alert(*alert_args)

    def record_cache_hit(self, room_id: Optional[str] = None) -> None:
        """Record a cache hit for an analysis task (FR40.1, US-75).

        Args:
            room_id: The room identifier where the cache hit occurred. When None or empty,
                     updates platform totals without creating a synthetic room entry.
        """
        with self._lock:
            self._total_cache_hits += 1
            if room_id:
                room = self._ensure_room(str(room_id))
                room["cache_hits"] += 1

    def record_fallback(self, room_id: Optional[str] = None) -> None:
        """Record a fallback / skipped AI step activation (FR40.1, US-75).

        Args:
            room_id: The room identifier where the fallback occurred. When None or empty,
                     updates platform totals without creating a synthetic room entry.
        """
        with self._lock:
            self._total_fallback_activations += 1
            if room_id:
                room = self._ensure_room(str(room_id))
                room["fallback_activations"] += 1

    def clear_room(self, room_id: str) -> bool:
        """Remove counters for a specific room when closed/deleted (US-56, FR32).

        Leaves total platform aggregates, monthly spend, and other rooms intact.

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
        """Return overall system usage counters formatted per T-18 contract §16.5 and schema.

        Returns:
            Dictionary matching docs/schemas/usage-response.json specifications:
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
            self._check_month_rollover()
            cost_dec = Decimal(str(self.token_cost_per_token))
            budget_dec = Decimal(str(self.monthly_budget_usd))
            spend_dec = Decimal(self._monthly_tokens_used) * cost_dec
            return {
                "total_model_calls": self._total_model_calls,
                "total_tokens_used": self._total_tokens_used,
                "total_cache_hits": self._total_cache_hits,
                "total_fallback_activations": self._total_fallback_activations,
                "monthly_spend_usd": round(float(spend_dec), 4),
                "monthly_budget_usd": float(budget_dec),
                "alert_80_percent_triggered": self._alert_80_percent_triggered,
                "cost_cap_reached": spend_dec >= budget_dec,
                "rooms_tracked": len(self._room_counters),
            }

    def reset(self) -> None:
        """Reset all counters, tracked rooms, reservations, and monthly spend (for test isolation)."""
        with self._lock:
            self._monthly_budget_override = None
            self._token_cost_override = None
            self._total_model_calls = 0
            self._total_tokens_used = 0
            self._total_cache_hits = 0
            self._total_fallback_activations = 0
            self._room_counters.clear()
            self._monthly_tokens_used = 0
            self._alert_80_percent_triggered = False
            self._active_reservations_tokens = 0
            self._reservations.clear()
            now = self._get_utc_now()
            self._current_month_key = (now.year, now.month)


# Default global tracker instance
_default_tracker = UsageTracker()


def get_tracker() -> UsageTracker:
    """Retrieve the global default usage tracker."""
    return _default_tracker


def set_tracker(tracker: UsageTracker) -> None:
    """Configure the global default usage tracker (useful for testing)."""
    global _default_tracker
    _default_tracker = tracker


def record_model_call(room_id: Optional[str] = None, tokens: int = 0) -> None:
    """Record an outbound LLM call and tokens used (FR40.1, US-75, US-24)."""
    _default_tracker.record_model_call(room_id=room_id, tokens=tokens)


def record_cache_hit(room_id: Optional[str] = None) -> None:
    """Record a cache hit (FR40.1, US-75)."""
    _default_tracker.record_cache_hit(room_id=room_id)


def record_fallback(room_id: Optional[str] = None) -> None:
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


def is_cost_cap_reached(include_reservations: bool = False) -> bool:
    """Check if the monthly AI cost cap has been reached (US-24, NFR7.2)."""
    return _default_tracker.is_cost_cap_reached(include_reservations=include_reservations)


def reserve_call(estimated_tokens: int = 100) -> CallReservation:
    """Reserve budget headroom for an outbound LLM call (US-24, NFR7.2)."""
    return _default_tracker.reserve_call(estimated_tokens=estimated_tokens)


def get_monthly_spend() -> float:
    """Return the external AI spending in USD for the current UTC month (US-24, NFR7.1)."""
    return _default_tracker.get_monthly_spend_usd()


def set_clock(clock: Optional[Callable[[], datetime]]) -> None:
    """Configure clock function on default tracker for testing UTC month transitions."""
    _default_tracker.set_clock(clock)
