"""US-70 failing-input search. User and generated code execute only in execution-service Docker."""
import json
import os
import re
import secrets
import time
import uuid
from typing import Literal

import httpx
from fastapi import APIRouter, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict, Field

from app import chain
from app.chain import ChainRequest
from app.config import settings
from app.counters import record_fallback
from app.section import extract_relevant_section

router = APIRouter(prefix="/v1", tags=["analyses"])

LANGUAGE = Literal["cpp", "python", "c", "java", "javascript"]
FAILURES = {"wrong_output", "crash", "timeout"}


class FailingInputRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    request_id: str
    room_id: str = Field(pattern=r"^[A-Za-z0-9_-]{22}$")
    analysis_id: str
    request_type: Literal["failing_input"]
    language: LANGUAGE
    consent: bool
    ai_enabled: bool
    code: str = Field(min_length=1)
    reference_solution: str | None = Field(default=None, min_length=1)


class AIStep(BaseModel):
    status: Literal["ok", "skipped"]
    error_code: str | None = None


class TestCase(BaseModel):
    input: str
    expected_output: str | None
    failure_type: Literal["wrong_output", "crash", "timeout"]
    language: LANGUAGE


class FailingInputResponse(BaseModel):
    request_id: str
    analysis_id: str
    task: Literal["failing_input"] = "failing_input"
    failure_found: bool = False
    failure_type: Literal["wrong_output", "crash", "timeout"] | None = None
    original_input: str | None = None
    minimized_input: str | None = None
    expected_output: str | None = None
    actual_output: str | None = None
    tests_executed: int = 0
    minimization_status: Literal["completed", "budget_exhausted", "not_applicable"] = "not_applicable"
    test_case: TestCase | None = None
    can_save_as_test_case: bool = False
    ai_step: AIStep


class Budget:
    """Conservatively bound all sandbox processes and elapsed execution time across batches."""
    def __init__(self):
        self.remaining = settings.FAILING_INPUT_MAX_RUNS
        self.deadline = time.monotonic() + settings.FAILING_INPUT_BUDGET_SECONDS
        self.used = 0
        self.exhausted = False

    def can_run(self, count: int) -> bool:
        allowed = count > 0 and count <= self.remaining and time.monotonic() < self.deadline
        if not allowed:
            self.exhausted = True
        return allowed

    def record(self, count: int) -> None:
        self.used += count
        self.remaining -= count


def edge_cases(code: str) -> tuple[list[str], list[str]]:
    """Conservative cases requiring no assumed input grammar. Never fabricate bounds."""
    values = ["", "1"]
    skipped = ["maximum_bounds (not supplied)", "negative_values (format not supplied)"]
    # Only add multi-token inputs when a common token-based reader is evident.
    # This is not a full parser: unknown formats/bounds are explicitly skipped.
    token_reader = (
        r"input\(\)\.split\(\)|stdin\.read\(\)\.split\(\)"
        r"|\bcin\s*>>\s*\w+\s*>>|\bscanf\s*\("
        r"|\bScanner\s*\(|\bnextInt\s*\("
        r"|\breadFileSync\s*\("
    )
    if re.search(token_reader, code):
        values.extend(["1 1", "1 2 3", "10 -5 20"])
        skipped.remove("negative_values (format not supplied)")
    else:
        skipped.extend(["duplicates (format not supplied)", "multiple_values (format not supplied)"])
    return values, skipped


def _fixed_error(code: str, status: int) -> HTTPException:
    messages = {
        "UNAUTHORIZED": "An authoritative reference requires internal authentication.",
        "PROVIDER_ERROR": "Internal sandbox execution is unavailable.",
        "INVALID_REQUEST": "Invalid internal batch result.",
    }
    return HTTPException(status_code=status, detail={
        "code": code, "message": messages[code], "retry_after_seconds": None
    })


async def sandbox_batch(
    payload: FailingInputRequest,
    budget: Budget,
    code: str,
    inputs: list[str],
    *,
    language: str | None = None,
    expected: list[str] | None = None,
) -> tuple[list[dict], bool]:
    """One execution-service batch = one isolated container; no host code execution."""
    if not budget.can_run(len(inputs)):
        return [], True
    token = os.getenv("INTERNAL_API_TOKEN", "")
    if not token:
        raise _fixed_error("PROVIDER_ERROR", 503)
    url = settings.EXECUTION_SERVICE_URL.rstrip("/") + "/internal/runs"
    body = {
        "request_id": str(uuid.uuid4()), "room_id": payload.room_id,
        "purpose": "test_batch", "language": language or payload.language,
        "code": code, "inputs": inputs,
    }
    if expected is not None:
        body["expected_outputs"] = expected
    try:
        async with httpx.AsyncClient(timeout=settings.FAILING_INPUT_HTTP_TIMEOUT_SECONDS) as client:
            res = await client.post(url, json=body, headers={"X-Internal-Token": token})
        data = res.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise _fixed_error("PROVIDER_ERROR", 502) from exc

    if not isinstance(data, dict):
        raise _fixed_error("INVALID_REQUEST", 502)
    if res.status_code == 429 and data.get("error", {}).get("code") == "RUN_BUDGET_EXCEEDED":
        budget.exhausted = True
    elif res.status_code != 200:
        raise _fixed_error("PROVIDER_ERROR", 502)
    if data.get("request_id") != body["request_id"]:
        raise _fixed_error("INVALID_REQUEST", 502)
    records = data.get("batch_results", [])
    if not isinstance(records, list) or len(records) > len(inputs) or (res.status_code == 200 and len(records) != len(inputs)):
        raise _fixed_error("INVALID_REQUEST", 502)
    for i, record in enumerate(records):
        if not isinstance(record, dict) or record.get("input") != inputs[i] or record.get("status") not in (FAILURES | {"pass"}):
            raise _fixed_error("INVALID_REQUEST", 502)
    budget.record(len(records))
    if len(records) < len(inputs):
        budget.exhausted = True
    if time.monotonic() >= budget.deadline:
        budget.exhausted = True
    return records, budget.exhausted


def identify_failure(record: dict, has_reference: bool) -> str | None:
    """Never trust an unsupported wrong_output without authoritative reference."""
    status = record["status"]
    if record.get("timed_out") is True or status == "timeout":
        return "timeout"
    if record.get("oom_killed") is True or status == "crash" or record.get("exit_code", 0) != 0:
        return "crash"
    if has_reference and status == "wrong_output":
        return "wrong_output"
    return None


def as_response(payload: FailingInputRequest, step: AIStep, records: list[dict],
                with_reference: bool, exhausted: bool) -> FailingInputResponse:
    for record in records:
        failure = identify_failure(record, with_reference)
        if failure is None:
            continue
        value = record["input"]
        expected = record.get("expected_output") if failure == "wrong_output" else None
        actual = record.get("stdout") if failure == "wrong_output" else None
        return FailingInputResponse(
            request_id=payload.request_id, analysis_id=payload.analysis_id,
            failure_found=True, failure_type=failure, original_input=value,
            # US-72 owns minimisation; the original is retained unchanged.
            minimized_input=value, expected_output=expected, actual_output=actual,
            tests_executed=len(records),
            minimization_status="budget_exhausted" if exhausted else "completed",
            test_case=TestCase(input=value, expected_output=expected,
                               failure_type=failure, language=payload.language),
            can_save_as_test_case=True, ai_step=step,
        )
    return FailingInputResponse(
        request_id=payload.request_id, analysis_id=payload.analysis_id,
        tests_executed=len(records), ai_step=step,
    )


def _extract_script(answer: str | None) -> str | None:
    if not answer:
        return None
    text = answer.strip()
    match = re.search(r"```(?:python)?\s*\n([\s\S]*?)\n```", text)
    script = match.group(1).strip() if match else text
    # Text must be executable Python, not prose; still ALWAYS run it in Docker.
    if not script or len(script) > settings.FAILING_INPUT_SCRIPT_MAX_CHARS:
        return None
    return script


async def ai_inputs(payload: FailingInputRequest, budget: Budget) -> tuple[list[str], AIStep]:
    """Get a cached/generated script via US-77, then execute it *inside* sandbox."""
    if payload.consent is not True:
        return [], AIStep(status="skipped", error_code="CONSENT_MISSING")
    if payload.ai_enabled is not True:
        return [], AIStep(status="skipped", error_code="AI_DISABLED")
    if not budget.can_run(1):
        return [], AIStep(status="skipped", error_code=None)
    relevant = extract_relevant_section(payload.code, payload.language)
    prompt = (
        "Return ONLY Python 3 code that prints a JSON array of at most 3 short "
        "stdin strings for edge cases of the following program. No prose, no "
        "markdown. Do not import network or filesystem modules.\n"
        f"Language: {payload.language}\nRelevant code:\n{relevant}"
    )
    result = await chain.answer(ChainRequest(
        request_type="failing_input", code=relevant,
        language=payload.language, room_id=payload.room_id,
        request_id=payload.request_id, consent=True, ai_enabled=True,
        rules_first=False, prompt=prompt, pre_sliced=True, max_tokens=1024,
    ))
    step = AIStep(status=result.ai_step.get("status", "skipped"),
                  error_code=result.ai_step.get("error_code"))
    script = _extract_script(result.answer)
    if step.status != "ok" or script is None:
        return [], step if step.status == "skipped" else AIStep(status="skipped", error_code="PROVIDER_ERROR")
    result_rows, _ = await sandbox_batch(payload, budget, script, [""], language="python")
    if not result_rows or result_rows[0].get("status") != "pass":
        return [], step
    try:
        generated = json.loads(result_rows[0].get("stdout", ""))
    except (ValueError, TypeError):
        return [], step
    if not isinstance(generated, list):
        return [], step
    return ([s for s in generated[:3] if isinstance(s, str) and len(s) <= 512], step)


@router.post("/analyses/failing-input", response_model=FailingInputResponse)
async def failing_input(
    payload: FailingInputRequest, request: Request, response: Response,
) -> FailingInputResponse:
    """US-70: report first failure; never claim an unreferenced pass means correctness."""
    request.state.request_id = payload.request_id
    request.state.room_id = payload.room_id
    request.state.request_type = "failing_input"
    request.state.language = payload.language
    token = os.getenv("INTERNAL_API_TOKEN", "")
    if payload.reference_solution is not None and (
        not token or not secrets.compare_digest(request.headers.get("X-Internal-Token", ""), token)
    ):
        request.state.error_code = "UNAUTHORIZED"
        raise _fixed_error("UNAUTHORIZED", 401)

    cases, skipped = edge_cases(payload.code)
    # Reserve process slots for reference + candidate runs. Aggregate budget
    # applies across both calls, unlike execution-service's per-request budget.
    budget = Budget()
    with_reference = payload.reference_solution is not None
    if with_reference:
        cases = cases[:max(1, budget.remaining // 2)]
    else:
        cases = cases[:budget.remaining]
    if not payload.consent:
        step = AIStep(status="skipped", error_code="CONSENT_MISSING")
    elif not payload.ai_enabled:
        step = AIStep(status="skipped", error_code="AI_DISABLED")
    else:
        # A deterministic finding is a successful analysis even without an AI call.
        step = AIStep(status="ok", error_code=None)
    records: list[dict] = []
    reference_valid = with_reference

    async def check(inputs: list[str]) -> None:
        nonlocal records, reference_valid
        if not inputs or budget.exhausted:
            return
        expected = None
        if reference_valid:
            reference_records, _ = await sandbox_batch(
                payload, budget, payload.reference_solution, inputs
            )
            # Only trust a reference that ran successfully for *every* input.
            if len(reference_records) != len(inputs) or any(
                r["status"] != "pass" or r.get("output_truncated") for r in reference_records
            ):
                reference_valid = False
                # Reference crashed/timed out: do not infer correctness from its output.
                # Continue with crash/timeout-only candidate search when budget permits.
                expected = None
            else:
                expected = [r.get("stdout", "") for r in reference_records]
        if not budget.exhausted:
            batch_records, _ = await sandbox_batch(payload, budget, payload.code, inputs, expected=expected)
            records.extend(batch_records)

    await check(cases)
    found = any(identify_failure(r, reference_valid) for r in records)
    chain_used = False
    if not found and not budget.exhausted and payload.consent and payload.ai_enabled:
        chain_used = True
        generated, step = await ai_inputs(payload, budget)
        generated = [s for s in dict.fromkeys(generated) if s not in cases]
        # With a reference, two process slots are required for each candidate.
        generated = generated[:budget.remaining // (2 if reference_valid else 1)]
        await check(generated)
    response.headers["X-Failing-Input-Reference"] = "available" if reference_valid else "unavailable"
    response.headers["X-Failing-Input-Skipped-Cases"] = ", ".join(skipped)
    response.headers["X-Failing-Input-Budget"] = "exhausted" if budget.exhausted else "ok"
    if not reference_valid:
        response.headers["X-Failing-Input-Scope"] = "crash-and-timeout-only"
    final = as_response(payload, step, records, reference_valid, budget.exhausted)
    # US-77 already records fallback when its provider gates reject a request.
    # Count the AI-free skipped path here without double-counting chain failures.
    if step.error_code and not chain_used:
        record_fallback(payload.room_id)
    request.state.error_code = step.error_code
    return final
