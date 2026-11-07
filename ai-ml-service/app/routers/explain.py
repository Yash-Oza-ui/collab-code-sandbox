"""Execution failure explainer router.

Implements T-16 (FR13.1, EPIC 3) failure explanation prototype behind T-07 contract.
Conforms to docs/ai-ml-contract.md §4.3, §5, FR12.3 and US-17 consent gating.
"""

import logging
from typing import Literal, Optional
from fastapi import APIRouter, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.llm import (
    ConsentMissingError,
    LLMProviderError,
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
    code: str
    stderr: str = ""
    exit_code: int
    execution_time_ms: float
    peak_memory_mb: float
    timed_out: bool = False
    oom_killed: bool = False
    failure_kind: Optional[Literal["compile_error", "non_zero_exit", "timeout"]] = None


class ExplainResponse(BaseModel):
    """Short plain-language explanation (at most 3 sentences)."""

    request_id: str
    explanation: str


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
            ai_fallback=explain_fallback,
        )

        # 2. Preserve rule-based findings internally (FR12.3):
        request.state.rule_findings = rule_analysis.get("slow_patterns", [])
        logger.info(
            "Preserved rule-based analysis findings for request_id=%s (patterns_found=%d)",
            payload.request_id,
            len(request.state.rule_findings),
        )

        # 3. Check consent gate for external AI call (FR12.2, US-17 Version 0 behavior):
        # When consent is false or missing (None), rule-based analysis was preserved above,
        # and NO external LLM call occurs. Raise ConsentMissingError to return HTTP 403 CONSENT_MISSING.
        if payload.consent is not True:
            raise ConsentMissingError(
                message="User consent is required before code or execution context can be sent to external AI.",
                status_code=403,
                code="CONSENT_MISSING",
            )

        explanation = rule_analysis["ai_step"]["suggested_fix"]
        return ExplainResponse(
            request_id=payload.request_id,
            explanation=explanation,
        )

    except ConsentMissingError as exc:
        raise HTTPException(
            status_code=exc.status_code,
            detail={
                "code": exc.code,
                "message": exc.message,
                "retry_after_seconds": exc.retry_after_seconds,
            },
        ) from exc

    except LLMTimeoutError as exc:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
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
