"""Analysis router implementing T-18 v2 contract (FR12, FR35, FR36, FR39, US-17, US-65..US-76).

Endpoints:
- POST /v1/analyses/complexity (Task 1: Time complexity, rules-first static analysis, patch verification, AI fallback)
- POST /v1/analyses/crash-explanation (Task 4: C/C++ crash explanation from sanitizer report)
- GET /v1/usage (Task 5: Telemetry counters)
"""

import logging
import re
from typing import Any, Literal, Optional

import numpy as np
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from app.cache import compute_cache_key, get_analysis_cache
from app.counters import (
    clear_room,
    get_room_usage,
    get_total_usage,
    record_fallback,
)
from app.llm import (
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
    generate_explanation,
)
from app.llm.client import call_llm_with_consent
from app.rules.patterns import (
    detect_slow_patterns,
    run_rule_based_analysis_with_ai_fallback,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1")


# ============================================================================
# Schemas for Complexity Analysis (Task 1, T-18 v2 §16.1)
# ============================================================================


class MeasurementItem(BaseModel):
    input_size: int
    runtimes_ms: list[float]


class ComplexityRequest(BaseModel):
    request_id: str
    room_id: str
    analysis_id: str
    request_type: Literal["complexity"]
    language: Literal["cpp", "python", "c", "java", "javascript"]
    consent: Optional[bool] = None
    ai_enabled: Optional[bool] = None
    code: str
    measurements: list[MeasurementItem] = Field(default_factory=list)
    suggested_patch: Optional[str] = None
    suggested_patch_runtimes_ms: Optional[list[float]] = None


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


class ComplexityResponse(BaseModel):
    request_id: str
    analysis_id: str
    task: Literal["complexity"] = "complexity"
    growth_rate: float
    estimated_complexity: str
    r2_score: float
    projected_timeout_input_size: int
    slow_patterns: list[SlowPatternItem]
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
    error_type: str
    line_number: int
    summary: str
    ai_step: CrashAIStep


# ============================================================================
# Helpers
# ============================================================================


def estimate_complexity_from_measurements(measurements: list[MeasurementItem]) -> dict[str, Any]:
    """Fit log-log linear regression to runtime measurements and determine Big-O (FR36.2, US-65)."""
    if not measurements or len(measurements) < 2:
        return {
            "growth_rate": 0.0,
            "estimated_complexity": "O(1)",
            "r2_score": 1.0,
            "projected_timeout_input_size": 1000000,
        }

    input_sizes = []
    median_runtimes = []
    for m in measurements:
        if m.runtimes_ms:
            input_sizes.append(m.input_size)
            median_runtimes.append(float(np.median(m.runtimes_ms)))

    if len(input_sizes) < 2:
        return {
            "growth_rate": 0.0,
            "estimated_complexity": "O(1)",
            "r2_score": 1.0,
            "projected_timeout_input_size": 1000000,
        }

    log_n = np.log(np.array(input_sizes, dtype=float))
    log_t = np.log(np.array(median_runtimes, dtype=float))

    try:
        m, c = np.polyfit(log_n, log_t, 1)
        pred = m * log_n + c
        ss_res = np.sum((log_t - pred) ** 2)
        ss_tot = np.sum((log_t - np.mean(log_t)) ** 2)
        r2 = 1.0 - (ss_res / ss_tot) if ss_tot > 0 else 1.0
        r2 = max(0.0, min(1.0, float(r2)))

        if m < 0.2:
            comp = "O(1)"
        elif m < 0.8:
            comp = "O(log n)"
        elif m < 1.3:
            comp = "O(n)"
        elif m < 1.8:
            comp = "O(n log n)"
        elif m < 2.5:
            comp = "O(n^2)"
        elif m < 3.5:
            comp = "O(n^3)"
        else:
            comp = "O(2^n)"

        timeout_n = 1000000
        if m > 0.05:
            val = np.exp((np.log(5000.0) - c) / m)
            if not np.isnan(val) and not np.isinf(val) and val > 0:
                timeout_n = int(round(val))

        return {
            "growth_rate": round(float(m), 2),
            "estimated_complexity": comp,
            "r2_score": round(float(r2), 3),
            "projected_timeout_input_size": timeout_n,
        }
    except Exception as e:
        logger.warning("Error computing regression: %s", type(e).__name__)
        return {
            "growth_rate": 0.0,
            "estimated_complexity": "O(1)",
            "r2_score": 1.0,
            "projected_timeout_input_size": 1000000,
        }


# ============================================================================
# Endpoints
# ============================================================================


@router.post("/analyses/complexity", response_model=ComplexityResponse)
async def analyze_complexity(payload: ComplexityRequest, request: Request) -> ComplexityResponse:
    """Analyze time complexity using rules-first static analysis and AI fallback (US-17, US-65..US-67, FR12.3)."""
    request.state.request_id = payload.request_id
    request.state.room_id = payload.room_id
    request.state.request_type = payload.request_type
    request.state.language = payload.language

    # 1. Compute empirical complexity metrics from measurements (FR36.1, FR36.2)
    comp_metrics = estimate_complexity_from_measurements(payload.measurements)

    # 2. Define the AI optimization fallback with normalized code cache (NFR5.2, FR40.1)
    async def ai_fallback() -> str:
        cache = get_analysis_cache()
        cache_key = compute_cache_key(
            code=payload.code,
            language=payload.language,
            request_type="complexity",
        )
        cached = cache.get(cache_key, room_id=payload.room_id)
        if cached is not None and "suggested_fix" in cached:
            return str(cached["suggested_fix"])

        prompt = (
            f"Suggest an optimization fix for {payload.language} code:\n"
            f"```\n{payload.code}\n```"
        )
        suggestion = await call_llm_with_consent(
            prompt=prompt,
            consent=payload.consent,
            ai_enabled=payload.ai_enabled,
            room_id=payload.room_id,
        )
        cache.set(cache_key, {"suggested_fix": suggestion}, room_id=payload.room_id)
        return suggestion

    # 3. Rules-First Static Analysis with Central Consent/AI Gate (FR12.2, FR12.3, FR35)
    rule_analysis = await run_rule_based_analysis_with_ai_fallback(
        code=payload.code,
        language=payload.language,
        consent=payload.consent,
        ai_enabled=payload.ai_enabled,
        ai_fallback=ai_fallback,
        room_id=payload.room_id,
    )

    ai_step = rule_analysis["ai_step"]
    request.state.error_code = ai_step.get("error_code")

    # 4. Sandbox patch verification (FR36.3, US-66)
    if ai_step.get("status") == "ok":
        if payload.suggested_patch and payload.suggested_patch_runtimes_ms and payload.measurements:
            orig_medians = [float(np.median(m.runtimes_ms)) for m in payload.measurements if m.runtimes_ms]
            if orig_medians:
                orig_median = orig_medians[-1]
                patch_median = float(np.median(payload.suggested_patch_runtimes_ms))
                if patch_median < orig_median:
                    ai_step["verified"] = True
                    ai_step["verified_speedup_pct"] = round(
                        ((orig_median - patch_median) / orig_median) * 100.0, 1
                    )
                else:
                    ai_step["verified"] = False
                    ai_step["verified_speedup_pct"] = None

    return ComplexityResponse(
        request_id=payload.request_id,
        analysis_id=payload.analysis_id,
        task="complexity",
        growth_rate=comp_metrics["growth_rate"],
        estimated_complexity=comp_metrics["estimated_complexity"],
        r2_score=comp_metrics["r2_score"],
        projected_timeout_input_size=comp_metrics["projected_timeout_input_size"],
        slow_patterns=[
            SlowPatternItem(
                pattern=p["pattern"],
                line=p["line"],
                message=p["message"],
            )
            for p in rule_analysis.get("slow_patterns", [])
        ],
        ai_step=AIStepResponse(
            status=ai_step["status"],
            error_code=ai_step.get("error_code"),
            suggested_fix=ai_step.get("suggested_fix"),
            verified=ai_step.get("verified", False),
            verified_speedup_pct=ai_step.get("verified_speedup_pct"),
        ),
    )


@router.post("/analyses/crash-explanation", response_model=CrashExplanationResponse)
async def explain_crash(payload: CrashExplanationRequest, request: Request) -> CrashExplanationResponse:
    """Explain sanitizer crash report for C/C++ code (US-73, US-74, T-18 v2 §16.4)."""
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
    error_type = "unknown-crash"
    line_number = 1
    match_err = re.search(r"AddressSanitizer:\s*([a-zA-Z0-9_\-]+)", payload.sanitizer_output)
    if match_err:
        error_type = match_err.group(1)
    match_line = re.search(r":(\d+)", payload.sanitizer_output)
    if match_line:
        line_number = int(match_line.group(1))

    summary = f"Detected {error_type} at line {line_number}."

    # 3. AI Gate (FR12.2, FR35)
    if payload.consent is not True:
        record_fallback(payload.room_id)
        ai_step = CrashAIStep(
            status="skipped",
            error_code="CONSENT_MISSING",
            explanation=None,
            suggested_fix=None,
        )
    elif payload.ai_enabled is not True:
        record_fallback(payload.room_id)
        ai_step = CrashAIStep(
            status="skipped",
            error_code="AI_DISABLED",
            explanation=None,
            suggested_fix=None,
        )
    else:
        cache = get_analysis_cache()
        cache_key = compute_cache_key(
            code=payload.code_snippet,
            language=payload.language,
            request_type="crash_explanation",
            error_signature=f"{error_type}:{line_number}",
        )
        cached = cache.get(cache_key, room_id=payload.room_id)
        if cached is not None and "explanation" in cached:
            ai_step = CrashAIStep(
                status="ok",
                error_code=None,
                explanation=str(cached["explanation"]),
                suggested_fix=cached.get("suggested_fix"),
            )
        else:
            try:
                explanation = await generate_explanation(
                    language=payload.language,
                    consent=payload.consent,
                    ai_enabled=payload.ai_enabled,
                    failure_kind="crash",
                    exit_code=139,
                    stderr=payload.sanitizer_output,
                    timed_out=False,
                    oom_killed=False,
                    code=payload.code_snippet,
                    request_id=payload.request_id,
                    room_id=payload.room_id,
                )
                suggested_fix = f"Add boundary check before line {line_number}."
                cache.set(
                    cache_key,
                    {"explanation": explanation, "suggested_fix": suggested_fix},
                    room_id=payload.room_id,
                )
                ai_step = CrashAIStep(
                    status="ok",
                    error_code=None,
                    explanation=explanation,
                    suggested_fix=suggested_fix,
                )
            except LLMTimeoutError:
                logger.warning("Crash explanation LLM call timed out")
                record_fallback(payload.room_id)
                ai_step = CrashAIStep(
                    status="skipped",
                    error_code="PROVIDER_TIMEOUT",
                    explanation=None,
                    suggested_fix=None,
                )
            except LLMRateLimitError:
                logger.warning("Crash explanation LLM call rate limited")
                record_fallback(payload.room_id)
                ai_step = CrashAIStep(
                    status="skipped",
                    error_code="RATE_LIMITED_PROVIDER",
                    explanation=None,
                    suggested_fix=None,
                )
            except LLMProviderError as e:
                logger.warning("Crash explanation LLM provider error: %s", e.code)
                record_fallback(payload.room_id)
                ai_step = CrashAIStep(
                    status="skipped",
                    error_code=e.code,
                    explanation=None,
                    suggested_fix=None,
                )
            except Exception as e:
                logger.warning("Crash explanation LLM call failed: %s", type(e).__name__)
                record_fallback(payload.room_id)
                ai_step = CrashAIStep(
                    status="skipped",
                    error_code="PROVIDER_ERROR",
                    explanation=None,
                    suggested_fix=None,
                )

    request.state.error_code = ai_step.error_code

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
    """Return telemetry usage counters (Task 5, T-18 v2 §16.5)."""
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


@router.delete("/rooms/{room_id}/state")
async def clear_room_state(room_id: str, request: Request) -> dict[str, Any]:
    """Clean up room-scoped measurement history and counters when closed/deleted (US-56, FR32, T-18 §16.5)."""
    request.state.request_type = "room_state_delete"
    request.state.language = "none"
    request.state.room_id = room_id
    clear_room(room_id)
    get_analysis_cache().clear_room(room_id)
    return {
        "room_id": room_id,
        "status": "cleared",
    }
