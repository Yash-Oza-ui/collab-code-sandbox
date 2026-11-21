"""Internal container-execution client and measurement runner (US-64, US-65, T-18 Decision 7, §18).

This module coordinates repeated-run container executions with execution-service
through POST /internal/runs (purpose: "measurement") with authentication header X-Internal-Token.

Includes:
- MeasurementRunner interface (Protocol/ABC)
- StubMeasurementRunner for deterministic unit testing without Docker or external network
- HttpInternalRunClient for containerized executions through execution-service
"""

from abc import ABC, abstractmethod
import logging
from typing import Any, Optional

import httpx
from pydantic import BaseModel, Field

from app.complexity import MeasurementItem
from app.config import settings

logger = logging.getLogger(__name__)


class RunBudgetExceededError(Exception):
    """Raised when execution-service responds with HTTP 429 RUN_BUDGET_EXCEEDED (US-64, NFR14)."""

    def __init__(
        self,
        reason: str = "aggregate_time_budget",
        message: Optional[str] = None,
        retry_after_seconds: Optional[int] = 30,
        request_id: Optional[str] = None,
    ) -> None:
        self.code = "RUN_BUDGET_EXCEEDED"
        self.reason = reason
        self.retry_after_seconds = retry_after_seconds or 30
        self.request_id = request_id
        self.message = message or f"Repeated-run execution budget exceeded ({reason})."
        super().__init__(self.message)


class ExecutionServiceUnavailableError(Exception):
    """Raised when execution-service cannot be reached or returns an unavailable error."""

    def __init__(
        self,
        message: str,
        retry_after_seconds: Optional[int] = 5,
        code: str = "ROOM_SERVICE_UNAVAILABLE",
    ) -> None:
        self.code = code
        self.message = message
        self.retry_after_seconds = retry_after_seconds
        super().__init__(self.message)


class MeasurementRunItem(BaseModel):
    """A single input size run item from execution-service."""

    input: str
    runtimes_ms: list[float]


class InternalMeasurementResult(BaseModel):
    """Aggregate result from repeated container execution."""

    request_id: str
    purpose: str = "measurement"
    completed_runs: list[MeasurementRunItem] = Field(default_factory=list)
    measurements: list[MeasurementItem] = Field(default_factory=list)
    timed_out: bool = False
    timed_out_size: Optional[int] = None
    refused: bool = False
    refusal_code: Optional[str] = None
    refusal_reason: Optional[str] = None
    retry_after_seconds: Optional[int] = None
    incomplete: bool = False
    incomplete_reason: Optional[str] = None
    total_runs_completed: int = 0
    total_runs_requested: int = 0


class MeasurementRunner(ABC):
    """Interface for measurement executions at geometric input sizes."""

    @abstractmethod
    async def run_measurement(
        self,
        request_id: str,
        room_id: str,
        language: str,
        code: str,
        inputs: list[str],
        repeats: int = 3,
    ) -> InternalMeasurementResult:
        """Run the program at the specified inputs and collect repeated execution runtimes."""
        pass


class StubMeasurementRunner(MeasurementRunner):
    """Stub runner for deterministic unit testing (Sprint 3 early start / US-65 ACs).

    Never executes user code on the host; returns predefined or synthetic timings
    matching requested growth classes (constant, linear, quadratic, noisy), simulated timeouts,
    or budget refusals.
    """

    def __init__(
        self,
        growth_type: Optional[str] = None,
        base_time_ms: float = 1.0,
        timeout_sizes: Optional[set[str]] = None,
        refusal_code: Optional[str] = None,
        refusal_reason: Optional[str] = None,
        retry_after_seconds: Optional[int] = 30,
        predefined_runs: Optional[list[dict[str, Any]]] = None,
        incomplete_reason: Optional[str] = None,
    ) -> None:
        self.growth_type = growth_type
        self.base_time_ms = base_time_ms
        self.timeout_sizes = timeout_sizes or set()
        self.refusal_code = refusal_code
        self.refusal_reason = refusal_reason
        self.retry_after_seconds = retry_after_seconds
        self.predefined_runs = predefined_runs
        self.incomplete_reason = incomplete_reason
        self.call_count = 0
        self.total_runs_requested = 0
        self.last_request: Optional[dict[str, Any]] = None

    async def run_measurement(
        self,
        request_id: str,
        room_id: str,
        language: str,
        code: str,
        inputs: list[str],
        repeats: int = 3,
    ) -> InternalMeasurementResult:
        self.call_count += 1
        runs_requested = len(inputs) * repeats
        self.total_runs_requested += runs_requested
        self.last_request = {
            "request_id": request_id,
            "room_id": room_id,
            "language": language,
            "inputs": inputs,
            "repeats": repeats,
        }

        # 0. Incomplete Simulation
        if self.incomplete_reason:
            return InternalMeasurementResult(
                request_id=request_id,
                purpose="measurement",
                incomplete=True,
                incomplete_reason=self.incomplete_reason,
                total_runs_requested=runs_requested,
                total_runs_completed=0,
            )

        # 1. Budget Refusal Simulation (AC 5, US-64)
        if self.refusal_code == "RUN_BUDGET_EXCEEDED":
            reason = self.refusal_reason or "aggregate_time_budget"
            return InternalMeasurementResult(
                request_id=request_id,
                purpose="measurement",
                refused=True,
                refusal_code="RUN_BUDGET_EXCEEDED",
                refusal_reason=reason,
                retry_after_seconds=self.retry_after_seconds,
                total_runs_requested=runs_requested,
                total_runs_completed=0,
            )

        # 2. Predefined runs if provided
        completed_run_items: list[MeasurementRunItem] = []
        measurements: list[MeasurementItem] = []
        timed_out = False
        first_timed_out_size: Optional[int] = None

        if self.predefined_runs is not None:
            for item in self.predefined_runs:
                inp_str = str(item["input"])
                runtimes = [float(x) for x in item["runtimes_ms"]]
                completed_run_items.append(MeasurementRunItem(input=inp_str, runtimes_ms=runtimes))
                try:
                    size = int(inp_str)
                    measurements.append(MeasurementItem(input_size=size, runtimes_ms=runtimes))
                    if any(t >= 5000.0 for t in runtimes) and first_timed_out_size is None:
                        timed_out = True
                        first_timed_out_size = size
                except ValueError:
                    pass
            return InternalMeasurementResult(
                request_id=request_id,
                purpose="measurement",
                completed_runs=completed_run_items,
                measurements=measurements,
                timed_out=timed_out,
                timed_out_size=first_timed_out_size,
                total_runs_completed=len(completed_run_items) * repeats,
                total_runs_requested=runs_requested,
            )

        # 3. Synthetic Generation Based on growth_type
        # Default n=100 as base
        for inp_str in inputs:
            try:
                size = int(inp_str)
            except ValueError:
                size = 100

            # Check for simulated timeout
            if inp_str in self.timeout_sizes or str(size) in self.timeout_sizes:
                runtimes = [5000.0] * repeats
                timed_out = True
                if first_timed_out_size is None:
                    first_timed_out_size = size
            elif self.growth_type == "constant":
                # O(1) -> timings flat
                runtimes = [round(self.base_time_ms * (1.0 + (i * 0.02 - 0.02)), 3) for i in range(repeats)]
            elif self.growth_type == "linear":
                # O(n) -> time scales linearly with size / 100
                scale = size / 100.0
                runtimes = [round(self.base_time_ms * scale * (1.0 + (i * 0.02 - 0.02)), 3) for i in range(repeats)]
            elif self.growth_type == "quadratic":
                # O(n^2) -> time scales quadratically with (size / 100)^2
                scale = (size / 100.0) ** 2
                runtimes = [round(self.base_time_ms * scale * (1.0 + (i * 0.02 - 0.02)), 3) for i in range(repeats)]
            elif self.growth_type == "noisy":
                # High jitter to simulate poor fit (R^2 < 0.85)
                # Vary irregularly across repeats and sizes
                runtimes = [round(self.base_time_ms * ((size % 3 + 1) * 2.5 + i * 5.0), 3) for i in range(repeats)]
            else:
                # Default linear scaling
                scale = size / 100.0
                runtimes = [round(self.base_time_ms * scale, 3) for _ in range(repeats)]

            completed_run_items.append(MeasurementRunItem(input=inp_str, runtimes_ms=runtimes))
            measurements.append(MeasurementItem(input_size=size, runtimes_ms=runtimes))

        return InternalMeasurementResult(
            request_id=request_id,
            purpose="measurement",
            completed_runs=completed_run_items,
            measurements=measurements,
            timed_out=timed_out,
            timed_out_size=first_timed_out_size,
            total_runs_completed=len(inputs) * repeats,
            total_runs_requested=runs_requested,
        )


class HttpInternalRunClient(MeasurementRunner):
    """HTTP client calling execution-service POST /internal/runs (T-18 Decision 7, §18).

    Enforces container isolation, queue sharing, per-request budget, and timeout rules
    implemented in execution-service (US-64, NFR14).
    """

    def __init__(self, base_url: Optional[str] = None, token: Optional[str] = None, timeout: float = 30.0) -> None:
        self.base_url = (base_url or getattr(settings, "EXECUTION_SERVICE_URL", "http://execution-service:8082")).rstrip("/")
        self.token = token or getattr(settings, "INTERNAL_API_TOKEN", "")
        self.timeout = timeout

    async def run_measurement(
        self,
        request_id: str,
        room_id: str,
        language: str,
        code: str,
        inputs: list[str],
        repeats: int = 3,
    ) -> InternalMeasurementResult:
        url = f"{self.base_url}/internal/runs"
        headers = {
            "Content-Type": "application/json",
            "X-Internal-Token": self.token,
        }
        payload = {
            "request_id": request_id,
            "room_id": room_id,
            "purpose": "measurement",
            "language": language,
            "code": code,
            "inputs": inputs,
            "repeats": repeats,
        }

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            try:
                resp = await client.post(url, json=payload, headers=headers)
            except (httpx.ConnectError, httpx.ConnectTimeout, httpx.NetworkError) as exc:
                logger.error(
                    "execution-service not reachable at %s (%s). Never returning synthetic measurements.",
                    self.base_url,
                    type(exc).__name__,
                )
                raise ExecutionServiceUnavailableError(
                    f"Execution service unavailable at {self.base_url} ({type(exc).__name__}). Measurements could not be performed."
                ) from exc
            except Exception as exc:
                logger.error("Failed to connect to execution-service internal endpoint: %s", type(exc).__name__)
                raise ExecutionServiceUnavailableError(
                    f"Failed to communicate with execution-service: {type(exc).__name__}"
                ) from exc

        # Check HTTP 429 RUN_BUDGET_EXCEEDED
        if resp.status_code == 429:
            body = resp.json()
            err = body.get("error", {})
            reason = err.get("reason", "aggregate_time_budget")
            retry_after = err.get("retry_after_seconds", 30)
            message = err.get("message", f"Repeated-run execution budget exceeded ({reason}).")
            logger.warning("Internal run refused with RUN_BUDGET_EXCEEDED: %s (reason: %s)", message, reason)
            return InternalMeasurementResult(
                request_id=request_id,
                purpose="measurement",
                refused=True,
                refusal_code="RUN_BUDGET_EXCEEDED",
                refusal_reason=reason,
                retry_after_seconds=retry_after,
                total_runs_requested=len(inputs) * repeats,
                total_runs_completed=0,
            )

        if resp.status_code == 503:
            body = {}
            try:
                body = resp.json()
            except Exception:
                pass
            err = body.get("error", {})
            err_msg = err.get("message", "Execution service queue is full or service unavailable.")
            retry = err.get("retry_after_seconds", 5)
            logger.warning("execution-service /internal/runs 503: %s", err_msg)
            raise ExecutionServiceUnavailableError(err_msg, retry_after_seconds=retry)

        if resp.status_code != 200:
            logger.warning("execution-service /internal/runs failed with status %d: %s", resp.status_code, resp.text[:200])
            raise ExecutionServiceUnavailableError(
                f"Execution service returned error status {resp.status_code}."
            )

        data = resp.json()
        completed_runs_raw = data.get("completed_runs", [])
        completed_run_items: list[MeasurementRunItem] = []
        measurements: list[MeasurementItem] = []
        timed_out = False
        first_timed_out_size: Optional[int] = None
        incomplete = False
        incomplete_reason: Optional[str] = None

        returned_inputs: set[str] = set()
        for item in completed_runs_raw:
            inp_str = str(item.get("input", ""))
            returned_inputs.add(inp_str)
            runtimes = [float(r) for r in item.get("runtimes_ms", [])]
            completed_run_items.append(MeasurementRunItem(input=inp_str, runtimes_ms=runtimes))

            # Check if any input has fewer repeats than requested
            if len(runtimes) < repeats:
                incomplete = True
                incomplete_reason = f"Incomplete repeats for input size '{inp_str}': expected {repeats}, received {len(runtimes)}."

            try:
                size = int(inp_str)
                measurements.append(MeasurementItem(input_size=size, runtimes_ms=runtimes))
                if any(t >= 5000.0 for t in runtimes) and first_timed_out_size is None:
                    timed_out = True
                    first_timed_out_size = size
            except ValueError:
                pass

        # Check if any requested inputs were completely missing
        missing_inputs = [inp for inp in inputs if inp not in returned_inputs]
        if missing_inputs and not timed_out:
            incomplete = True
            incomplete_reason = f"Missing measurement results for input size(s): {', '.join(missing_inputs)}."

        return InternalMeasurementResult(
            request_id=request_id,
            purpose="measurement",
            completed_runs=completed_run_items,
            measurements=measurements,
            timed_out=timed_out,
            timed_out_size=first_timed_out_size,
            incomplete=incomplete,
            incomplete_reason=incomplete_reason,
            total_runs_completed=len(completed_run_items) * repeats,
            total_runs_requested=len(inputs) * repeats,
        )


_runner_instance: Optional[MeasurementRunner] = None


def get_measurement_runner() -> MeasurementRunner:
    """Return the active MeasurementRunner instance (HttpInternalRunClient by default)."""
    global _runner_instance
    if _runner_instance is None:
        if getattr(settings, "USE_STUB_RUNNER", False):
            _runner_instance = StubMeasurementRunner(growth_type="linear", base_time_ms=1.0)
        else:
            _runner_instance = HttpInternalRunClient()
    return _runner_instance


def set_measurement_runner(runner: Optional[MeasurementRunner]) -> None:
    """Inject a MeasurementRunner instance (e.g. StubMeasurementRunner in unit tests)."""
    global _runner_instance
    _runner_instance = runner
