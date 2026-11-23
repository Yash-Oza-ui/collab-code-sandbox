"""Slowdown detection and room-scoped measurement storage (FR37, US-68, T-18 v2 §16.2).

User Story: US-68 — Flag a run that is much slower than usual.
Scope: Statistics-only analysis comparing runs with identical input_hash against
an empirical Median and Median Absolute Deviation (MAD) baseline.
"""

import logging
import math
import re
import threading
import time
from typing import Any, Literal, Optional

from fastapi import HTTPException
import numpy as np
from pydantic import BaseModel, Field, field_validator

from app.cache import compute_cache_key, get_analysis_cache
from app.config import settings
from app.counters import get_total_usage, record_fallback
from app.diff import compute_line_hashes, diff_line_hashes
from app.limiter import get_room_rate_limiter
from app.llm.client import (
    AIDisabledError,
    ConsentMissingError,
    LLMClient,
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
    RoomRateLimitedError,
    call_llm_with_consent,
)
from app.llm.explainer import enforce_sentence_limit
from app.section import extract_relevant_section

logger = logging.getLogger(__name__)


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


_SHA256_HEX_RE = re.compile(r"^[0-9a-fA-F]{64}$")


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
        if not isinstance(v, str) or not _SHA256_HEX_RE.fullmatch(v.strip()):
            raise ValueError("input_hash must be a 64-character hexadecimal SHA-256 digest")
        return v.strip().lower()

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
    """Line-by-line diff item pointing to the current source version (FR37.3, US-69)."""

    line: int = Field(..., ge=1, description="1-based line number in the current source code")
    module_id: Optional[str] = Field(default=None, description="Open mode module identifier")


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
    removed_lines_count: int = Field(default=0, ge=0)
    comparison_available: bool = False
    comparison_note: Optional[str] = None
    message: str
    ai_step: SlowdownAIStep


# ============================================================================
# In-Memory Room-Scoped Measurement Store (FR37.4, US-56, CR11, NFR8.2)
# ============================================================================


class SlowdownStore:
    """Thread-safe, room-scoped in-memory store for execution measurements and line hashes.

    Invariants (NFR8.2, FR37.4, US-52, US-69):
    - Stores numeric metrics: execution_time_ms, peak_memory_mb, and timestamp.
    - Stores cryptographic SHA-256 line hashes of the most recent normal run for diffing.
    - Keyed strictly by (room_id, input_hash).
    - NEVER stores raw source code, line strings, diff text, stdin, stdout, stderr, prompts, or secrets.
    - Completely purged on DELETE /v1/rooms/{room_id}/state (US-56, FR32).
    - Deleting one room has zero effect on any other room.
    """

    def __init__(self, max_history_per_hash: Optional[int] = None) -> None:
        self._lock = threading.Lock()
        # Structure: {room_id: {input_hash: [ {"execution_time_ms": float, "peak_memory_mb": float, "timestamp": float}, ... ]}}
        self._rooms: dict[str, dict[str, list[dict[str, Any]]]] = {}
        # Structure: {room_id: {input_hash: [sha256_hex_hash, ...]}}
        # Holds ONLY cryptographic digests for the most recent normal run (NFR8.2, AC2).
        self._normal_line_hashes: dict[str, dict[str, list[str]]] = {}
        self._key_locks: dict[tuple[str, str], threading.Lock] = {}
        self._max_history = max_history_per_hash or getattr(
            settings, "SLOWDOWN_MAX_HISTORY", 100
        )

    def get_lock(self, room_id: str, input_hash: str) -> threading.Lock:
        """Return the threading.Lock for a specific (room_id, input_hash) pair."""
        with self._lock:
            key = (room_id, input_hash)
            if key not in self._key_locks:
                self._key_locks[key] = threading.Lock()
            return self._key_locks[key]

    def get_history(self, room_id: str, input_hash: str) -> list[dict[str, Any]]:
        """Return a shallow copy of previous measurements for this room and input hash."""
        with self._lock:
            room_data = self._rooms.get(room_id, {})
            measurements = room_data.get(input_hash, [])
            return [dict(m) for m in measurements]

    def get_last_normal_line_hashes(self, room_id: str, input_hash: str) -> Optional[list[str]]:
        """Return a copy of the most recent normal run's line hashes for this room and input hash."""
        with self._lock:
            room_hashes = self._normal_line_hashes.get(room_id, {})
            hashes = room_hashes.get(input_hash)
            return list(hashes) if hashes is not None else None

    def record_measurement(
        self,
        room_id: str,
        input_hash: str,
        execution_time_ms: float,
        peak_memory_mb: float,
        line_hashes: Optional[list[str]] = None,
    ) -> None:
        """Record a single normal execution measurement and optional line hashes."""
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

            if line_hashes is not None:
                if room_id not in self._normal_line_hashes:
                    self._normal_line_hashes[room_id] = {}
                self._normal_line_hashes[room_id][input_hash] = list(line_hashes)

    def record_normal_line_hashes(
        self,
        room_id: str,
        input_hash: str,
        line_hashes: list[str],
    ) -> None:
        """Update the normal line hashes without adding a measurement record."""
        with self._lock:
            if room_id not in self._normal_line_hashes:
                self._normal_line_hashes[room_id] = {}
            self._normal_line_hashes[room_id][input_hash] = list(line_hashes)

    def clear_room(self, room_id: str) -> bool:
        """Purge all measurements, line hashes, and key locks for a specific room. Idempotent."""
        with self._lock:
            self._key_locks = {
                k: v for k, v in self._key_locks.items() if k[0] != room_id
            }
            removed_rooms = self._rooms.pop(room_id, None) is not None
            removed_hashes = self._normal_line_hashes.pop(room_id, None) is not None
            return removed_rooms or removed_hashes

    def clear_all(self) -> None:
        """Clear all room measurements and line hashes across the service (used for test teardown)."""
        with self._lock:
            self._key_locks.clear()
            self._rooms.clear()
            self._normal_line_hashes.clear()


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


# ============================================================================
# Override Validation (US-68 Contract Alignment)
# ============================================================================


def _validate_float_override(
    val: Any, param_name: str, min_val: float, strict_min: bool = False
) -> float:
    """Validate a request-level float override strictly rejecting negative/zero/non-finite values."""
    try:
        f = float(val)
    except (ValueError, TypeError):
        raise HTTPException(
            status_code=422,
            detail={
                "code": "INVALID_REQUEST",
                "message": f"Invalid {param_name}: must be a valid finite number.",
                "retry_after_seconds": None,
            },
        )
    if math.isnan(f) or math.isinf(f):
        raise HTTPException(
            status_code=422,
            detail={
                "code": "INVALID_REQUEST",
                "message": f"Invalid {param_name}: non-finite values (NaN, Inf) are not permitted.",
                "retry_after_seconds": None,
            },
        )
    if (f <= min_val) if strict_min else (f < min_val):
        comp = f"> {min_val}" if strict_min else f">= {min_val}"
        raise HTTPException(
            status_code=422,
            detail={
                "code": "INVALID_REQUEST",
                "message": f"Invalid {param_name}: must be {comp}.",
                "retry_after_seconds": None,
            },
        )
    return f


def _validate_int_override(val: Any, param_name: str, min_val: int = 1) -> int:
    """Validate a request-level integer override strictly rejecting values < min_val and non-integers."""
    try:
        if isinstance(val, str) and "." in val:
            raise ValueError("Float string supplied for integer parameter")
        i = int(val)
    except (ValueError, TypeError):
        raise HTTPException(
            status_code=422,
            detail={
                "code": "INVALID_REQUEST",
                "message": f"Invalid {param_name}: must be an integer.",
                "retry_after_seconds": None,
            },
        )
    if i < min_val:
        raise HTTPException(
            status_code=422,
            detail={
                "code": "INVALID_REQUEST",
                "message": f"Invalid {param_name}: must be an integer >= {min_val}.",
                "retry_after_seconds": None,
            },
        )
    return i


def validate_threshold_overrides(
    factor_param: Optional[Any] = None,
    outlier_param: Optional[Any] = None,
    min_samples_param: Optional[Any] = None,
) -> tuple[Optional[float], Optional[float], Optional[int]]:
    """Validate request-level threshold overrides against strict bounds (T-18 v2 §16.2).

    Rules:
    - min_samples: must be an integer >= 1. Reject <= 0, floats, and non-integers.
    - factor_threshold: must be a finite number >= 1.0. Reject <= 0, < 1.0, NaN, Inf, and non-numeric.
    - outlier_threshold: must be a positive finite number > 0.0. Reject <= 0, NaN, Inf, and non-numeric.

    Raises:
        HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", ...}) on validation failure.
    """
    valid_factor: Optional[float] = None
    if factor_param is not None and str(factor_param).strip() != "":
        valid_factor = _validate_float_override(
            factor_param, param_name="factor_threshold", min_val=1.0, strict_min=False
        )

    valid_outlier: Optional[float] = None
    if outlier_param is not None and str(outlier_param).strip() != "":
        valid_outlier = _validate_float_override(
            outlier_param, param_name="outlier_threshold", min_val=0.0, strict_min=True
        )

    valid_min_samples: Optional[int] = None
    if min_samples_param is not None and str(min_samples_param).strip() != "":
        valid_min_samples = _validate_int_override(
            min_samples_param, param_name="min_samples", min_val=1
        )

    return valid_factor, valid_outlier, valid_min_samples


# ============================================================================
# Core Slowdown Detection & AI Explanation
# ============================================================================


def detect_slowdown(
    room_id: str,
    input_hash: str,
    execution_time_ms: float,
    peak_memory_mb: float,
    code: Optional[str] = None,
    module_id: Optional[str] = None,
    consent: Optional[bool] = None,
    ai_enabled: Optional[bool] = None,
    factor_threshold: Optional[float] = None,
    outlier_threshold: Optional[float] = None,
    z_threshold: Optional[float] = None,
    min_samples: Optional[int] = None,
    store: Optional[SlowdownStore] = None,
) -> dict[str, Any]:
    """Execute slowdown detection algorithm against room-scoped historical baseline (T-18 v2 §16.2).

    Conditions for flagging a run as a regression (BOTH must hold, FR37.2, §16.2):
    1. Statistical outlier: current_runtime > median + outlier_threshold * MAD (and current_runtime > median)
       Default outlier_threshold: 3.0 (from settings.SLOWDOWN_OUTLIER_THRESHOLD).
    2. Severe fold slowdown: current_runtime >= factor_threshold * median
       Default factor_threshold: 8.0 (from settings.SLOWDOWN_FACTOR_THRESHOLD).

    Safe handling of MAD = 0:
    - If current_runtime <= median: returns False (no upward deviation).
    - If current_runtime > median and MAD == 0:
      - If mean absolute deviation (mean_ad) > 0: checks T_curr > median + outlier_threshold * mean_ad.
      - If mean_ad == 0 (all baseline runs are identical, zero variance): Condition 1 evaluates to True.
      Condition 2 (current_runtime >= factor_threshold * median) strictly requires an 8x fold increase,
      completely preventing false alarms on minor variance.

    Changed-Line Highlighting (FR37.3, US-69):
    When a run is flagged as a slowdown (is_slowdown=True), changed_lines identifies 1-based
    added/modified lines in the current code compared to the line hashes of the most recent
    normal run for the same input_hash. Raw code is never stored or returned (NFR8.2, AC2).

    Returns a dict ready to construct SlowdownAnalysisResponse.
    """
    if factor_threshold is None:
        factor_threshold = getattr(settings, "SLOWDOWN_FACTOR_THRESHOLD", 8.0)
    if outlier_threshold is None:
        if z_threshold is not None:
            outlier_threshold = z_threshold
        else:
            outlier_threshold = getattr(
                settings,
                "SLOWDOWN_OUTLIER_THRESHOLD",
                getattr(settings, "SLOWDOWN_MODIFIED_Z_THRESHOLD", 3.0),
            )
    if min_samples is None:
        min_samples = getattr(settings, "SLOWDOWN_MIN_SAMPLES", 3)

    if store is None:
        store = get_slowdown_store()

    current_line_hashes = compute_line_hashes(code) if code is not None else None

    with store.get_lock(room_id, input_hash):
        earlier_runs = store.get_history(room_id, input_hash)
        baseline_median, baseline_mad, sample_count, abs_devs = calculate_baseline(earlier_runs)

        # 1. Check if history is sufficient
        if sample_count < min_samples:
            # Record current run into history as a normal baseline point and normal line hashes
            store.record_measurement(
                room_id,
                input_hash,
                execution_time_ms,
                peak_memory_mb,
                line_hashes=current_line_hashes,
            )
            all_runs = store.get_history(room_id, input_hash)
            new_median, new_mad, new_count, _ = calculate_baseline(all_runs)

            # AI step representation when baseline is establishing (§16.2 Example 3)
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
                "removed_lines_count": 0,
                "comparison_available": False,
                "comparison_note": None,
                "message": f"Recorded run measurements. Baseline establishing ({new_count} of {min_samples} minimum samples collected).",
                "ai_step": ai_step,
            }

        # 2. Baseline is established (sample_count >= min_samples)
        if baseline_median > 0.0:
            raw_factor = execution_time_ms / baseline_median
        elif execution_time_ms == 0.0:
            raw_factor = 1.0
        else:
            # baseline_median is 0.0 ms, runtime > 0.0 ms.
            # Use timer resolution floor (1.0 ms) as effective baseline denominator
            # to prevent division by zero, ensure finite JSON serialization, and prevent
            # false alarms on insignificant sub-millisecond executions.
            raw_factor = execution_time_ms / 1.0

        slowdown_factor = round(raw_factor, 2)

        # Condition 1: Statistical Outlier (contract §16.2: T_curr > median + outlier_threshold * MAD)
        if execution_time_ms <= baseline_median:
            condition_1_outlier = False
        elif baseline_mad > 0.0:
            condition_1_outlier = execution_time_ms > (baseline_median + outlier_threshold * baseline_mad)
        else:
            # Safe handling of MAD = 0
            mean_ad = float(np.mean(abs_devs)) if abs_devs else 0.0
            if mean_ad > 0.0:
                condition_1_outlier = execution_time_ms > (baseline_median + outlier_threshold * mean_ad)
            else:
                # Baseline samples are completely identical: any non-zero increase is statistically anomalous
                condition_1_outlier = True

        # Condition 2: Severe Fold Slowdown (contract §16.2: slowdown_factor >= factor_threshold)
        condition_2_severe = raw_factor >= factor_threshold

        is_slowdown = bool(condition_1_outlier and condition_2_severe)

        # 3. Base AI step envelope (evaluated further in router if regression detected)
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
                status="ok",
                error_code=None,
                explanation=None,
            )

        # 4. Changed lines comparison and storage update (FR37.3, US-69)
        if is_slowdown:
            # Abnormal run is NOT recorded into normal baseline history to avoid baseline poisoning
            display_factor = round(raw_factor, 1)

            # Retrieve the most recent normal run's line hashes for this room and input_hash
            prev_normal_hashes = store.get_last_normal_line_hashes(room_id, input_hash)

            # Perform pure line-hash diff
            diff_res = diff_line_hashes(
                previous_hashes=prev_normal_hashes,
                current_code=code or "",
                module_id=module_id,
            )

            changed_lines = [
                ChangedLineItem(line=item.line, module_id=item.module_id)
                for item in diff_res.changed_lines
            ]
            removed_lines_count = diff_res.removed_lines_count
            comparison_available = diff_res.comparison_available
            comparison_note = diff_res.comparison_note

            if comparison_available:
                message = f"Execution runtime was {display_factor}x slower than normal baseline."
            else:
                message = f"Execution runtime was {display_factor}x slower than normal baseline. {comparison_note}"
        else:
            # Normal run is recorded into the historical baseline and updates normal line hashes
            store.record_measurement(
                room_id,
                input_hash,
                execution_time_ms,
                peak_memory_mb,
                line_hashes=current_line_hashes,
            )
            changed_lines = []
            removed_lines_count = 0
            comparison_available = False
            comparison_note = None
            message = "Execution runtime is within normal parameters."

        return {
            "is_slowdown": is_slowdown,
            "slowdown_factor": slowdown_factor,
            "baseline": BaselineMetrics(
                median_ms=round(baseline_median, 1),
                mad_ms=round(baseline_mad, 1),
                sample_count=sample_count,
            ),
            "changed_lines": changed_lines,
            "removed_lines_count": removed_lines_count,
            "comparison_available": comparison_available,
            "comparison_note": comparison_note,
            "message": message,
            "ai_step": ai_step,
        }


async def generate_slowdown_explanation(
    code: str,
    language: str,
    execution_time_ms: float,
    baseline_median_ms: float,
    slowdown_factor: float,
    room_id: str,
    consent: Optional[bool] = None,
    ai_enabled: Optional[bool] = None,
    client: Optional[LLMClient] = None,
) -> SlowdownAIStep:
    """Generate an AI explanation for an execution slowdown when consent and AI are enabled (FR13.1, FR40.2, §16.2).

    Enforces:
    - Consent and Host AI switch gate (FR12.2, FR35, US-17, US-21).
    - Code window extraction (FR40.2, US-76, NFR8.2): never sends full file if large.
    - Capped at at most 3 sentences in plain prose (FR13.1).
    - Normalized code cache to limit API spend (NFR5.2, NFR7).
    - Room hourly rate limiting (NFR5.3, US-22).
    - Monthly budget cap checking (NFR7.2).
    """
    if consent is not True:
        record_fallback(room_id)
        return SlowdownAIStep(
            status="skipped",
            error_code="CONSENT_MISSING",
            explanation=None,
        )

    if ai_enabled is not True:
        record_fallback(room_id)
        return SlowdownAIStep(
            status="skipped",
            error_code="AI_DISABLED",
            explanation=None,
        )

    # Check monthly cost cap (NFR7.2, §16.5)
    total_usage = get_total_usage()
    if total_usage.get("cost_cap_reached", False):
        record_fallback(room_id)
        return SlowdownAIStep(
            status="skipped",
            error_code="COST_CAP_REACHED",
            explanation=None,
        )

    # Extract relevant code section (FR40.2, US-76, NFR8.2)
    relevant_code = extract_relevant_section(
        code=code,
        language=language,
    )

    # Cache lookup (NFR5.2, Decision 11)
    cache_context = {
        "execution_time_ms": round(execution_time_ms, 1),
        "baseline_median_ms": round(baseline_median_ms, 1),
        "slowdown_factor": round(slowdown_factor, 1),
    }
    cache_key = compute_cache_key(
        code=relevant_code,
        language=language,
        request_type="slowdown",
        error_signature=f"slowdown:{round(slowdown_factor, 1)}",
        cache_context=cache_context,
    )
    cache = get_analysis_cache()
    cached = cache.get(cache_key, room_id=room_id)
    if cached is not None and "explanation" in cached and cached["explanation"]:
        return SlowdownAIStep(
            status="ok",
            error_code=None,
            explanation=str(cached["explanation"]),
        )

    # Check and reserve room hourly rate limit (NFR5.3, US-22)
    if room_id and not get_room_rate_limiter().check_and_reserve(room_id):
        record_fallback(room_id)
        return SlowdownAIStep(
            status="skipped",
            error_code="ROOM_RATE_LIMITED",
            explanation=None,
        )

    prompt = (
        f"The following {language} code executed in {execution_time_ms:.1f}ms, which is {slowdown_factor:.1f}x slower "
        f"than the baseline median of {baseline_median_ms:.1f}ms.\n\n"
        f"Code:\n```\n{relevant_code}\n```\n\n"
        f"Provide a concise explanation (at most 3 sentences) in plain prose explaining why this execution runtime regression likely occurred."
    )

    try:
        raw_explanation = await call_llm_with_consent(
            prompt=prompt,
            consent=consent,
            ai_enabled=ai_enabled,
            client=client,
            room_id=room_id,
        )
        explanation = enforce_sentence_limit(raw_explanation, max_sentences=3)
        cache.set(cache_key, {"explanation": explanation}, room_id=room_id)
        return SlowdownAIStep(
            status="ok",
            error_code=None,
            explanation=explanation,
        )
    except RoomRateLimitedError:
        record_fallback(room_id)
        return SlowdownAIStep(
            status="skipped",
            error_code="ROOM_RATE_LIMITED",
            explanation=None,
        )
    except ConsentMissingError:
        record_fallback(room_id)
        return SlowdownAIStep(
            status="skipped",
            error_code="CONSENT_MISSING",
            explanation=None,
        )
    except AIDisabledError:
        record_fallback(room_id)
        return SlowdownAIStep(
            status="skipped",
            error_code="AI_DISABLED",
            explanation=None,
        )
    except LLMTimeoutError:
        logger.warning("Slowdown explanation LLM call timed out")
        record_fallback(room_id)
        return SlowdownAIStep(
            status="skipped",
            error_code="PROVIDER_TIMEOUT",
            explanation=None,
        )
    except LLMRateLimitError:
        logger.warning("Slowdown explanation LLM call rate limited by provider")
        record_fallback(room_id)
        return SlowdownAIStep(
            status="skipped",
            error_code="RATE_LIMITED_PROVIDER",
            explanation=None,
        )
    except LLMProviderError as e:
        logger.warning("Slowdown explanation LLM provider error: %s", getattr(e, "code", "PROVIDER_ERROR"))
        record_fallback(room_id)
        code_val = getattr(e, "code", "PROVIDER_ERROR")
        if code_val not in (
            "CONSENT_MISSING",
            "AI_DISABLED",
            "COST_CAP_REACHED",
            "ROOM_RATE_LIMITED",
            "RATE_LIMITED_PROVIDER",
            "PROVIDER_ERROR",
            "PROVIDER_TIMEOUT",
        ):
            code_val = "PROVIDER_ERROR"
        return SlowdownAIStep(
            status="skipped",
            error_code=code_val,
            explanation=None,
        )
    except Exception as e:
        logger.warning("Slowdown explanation unexpected error: %s", type(e).__name__)
        record_fallback(room_id)
        return SlowdownAIStep(
            status="skipped",
            error_code="PROVIDER_ERROR",
            explanation=None,
        )

