"""Execution failure explainer router.

Implements T-16 (FR13.1, EPIC 3) failure explanation prototype behind T-07 contract.
Conforms to docs/ai-ml-contract.md §4.3 and §5.
"""

import logging
from typing import Literal, Optional
from fastapi import APIRouter, HTTPException, Request, status
from pydantic import BaseModel, Field

from app.llm.explainer import (
    LLMProviderError,
    LLMTimeoutError,
    generate_explanation,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1", tags=["explain"])


class ExplainRequest(BaseModel):
    """Execution context payload for failed run explanation."""

    request_id: str
    room_id: str
    request_type: Literal["explanation"] = "explanation"
    language: Literal["cpp", "python", "c", "java", "javascript"]
    consent: bool = Field(..., description="User consent boolean (FR12)")
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
    """Explain a failed run in plain prose using LLM API (FR13.1, T-16)."""
    # Track request_id for consistent error envelope responses
    request.state.request_id = payload.request_id

    # Enforce privacy consent gate (FR12.2, contract §5)
    if not payload.consent:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "CONSENT_MISSING",
                "message": "User consent is required before code or execution context can be sent to external AI.",
                "retry_after_seconds": None,
            },
        )

    try:
        explanation = await generate_explanation(
            language=payload.language,
            failure_kind=payload.failure_kind,
            exit_code=payload.exit_code,
            stderr=payload.stderr,
            timed_out=payload.timed_out,
            oom_killed=payload.oom_killed,
            code=payload.code,
            request_id=payload.request_id,
        )

        return ExplainResponse(
            request_id=payload.request_id,
            explanation=explanation,
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
