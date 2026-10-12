"""Execution failure explainer router.

PLACEHOLDER - Baseline stub implementation for T-04 scaffold.
Full LLM prototype is implemented in T-16 (FR13).
Conforms to docs/ai-ml-contract.md §4.3 and §5.
"""

from typing import Literal, Optional
from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field

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
def explain_failure(payload: ExplainRequest) -> ExplainResponse:
    """Explain a failed run in plain prose (PLACEHOLDER stub)."""
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

    # Stub response for scaffold testing
    stub_message = (
        f"Execution failed with exit code {payload.exit_code}. "
        "Review standard error output for diagnostic details."
    )

    return ExplainResponse(
        request_id=payload.request_id,
        explanation=stub_message,
    )
