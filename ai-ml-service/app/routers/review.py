"""AI code review router.

PLACEHOLDER - Baseline stub implementation for T-04 scaffold.
User-facing review functionality is planned for Sprint 3 (FR7, FR12).
Conforms to docs/ai-ml-contract.md §4.2 and §5.
"""

from typing import List, Literal, Optional
from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field

router = APIRouter(prefix="/v1", tags=["review"])


class ReviewComment(BaseModel):
    """Individual code review feedback comment."""

    line: Optional[int] = None
    severity: Literal["info", "warning", "error"] = "info"
    message: str
    suggestion: Optional[str] = None


class ReviewRequest(BaseModel):
    """Payload for on-demand code review."""

    request_id: str
    room_id: str
    request_type: Literal["review"] = "review"
    language: Literal["cpp", "python", "c", "java", "javascript"]
    consent: bool = Field(..., description="User consent boolean (FR12)")
    code: str
    stderr: str = ""
    exit_code: int = 0
    execution_time_ms: float = 0.0
    peak_memory_mb: float = 0.0
    timed_out: bool = False
    oom_killed: bool = False
    failure_kind: Optional[Literal["compile_error", "non_zero_exit", "timeout"]] = None


class ReviewResponse(BaseModel):
    """Code review result."""

    request_id: str
    summary: str
    comments: List[ReviewComment] = []


@router.post("/review", response_model=ReviewResponse)
def review_code(payload: ReviewRequest) -> ReviewResponse:
    """Perform on-demand code review (PLACEHOLDER stub)."""
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

    return ReviewResponse(
        request_id=payload.request_id,
        summary="Code review completed successfully; no issues detected in baseline check.",
        comments=[],
    )
