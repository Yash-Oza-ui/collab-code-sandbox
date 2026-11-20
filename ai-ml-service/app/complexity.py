"""Time complexity estimation and empirical curve fitting (FR36.1, FR36.2, US-65, T-18 v2 §16.1).

User Story: US-65 — Measure time complexity and estimate Big-O growth rate.
Scope: Statistics-only analysis fitting linear regression to log(input size) against
log(median runtime) at geometric sizes (n, 2n, 4n), detecting timeouts (5 s limit),
handling budget refusals, flagging poor fits (low R²), and avoiding nonsense
extrapolations for flat or falling curves.
"""

import logging
import math
from typing import Any, Literal, Optional, Union

import numpy as np
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)


class MeasurementItem(BaseModel):
    """Execution runtime measurements at a specific input size (docs/schemas/complexity-request.json)."""

    input_size: int = Field(..., description="Geometric input size N (e.g. n, 2n, 4n)")
    runtimes_ms: list[float] = Field(..., description="Repeated runtimes in milliseconds for this input size")


class ComplexityFitResult(BaseModel):
    """Result of log-log regression fitting and Big-O estimation (FR36.2)."""

    growth_rate: Optional[float] = None
    estimated_complexity: str = "no estimate"
    r2_score: Optional[float] = None
    projected_timeout_input_size: Optional[Union[int, str]] = None
    timeout_at_size: Optional[int] = None
    is_poor_fit: bool = False
    can_estimate: bool = True
    message: str
    medians: dict[int, float] = Field(default_factory=dict)


def map_growth_rate_to_complexity(growth_rate: float) -> str:
    """Map empirical regression slope m to standard Big-O notation (T-18 v2 §16.1).

    Mapping:
      m < 0.2         -> O(1)
      0.2 <= m < 0.8  -> O(log n)
      0.8 <= m < 1.3  -> O(n)
      1.3 <= m < 1.8  -> O(n log n)
      1.8 <= m < 2.5  -> O(n^2)
      2.5 <= m < 3.5  -> O(n^3)
      m >= 3.5        -> O(2^n)
    """
    if growth_rate < 0.2:
        return "O(1)"
    if growth_rate < 0.8:
        return "O(log n)"
    if growth_rate < 1.3:
        return "O(n)"
    if growth_rate < 1.8:
        return "O(n log n)"
    if growth_rate < 2.5:
        return "O(n^2)"
    if growth_rate < 3.5:
        return "O(n^3)"
    return "O(2^n)"


def fit_complexity_curve(
    measurements: list[MeasurementItem],
    time_limit_ms: float = 5000.0,
    r2_threshold: float = 0.85,
    budget_refusal_reason: Optional[str] = None,
) -> ComplexityFitResult:
    """Fit a linear regression to log(input size) against log(median runtime) (FR36.1, FR36.2).

    Enforces Acceptance Criteria (US-65):
    1. Medians: Takes the median run time at each input size (FR36.1).
    2. Log-Log Fit: Fits line to log(N) vs log(T_median) and reports growth rate, R², and projected timeout.
    3. Run Time Only: Considers execution_time_ms only.
    4. Timeout Handling: A size that times out (runtime >= time_limit_ms, 5 s NFR4.1) is reported as a timeout
       at that size. If fewer than 3 sizes finish, reports 'no estimate possible' and names the size that timed out;
       NEVER fits a line through two points.
    5. Budget Refusal: A request refused with RUN_BUDGET_EXCEEDED (429) gives no estimate from incomplete data
       and names which budget was hit.
    6. Poor Fit: R² < r2_threshold (default 0.85) is reported with low confidence warning.
    7. Flat / Falling Line: Growth rate near zero (m <= 0.05) gives 'limit not reached' instead of nonsense input size.
    """
    # 1. Budget Refusal Check (AC 5, US-64, NFR14)
    if budget_refusal_reason:
        return ComplexityFitResult(
            growth_rate=None,
            estimated_complexity="no estimate",
            r2_score=None,
            projected_timeout_input_size=None,
            timeout_at_size=None,
            is_poor_fit=False,
            can_estimate=False,
            message=(
                f"Analysis failed: repeated-run execution budget exceeded "
                f"(RUN_BUDGET_EXCEEDED: {budget_refusal_reason})."
            ),
            medians={},
        )

    if not measurements:
        return ComplexityFitResult(
            growth_rate=None,
            estimated_complexity="no estimate",
            r2_score=None,
            projected_timeout_input_size=None,
            timeout_at_size=None,
            is_poor_fit=False,
            can_estimate=False,
            message="No estimate possible: no measurement data provided.",
            medians={},
        )

    # Sort measurements by input size
    sorted_meas = sorted(measurements, key=lambda m: m.input_size)

    # 2. Timeout and Completion Check (AC 4, NFR4.1)
    # A size times out if any runtime reaches or exceeds time_limit_ms (5000 ms)
    timed_out_sizes: list[int] = []
    finished_meas: list[MeasurementItem] = []

    for m in sorted_meas:
        valid_runtimes = [t for t in m.runtimes_ms if t is not None and not math.isnan(t) and not math.isinf(t)]
        if not valid_runtimes:
            continue
        has_timeout = any(t >= time_limit_ms for t in valid_runtimes)
        if has_timeout:
            timed_out_sizes.append(m.input_size)
        else:
            finished_meas.append(m)

    # If any size timed out or fewer than 3 sizes finished, we CANNOT fit a line
    # "If fewer than three sizes finish, the result says that no estimate is possible
    # and names the size that timed out; it never fits a line through two points."
    if timed_out_sizes:
        first_timeout = timed_out_sizes[0]
        return ComplexityFitResult(
            growth_rate=None,
            estimated_complexity="no estimate",
            r2_score=None,
            projected_timeout_input_size=None,
            timeout_at_size=first_timeout,
            is_poor_fit=False,
            can_estimate=False,
            message=(
                f"No estimate possible: execution timed out at input size {first_timeout} "
                f"({time_limit_ms:.0f}ms limit reached)."
            ),
            medians={},
        )

    if len(finished_meas) < 3:
        return ComplexityFitResult(
            growth_rate=None,
            estimated_complexity="no estimate",
            r2_score=None,
            projected_timeout_input_size=None,
            timeout_at_size=None,
            is_poor_fit=False,
            can_estimate=False,
            message=(
                f"No estimate possible: fewer than three measurement sizes finished "
                f"({len(finished_meas)} of 3 required; never fitting a line through two points)."
            ),
            medians={},
        )

    # 3. Calculate Medians at Each Input Size (AC 1, FR36.1)
    input_sizes: list[int] = []
    median_runtimes: list[float] = []
    medians_dict: dict[int, float] = {}

    for m in finished_meas:
        valid_runtimes = [max(0.0, float(t)) for t in m.runtimes_ms if t is not None and not math.isnan(t)]
        if not valid_runtimes:
            continue
        med = float(np.median(valid_runtimes))
        # Clamp to 0.001 ms floor to ensure log is finite
        med_clamped = max(0.001, med)
        input_sizes.append(m.input_size)
        median_runtimes.append(med_clamped)
        medians_dict[m.input_size] = round(med, 3)

    if len(input_sizes) < 3:
        return ComplexityFitResult(
            growth_rate=None,
            estimated_complexity="no estimate",
            r2_score=None,
            projected_timeout_input_size=None,
            timeout_at_size=None,
            is_poor_fit=False,
            can_estimate=False,
            message="No estimate possible: insufficient valid measurements after filtering.",
            medians=medians_dict,
        )

    # 4. Log-Log Linear Regression (AC 2, FR36.2)
    # log(T) = m * log(N) + c
    log_n = np.log(np.array(input_sizes, dtype=float))
    log_t = np.log(np.array(median_runtimes, dtype=float))

    try:
        m, c = np.polyfit(log_n, log_t, 1)
        pred = m * log_n + c
        ss_res = float(np.sum((log_t - pred) ** 2))
        ss_tot = float(np.sum((log_t - np.mean(log_t)) ** 2))

        # Coefficient of determination R^2
        if ss_tot > 0.0:
            r2 = 1.0 - (ss_res / ss_tot)
            r2 = max(0.0, min(1.0, float(r2)))
        else:
            # All median times are identical (pure constant runtime) -> perfect fit
            r2 = 1.0

        growth_rate = round(float(m), 2)
        r2_score = round(float(r2), 3)
        comp = map_growth_rate_to_complexity(growth_rate)

        # 5. Projected Timeout Input Size Extrapolation (AC 2, AC 7)
        # N_timeout = exp((log(5000) - c) / m)
        # AC 7: "A flat or falling line (growth rate near zero) gives 'limit not reached' instead of a nonsense input size."
        if growth_rate <= 0.05:
            projected_timeout: Union[int, str] = "limit not reached"
        else:
            val = np.exp((np.log(time_limit_ms) - c) / m)
            if math.isnan(val) or math.isinf(val) or val <= 0.0:
                projected_timeout = "limit not reached"
            else:
                timeout_n = int(round(val))
                if timeout_n <= 0:
                    projected_timeout = "limit not reached"
                else:
                    projected_timeout = timeout_n

        # 6. Poor Fit Evaluation (AC 6)
        # "A poor fit is reported as such: the README states what low R² means and how the result shows it,
        # so a weak estimate is never presented as a firm answer."
        is_poor_fit = bool(r2_score < r2_threshold)

        if is_poor_fit:
            message = (
                f"Time complexity estimated as {comp} with low confidence "
                f"(growth rate {growth_rate:.2f}, R² = {r2_score:.3f}; weak fit due to measurement noise)."
            )
        elif projected_timeout == "limit not reached":
            message = (
                f"Time complexity estimated as {comp} "
                f"(growth rate {growth_rate:.2f}, R² = {r2_score:.3f}); {time_limit_ms:.0f}ms limit not reached."
            )
        else:
            message = (
                f"Time complexity estimated as {comp} "
                f"(growth rate {growth_rate:.2f}, R² = {r2_score:.3f}); "
                f"projected timeout at input size {projected_timeout}."
            )

        return ComplexityFitResult(
            growth_rate=growth_rate,
            estimated_complexity=comp,
            r2_score=r2_score,
            projected_timeout_input_size=projected_timeout,
            timeout_at_size=None,
            is_poor_fit=is_poor_fit,
            can_estimate=True,
            message=message,
            medians=medians_dict,
        )

    except Exception as e:
        logger.warning("Complexity regression computation failed: %s", type(e).__name__)
        return ComplexityFitResult(
            growth_rate=None,
            estimated_complexity="no estimate",
            r2_score=None,
            projected_timeout_input_size=None,
            timeout_at_size=None,
            is_poor_fit=False,
            can_estimate=False,
            message=f"No estimate possible: regression calculation failed ({type(e).__name__}).",
            medians=medians_dict,
        )

