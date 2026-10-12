"""Anomaly detection endpoint router.

PLACEHOLDER - Baseline stub implementation for T-04 scaffold.
Full model integration is handled in T-15 using synthetic data from T-12.
Conforms to docs/ai-ml-contract.md §4.4 and anomaly-spec.md (T-13).
"""

from typing import List, Literal, Optional
from fastapi import APIRouter
from pydantic import BaseModel, Field

router = APIRouter(prefix="/v1", tags=["anomaly"])


class AnomalyRequest(BaseModel):
    """Execution metrics payload for anomaly scoring (no code or text)."""

    request_id: str = Field(..., description="UUID v4 from execution-service")
    room_id: str = Field(..., description="Room identifier")
    request_type: Literal["anomaly"] = "anomaly"
    language: Literal["cpp", "python", "c", "java", "javascript"]
    consent: bool = True
    execution_time_ms: float = Field(..., description="Wall-clock time in ms")
    peak_memory_mb: float = Field(..., description="Peak memory in MB")
    stdout_bytes: int = Field(0, description="Size of stdout in bytes")
    stderr_bytes: int = Field(0, description="Size of stderr in bytes")
    exit_code: int = Field(..., description="Process exit code")
    timed_out: bool = Field(False, description="Killed by time limit")
    oom_killed: bool = Field(False, description="Killed by memory limit")


class AnomalyResponse(BaseModel):
    """Anomaly evaluation result."""

    request_id: str
    is_anomalous: bool = False
    score: float = 0.0
    reasons: List[str] = []
    message_short: str = ""


@router.post("/anomaly", response_model=AnomalyResponse)
def evaluate_anomaly(payload: AnomalyRequest) -> AnomalyResponse:
    """Evaluate execution metrics for anomalies (PLACEHOLDER stub).

    Always returns is_anomalous=False in Sprint 1 scaffold (FR8.1 baseline).
    """
    return AnomalyResponse(
        request_id=payload.request_id,
        is_anomalous=False,
        score=0.0,
        reasons=[],
        message_short="",
    )
