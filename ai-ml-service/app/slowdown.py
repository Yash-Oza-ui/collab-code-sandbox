"""Slowdown detection and room-scoped measurement storage (FR37, US-68, T-18 v2 §16.2).

User Story: US-68 — Flag a run that is much slower than usual.
Scope: Statistics-only analysis comparing runs with identical input_hash against
an empirical Median and Median Absolute Deviation (MAD) baseline.
"""

import math
import threading
import time
from typing import Any, Literal, Optional

import numpy as np
from pydantic import BaseModel, Field, field_validator

from app.config import settings


# ============================================================================
# Schemas for Slowdown Analysis (Task 2, T-18 v2 §16.2)
# ============================================================================


class SlowdownCurrentRun(BaseModel):
    """Execution measurements and code for the current run (docs/schemas/slowdown-request.json)."""

    execution_time_ms: float
    peak_memory_mb: float
    code: str

    @field_validator("execution_time_ms")
    @classmethod
    def validate_execution_time(cls, v: float) -> float:
        if v is None or math.isnan(v) or math.isinf(v) or v < 0:
            raise ValueError("execution_time_ms must be a non-negative finite number")
        return v

    @field_validator("peak_memory_mb")
    @classmethod
    def validate_peak_memory(cls, v: float) -> float:
        if v is None or math.isnan(v) or math.isinf(v) or v < 0:
            raise ValueError("peak_memory_mb must be a non-negative finite number")
        return v


class SlowdownAnalysisRequest(BaseModel):
    """Payload for POST /v1/analyses/slowdown conforming to T-18 v2 contract."""

    request_id: str
    room_id: str
    analysis_id: str
    request_type: Literal["slowdown"]
    language: Literal["cpp", "python", "c", "java", "javascript"]
    consent: Optional[bool] = None
    ai_enabled: Optional[bool] = None
    input_hash: str
    current_run: SlowdownCurrentRun

    @field_validator("input_hash")
    @classmethod
    def validate_input_hash(cls, v: str) -> str:
        if not v or not isinstance(v, str) or not v.strip():
            raise ValueError("input_hash must be a non-empty string")
        return v

    @field_validator("room_id")
    @classmethod
    def validate_room_id(cls, v: str) -> str:
        if not v or not isinstance(v, str) or not v.strip():
            raise ValueError("room_id must be a non-empty string")
        return v


class BaselineMetrics(BaseModel):
    """Historical baseline summary statistics."""

    median_ms: float
    mad_ms: float
    sample_count: int


class ChangedLineItem(BaseModel):
    """Line-by-line diff item."""

    line: int
    content: str


class SlowdownAIStep(BaseModel):
    """AI step status envelope strictly matching docs/schemas/slowdown-response.json."""

    status: Literal["ok", "skipped"]
    error_code: Optional[
        Literal[
            "CONSENT_MISSING",
            "AI_DISABLED",
            "COST_CAP_REACHED",
            "ROOM_RATE_LIMITED",
            "RATE_LIMITED_PROVIDER",
            "PROVIDER_ERROR",
            "PROVIDER_TIMEOUT",
        ]
    ] = None
    explanation: Optional[str] = None


class SlowdownAnalysisResponse(BaseModel):
    """Response payload for POST /v1/analyses/slowdown conforming to T-18 v2 contract."""

    request_id: str
    analysis_id: str
    task: Literal["slowdown"] = "slowdown"
    input_hash: str
    is_slowdown: bool
    slowdown_factor: float
    baseline: BaselineMetrics
    changed_lines: list[ChangedLineItem] = Field(default_factory=list)
    message: str
    ai_step: SlowdownAIStep


# ============================================================================
# In-Memory Room-Scoped Measurement Store (FR37.4, US-56, CR11, NFR8.2)
# ============================================================================


class SlowdownStore:
    """Thread-safe, room-scoped in-memory store for execution measurements.

    Invariants (NFR8.2, FR37.4, US-52):
    - Stores ONLY numeric metrics: execution_time_ms, peak_memory_mb, and timestamp.
    - Keyed strictly by (room_id, input_hash).
    - NEVER stores source code, stdin, stdout, stderr, prompts, or complete run records.
    - Completely purged on DELETE /v1/rooms/{room_id}/state (US-56, FR32).
    - Deleting one room has zero effect on any other room.
    """

    def __init__(self, max_history_per_hash: Optional[int] = None) -> None:
        self._lock = threading.Lock()
        # Structure: {room_id: {input_hash: [ {"execution_time_ms": float, "peak_memory_mb": float, "timestamp": float}, ... ]}}
        self._rooms: dict[str, dict[str, list[dict[str, Any]]]] = {}
        self._max_history = max_history_per_hash or getattr(
            settings, "SLOWDOWN_MAX_HISTORY", 100
        )

    def get_history(self, room_id: str, input_hash: str) -> list[dict[str, Any]]:
        """Return a shallow copy of previous measurements for this room and input hash."""
        with self._lock:
            room_data = self._rooms.get(room_id, {})
            measurements = room_data.get(input_hash, [])
            return [dict(m) for m in measurements]

    def record_measurement(
        self,
        room_id: str,
        input_hash: str,
        execution_time_ms: float,
        peak_memory_mb: float,
    ) -> None:
        """Record a single normal execution measurement."""
        with self._lock:
            if room_id not in self._rooms:
                self._rooms[room_id] = {}
            if input_hash not in self._rooms[room_id]:
                self._rooms[room_id][input_hash] = []

            history = self._rooms[room_id][input_hash]
            history.append(
                {
                    "execution_time_ms": float(execution_time_ms),
                    "peak_memory_mb": float(peak_memory_mb),
                    "timestamp": time.time(),
                }
            )
            if len(history) > self._max_history:
                history.pop(0)

    def clear_room(self, room_id: str) -> bool:
        """Purge all measurements for a specific room. Idempotent."""
        with self._lock:
            return self._rooms.pop(room_id, None) is not None

    def clear_all(self) -> None:
        """Clear all room measurements across the service (used for test teardown)."""
        with self._lock:
            self._rooms.clear()


_store_instance: Optional[SlowdownStore] = None
_store_lock = threading.Lock()


def get_slowdown_store() -> SlowdownStore:
    """Return the singleton instance of SlowdownStore."""
    global _store_instance
    if _store_instance is None:
        with _store_lock:
            if _store_instance is None:
                _store_instance = SlowdownStore()
    return _store_instance


# ============================================================================
# Statistical Calculation Functions
# ============================================================================


def calculate_baseline(
    measurements: list[dict[str, Any]],
) -> tuple[float, float, int, list[float]]:
    """Extract valid runtimes and compute baseline median and MAD.

    Handles missing or invalid historical records safely.

    Returns:
        (median_ms, mad_ms, sample_count, abs_deviations)
    """
    valid_times: list[float] = []
    for m in measurements:
        if not isinstance(m, dict):
            continue
        t = m.get("execution_time_ms")
        if (
            t is not None
            and isinstance(t, (int, float))
            and not math.isnan(t)
            and not math.isinf(t)
            and t >= 0.0
        ):
            valid_times.append(float(t))

    if not valid_times:
        return 0.0, 0.0, 0, []

    median_val = float(np.median(valid_times))
    abs_deviations = [abs(t - median_val) for t in valid_times]
    mad_val = float(np.median(abs_deviations))
    return median_val, mad_val, len(valid_times), abs_deviations


def compute_modified_z_score(
    current_runtime: float,
    median: float,
    mad: float,
    abs_deviations: list[float],
) -> float:
    """Compute modified z-score using Boris Iglewicz and David Hoaglin (1993) formulation:

        modified_z = 0.6745 * (current_runtime - median) / MAD

    Safe handling of MAD = 0:
    - If current_runtime <= median: returns 0.0 (no upward outlier).
    - If current_runtime > median and MAD == 0:
      - If mean absolute deviation (mean_ad) > 0: uses mean_ad in place of MAD:
            modified_z = 0.6745 * (current_runtime - median) / mean_ad
      - If mean_ad == 0 (all historical baseline runs are identical):
        historical variance is zero, so any execution exceeding the median is
        statistically anomalous. Returns float("inf") so that Condition 1
        (modified_z > z_threshold) is satisfied without division by zero.
        Condition 2 (current_runtime >= factor_threshold * median) still governs
        whether it represents a severe fold slowdown, completely preventing false alarms.
    """
    if current_runtime <= median:
        return 0.0

    if mad > 0.0:
        return 0.6745 * (current_runtime - median) / mad

    # MAD is 0.0: check mean absolute deviation
    mean_ad = float(np.mean(abs_deviations)) if abs_deviations else 0.0
    if mean_ad > 0.0:
        return 0.6745 * (current_runtime - median) / mean_ad

    # All baseline runs are identical: non-zero increase has infinite modified z-score
    return float("inf")


def detect_slowdown(
    room_id: str,
    input_hash: str,
    execution_time_ms: float,
    peak_memory_mb: float,
    consent: Optional[bool] = None,
    ai_enabled: Optional[bool] = None,
    factor_threshold: Optional[float] = None,
    z_threshold: Optional[float] = None,
    min_samples: Optional[int] = None,
    store: Optional[SlowdownStore] = None,
) -> dict[str, Any]:
    """Execute slowdown detection algorithm against room-scoped historical baseline.

    Conditions for flagging a run as a regression (BOTH must hold):
    1. Statistical outlier: modified_z > z_threshold (and current_runtime > median)
    2. Severe fold slowdown: current_runtime >= factor_threshold * median

    Returns a dict ready to construct SlowdownAnalysisResponse.
    """
    if factor_threshold is None:
        factor_threshold = getattr(settings, "SLOWDOWN_FACTOR_THRESHOLD", 8.0)
    if z_threshold is None:
        z_threshold = getattr(settings, "SLOWDOWN_MODIFIED_Z_THRESHOLD", 3.5)
    if min_samples is None:
        min_samples = getattr(settings, "SLOWDOWN_MIN_SAMPLES", 3)

    if store is None:
        store = get_slowdown_store()

    earlier_runs = store.get_history(room_id, input_hash)
    baseline_median, baseline_mad, sample_count, abs_devs = calculate_baseline(earlier_runs)

    # 1. Check if history is sufficient
    if sample_count < min_samples:
        # Record current run into history as a normal baseline point
        store.record_measurement(room_id, input_hash, execution_time_ms, peak_memory_mb)
        all_runs = store.get_history(room_id, input_hash)
        new_median, new_mad, new_count, _ = calculate_baseline(all_runs)

        # AI step representation when baseline is establishing
        if consent is False or consent is None:
            ai_step = SlowdownAIStep(
                status="skipped",
                error_code="CONSENT_MISSING",
                explanation=None,
            )
        elif ai_enabled is False:
            ai_step = SlowdownAIStep(
                status="skipped",
                error_code="AI_DISABLED",
                explanation=None,
            )
        else:
            ai_step = SlowdownAIStep(
                status="skipped",
                error_code=None,
                explanation=None,
            )

        return {
            "is_slowdown": False,
            "slowdown_factor": 1.0,
            "baseline": BaselineMetrics(
                median_ms=round(new_median, 1),
                mad_ms=round(new_mad, 1),
                sample_count=new_count,
            ),
            "changed_lines": [],
            "message": f"Recorded run measurements. Baseline establishing ({new_count} of {min_samples} minimum samples collected).",
            "ai_step": ai_step,
        }

    # 2. Baseline is established (sample_count >= min_samples)
    if baseline_median > 0.0:
        raw_factor = execution_time_ms / baseline_median
    else:
        raw_factor = 1.0 if execution_time_ms == 0.0 else float("inf")

    slowdown_factor = round(raw_factor, 2)

    modified_z = compute_modified_z_score(
        execution_time_ms, baseline_median, baseline_mad, abs_devs
    )

    condition_1_outlier = (modified_z > z_threshold) and (execution_time_ms > baseline_median)
    condition_2_severe = raw_factor >= factor_threshold

    is_slowdown = bool(condition_1_outlier and condition_2_severe)

    # 3. AI step envelope
    if consent is False or consent is None:
        ai_step = SlowdownAIStep(
            status="skipped",
            error_code="CONSENT_MISSING",
            explanation=None,
        )
    elif ai_enabled is False:
        ai_step = SlowdownAIStep(
            status="skipped",
            error_code="AI_DISABLED",
            explanation=None,
        )
    else:
        # Statistics-only feature under US-68: no external LLM API calls
        ai_step = SlowdownAIStep(
            status="ok",
            error_code=None,
            explanation=None,
        )

    # 4. Message and storage update
    if is_slowdown:
        # Abnormal run is NOT recorded into normal baseline history to avoid baseline poisoning
        display_factor = round(raw_factor, 1)
        message = f"Execution runtime was {display_factor}x slower than normal baseline."
    else:
        # Normal run is recorded into the historical baseline
        store.record_measurement(room_id, input_hash, execution_time_ms, peak_memory_mb)
        message = "Execution runtime is within normal parameters."

    return {
        "is_slowdown": is_slowdown,
        "slowdown_factor": slowdown_factor,
        "baseline": BaselineMetrics(
            median_ms=round(baseline_median, 1),
            mad_ms=round(baseline_mad, 1),
            sample_count=sample_count,
        ),
        "changed_lines": [],
        "message": message,
        "ai_step": ai_step,
    }

