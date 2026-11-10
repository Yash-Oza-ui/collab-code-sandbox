"""Execution failure explainer router.

Implements T-16 (FR13.1, EPIC 3) failure explanation prototype behind T-07 contract.
Conforms to docs/ai-ml-contract.md §4.3, §5, FR12.3, and US-17 consent gating.
"""

import logging
from typing import Any, Literal, Optional
from fastapi import APIRouter, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.llm import (
    AIDisabledError,
    ConsentMissingError,
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
    generate_explanation,
)
from app.rules.patterns import run_rule_based_analysis_with_ai_fallback

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1", tags=["explain"])


class ExplainRequest(BaseModel):
    """Execution context payload for failed run explanation."""

    request_id: str
    room_id: str
    request_type: Literal["explanation"] = "explanation"
    language: Literal["cpp", "python", "c", "java", "javascript"]
    consent: Optional[bool] = Field(
        default=None,
        description="User consent boolean (FR12). When missing or false, external AI is not called.",
    )
    ai_enabled: Optional[bool] = Field(
        default=None,
        description="Host AI switch boolean (FR35). When missing or false, external AI is not called.",
    )
    code: str
    stderr: str = ""
    exit_code: int
    execution_time_ms: float
    peak_memory_mb: float
    timed_out: bool = False
    oom_killed: bool = False
    failure_kind: Optional[Literal["compile_error", "non_zero_exit", "timeout"]] = None


class AIStep(BaseModel):
    """AI step execution or skip status (FR12.3, T-18 v2)."""

    status: Literal["ok", "skipped"]
    error_code: Optional[str] = None
    suggested_fix: Optional[str] = None


class ExplainResponse(BaseModel):
    """Plain-language explanation and rule-based findings (FR12.3)."""

    request_id: str
    explanation: Optional[str] = None
    slow_patterns: list[dict[str, Any]] = Field(default_factory=list)
    ai_step: Optional[AIStep] = None


@router.post("/explain", response_model=ExplainResponse)
async def explain_failure(payload: ExplainRequest, request: Request) -> ExplainResponse:
    """Explain a failed run in plain prose using rules-first analysis and LLM API (FR12.3, FR13.1, T-16, US-17)."""
    # Track request_id for consistent error envelope responses
    request.state.request_id = payload.request_id

    # Define the failure explanation AI fallback
    async def explain_fallback() -> str:
        return await generate_explanation(
            language=payload.language,
            consent=payload.consent,
            ai_enabled=payload.ai_enabled,
            failure_kind=payload.failure_kind,
            exit_code=payload.exit_code,
            stderr=payload.stderr,
            timed_out=payload.timed_out,
            oom_killed=payload.oom_killed,
            code=payload.code,
            request_id=payload.request_id,
        )

    try:
        # 1. Rules-First Static Analysis (FR12.3, US-67):
        # Always execute rule-based analysis BEFORE any AI fallback.
        rule_analysis = await run_rule_based_analysis_with_ai_fallback(
            code=payload.code,
            language=payload.language,
            consent=payload.consent,
            ai_enabled=payload.ai_enabled,
            ai_fallback=explain_fallback,
            reraise_ai_errors=True,
        )

        slow_patterns = rule_analysis.get("slow_patterns", [])
        request.state.rule_findings = slow_patterns

        # 2. FR12.3 Invariant: When consent is false or missing, return rule-based findings
        # with HTTP 200 and ai_step.status = "skipped", making 0 outbound LLM calls.
        if payload.consent is not True:
            return ExplainResponse(
                request_id=payload.request_id,
                explanation=None,
                slow_patterns=slow_patterns,
                ai_step=AIStep(
                    status="skipped",
                    error_code="CONSENT_MISSING",
                    suggested_fix=None,
                ),
            )

        # 3. Host AI Switch Invariant (FR35): When AI is disabled or missing, return rule-based findings
        # with HTTP 200 and ai_step.status = "skipped", error_code = "AI_DISABLED".
        if payload.ai_enabled is not True:
            return ExplainResponse(
                request_id=payload.request_id,
                explanation=None,
                slow_patterns=slow_patterns,
                ai_step=AIStep(
                    status="skipped",
                    error_code="AI_DISABLED",
                    suggested_fix=None,
                ),
            )

        explanation = rule_analysis["ai_step"].get("suggested_fix")
        return ExplainResponse(
            request_id=payload.request_id,
            explanation=explanation,
            slow_patterns=slow_patterns,
            ai_step=AIStep(
                status="ok",
                error_code=None,
                suggested_fix=explanation,
            ),
        )

    except LLMTimeoutError as exc:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail={
                "code": exc.code,
                "message": exc.message,
                "retry_after_seconds": exc.retry_after_seconds,
            },
        ) from exc

    except LLMRateLimitError as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail={
                "code": exc.code,
                "message": exc.message,
                "retry_after_seconds": exc.retry_after_seconds,
            },
        ) from exc

    except LLMProviderError as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail={
                "code": exc.code,
                "message": exc.message,
                "retry_after_seconds": exc.retry_after_seconds,
            },
        ) from exc

    except Exception as exc:
        # Non-sensitive error logging (NFR8.2: no raw code or stderr logged)
        logger.error(
            "Unexpected error in explain endpoint for request_id=%s: %s",
            payload.request_id,
            type(exc).__name__,
        )
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={
                "code": "PROVIDER_ERROR",
                "message": "Internal error occurred while communicating with AI provider.",
                "retry_after_seconds": None,
            },
        ) from exc
