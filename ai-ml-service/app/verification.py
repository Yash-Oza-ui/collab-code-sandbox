"""Optimization fix verification module for time complexity analysis (US-66, FR36.3, T-18 v2).

Executes candidate optimization fixes in the isolated sandbox under identical conditions
to the original program (same runner, same inputs, same repeat count). Fixes are labeled
as verified improvements (verified: true) only when measured execution time is lower
under comparable conditions and beats the configured noise margin.

Strict Privacy Invariant (NFR8.2):
Never logs user code, candidate fixes, or stdin input text. Only records request IDs,
language, runtimes, and status metadata.
"""

from dataclasses import dataclass
import logging
from typing import Optional

import numpy as np

from app.complexity import MeasurementItem
from app.config import settings
from app.internal_runner import (
    ExecutionServiceUnavailableError,
    MeasurementRunner,
    MeasurementRunItem,
    RunBudgetExceededError,
)

logger = logging.getLogger(__name__)


@dataclass
class FixVerificationResult:
    """Result of sandbox verification comparing candidate fix against original code (US-66, FR36.3)."""

    verified: bool = False
    verified_speedup_pct: Optional[float] = None
    original_runtime_ms: Optional[float] = None
    patch_runtime_ms: Optional[float] = None
    reason: Optional[str] = None
    error_code: Optional[str] = None


def compare_runtimes(
    orig_median: float,
    fix_median: float,
    noise_margin_pct: float = 5.0,
) -> tuple[bool, Optional[float], str]:
    """Compare original and fix medians against the configured noise margin.

    Returns:
        (verified, verified_speedup_pct, reason)
    """
    if orig_median <= 0.0:
        return False, None, "Original runtime was 0 ms; cannot calculate speedup."

    speedup_pct = ((orig_median - fix_median) / orig_median) * 100.0

    if speedup_pct > noise_margin_pct:
        rounded_speedup = round(speedup_pct, 1)
        reason = f"Verified improvement: {rounded_speedup}% speedup (exceeds {noise_margin_pct}% noise margin)."
        return True, rounded_speedup, reason
    elif speedup_pct >= 0.0:
        rounded_speedup = round(speedup_pct, 1)
        reason = f"Not verified: speedup of {rounded_speedup}% is within the {noise_margin_pct}% noise margin."
        return False, None, reason
    else:
        slowdown_pct = round(abs(speedup_pct), 1)
        reason = f"Not verified: fix is slower than original ({slowdown_pct}% slowdown)."
        return False, None, reason


async def verify_suggested_fix(
    runner: MeasurementRunner,
    request_id: str,
    room_id: str,
    language: str,
    original_code: str,
    suggested_fix: str,
    inputs: list[str],
    repeats: int = 3,
    original_measurements: Optional[list[MeasurementItem]] = None,
    original_runs: Optional[list[MeasurementRunItem]] = None,
    suggested_patch_runtimes_ms: Optional[list[float]] = None,
    noise_margin_pct: Optional[float] = None,
) -> FixVerificationResult:
    """Verify an optimization fix in the sandbox under comparable conditions to the original (FR36.3, US-66).

    Reuses the measurement work of US-65 (same sizes, same repeats, same runner).
    Handles failures (compile error, crash, timeout, budget refusal, output mismatch)
    gracefully, returning explicit reasons and measured numbers without unsubstantiated claims.
    """
    margin = (
        noise_margin_pct
        if noise_margin_pct is not None
        else getattr(settings, "VERIFICATION_NOISE_MARGIN_PCT", 5.0)
    )

    # 1. Determine original median runtime (evaluating at largest input size 4n where asymptotic differences dominate)
    if not original_measurements:
        return FixVerificationResult(
            verified=False,
            reason="No original measurements available to compare.",
        )

    orig_medians = [
        float(np.median(m.runtimes_ms))
        for m in original_measurements
        if m.runtimes_ms
    ]
    if not orig_medians:
        return FixVerificationResult(
            verified=False,
            reason="Original measurements contained no runtime data.",
        )

    orig_median = orig_medians[-1]

    # 2. Case A: Fix runtimes explicitly provided in request payload (precomputed runtimes)
    if suggested_patch_runtimes_ms:
        patch_median = float(np.median(suggested_patch_runtimes_ms))
        verified, speedup, reason = compare_runtimes(orig_median, patch_median, margin)
        logger.info(
            "Fix verification (precomputed): request_id=%s verified=%s orig_median=%.3f patch_median=%.3f speedup=%s",
            request_id,
            verified,
            orig_median,
            patch_median,
            speedup,
        )
        return FixVerificationResult(
            verified=verified,
            verified_speedup_pct=speedup,
            original_runtime_ms=round(orig_median, 3),
            patch_runtime_ms=round(patch_median, 3),
            reason=reason,
        )

    # 3. Case B: Execute fix via sandbox runner under identical conditions (same inputs, same repeats)
    verify_request_id = f"{request_id}-verify"
    try:
        patch_res = await runner.run_measurement(
            request_id=verify_request_id,
            room_id=room_id,
            language=language,
            code=suggested_fix,
            inputs=inputs,
            repeats=repeats,
        )
    except ExecutionServiceUnavailableError as esu:
        logger.warning("Execution service unavailable for fix verification: %s", esu.message)
        return FixVerificationResult(
            verified=False,
            original_runtime_ms=round(orig_median, 3),
            patch_runtime_ms=None,
            reason=f"Execution service unavailable for fix verification: {esu.message}",
            error_code=esu.code,
        )
    except RunBudgetExceededError as rbe:
        logger.warning("Fix verification refused with RUN_BUDGET_EXCEEDED: %s", rbe.reason)
        return FixVerificationResult(
            verified=False,
            original_runtime_ms=round(orig_median, 3),
            patch_runtime_ms=None,
            reason=f"Verification could not be performed: repeated-run execution budget exceeded ({rbe.reason}).",
            error_code="RUN_BUDGET_EXCEEDED",
        )
    except Exception as exc:
        logger.error("Fix verification failed unexpectedly: %s", type(exc).__name__)
        return FixVerificationResult(
            verified=False,
            original_runtime_ms=round(orig_median, 3),
            patch_runtime_ms=None,
            reason=f"Fix verification failed unexpectedly: {type(exc).__name__}",
            error_code="INTERNAL_ERROR",
        )

    # Check budget refusal from runner response
    if patch_res.refused and patch_res.refusal_code == "RUN_BUDGET_EXCEEDED":
        budget_reason = patch_res.refusal_reason or "aggregate_time_budget"
        logger.warning("Fix verification refused with RUN_BUDGET_EXCEEDED (%s)", budget_reason)
        return FixVerificationResult(
            verified=False,
            original_runtime_ms=round(orig_median, 3),
            patch_runtime_ms=None,
            reason=f"Verification could not be performed: repeated-run execution budget exceeded ({budget_reason}).",
            error_code="RUN_BUDGET_EXCEEDED",
        )

    # Check compilation failure or crash
    if patch_res.failed:
        fail_msg = patch_res.failure_reason or "Execution failure"
        logger.info("Fix verification failed compilation or execution: %s", fail_msg)
        return FixVerificationResult(
            verified=False,
            original_runtime_ms=round(orig_median, 3),
            patch_runtime_ms=None,
            reason=f"Fix failed to compile or crashed: {fail_msg}.",
            error_code="EXECUTION_FAILED",
        )

    # Check timeout (5 s sandbox limit, NFR4.1)
    if patch_res.timed_out:
        timeout_sz = patch_res.timed_out_size or (inputs[-1] if inputs else 400)
        logger.info("Fix execution timed out at input size %s", timeout_sz)
        return FixVerificationResult(
            verified=False,
            original_runtime_ms=round(orig_median, 3),
            patch_runtime_ms=None,
            reason=f"Fix execution timed out at input size {timeout_sz} (5000 ms limit reached).",
            error_code="TIMEOUT",
        )

    # Check incomplete measurements
    if patch_res.incomplete:
        inc_msg = patch_res.incomplete_reason or "Incomplete measurement runs"
        logger.info("Fix verification incomplete: %s", inc_msg)
        return FixVerificationResult(
            verified=False,
            original_runtime_ms=round(orig_median, 3),
            patch_runtime_ms=None,
            reason=f"Fix verification incomplete: {inc_msg}.",
            error_code="INCOMPLETE_MEASUREMENTS",
        )

    # 4. Check output equivalence (A faster program with mismatched output is not an improvement)
    patch_medians = [
        float(np.median(m.runtimes_ms))
        for m in patch_res.measurements
        if m.runtimes_ms
    ]
    patch_median = patch_medians[-1] if patch_medians else None

    if original_runs and patch_res.completed_runs:
        orig_outputs = {r.input: r.output for r in original_runs if r.output is not None}
        fix_outputs = {r.input: r.output for r in patch_res.completed_runs if r.output is not None}
        for inp in inputs:
            if inp in orig_outputs and inp in fix_outputs:
                if orig_outputs[inp] != fix_outputs[inp]:
                    logger.info("Fix produced mismatched output on input size %s", inp)
                    return FixVerificationResult(
                        verified=False,
                        verified_speedup_pct=None,
                        original_runtime_ms=round(orig_median, 3),
                        patch_runtime_ms=round(patch_median, 3) if patch_median is not None else None,
                        reason=f"Output mismatch on input '{inp}': fix produced different output than original code.",
                        error_code="OUTPUT_MISMATCH",
                    )

    if patch_median is None:
        return FixVerificationResult(
            verified=False,
            original_runtime_ms=round(orig_median, 3),
            patch_runtime_ms=None,
            reason="Fix measurements contained no runtime data.",
        )

    # 5. Evaluate speedup against noise margin
    verified, speedup, reason = compare_runtimes(orig_median, patch_median, margin)
    logger.info(
        "Fix verification completed: request_id=%s verified=%s orig_median=%.3f patch_median=%.3f speedup=%s",
        request_id,
        verified,
        orig_median,
        patch_median,
        speedup,
    )

    return FixVerificationResult(
        verified=verified,
        verified_speedup_pct=speedup,
        original_runtime_ms=round(orig_median, 3),
        patch_runtime_ms=round(patch_median, 3),
        reason=reason,
    )

