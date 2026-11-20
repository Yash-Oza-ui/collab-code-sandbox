"""Execution failure explainer router.

Implements T-16 (FR13.1, EPIC 3) failure explanation prototype behind T-07 contract.
Conforms to docs/ai-ml-contract.md §4.3, §5, FR12.3, FR12.4, US-17, US-21 consent gating, US-22 AI control, and US-76 relevant code extraction (FR40.2).
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
    RoomRateLimitedError,
    generate_explanation,
)
from app import chain
from app.chain import ChainRequest
from app.rules.patterns import detect_slow_patterns, run_rule_based_analysis_with_ai_fallback

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
    error_line: Optional[int] = Field(
        default=None,
        description="Optional 1-based error line for relevant section extraction (FR40.2).",
    )
    function_name: Optional[str] = Field(
        default=None,
        description="Optional target function name for relevant section extraction (FR40.2).",
    )


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
    """Explain a failed run in plain prose using central method chain (FR12.3, FR13.1, T-16, US-17, US-22, US-76, US-77, FR40.3)."""
    # Publish validated metadata into request.state for the logging middleware.
    # Only safe, non-user-text metadata fields are stored — never code/stderr.
    request.state.request_id = payload.request_id
    request.state.room_id = payload.room_id
    request.state.request_type = payload.request_type
    request.state.language = payload.language

    try:
        chain_req = ChainRequest(
            request_type=payload.request_type,
            code=payload.code,
            language=payload.language,
            room_id=payload.room_id,
            request_id=payload.request_id,
            consent=payload.consent,
            ai_enabled=payload.ai_enabled,
            failure_kind=payload.failure_kind,
            exit_code=payload.exit_code,
            error_output=payload.stderr,
            timed_out=payload.timed_out,
            oom_killed=payload.oom_killed,
            error_line=payload.error_line,
            function_name=payload.function_name,
            rules_first=False,
            reraise_ai_errors=True,
            cache_context={
                "failure_kind": payload.failure_kind,
                "exit_code": payload.exit_code,
                "timed_out": payload.timed_out,
                "oom_killed": payload.oom_killed,
                "error_line": payload.error_line,
                "function_name": payload.function_name,
            },
        )
        chain_resp = await chain.answer(chain_req)
        slow_patterns = (
            chain_resp.rule_findings
            if chain_resp.rule_findings is not None
            else detect_slow_patterns(payload.code, language=payload.language)
        )
        request.state.rule_findings = slow_patterns
        ai_step = chain_resp.ai_step

        if ai_step.get("status") == "skipped":
            request.state.error_code = ai_step.get("error_code")
            return ExplainResponse(
                request_id=payload.request_id,
                explanation=None,
                slow_patterns=slow_patterns,
                ai_step=AIStep(
                    status="skipped",
                    error_code=ai_step.get("error_code"),
                    suggested_fix=None,
                ),
            )

        explanation = ai_step.get("suggested_fix") or chain_resp.answer
        request.state.error_code = None
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
        request.state.error_code = exc.code
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail={
                "code": exc.code,
                "message": exc.message,
                "retry_after_seconds": exc.retry_after_seconds,
            },
        ) from exc

    except LLMRateLimitError as exc:
        request.state.error_code = exc.code
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail={
                "code": exc.code,
                "message": exc.message,
                "retry_after_seconds": exc.retry_after_seconds,
            },
        ) from exc

    except LLMProviderError as exc:
        request.state.error_code = exc.code
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
        request.state.error_code = "PROVIDER_ERROR"
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
