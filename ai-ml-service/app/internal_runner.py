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
import re
from typing import Any, Optional
import uuid

import httpx
from pydantic import BaseModel, Field

from app.complexity import MeasurementItem
from app.config import settings

logger = logging.getLogger(__name__)

UUID_REGEX = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)


def to_uuid_string(val: str) -> str:
    """Ensure val conforms to UUID format (8-4-4-4-12 hex) as required by internal-run schema."""
    if val and UUID_REGEX.match(val):
        return val
    try:
        return str(uuid.UUID(val))
    except (ValueError, AttributeError, TypeError):
        if val:
            return str(uuid.uuid5(uuid.NAMESPACE_DNS, val))
        return str(uuid.uuid4())


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
    output: Optional[str] = None


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
    failed: bool = False
    failure_reason: Optional[str] = None
    total_runs_completed: int = 0
    total_runs_requested: int = 0

    @property
    def can_estimate(self) -> bool:
        if self.refused or self.timed_out or self.incomplete or self.failed:
            return False
        return len(self.measurements) >= 3


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
        failed: bool = False,
        failure_reason: Optional[str] = None,
        default_output: Optional[str] = None,
        code_overrides: Optional[dict[str, dict[str, Any]]] = None,
    ) -> None:
        self.growth_type = growth_type
        self.base_time_ms = base_time_ms
        self.timeout_sizes = timeout_sizes or set()
        self.refusal_code = refusal_code
        self.refusal_reason = refusal_reason
        self.retry_after_seconds = retry_after_seconds
        self.predefined_runs = predefined_runs
        self.incomplete_reason = incomplete_reason
        self.failed = failed
        self.failure_reason = failure_reason
        self.default_output = default_output
        self.code_overrides = code_overrides or {}
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

        # Resolve any per-code behavior overrides for testing fixes vs original code
        override: dict[str, Any] = {}
        if self.code_overrides:
            for marker, ov in self.code_overrides.items():
                if marker in code:
                    override = ov
                    break

        failed = override.get("failed", self.failed)
        failure_reason = override.get("failure_reason", self.failure_reason)
        incomplete_reason = override.get("incomplete_reason", self.incomplete_reason)
        refusal_code = override.get("refusal_code", self.refusal_code)
        refusal_reason = override.get("refusal_reason", self.refusal_reason)
        retry_after = override.get("retry_after_seconds", self.retry_after_seconds)
        predefined_runs = override.get("predefined_runs", self.predefined_runs)
        timeout_sizes = override.get("timeout_sizes", self.timeout_sizes)
        growth_type = override.get("growth_type", self.growth_type)
        base_time_ms = override.get("base_time_ms", self.base_time_ms)
        output_val = override.get("output", self.default_output)

        # 0. Failure Simulation (compile error, crash)
        if failed:
            return InternalMeasurementResult(
                request_id=request_id,
                purpose="measurement",
                failed=True,
                failure_reason=failure_reason or "Code failed to compile or run.",
                total_runs_requested=runs_requested,
                total_runs_completed=0,
            )

        # 1. Incomplete Simulation
        if incomplete_reason:
            return InternalMeasurementResult(
                request_id=request_id,
                purpose="measurement",
                incomplete=True,
                incomplete_reason=incomplete_reason,
                total_runs_requested=runs_requested,
                total_runs_completed=0,
            )

        # 2. Budget Refusal Simulation (AC 5, US-64)
        if refusal_code == "RUN_BUDGET_EXCEEDED":
            reason = refusal_reason or "aggregate_time_budget"
            return InternalMeasurementResult(
                request_id=request_id,
                purpose="measurement",
                refused=True,
                refusal_code="RUN_BUDGET_EXCEEDED",
                refusal_reason=reason,
                retry_after_seconds=retry_after,
                total_runs_requested=runs_requested,
                total_runs_completed=0,
            )

        # 3. Predefined runs if provided
        completed_run_items: list[MeasurementRunItem] = []
        measurements: list[MeasurementItem] = []
        timed_out = False
        first_timed_out_size: Optional[int] = None

        if predefined_runs is not None:
            for item in predefined_runs:
                inp_str = str(item["input"])
                runtimes = [float(x) for x in item["runtimes_ms"]]
                item_out = item.get("output", output_val)
                completed_run_items.append(MeasurementRunItem(input=inp_str, runtimes_ms=runtimes, output=item_out))
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

        # 4. Synthetic Generation Based on growth_type
        # Default n=100 as base
        for inp_str in inputs:
            try:
                size = int(inp_str)
            except ValueError:
                size = 100

            # Check for simulated timeout
            if inp_str in timeout_sizes or str(size) in timeout_sizes:
                runtimes = [5000.0] * repeats
                timed_out = True
                if first_timed_out_size is None:
                    first_timed_out_size = size
            elif growth_type == "constant":
                # O(1) -> timings flat
                runtimes = [round(base_time_ms * (1.0 + (i * 0.02 - 0.02)), 3) for i in range(repeats)]
            elif growth_type == "linear":
                # O(n) -> time scales linearly with size / 100
                scale = size / 100.0
                runtimes = [round(base_time_ms * scale * (1.0 + (i * 0.02 - 0.02)), 3) for i in range(repeats)]
            elif growth_type == "quadratic":
                # O(n^2) -> time scales quadratically with (size / 100)^2
                scale = (size / 100.0) ** 2
                runtimes = [round(base_time_ms * scale * (1.0 + (i * 0.02 - 0.02)), 3) for i in range(repeats)]
            elif growth_type == "noisy":
                # High jitter to simulate poor fit (R^2 < 0.85)
                # Vary irregularly across repeats and sizes
                runtimes = [round(base_time_ms * ((size % 3 + 1) * 2.5 + i * 5.0), 3) for i in range(repeats)]
            else:
                # Default linear scaling
                scale = size / 100.0
                runtimes = [round(base_time_ms * scale, 3) for _ in range(repeats)]

            completed_run_items.append(MeasurementRunItem(input=inp_str, runtimes_ms=runtimes, output=output_val))
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
        # execution-service internal endpoint strictly requires request_id to conform to UUID format (8-4-4-4-12 hex)
        # per docs/schemas/internal-run-request.json.
        internal_request_id = to_uuid_string(request_id)
        payload = {
            "request_id": internal_request_id,
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

        if resp.status_code == 422:
            body = {}
            try:
                body = resp.json()
            except Exception:
                pass
            err = body.get("error", {})
            err_msg = err.get("message", "Execution service rejected measurement request.")
            err_code = err.get("code", "INVALID_REQUEST")
            logger.warning("execution-service /internal/runs 422: %s (%s)", err_msg, err_code)
            return InternalMeasurementResult(
                request_id=request_id,
                purpose="measurement",
                incomplete=True,
                incomplete_reason=f"Execution service could not process measurement: {err_msg}",
                total_runs_requested=len(inputs) * repeats,
                total_runs_completed=0,
            )

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
            item_out = item.get("output") or item.get("stdout")
            completed_run_items.append(MeasurementRunItem(input=inp_str, runtimes_ms=runtimes, output=item_out))

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
