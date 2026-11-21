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
    if not np.isfinite(orig_median) or not np.isfinite(fix_median):
        return False, None, "Runtime values must be finite numbers."
    if orig_median <= 0.0:
        return False, None, "Original runtime was 0 ms; cannot calculate speedup."
    if fix_median < 0.0:
        return False, None, "Fix runtime cannot be negative."

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

    # 1. Determine original runs and baseline measurements
    # When original execution runs occur, baseline measurements MUST be derived directly
    # from sandbox execution, completely discarding any untrusted input from the caller (FR36.3, US-66).
    if not original_runs:
        try:
            orig_run_res = await runner.run_measurement(
                request_id=f"{request_id}-orig",
                room_id=room_id,
                language=language,
                code=original_code,
                inputs=inputs,
                repeats=repeats,
            )
        except ExecutionServiceUnavailableError as esu:
            logger.warning("Execution service unavailable for original code verification: %s", esu.message)
            return FixVerificationResult(
                verified=False,
                reason=f"Execution service unavailable for verification: {esu.message}",
                error_code=esu.code,
            )
        except RunBudgetExceededError as rbe:
            logger.warning("Original code verification refused with RUN_BUDGET_EXCEEDED: %s", rbe.reason)
            return FixVerificationResult(
                verified=False,
                reason=f"Verification could not be performed: repeated-run execution budget exceeded ({rbe.reason}).",
                error_code="RUN_BUDGET_EXCEEDED",
            )
        except Exception as exc:
            logger.error("Original code verification failed unexpectedly: %s", type(exc).__name__)
            return FixVerificationResult(
                verified=False,
                reason=f"Original code verification failed unexpectedly: {type(exc).__name__}",
                error_code="INTERNAL_ERROR",
            )

        if orig_run_res.refused and orig_run_res.refusal_code == "RUN_BUDGET_EXCEEDED":
            budget_reason = orig_run_res.refusal_reason or "aggregate_time_budget"
            logger.warning("Original code verification refused with RUN_BUDGET_EXCEEDED (%s)", budget_reason)
            return FixVerificationResult(
                verified=False,
                reason=f"Verification could not be performed: repeated-run execution budget exceeded ({budget_reason}).",
                error_code="RUN_BUDGET_EXCEEDED",
            )

        if orig_run_res.failed:
            fail_msg = orig_run_res.failure_reason or "Execution failure"
            logger.info("Original code verification failed compilation or execution: %s", fail_msg)
            return FixVerificationResult(
                verified=False,
                reason=f"Original code failed to compile or crashed: {fail_msg}.",
                error_code="EXECUTION_FAILED",
            )

        if orig_run_res.timed_out:
            timeout_sz = orig_run_res.timed_out_size or (inputs[-1] if inputs else 400)
            logger.info("Original code execution timed out at input size %s", timeout_sz)
            return FixVerificationResult(
                verified=False,
                reason=f"Original code execution timed out at input size {timeout_sz} (5000 ms limit reached).",
                error_code="TIMEOUT",
            )

        if orig_run_res.incomplete:
            inc_msg = orig_run_res.incomplete_reason or "Incomplete measurement runs"
            logger.info("Original code verification incomplete: %s", inc_msg)
            return FixVerificationResult(
                verified=False,
                reason=f"Original code verification incomplete: {inc_msg}.",
                error_code="INCOMPLETE_MEASUREMENTS",
            )

        original_runs = orig_run_res.completed_runs

    if not original_runs:
        return FixVerificationResult(
            verified=False,
            reason="No original runs available to compare.",
            error_code="INCOMPLETE_MEASUREMENTS",
        )

    # Validate comparability and data validity of original runs
    orig_runs_by_input = {r.input: r for r in original_runs}
    for inp in inputs:
        orig_run_item = orig_runs_by_input.get(inp)
        if not orig_run_item or len(orig_run_item.runtimes_ms) < repeats:
            logger.warning("Original code incomplete runs for input '%s': required %d repeats", inp, repeats)
            return FixVerificationResult(
                verified=False,
                reason=f"Original measurements incomplete for input '{inp}': expected at least {repeats} repeats.",
                error_code="INCOMPLETE_MEASUREMENTS",
            )
        for rt in orig_run_item.runtimes_ms:
            if not np.isfinite(rt) or rt < 0.0:
                logger.warning("Original code invalid runtime %s for input '%s'", rt, inp)
                return FixVerificationResult(
                    verified=False,
                    reason="Original code produced invalid or non-finite runtime measurements.",
                    error_code="INVALID_MEASUREMENTS",
                )
            if rt >= 5000.0:
                logger.info("Original code runtime %s ms >= 5000 ms limit at input '%s'", rt, inp)
                return FixVerificationResult(
                    verified=False,
                    reason=f"Original code execution timed out at input size {inp} (5000 ms limit reached).",
                    error_code="TIMEOUT",
                )

    target_input = inputs[-1]
    orig_target_run = orig_runs_by_input[target_input]
    orig_median = float(np.median(orig_target_run.runtimes_ms))
    if orig_median <= 0.0:
        return FixVerificationResult(
            verified=False,
            original_runtime_ms=round(orig_median, 3),
            reason="Original runtime was <= 0 ms; cannot calculate speedup.",
            error_code="INVALID_MEASUREMENTS",
        )

    # 2. Execute fix via sandbox runner under identical conditions (same runner, same inputs, same repeats)
    # Caller-supplied timing data (e.g. suggested_patch_runtimes_ms) cannot bypass sandbox execution;
    # actual sandbox measurements and output validation are strictly required (US-66, FR36.3).
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

    # Validate comparability and data validity of fix runs
    if not patch_res.completed_runs:
        return FixVerificationResult(
            verified=False,
            original_runtime_ms=round(orig_median, 3),
            patch_runtime_ms=None,
            reason="Fix measurements contained no runtime data.",
            error_code="INCOMPLETE_MEASUREMENTS",
        )

    patch_runs_by_input = {r.input: r for r in patch_res.completed_runs}
    for inp in inputs:
        patch_run_item = patch_runs_by_input.get(inp)
        if not patch_run_item or len(patch_run_item.runtimes_ms) < repeats:
            logger.warning("Fix incomplete runs for input '%s': required %d repeats", inp, repeats)
            return FixVerificationResult(
                verified=False,
                original_runtime_ms=round(orig_median, 3),
                patch_runtime_ms=None,
                reason=f"Fix measurements incomplete for input '{inp}': expected at least {repeats} repeats.",
                error_code="INCOMPLETE_MEASUREMENTS",
            )
        for rt in patch_run_item.runtimes_ms:
            if not np.isfinite(rt) or rt < 0.0:
                logger.warning("Fix invalid runtime %s for input '%s'", rt, inp)
                return FixVerificationResult(
                    verified=False,
                    original_runtime_ms=round(orig_median, 3),
                    patch_runtime_ms=None,
                    reason="Fix produced invalid or non-finite runtime measurements.",
                    error_code="INVALID_MEASUREMENTS",
                )
            if rt >= 5000.0:
                logger.info("Fix runtime %s ms >= 5000 ms limit at input '%s'", rt, inp)
                return FixVerificationResult(
                    verified=False,
                    original_runtime_ms=round(orig_median, 3),
                    patch_runtime_ms=None,
                    reason=f"Fix execution timed out at input size {inp} (5000 ms limit reached).",
                    error_code="TIMEOUT",
                )

    patch_target_run = patch_runs_by_input[target_input]
    patch_median = float(np.median(patch_target_run.runtimes_ms))

    # 3. Check output equivalence (A faster program with mismatched output is not an improvement)
    orig_outputs = {r.input: r.output for r in original_runs}
    fix_outputs = {r.input: r.output for r in patch_res.completed_runs}

    for inp in inputs:
        orig_out = orig_outputs.get(inp)
        fix_out = fix_outputs.get(inp)

        if orig_out is None or fix_out is None:
            logger.warning("Verification cannot verify output equivalence: missing output for input '%s'.", inp)
            return FixVerificationResult(
                verified=False,
                original_runtime_ms=round(orig_median, 3),
                patch_runtime_ms=round(patch_median, 3),
                reason=f"Cannot verify improvement: output was not captured for input '{inp}'.",
                error_code="OUTPUT_NOT_CAPTURED",
            )

        if orig_out != fix_out:
            logger.info("Fix produced mismatched output on input size %s", inp)
            return FixVerificationResult(
                verified=False,
                verified_speedup_pct=None,
                original_runtime_ms=round(orig_median, 3),
                patch_runtime_ms=round(patch_median, 3),
                reason=f"Output mismatch on input '{inp}': fix produced different output than original code.",
                error_code="OUTPUT_MISMATCH",
            )

    # 4. Evaluate speedup against noise margin
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

