"""Analysis router implementing T-18 v2 contract (FR12, FR12.4, FR35, FR36, FR39, US-17, US-21, US-65..US-76).

Endpoints:
- POST /v1/analyses/complexity (Task 1: Time complexity, rules-first static analysis, patch verification, AI fallback)
- POST /v1/analyses/crash-explanation (Task 4: C/C++ crash explanation from sanitizer report)
- GET /v1/usage (Task 5: Telemetry counters)
"""

import logging
import re
from typing import Any, Literal, Optional, Union

import numpy as np
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from app import chain
from app.chain import ChainRequest
from app.complexity import (
    ComplexityFitResult,
    MeasurementItem,
    fit_complexity_curve,
    map_growth_rate_to_complexity,
)
from app.config import settings
from app.cache import compute_cache_key, get_analysis_cache
from app.counters import (
    clear_room,
    get_room_usage,
    get_total_usage,
    record_fallback,
)
from app.internal_runner import (
    ExecutionServiceUnavailableError,
    RunBudgetExceededError,
    get_measurement_runner,
)
from app.limiter import get_room_rate_limiter
from app.llm import (
    AIDisabledError,
    ConsentMissingError,
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
    RoomRateLimitedError,
    generate_explanation,
)
from app.llm.client import call_llm_with_consent
from app.rules.patterns import (
    detect_slow_patterns,
    run_rule_based_analysis_with_ai_fallback,
)
from app.sanitizer import parse_sanitizer_report
from app.section import extract_relevant_section
from app.slowdown import (
    SlowdownAnalysisRequest,
    SlowdownAnalysisResponse,
    detect_slowdown,
    generate_slowdown_explanation,
    get_slowdown_store,
    validate_threshold_overrides,
)
from app.verification import verify_suggested_fix

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1")


# ============================================================================
# Schemas for Complexity Analysis (Task 1, T-18 v2 §16.1)
# ============================================================================


class ComplexityRequest(BaseModel):
    request_id: str
    room_id: str
    analysis_id: str
    request_type: Literal["complexity"]
    language: Literal["cpp", "python", "c", "java", "javascript"]
    consent: Optional[bool] = None
    ai_enabled: Optional[bool] = None
    code: str
    base_input_size: Optional[int] = Field(default=100, description="Base input size n (default: 100)")
    inputs: Optional[list[str]] = Field(default=None, description="Explicit measurement inputs if overriding default [n, 2n, 4n]")
    repeats: Optional[int] = Field(default=3, description="Repeats per input size (default: 3)")
    measurements: list[MeasurementItem] = Field(default_factory=list)
    suggested_patch: Optional[str] = None
    suggested_fix: Optional[str] = None
    suggested_patch_runtimes_ms: Optional[list[float]] = None
    rules_first: Optional[bool] = Field(
        default=True,
        description="US-67: When true (default), evaluate rules first before AI fallback.",
    )


class SlowPatternItem(BaseModel):
    pattern: str
    line: int
    message: str


class AIStepResponse(BaseModel):
    status: Literal["ok", "skipped"]
    error_code: Optional[str] = None
    suggested_fix: Optional[str] = None
    verified: bool = False
    verified_speedup_pct: Optional[float] = None
    original_runtime_ms: Optional[float] = None
    patch_runtime_ms: Optional[float] = None
    verification_reason: Optional[str] = None


class ComplexityResponse(BaseModel):
    request_id: str
    analysis_id: str
    task: Literal["complexity"] = "complexity"
    growth_rate: Optional[float] = None
    estimated_complexity: str
    r2_score: Optional[float] = None
    projected_timeout_input_size: Optional[Union[int, str]] = None
    timeout_at_size: Optional[int] = None
    is_poor_fit: Optional[bool] = None
    message: Optional[str] = None
    slow_patterns: list[SlowPatternItem] = Field(default_factory=list)
    ai_step: AIStepResponse


# ============================================================================
# Schemas for Crash Explanation (Task 4, T-18 v2 §16.4)
# ============================================================================


class CrashExplanationRequest(BaseModel):
    request_id: str
    room_id: str
    analysis_id: str
    request_type: Literal["crash_explanation"]
    language: Literal["cpp", "python", "c", "java", "javascript"]
    consent: Optional[bool] = None
    ai_enabled: Optional[bool] = None
    sanitizer_output: str
    code_snippet: str


class CrashAIStep(BaseModel):
    status: Literal["ok", "skipped"]
    error_code: Optional[str] = None
    explanation: Optional[str] = None
    suggested_fix: Optional[str] = None


class CrashExplanationResponse(BaseModel):
    request_id: str
    analysis_id: str
    task: Literal["crash_explanation"] = "crash_explanation"
    error_type: Optional[str] = None
    line_number: Optional[int] = None
    summary: str
    ai_step: CrashAIStep


# ============================================================================
# Helpers
# ============================================================================


def estimate_complexity_from_measurements(
    measurements: list[MeasurementItem],
    time_limit_ms: float = 5000.0,
    r2_threshold: float = 0.85,
    budget_refusal_reason: Optional[str] = None,
) -> dict[str, Any]:
    """Fit log-log linear regression to runtime measurements and determine Big-O (FR36.2, US-65)."""
    res = fit_complexity_curve(
        measurements=measurements,
        time_limit_ms=time_limit_ms,
        r2_threshold=r2_threshold,
        budget_refusal_reason=budget_refusal_reason,
    )
    return {
        "growth_rate": res.growth_rate,
        "estimated_complexity": res.estimated_complexity,
        "r2_score": res.r2_score,
        "projected_timeout_input_size": res.projected_timeout_input_size,
        "timeout_at_size": res.timeout_at_size,
        "is_poor_fit": res.is_poor_fit,
        "can_estimate": res.can_estimate,
        "message": res.message,
    }


# ============================================================================
# Endpoints
# ============================================================================


@router.post("/analyses/complexity", response_model=ComplexityResponse)
async def analyze_complexity(payload: ComplexityRequest, request: Request) -> ComplexityResponse:
    """Analyze time complexity using rules-first static analysis and central method chain (US-17, US-65..US-67, US-77, FR12.3, FR40.3)."""
    request.state.request_id = payload.request_id
    request.state.room_id = payload.room_id
    request.state.request_type = payload.request_type
    request.state.language = payload.language

    # 1. Obtain measurements: either provided in payload or run via measurement runner (US-64, US-65)
    budget_refusal_reason: Optional[str] = None
    incomplete_reason: Optional[str] = None
    measurements = payload.measurements
    orig_completed_runs = []

    if not measurements:
        base_n = payload.base_input_size or getattr(settings, "COMPLEXITY_BASE_INPUT_SIZE", 100)
        repeats = payload.repeats or getattr(settings, "COMPLEXITY_DEFAULT_REPEATS", 3)
        inputs = payload.inputs or [str(base_n), str(base_n * 2), str(base_n * 4)]

        # Pre-validate 10-run per-request limit before dispatching (US-64, NFR14)
        total_runs_requested = len(inputs) * repeats
        if total_runs_requested > 10:
            request.state.error_code = "RUN_BUDGET_EXCEEDED"
            raise HTTPException(
                status_code=429,
                detail={
                    "code": "RUN_BUDGET_EXCEEDED",
                    "message": (
                        f"Requested measurement run count ({total_runs_requested}) exceeds the "
                        f"maximum limit of 10 runs per request (US-64, NFR14)."
                    ),
                    "reason": "max_runs_per_request",
                    "retry_after_seconds": 30,
                },
                headers={"Retry-After": "30"},
            )

        runner = get_measurement_runner()
        try:
            meas_res = await runner.run_measurement(
                request_id=payload.request_id,
                room_id=payload.room_id,
                language=payload.language,
                code=payload.code,
                inputs=inputs,
                repeats=repeats,
            )
            if meas_res.refused and meas_res.refusal_code == "RUN_BUDGET_EXCEEDED":
                budget_refusal_reason = meas_res.refusal_reason or "aggregate_time_budget"
                request.state.error_code = "RUN_BUDGET_EXCEEDED"
                raise HTTPException(
                    status_code=429,
                    detail={
                        "code": "RUN_BUDGET_EXCEEDED",
                        "message": f"Aggregate repeated-run execution budget exceeded ({budget_refusal_reason}).",
                        "reason": budget_refusal_reason,
                        "retry_after_seconds": meas_res.retry_after_seconds or 30,
                    },
                    headers={"Retry-After": str(meas_res.retry_after_seconds or 30)},
                )
            if meas_res.incomplete:
                incomplete_reason = meas_res.incomplete_reason

            orig_completed_runs = meas_res.completed_runs
            measurements = meas_res.measurements
        except ExecutionServiceUnavailableError as esu:
            request.state.error_code = esu.code
            raise HTTPException(
                status_code=503,
                detail={
                    "code": esu.code,
                    "message": esu.message,
                    "retry_after_seconds": esu.retry_after_seconds,
                },
                headers={"Retry-After": str(esu.retry_after_seconds or 5)},
            ) from esu
        except HTTPException:
            raise
        except RunBudgetExceededError as rbe:
            request.state.error_code = "RUN_BUDGET_EXCEEDED"
            raise HTTPException(
                status_code=429,
                detail={
                    "code": "RUN_BUDGET_EXCEEDED",
                    "message": rbe.message,
                    "reason": rbe.reason,
                    "retry_after_seconds": rbe.retry_after_seconds,
                },
                headers={"Retry-After": str(rbe.retry_after_seconds)},
            ) from rbe

    # Compute empirical complexity metrics from measurements (FR36.1, FR36.2, US-65)
    comp_metrics = fit_complexity_curve(
        measurements=measurements,
        time_limit_ms=getattr(settings, "COMPLEXITY_TIMEOUT_MS", 5000.0),
        r2_threshold=getattr(settings, "COMPLEXITY_R2_POOR_FIT_THRESHOLD", 0.85),
        budget_refusal_reason=budget_refusal_reason,
        incomplete_reason=incomplete_reason,
    )

    # 2. Rules-First Static Analysis via Central Method Chain (US-77, US-67, FR40.3)
    rules_first = (
        payload.rules_first is not False
        and request.headers.get("x-rules-first", "").lower() not in ("false", "0")
        and request.query_params.get("rules_first", "").lower() not in ("false", "0")
    )

    rule_analysis = await run_rule_based_analysis_with_ai_fallback(
        code=payload.code,
        language=payload.language,
        consent=payload.consent,
        ai_enabled=payload.ai_enabled,
        room_id=payload.room_id,
        request_type="complexity",
        rules_first=rules_first,
    )
    ai_step = rule_analysis["ai_step"]
    request.state.error_code = ai_step.get("error_code")

    # 3. Sandbox fix verification (FR36.3, US-66)
    # External fix generation was gated on consent/AI state above in run_rule_based_analysis_with_ai_fallback.
    # An already-given candidate fix (payload.suggested_patch or payload.suggested_fix) or AI-generated fix
    # is run and verified in the sandbox. Verifying an already-given fix requires no AI consent (US-66, FR36.3).
    candidate_fix = None
    if ai_step.get("error_code") not in ("CONSENT_MISSING", "AI_DISABLED"):
        candidate_fix = ai_step.get("suggested_fix")

    if not candidate_fix:
        candidate_fix = payload.suggested_patch or payload.suggested_fix

    if candidate_fix:
        if ai_step.get("error_code") not in ("CONSENT_MISSING", "AI_DISABLED") and not ai_step.get("suggested_fix"):
            ai_step["suggested_fix"] = candidate_fix

        base_n = payload.base_input_size or getattr(settings, "COMPLEXITY_BASE_INPUT_SIZE", 100)
        meas_inputs = payload.inputs or [str(base_n), str(base_n * 2), str(base_n * 4)]
        repeats = payload.repeats or getattr(settings, "COMPLEXITY_DEFAULT_REPEATS", 3)
        runner = get_measurement_runner()

        # If measurements were provided in payload instead of executing original code,
        # run original code via runner so comparable outputs and baseline runtimes are captured (FR36.3).
        if not orig_completed_runs:
            try:
                orig_meas_res = await runner.run_measurement(
                    request_id=f"{payload.request_id}-orig",
                    room_id=payload.room_id,
                    language=payload.language,
                    code=payload.code,
                    inputs=meas_inputs,
                    repeats=repeats,
                )
                if not orig_meas_res.refused and not orig_meas_res.failed:
                    orig_completed_runs = orig_meas_res.completed_runs
                    if not measurements:
                        measurements = orig_meas_res.measurements
            except Exception as e:
                logger.warning("Could not execute original code for verification comparison: %s", e)

        verif_res = await verify_suggested_fix(
            runner=runner,
            request_id=payload.request_id,
            room_id=payload.room_id,
            language=payload.language,
            original_code=payload.code,
            suggested_fix=candidate_fix,
            inputs=meas_inputs,
            repeats=repeats,
            original_measurements=measurements,
            original_runs=orig_completed_runs,
            noise_margin_pct=getattr(settings, "VERIFICATION_NOISE_MARGIN_PCT", 5.0),
        )
        ai_step["verified"] = verif_res.verified
        ai_step["verified_speedup_pct"] = verif_res.verified_speedup_pct
        ai_step["original_runtime_ms"] = verif_res.original_runtime_ms
        ai_step["patch_runtime_ms"] = verif_res.patch_runtime_ms
        ai_step["verification_reason"] = verif_res.reason

    slow_patterns_list = [
        SlowPatternItem(
            pattern=p["pattern"],
            line=p["line"],
            message=p["message"],
        )
        for p in rule_analysis.get("slow_patterns", [])
    ]

    return ComplexityResponse(
        request_id=payload.request_id,
        analysis_id=payload.analysis_id,
        task="complexity",
        growth_rate=comp_metrics.growth_rate,
        estimated_complexity=comp_metrics.estimated_complexity,
        r2_score=comp_metrics.r2_score,
        projected_timeout_input_size=comp_metrics.projected_timeout_input_size,
        timeout_at_size=comp_metrics.timeout_at_size,
        is_poor_fit=comp_metrics.is_poor_fit,
        message=comp_metrics.message,
        slow_patterns=slow_patterns_list,
        ai_step=AIStepResponse(
            status=ai_step["status"],
            error_code=ai_step.get("error_code"),
            suggested_fix=ai_step.get("suggested_fix"),
            verified=ai_step.get("verified", False),
            verified_speedup_pct=ai_step.get("verified_speedup_pct"),
            original_runtime_ms=ai_step.get("original_runtime_ms"),
            patch_runtime_ms=ai_step.get("patch_runtime_ms"),
            verification_reason=ai_step.get("verification_reason"),
        ),
    )


@router.post("/analyses/crash-explanation", response_model=CrashExplanationResponse)
async def explain_crash(payload: CrashExplanationRequest, request: Request) -> CrashExplanationResponse:
    """Explain sanitizer crash report for C/C++ code using central method chain (US-73, US-74, US-76, US-77, T-18 v2 §16.4)."""
    request.state.request_id = payload.request_id
    request.state.room_id = payload.room_id
    request.state.request_type = payload.request_type
    request.state.language = payload.language

    # 1. DR5: Sanitizer crash explanation is supported for C and C++ only
    if payload.language not in ["c", "cpp"]:
        request.state.error_code = "UNSUPPORTED_LANGUAGE"
        raise HTTPException(
            status_code=422,
            detail={
                "code": "UNSUPPORTED_LANGUAGE",
                "message": f"Sanitizer crash explanation is supported for C and C++ only, received '{payload.language}'.",
                "retry_after_seconds": None,
            },
        )

    # 2. Deterministic parsing of sanitizer output (FR39.2, US-73)
    parsed = parse_sanitizer_report(payload.sanitizer_output)
    error_type = parsed.error_type
    line_number = parsed.line_number
    summary = parsed.summary

    if not error_type and not summary:
        ai_step = CrashAIStep(
            status="ok",
            error_code=None,
            explanation=None,
            suggested_fix=None,
        )
        return CrashExplanationResponse(
            request_id=payload.request_id,
            analysis_id=payload.analysis_id,
            task="crash_explanation",
            error_type=error_type,
            line_number=line_number,
            summary=summary,
            ai_step=ai_step,
        )

    # 3. Extract relevant localized code section around sanitizer line_number (FR40.2, US-74, US-76)
    snippet_lines = payload.code_snippet.splitlines()
    if line_number is not None and line_number <= len(snippet_lines):
        localized_code = extract_relevant_section(
            code=payload.code_snippet,
            language=payload.language,
            error_line=line_number,
        )
    else:
        if len(snippet_lines) > settings.SECTION_MAX_LINES:
            localized_code = extract_relevant_section(
                code=payload.code_snippet,
                language=payload.language,
                error_line=line_number,
            )
        else:
            localized_code = payload.code_snippet

    # 4. Controlled AI pipeline using Central Method Chain (US-77, FR40.3)
    req_type = payload.request_type or "crash_explanation"
    err_sig = f"{error_type}:{line_number}" if error_type and line_number else (error_type or "")
    chain_req = ChainRequest(
        request_type=req_type,
        code=localized_code,
        language=payload.language,
        room_id=payload.room_id,
        request_id=payload.request_id,
        consent=payload.consent,
        ai_enabled=payload.ai_enabled,
        failure_kind="crash",
        exit_code=139,
        error_output=payload.sanitizer_output,
        sanitizer_output=payload.sanitizer_output,
        sanitizer_summary=summary,
        error_signature=err_sig,
        error_line=line_number,
        pre_sliced=True,
        rules_first=False,
    )
    chain_resp = await chain.answer(chain_req)
    c_step = chain_resp.ai_step
    status_str = c_step.get("status", "skipped")
    err_code = c_step.get("error_code")
    request.state.error_code = err_code

    if status_str == "ok":
        explanation = c_step.get("explanation") or chain_resp.answer
        suggested_fix = c_step.get("suggested_fix")
        if not suggested_fix:
            err_lower = (error_type or "").lower()
            if any(k in err_lower for k in ("overflow", "underflow", "shift", "zero", "divide", "float")):
                suggested_fix = (
                    f"Inspect line {line_number} to prevent undefined arithmetic or operations."
                    if line_number
                    else "Verify operand ranges and valid mathematical values."
                )
            elif line_number:
                suggested_fix = f"Inspect and fix the faulty statement at line {line_number}."
            else:
                suggested_fix = "Verify pointer validity and operational constraints."
        ai_step = CrashAIStep(
            status="ok",
            error_code=None,
            explanation=explanation,
            suggested_fix=suggested_fix,
        )
    else:
        ai_step = CrashAIStep(
            status="skipped",
            error_code=err_code,
            explanation=None,
            suggested_fix=None,
        )

    return CrashExplanationResponse(
        request_id=payload.request_id,
        analysis_id=payload.analysis_id,
        task="crash_explanation",
        error_type=error_type,
        line_number=line_number,
        summary=summary,
        ai_step=ai_step,
    )


@router.get("/usage")
@router.get("/analyses/usage")
async def get_usage(request: Request, room_id: Optional[str] = None) -> dict[str, Any]:
    """Return telemetry usage counters (Task 5, T-18 v2 section 16.5)."""
    request.state.request_type = "usage"
    request.state.language = "none"
    if room_id:
        request.state.room_id = room_id
        return get_room_usage(room_id)
    return get_total_usage()


@router.get("/rooms/{room_id}/usage")
async def get_room_usage_endpoint(room_id: str, request: Request) -> dict[str, Any]:
    """Return telemetry usage counters for a specific room (US-75)."""
    request.state.request_type = "usage"
    request.state.language = "none"
    request.state.room_id = room_id
    return get_room_usage(room_id)


@router.post("/analyses/slowdown", response_model=SlowdownAnalysisResponse)
async def analyze_slowdown(
    payload: SlowdownAnalysisRequest, request: Request
) -> SlowdownAnalysisResponse:
    """Detect runtime slowdown against empirical Median and MAD baseline (FR37, US-68, T-18 v2 §16.2)."""
    request.state.request_id = payload.request_id
    request.state.room_id = payload.room_id
    request.state.request_type = payload.request_type
    request.state.language = payload.language

    # Extract query param or header threshold overrides
    raw_factor = request.query_params.get("factor_threshold") or request.headers.get("x-slowdown-factor")
    raw_outlier = (
        request.query_params.get("outlier_threshold")
        or request.query_params.get("z_threshold")
        or request.headers.get("x-outlier-threshold")
        or request.headers.get("x-modified-z-threshold")
    )
    raw_min_samples = request.query_params.get("min_samples") or request.headers.get("x-min-samples")

    # Validate threshold overrides strictly (422 INVALID_REQUEST on invalid, non-finite, or <= 0 values)
    factor_threshold, outlier_threshold, min_samples = validate_threshold_overrides(
        factor_param=raw_factor,
        outlier_param=raw_outlier,
        min_samples_param=raw_min_samples,
    )

    # Statistical slowdown detection against empirical Median and MAD baseline
    result = detect_slowdown(
        room_id=payload.room_id,
        input_hash=payload.input_hash,
        execution_time_ms=payload.current_run.execution_time_ms,
        peak_memory_mb=payload.current_run.peak_memory_mb,
        consent=payload.consent,
        ai_enabled=payload.ai_enabled,
        factor_threshold=factor_threshold,
        outlier_threshold=outlier_threshold,
        min_samples=min_samples,
    )

    # Generate AI explanation when regression is detected and both consent and AI are enabled (§16.2)
    if result["is_slowdown"] and payload.consent is True and payload.ai_enabled is True:
        ai_step = await generate_slowdown_explanation(
            code=payload.current_run.code,
            language=payload.language,
            execution_time_ms=payload.current_run.execution_time_ms,
            baseline_median_ms=result["baseline"].median_ms,
            slowdown_factor=result["slowdown_factor"],
            room_id=payload.room_id,
            consent=payload.consent,
            ai_enabled=payload.ai_enabled,
        )
    else:
        ai_step = result["ai_step"]
        if ai_step.error_code:
            record_fallback(payload.room_id)

    request.state.error_code = ai_step.error_code

    return SlowdownAnalysisResponse(
        request_id=payload.request_id,
        analysis_id=payload.analysis_id,
        task="slowdown",
        input_hash=payload.input_hash,
        is_slowdown=result["is_slowdown"],
        slowdown_factor=result["slowdown_factor"],
        baseline=result["baseline"],
        changed_lines=result.get("changed_lines", []),
        message=result["message"],
        ai_step=ai_step,
    )


@router.delete("/rooms/{room_id}/state")
async def clear_room_state(room_id: str, request: Request) -> dict[str, Any]:
    """Clean up room-scoped measurement history, cache, counters, and rate limits when closed/deleted (US-22, US-56, FR32, T-18 section 16.5)."""
    request.state.request_type = "room_state_delete"
    request.state.language = "none"
    request.state.room_id = room_id
    clear_room(room_id)
    get_analysis_cache().clear_room(room_id)
    get_room_rate_limiter().clear_room(room_id)
    get_slowdown_store().clear_room(room_id)
    return {
        "room_id": room_id,
        "status": "cleared",
    }
