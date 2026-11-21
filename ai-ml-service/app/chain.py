"""Central method chain implementation: cheapest method first (US-77, FR40.3, FR40.4).

Executes AI/analysis tasks in strict ascending order of operational cost:
1. Cache (US-22) — $0 cost, zero outbound calls, instantaneous return.
2. Deterministic rules & statistics (US-67) — $0 cost, deterministic AST/regex/statistics.
3. Local model (optional, US-77) — $0 external API cost, self-hosted/on-premise model.
4. Paid external model (US-17, US-21, US-22, US-24, US-76) — metered outbound LLM call.

A cheaper step producing a valid answer stops the chain immediately.
Never proceeds to a more expensive step unnecessarily.

Strict Privacy Invariant (NFR8.2):
Never logs source code, prompt contents, user input, generated answers, or API keys.
Only safe request metadata and numeric telemetry are recorded.
"""

from dataclasses import dataclass, field
import inspect
import logging
from typing import Any, Awaitable, Callable, Optional, Union

from app.cache import compute_cache_key, get_analysis_cache
from app.config import settings
from app.counters import (
    record_fallback,
    record_local_model_answer,
    record_rules_answer,
)

# -----------------------------------------------------------------------------
# US-24 Monthly Cost-Cap Integration Boundary (PR #156, NFR7.2, US-77 FR40.3)
# -----------------------------------------------------------------------------
# US-24 owns the monthly budget tracker, atomic reservations, and admission checks.
# US-77 does not duplicate monthly cost-cap accounting.
#
# When US-24 (PR #156) is merged into main, app.counters exports:
#   - is_cost_cap_reached(include_reservations: bool = False) -> bool
#   - reserve_call(estimated_tokens: int = 100) -> CallReservation
#   - CostCapReachedError (Exception)
#
# If the US-24 subsystem is unavailable, the central chain FAILS CLOSED before
# dispatching to any paid external model, returning:
#   status="skipped", error_code="COST_CAP_REACHED"
# Cheaper stages (cache, deterministic rules, local model) remain 100% functional.
# Module-level references for test monkeypatching (_UNSET by default)
_UNSET = object()
CostCapReachedError = _UNSET
is_cost_cap_reached = _UNSET
reserve_call = _UNSET


def get_cost_cap_provider():
    """Resolve the active cost-cap provider tuple or None if unmerged/unavailable.

    Returns:
        (is_cap_fn, reserve_fn, err_cls) if cost-cap protection is available,
        or None if the US-24 cost-cap subsystem is unavailable.
    """
    import app.counters as counters

    # 1. Authoritative provider: query app.counters directly
    is_cap_fn = getattr(counters, "is_cost_cap_reached", None)
    reserve_fn = getattr(counters, "reserve_call", None)
    err_cls = getattr(counters, "CostCapReachedError", None)

    # 2. Allow explicit test patches on app.chain module symbols
    mod_cap = globals().get("is_cost_cap_reached")
    mod_reserve = globals().get("reserve_call")
    mod_err = globals().get("CostCapReachedError")

    if mod_cap is not _UNSET:
        is_cap_fn = mod_cap
    if mod_reserve is not _UNSET:
        reserve_fn = mod_reserve
    if mod_err is not _UNSET:
        err_cls = mod_err

    # Both admission check and reservation function must be present and callable
    if callable(is_cap_fn) and callable(reserve_fn):
        return is_cap_fn, reserve_fn, err_cls
    return None
from app.limiter import get_room_rate_limiter
from app.llm.client import (
    AIDisabledError,
    ConsentMissingError,
    LLMClient,
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
    RoomRateLimitedError,
    call_llm_with_consent,
)
from app.llm.explainer import (
    build_explanation_prompt,
    enforce_sentence_limit,
    parse_crash_explanation_output,
)
from app.llm.prompts import build_crash_explanation_prompt
from app.rules.patterns import detect_slow_patterns
from app.section import extract_error_line_from_stderr, extract_relevant_section

logger = logging.getLogger(__name__)


@dataclass
class ChainRequest:
    """Request payload for the central method chain."""

    request_type: str = "complexity"  # "complexity", "crash-explanation", "explain"
    code: str = ""
    language: str = "python"
    room_id: Optional[str] = None
    request_id: Optional[str] = None
    consent: Optional[bool] = None
    ai_enabled: Optional[bool] = None
    rules_first: bool = True

    # Error and execution context
    error_output: str = ""
    error_signature: str = ""
    cache_context: Optional[dict[str, Any]] = None

    # Failure explanation fields (explain & crash-explanation)
    failure_kind: Optional[str] = None
    exit_code: Optional[int] = None
    timed_out: bool = False
    oom_killed: bool = False
    error_line: Optional[int] = None
    function_name: Optional[str] = None
    pre_sliced: bool = False
    sanitizer_output: Optional[str] = None
    sanitizer_summary: Optional[str] = None
    measurements: Optional[list[Any]] = None

    # Extensible callbacks & overrides
    prompt: Optional[str] = None
    prompt_builder: Optional[Callable[[], Union[Awaitable[str], str]]] = None
    rule_evaluator: Optional[Callable[[], Union[Awaitable[Any], Any]]] = None
    ai_fallback: Optional[Callable[[], Union[Awaitable[str], str]]] = None
    client: Optional[LLMClient] = None
    max_tokens: Optional[int] = None
    max_prompt_length: Optional[int] = None
    reraise_ai_errors: bool = False
    rule_findings: Any = None


@dataclass
class ChainResponse:
    """Standardized response from the central method chain."""

    stage: str  # "cache", "rules", "local_model", "paid_model", "fallback"
    status: str  # "ok", "skipped"
    answer: Optional[str] = None
    error_code: Optional[str] = None
    ai_step: dict[str, Any] = field(default_factory=dict)
    rule_findings: Any = None
    metadata: dict[str, Any] = field(default_factory=dict)


# ============================================================================
# Local Model Interface (US-77, FR40.3)
# ============================================================================

LocalModelCallable = Callable[[str, Optional[dict[str, Any]]], Union[Awaitable[Optional[str]], Optional[str]]]

_local_model_runner: Optional[LocalModelCallable] = None


def get_local_model_runner() -> Optional[LocalModelCallable]:
    """Retrieve the currently configured local model runner callable, if any."""
    return _local_model_runner


def set_local_model_runner(runner: Optional[LocalModelCallable]) -> None:
    """Register or clear the local model runner callback (US-77, FR40.3)."""
    global _local_model_runner
    _local_model_runner = runner


async def query_local_model(
    prompt: str,
    context: Optional[dict[str, Any]] = None,
) -> Optional[str]:
    """Clean one-function interface for an optional local model (US-77, FR40.3).

    A local model is completely optional. If no local model is configured,
    returns None immediately without raising an error, allowing the chain to
    proceed to the paid external model.

    This interface provides the integration point for future self-hosted or
    on-premise open-source models (e.g. Ollama, vLLM, llama.cpp, LocalAI)
    without imposing mandatory dependencies or installation burdens on users today.
    """
    runner = get_local_model_runner()
    if runner is not None:
        try:
            if inspect.iscoroutinefunction(runner):
                return await runner(prompt, context)
            res = runner(prompt, context)
            if inspect.isawaitable(res):
                return await res
            return res
        except Exception as exc:
            logger.warning(
                "Local model execution encountered an error, falling through to next stage: %s",
                type(exc).__name__,
            )
            return None

    # Optional environment-based local model dispatch
    if getattr(settings, "LOCAL_MODEL_ENABLED", False):
        url = getattr(settings, "LOCAL_MODEL_URL", None)
        if url:
            # Future local HTTP integration stub
            logger.info("Local model enabled at url=%s", url)
            return None

    return None


# ============================================================================
# Prompt Management & Token Budget (US-77, FR40.4)
# ============================================================================

def _build_default_prompt(request: ChainRequest) -> str:
    """Construct prompt for external AI based on request type."""
    if request.prompt:
        return request.prompt

    if request.request_type == "failing_input":
        # Preserve the requested task even when _enforce_prompt_size rebuilds
        # an oversized prompt with a smaller relevant-code section.
        relevant_code = (
            request.code if request.pre_sliced
            else extract_relevant_section(code=request.code, language=request.language)
        )
        return (
            "Return ONLY Python 3 code that prints a JSON array of at most 3 short "
            "stdin strings for edge cases of the following program. No prose, no "
            "markdown. Do not import network or filesystem modules.\n"
            f"Language: {request.language}\nRelevant code:\n{relevant_code}"
        )

    if request.request_type == "complexity":
        detected_patterns = (
            request.rule_findings
            if request.rule_findings is not None
            else detect_slow_patterns(request.code, language=request.language)
        )
        target_line = detected_patterns[0]["line"] if detected_patterns else None
        relevant_code = extract_relevant_section(
            code=request.code,
            language=request.language,
            error_line=target_line,
        )
        return (
            f"Suggest an optimization fix for {request.language} code:\n"
            f"```\n{relevant_code}\n```"
        )

    if request.request_type in ("crash-explanation", "crash_explanation"):
        summary = request.sanitizer_summary or request.error_signature or "Sanitizer crash detected."
        if request.pre_sliced and request.code:
            snippet_lines = request.code.splitlines()
            if len(snippet_lines) <= settings.SECTION_MAX_LINES:
                relevant_code = request.code
            else:
                relevant_code = "\n".join(snippet_lines[: settings.SECTION_MAX_LINES])
        else:
            snippet_lines = request.code.splitlines()
            if request.error_line is not None and request.error_line <= len(snippet_lines):
                relevant_code = extract_relevant_section(
                    code=request.code,
                    language=request.language,
                    function_name=request.function_name,
                    error_line=request.error_line,
                )
            else:
                if len(snippet_lines) > settings.SECTION_MAX_LINES:
                    relevant_code = extract_relevant_section(
                        code=request.code,
                        language=request.language,
                        function_name=request.function_name,
                        error_line=request.error_line,
                    )
                else:
                    relevant_code = request.code

        return build_crash_explanation_prompt(
            summary=summary,
            code=relevant_code,
        )

    if request.request_type in ("explain", "explanation"):
        if request.pre_sliced and request.code:
            code_lines = request.code.splitlines()
            if len(code_lines) <= settings.SECTION_MAX_LINES:
                relevant_code = request.code
            else:
                relevant_code = "\n".join(code_lines[: settings.SECTION_MAX_LINES])
        else:
            target_error_line = request.error_line
            stderr_text = request.error_output or request.sanitizer_output or ""
            if target_error_line is None and stderr_text:
                target_error_line = extract_error_line_from_stderr(
                    stderr_text,
                    language=request.language,
                )

            relevant_code = extract_relevant_section(
                code=request.code,
                language=request.language,
                function_name=request.function_name,
                error_line=target_error_line,
            )

        return build_explanation_prompt(
            language=request.language,
            failure_kind=request.failure_kind,
            exit_code=request.exit_code,
            stderr=request.error_output or request.sanitizer_output or "",
            timed_out=request.timed_out,
            oom_killed=request.oom_killed,
            code=relevant_code,
        )

    return f"Analyze {request.language} code:\n```{request.code}```"


def _enforce_prompt_size(
    prompt: str,
    request: ChainRequest,
    max_prompt_length: int,
) -> Optional[str]:
    """Ensure the final prompt never exceeds configured prompt limit (FR40.4, US-77, US-76).

    If oversized:
    - Attempts to shorten using US-76 extract_relevant_section with tighter limits.
    - If the prompt cannot be safely shortened to fit, returns None (skip paid step).
    """
    if len(prompt) <= max_prompt_length:
        return prompt

    logger.info(
        "Prompt exceeds configured limit (len=%d max=%d), attempting section shortening (FR40.4, US-77)",
        len(prompt),
        max_prompt_length,
    )

    if not request.code:
        # No source code to shorten; prompt text alone exceeds limit
        return None

    # Attempt progressive reduction of extracted code lines
    line_limits = [
        min(15, settings.SECTION_FALLBACK_LINES),
        min(8, settings.SECTION_FALLBACK_LINES // 2 or 5),
        3,
    ]

    for limit in line_limits:
        short_code = extract_relevant_section(
            code=request.code,
            language=request.language,
            function_name=request.function_name,
            error_line=request.error_line,
            max_lines=limit,
            window_lines=max(1, limit // 2),
            fallback_lines=limit,
        )
        # Rebuild prompt with shortened code
        rebuilt_req = ChainRequest(
            request_type=request.request_type,
            code=short_code,
            language=request.language,
            error_output=request.error_output,
            error_signature=request.error_signature,
            failure_kind=request.failure_kind,
            exit_code=request.exit_code,
            timed_out=request.timed_out,
            oom_killed=request.oom_killed,
            error_line=request.error_line,
            function_name=request.function_name,
            pre_sliced=True,
            sanitizer_output=request.sanitizer_output,
            sanitizer_summary=request.sanitizer_summary,
        )
        candidate_prompt = _build_default_prompt(rebuilt_req)
        if len(candidate_prompt) <= max_prompt_length:
            logger.info("Successfully shortened prompt to %d chars (FR40.4)", len(candidate_prompt))
            return candidate_prompt

    # Could not safely fit within configured limit
    logger.warning("Prompt cannot be safely shortened to fit within %d limit; skipping paid step", max_prompt_length)
    return None


# ============================================================================
# Central Method Chain Entry Point (US-77, FR40.3)
# ============================================================================

async def answer(request: ChainRequest) -> ChainResponse:
    """Central entry point evaluating AI/ML tasks using the cheapest method first (US-77, FR40.3).

    Strict Order:
    1. Cache: SHA-256 normalized code cache (US-22). Cache hit returns immediately.
    2. Deterministic rules & statistics: US-67 AST patterns / statistical estimation.
       Valid deterministic answer stops chain immediately.
    3. Local model: Optional local model interface (US-77). If answers, stops chain immediately.
       If unconfigured or returns None, skips to step 4 without error.
    4. Paid external model: Outbound paid LLM call with consent (US-17, US-21),
       Host AI switch (FR35), monthly cost cap check (US-24), room quota (US-22),
       and prompt / token budget limits (US-77, FR40.4).

    Safe Logging: Strictly logs metadata only; never code, prompt, or user text (NFR8.2).
    """
    req_type = request.request_type
    room_id = request.room_id
    req_id = request.request_id

    # Crash explanation never uses deterministic complexity rules (US-74)
    if req_type in ("crash-explanation", "crash_explanation"):
        request.rules_first = False

    # Compute cache key upfront (NFR5.2, Decision 11, US-22)
    cache_key = compute_cache_key(
        code=request.code,
        language=request.language,
        request_type=req_type,
        error_output=request.error_output or request.sanitizer_output or "",
        error_signature=request.error_signature,
        cache_context=request.cache_context,
    )

    # -------------------------------------------------------------------------
    # Rule Evaluation / Static Analysis (Always executed; $0, local AST)
    # -------------------------------------------------------------------------
    slow_patterns = []
    if request.code and request.language:
        try:
            slow_patterns = detect_slow_patterns(request.code, language=request.language)
        except Exception as exc:
            logger.warning("Rule pattern detection skipped due to parse error: %s", type(exc).__name__)
            slow_patterns = []

    request.rule_findings = slow_patterns

    # -------------------------------------------------------------------------
    # STAGE 1: Cache (US-22, FR40.3 - Cheapest: $0, 0 ms)
    # -------------------------------------------------------------------------
    if request.consent is True and request.ai_enabled is True:
        cache = get_analysis_cache()
        cached = cache.get(cache_key, room_id=room_id)
        if cached is not None:
            logger.info(
                "Chain resolved at Stage 1 [cache]: request_id=%s request_type=%s room_id=%s",
                req_id,
                req_type,
                room_id,
            )
            ans = cached.get("answer") or cached.get("suggested_fix") or cached.get("explanation")
            ai_step = {
                "status": "ok",
                "error_code": None,
                "suggested_fix": cached.get("suggested_fix") or ans,
                "explanation": cached.get("explanation") or ans,
                "verified": cached.get("verified", False),
                "verified_speedup_pct": cached.get("verified_speedup_pct"),
                "stage": "cache",
            }
            return ChainResponse(
                stage="cache",
                status="ok",
                answer=ans,
                error_code=None,
                ai_step=ai_step,
                rule_findings=cached.get("rule_findings") if cached.get("rule_findings") is not None else slow_patterns,
                metadata={"cache_key": cache_key},
            )

    # -------------------------------------------------------------------------
    # STAGE 2: Deterministic Rules & Statistics (US-67, FR40.3 - $0, local)
    # -------------------------------------------------------------------------
    # Check custom rule evaluator first if caller supplied one
    if request.rule_evaluator is not None:
        rule_eval_res = request.rule_evaluator()
        if inspect.isawaitable(rule_eval_res):
            rule_eval_res = await rule_eval_res
        if rule_eval_res is not None:
            record_rules_answer(room_id)
            logger.info(
                "Chain resolved at Stage 2 [rules evaluator]: request_id=%s request_type=%s room_id=%s",
                req_id,
                req_type,
                room_id,
            )
            ans = rule_eval_res if isinstance(rule_eval_res, str) else str(rule_eval_res)
            ai_step = {
                "status": "ok",
                "error_code": None,
                "suggested_fix": ans,
                "explanation": ans,
                "verified": False,
                "verified_speedup_pct": None,
                "stage": "rules",
            }
            cache.set(
                cache_key,
                {"answer": ans, "suggested_fix": ans, "stage": "rules"},
                room_id=room_id,
            )
            return ChainResponse(
                stage="rules",
                status="ok",
                answer=ans,
                error_code=None,
                ai_step=ai_step,
                rule_findings=rule_eval_res,
                metadata={"cache_key": cache_key},
            )

    # When rules_first is enabled and deterministic rules find an issue, answer and stop
    if request.rules_first and slow_patterns:
        record_rules_answer(room_id)
        logger.info(
            "Chain resolved at Stage 2 [rules pattern]: request_id=%s request_type=%s patterns_count=%d",
            req_id,
            req_type,
            len(slow_patterns),
        )
        err_code = None
        if request.consent is not True:
            err_code = "CONSENT_MISSING"
        elif request.ai_enabled is False:
            err_code = "AI_DISABLED"

        ai_step = {
            "status": "skipped",
            "error_code": err_code,
            "suggested_fix": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "rules",
        }
        return ChainResponse(
            stage="rules",
            status="skipped",
            answer=None,
            error_code=err_code,
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    # -------------------------------------------------------------------------
    # STAGE 3: Local Model (US-77, FR40.3 - Optional, $0 external API)
    # -------------------------------------------------------------------------
    max_prompt_len = request.max_prompt_length or getattr(settings, "AI_MAX_PROMPT_LENGTH", 4000)
    local_prompt: Optional[str] = None
    if request.prompt_builder:
        built = request.prompt_builder()
        local_prompt = await built if inspect.isawaitable(built) else built
    elif request.prompt:
        local_prompt = request.prompt
    else:
        local_prompt = _build_default_prompt(request)

    local_model_answer: Optional[str] = None
    if local_prompt:
        local_prompt_short = _enforce_prompt_size(local_prompt, request, max_prompt_len)
        if local_prompt_short:
            local_model_answer = await query_local_model(
                local_prompt_short,
                context={
                    "request_type": req_type,
                    "language": request.language,
                    "room_id": room_id,
                },
            )

    if local_model_answer is not None and local_model_answer.strip():
        record_local_model_answer(room_id)
        logger.info(
            "Chain resolved at Stage 3 [local_model]: request_id=%s request_type=%s room_id=%s",
            req_id,
            req_type,
            room_id,
        )
        if req_type in ("crash-explanation", "crash_explanation"):
            local_ans, local_fix = parse_crash_explanation_output(
                local_model_answer,
                request.error_line,
                request.error_signature,
            )
            local_expl = local_ans
        else:
            local_ans = local_model_answer
            local_expl = local_model_answer
            local_fix = local_model_answer

        ai_step = {
            "status": "ok",
            "error_code": None,
            "suggested_fix": local_fix,
            "explanation": local_expl,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "local_model",
        }
        cache.set(
            cache_key,
            {
                "answer": local_ans,
                "suggested_fix": local_fix,
                "explanation": local_expl,
                "stage": "local_model",
            },
            room_id=room_id,
        )
        return ChainResponse(
            stage="local_model",
            status="ok",
            answer=local_model_answer,
            error_code=None,
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    # -------------------------------------------------------------------------
    # STAGE 4: Paid External Model (US-17, US-21, US-22, US-24, US-76, US-77)
    # -------------------------------------------------------------------------
    # Cheaper methods could not answer. Now evaluate paid model gates:

    # 1. Consent Gate (FR12.2, FR12.4, US-17, US-21)
    if request.consent is not True:
        record_fallback(room_id)
        logger.info(
            "Chain blocked by consent gate: request_id=%s room_id=%s (FR12.2, US-21)",
            req_id,
            room_id,
        )
        ai_step = {
            "status": "skipped",
            "error_code": "CONSENT_MISSING",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="CONSENT_MISSING",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    # 2. Host AI Switch Gate (FR35)
    if request.ai_enabled is not True:
        record_fallback(room_id)
        logger.info(
            "Chain blocked by Host AI switch: request_id=%s room_id=%s (FR35)",
            req_id,
            room_id,
        )
        ai_step = {
            "status": "skipped",
            "error_code": "AI_DISABLED",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="AI_DISABLED",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    # 3. Monthly Cost-Cap Admission Check (US-24, NFR7.2, US-77)
    cost_cap = get_cost_cap_provider()
    if cost_cap is None:
        # Cost-cap protection is unavailable (US-24 unmerged): fail closed to protect budget
        record_fallback(room_id)
        logger.warning(
            "Chain Stage 4 blocked [cost-cap protection unavailable]: request_id=%s room_id=%s (US-24 unmerged)",
            req_id,
            room_id,
        )
        ai_step = {
            "status": "skipped",
            "error_code": "COST_CAP_REACHED",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="COST_CAP_REACHED",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key, "reason": "cost_cap_unavailable"},
        )

    cap_check_fn, reserve_fn, cap_err_cls = cost_cap

    try:
        is_capped = cap_check_fn()
    except Exception as exc:
        record_fallback(room_id)
        logger.warning(
            "Chain Stage 4 blocked [cost-cap check failed with error]: request_id=%s error=%s",
            req_id,
            exc,
        )
        ai_step = {
            "status": "skipped",
            "error_code": "COST_CAP_REACHED",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="COST_CAP_REACHED",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key, "reason": "cost_cap_check_failed"},
        )

    if is_capped:
        record_fallback(room_id)
        logger.info(
            "Chain Stage 4 blocked [cost cap reached]: request_id=%s room_id=%s",
            req_id,
            room_id,
        )
        ai_step = {
            "status": "skipped",
            "error_code": "COST_CAP_REACHED",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="COST_CAP_REACHED",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    # 4. Room Hourly Quota Gate (NFR5.3, US-22)
    if room_id and not get_room_rate_limiter().check_and_reserve(room_id):
        record_fallback(room_id)
        logger.info(
            "Chain Stage 4 blocked [room rate limited]: request_id=%s room_id=%s",
            req_id,
            room_id,
        )
        ai_step = {
            "status": "skipped",
            "error_code": "ROOM_RATE_LIMITED",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="ROOM_RATE_LIMITED",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    # 5. Prompt Size Enforcement (US-77, FR40.4)
    raw_prompt = local_prompt or _build_default_prompt(request)
    final_prompt = _enforce_prompt_size(raw_prompt, request, max_prompt_len)
    if final_prompt is None:
        # Prompt exceeds limit and cannot be safely shortened; skip paid step
        record_fallback(room_id)
        logger.warning(
            "Chain Stage 4 skipped [prompt too large]: request_id=%s max=%d",
            req_id,
            max_prompt_len,
        )
        ai_step = {
            "status": "skipped",
            "error_code": "PROVIDER_ERROR",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="PROVIDER_ERROR",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key, "reason": "prompt_too_large"},
        )

    # 6. Execute Paid Call with Output Token Budget (US-77, FR40.4)
    max_output_tokens = request.max_tokens or getattr(
        settings,
        "LLM_MAX_OUTPUT_TOKENS",
        getattr(settings, "AI_MAX_TOKENS_PER_REQUEST", 256),
    )

    try:
        # Reservation lifecycle and token accounting are owned by the US-24 LLM client
        if request.ai_fallback is not None:
            paid_output = (
                await request.ai_fallback()
                if callable(request.ai_fallback)
                else await request.ai_fallback
            )
        else:
            paid_output = await call_llm_with_consent(
                prompt=final_prompt,
                consent=request.consent,
                ai_enabled=request.ai_enabled,
                client=request.client,
                room_id=room_id,
                max_tokens=max_output_tokens,
            )

        if req_type in ("crash-explanation", "crash_explanation"):
            paid_answer, suggested_fix = parse_crash_explanation_output(
                paid_output,
                request.error_line,
                request.error_signature,
            )
            explanation = paid_answer
        elif req_type in ("explain", "explanation"):
            paid_answer = enforce_sentence_limit(paid_output, max_sentences=3)
            explanation = paid_answer
            suggested_fix = paid_answer
        else:
            paid_answer = paid_output
            explanation = paid_answer
            suggested_fix = paid_answer

        logger.info(
            "Chain resolved at Stage 4 [paid_model]: request_id=%s request_type=%s room_id=%s",
            req_id,
            req_type,
            room_id,
        )

        # Store result in cache
        cache.set(
            cache_key,
            {
                "answer": paid_answer,
                "suggested_fix": suggested_fix,
                "explanation": explanation,
                "stage": "paid_model",
            },
            room_id=room_id,
        )

        ai_step = {
            "status": "ok",
            "error_code": None,
            "suggested_fix": suggested_fix,
            "explanation": explanation,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "paid_model",
        }
        return ChainResponse(
            stage="paid_model",
            status="ok",
            answer=paid_answer,
            error_code=None,
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    except ConsentMissingError:
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "CONSENT_MISSING",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="CONSENT_MISSING",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    except AIDisabledError:
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "AI_DISABLED",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="AI_DISABLED",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    except RoomRateLimitedError:
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "ROOM_RATE_LIMITED",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="ROOM_RATE_LIMITED",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    except LLMTimeoutError:
        if request.reraise_ai_errors:
            raise
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "PROVIDER_TIMEOUT",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="PROVIDER_TIMEOUT",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    except LLMRateLimitError:
        if request.reraise_ai_errors:
            raise
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "RATE_LIMITED_PROVIDER",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="RATE_LIMITED_PROVIDER",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    except LLMProviderError:
        if request.reraise_ai_errors:
            raise
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "PROVIDER_ERROR",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="PROVIDER_ERROR",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )

    except Exception as exc:
        if (cap_err_cls is not None and isinstance(exc, cap_err_cls)) or exc.__class__.__name__ == "CostCapReachedError":
            record_fallback(room_id)
            logger.info("Paid call blocked by cost cap reservation (US-24, US-77)")
            ai_step = {
                "status": "skipped",
                "error_code": "COST_CAP_REACHED",
                "suggested_fix": None,
                "explanation": None,
                "verified": False,
                "verified_speedup_pct": None,
                "stage": "fallback",
            }
            return ChainResponse(
                stage="fallback",
                status="skipped",
                answer=None,
                error_code="COST_CAP_REACHED",
                ai_step=ai_step,
                rule_findings=slow_patterns,
                metadata={"cache_key": cache_key},
            )
        if request.reraise_ai_errors:
            raise
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "PROVIDER_ERROR",
            "suggested_fix": None,
            "explanation": None,
            "verified": False,
            "verified_speedup_pct": None,
            "stage": "fallback",
        }
        return ChainResponse(
            stage="fallback",
            status="skipped",
            answer=None,
            error_code="PROVIDER_ERROR",
            ai_step=ai_step,
            rule_findings=slow_patterns,
            metadata={"cache_key": cache_key},
        )
