"""Rule-based code pattern analysis (US-67, FR36.4, FR12.3, NFR5.2, NFR5.3, US-22, T-18 v2).

Provides static AST and regex rule analysis for slow code patterns before any AI call.
Enforces the mandatory AI control sequence (US-22):
1. User Consent (US-17)
2. Host AI Switch (FR35)
3. Room Hourly Rate Limit (NFR5.3)
4. Normalized SHA-256 Code Cache (NFR5.2, Decision 11) - cache hits avoid outbound calls
5. Outbound AI call + caching result
"""

import ast
import re
from typing import Any, Optional

from app.cache import compute_cache_key, get_analysis_cache
from app.counters import record_fallback
from app.limiter import get_room_rate_limiter
from app.llm import (
    AIDisabledError,
    ConsentMissingError,
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
    RoomRateLimitedError,
    call_llm_with_consent,
)
from app.llm.client import LLMClient

# Regex definitions for C-family languages (C++, C, Java, JavaScript)
_C_FAMILY_LOOP_HEADER_RE = re.compile(
    r"\b(for\s*\(|while\s*\(|do\s*\{|for\s+await\s*\(|for\s*\([^;]*\s+of\s+|for\s*\([^;]*\s+in\s+)",
    re.IGNORECASE,
)
_C_FAMILY_DO_WHILE_END_RE = re.compile(r"\}\s*while\s*\(.*;", re.IGNORECASE)
_C_FAMILY_STRING_CONCAT_RE = re.compile(
    r"(?:"
    r"\b(?:str|s|res|result|buf|buffer|output|text|line|msg|message|out|acc)\s*\+=\s*"
    r"|\+=\s*(?:\"|\x27|\w+\.(?:toString|to_string)|std::to_string|String\.valueOf)"
    r"|\b([a-zA-Z_]\w*)\s*=\s*\1\s*\+"
    r"|\.concat\s*\("
    r"|\bstrn?cat\s*\("
    r")"
)
_C_FAMILY_LINEAR_SEARCH_RE = re.compile(
    r"(\b(?:std::)?find\s*\(|\.indexOf\s*\(|\.includes\s*\(|\.contains\s*\()",
    re.IGNORECASE,
)


def _detect_c_family_slow_patterns(code: str, language: str = "cpp") -> list[dict[str, Any]]:
    """Detect slow patterns in C, C++, Java, and JavaScript using conservative lexical scanning (US-67)."""
    patterns: list[dict[str, Any]] = []

    # Strip block comments while preserving original line numbers
    stripped = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), code, flags=re.DOTALL)
    raw_lines = stripped.splitlines()

    active_loops: list[dict[str, int]] = []  # Stack of {"start_line": int, "brace_depth_at_start": int}
    brace_depth = 0

    for idx, raw_line in enumerate(raw_lines, start=1):
        # Strip line comments
        line = re.sub(r"//.*$", "", raw_line)
        # Strip string and character literals so braces inside quotes do not affect depth
        clean_line = re.sub(r"\"(?:\\.|[^\"\\])*\"|\x27(?:\\.|[^\x27\\])*\x27", "\"\"", line)

        opens = clean_line.count("{")
        closes = clean_line.count("}")

        # Pop any loops whose block closed on this line before a new loop starts
        if closes > 0:
            brace_depth -= closes
            while active_loops and brace_depth <= active_loops[-1]["brace_depth_at_start"]:
                active_loops.pop()

        is_do_while_close = bool(_C_FAMILY_DO_WHILE_END_RE.search(clean_line))
        has_loop = bool(_C_FAMILY_LOOP_HEADER_RE.search(clean_line)) and not is_do_while_close

        if has_loop:
            if active_loops:
                patterns.append(
                    {
                        "pattern": "nested_loops",
                        "line": idx,
                        "message": f"Nested loop detected at line {idx}: potential quadratic time complexity O(n^2).",
                    }
                )
            active_loops.append({"start_line": idx, "brace_depth_at_start": brace_depth})
        elif active_loops:
            # Inside loop body: check for string concatenation
            if _C_FAMILY_STRING_CONCAT_RE.search(clean_line):
                patterns.append(
                    {
                        "pattern": "repeated_string_concat",
                        "line": idx,
                        "message": f"Repeated accumulation in loop at line {idx}: consider using buffer or join.",
                    }
                )
            # Inside loop body: check for linear search
            if _C_FAMILY_LINEAR_SEARCH_RE.search(clean_line):
                patterns.append(
                    {
                        "pattern": "linear_search_in_loop",
                        "line": idx,
                        "message": f"Linear search in loop at line {idx}: consider using a hash set for O(1) lookup.",
                    }
                )

        if opens > 0:
            brace_depth += opens

    return patterns


def detect_slow_patterns(code: str, language: str = "python") -> list[dict[str, Any]]:
    """Detect common performance bottlenecks using deterministic AST and regex rules (US-67).

    Rules:
    1. Nested loops: loop within a loop (potential O(n^2) or worse).
    2. Repeated string concatenation in a loop: += or accumulation in loop body.
    3. Linear search in loop: membership testing (`in` / `not in` / search) in loop body.

    Covers Python via AST, and C++, C, Java, and JavaScript via conservative lexical scanning.
    """
    lang = language.lower()

    if lang in ("python", "py"):
        try:
            patterns: list[dict[str, Any]] = []
            tree = ast.parse(code)
            for node in ast.walk(tree):
                # Rule 1: Nested loops
                if isinstance(node, (ast.For, ast.While)):
                    for child in ast.walk(node):
                        if child is not node and isinstance(child, (ast.For, ast.While)):
                            line_no = getattr(child, "lineno", getattr(node, "lineno", 1))
                            patterns.append(
                                {
                                    "pattern": "nested_loops",
                                    "line": line_no,
                                    "message": f"Nested loop detected at line {line_no}: potential quadratic time complexity O(n^2).",
                                }
                            )
                            break

                # Rule 2: Repeated string concatenation in loops
                if isinstance(node, (ast.For, ast.While)):
                    for child in ast.walk(node):
                        is_concat = False
                        if isinstance(child, ast.AugAssign) and isinstance(child.op, ast.Add):
                            is_concat = True
                        elif isinstance(child, ast.Assign) and isinstance(child.value, ast.BinOp):
                            if isinstance(child.value.op, ast.Add):
                                is_concat = True

                        if is_concat:
                            line_no = getattr(child, "lineno", node.lineno)
                            patterns.append(
                                {
                                    "pattern": "repeated_string_concat",
                                    "line": line_no,
                                    "message": f"Repeated accumulation in loop at line {line_no}: consider using list append and join.",
                                }
                            )
                            break

                # Rule 3: Linear search in loop
                if isinstance(node, (ast.For, ast.While)):
                    for child in ast.walk(node):
                        if isinstance(child, ast.Compare):
                            for op in child.ops:
                                if isinstance(op, (ast.In, ast.NotIn)):
                                    line_no = getattr(child, "lineno", getattr(node, "lineno", 1))
                                    patterns.append(
                                        {
                                            "pattern": "linear_search_in_loop",
                                            "line": line_no,
                                            "message": f"Linear search in loop at line {line_no}: consider using a hash set for O(1) lookup.",
                                        }
                                    )
                                    break

            return patterns
        except SyntaxError:
            # Fall back to C-family line scanner if code contains syntax errors
            return _detect_c_family_slow_patterns(code, language=language)

    # For C++, C, Java, and JavaScript (DR1 languages)
    return _detect_c_family_slow_patterns(code, language=language)


async def run_rule_based_analysis_with_ai_fallback(
    code: str,
    language: str,
    consent: Optional[bool] = None,
    ai_enabled: Optional[bool] = None,
    client: Optional[LLMClient] = None,
    ai_fallback: Optional[Any] = None,
    reraise_ai_errors: bool = False,
    room_id: Optional[str] = None,
    request_type: str = "complexity",
    error_output: str = "",
    cache_context: Optional[dict[str, Any]] = None,
    rules_first: bool = False,
) -> dict[str, Any]:
    """Execute rule-based pattern checks first, followed by controlled AI assistance (US-22, US-67, FR12.3, FR35, T-18 v2).

    When rules_first is True (US-67, FR36.4):
    1. If any slow pattern rule fires, report its findings and stop (0 outbound AI calls).
    2. If none fires, proceed to the AI step subject to consent (FR12) and the AI switch (FR35).

    Mandatory Order of Checks (US-22):
    1. Consent (US-17, FR12.2) -> CONSENT_MISSING (skipped AI step, 0 calls)
    2. Host AI Switch (FR35) -> AI_DISABLED (skipped AI step, 0 calls)
    3. Code Cache (NFR5.2, Decision 11) -> Cache Hit returns cached suggestion (does NOT count as call)
    4. Room Hourly Rate Limit (NFR5.3) -> ROOM_RATE_LIMITED (skipped AI step, 0 calls)
    5. Outbound AI Call: records call on rate limiter, calls LLM, stores result in cache.
    """
    # 1. AI-free rule-based analysis (always executes first unconditionally)
    slow_patterns = detect_slow_patterns(code, language=language)

    # 2. US-67: If rules_first is active and a rule finds a likely issue, report it and stop (0 LLM calls)
    if rules_first and slow_patterns:
        return {
            "task": "complexity",
            "slow_patterns": slow_patterns,
            "ai_step": {
                "status": "skipped",
                "error_code": None,
                "suggested_fix": None,
                "verified": False,
                "verified_speedup_pct": None,
            },
        }

    # 3. Check 1: User Consent (FR12.2)
    if consent is not True:
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "CONSENT_MISSING",
            "suggested_fix": None,
            "verified": False,
            "verified_speedup_pct": None,
        }
    # 4. Check 2: Host AI Switch (FR35)
    elif ai_enabled is not True:
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "AI_DISABLED",
            "suggested_fix": None,
            "verified": False,
            "verified_speedup_pct": None,
        }
    else:
        # 5. Check 3: Normalized Code Cache (NFR5.2, FR40.3, Decision 11)
        # Cache hits answer immediately with $0 cost and do not count toward rate limits
        cache_key = compute_cache_key(
            code=code,
            language=language,
            request_type=request_type,
            error_output=error_output,
            cache_context=cache_context,
        )
        cached_result = get_analysis_cache().get(cache_key, room_id=room_id)

        if cached_result is not None:
            # Cache hit: does NOT count towards rate limit calls (US-22)
            ai_step = {
                "status": "ok",
                "error_code": None,
                "suggested_fix": cached_result.get("suggested_fix"),
                "verified": False,
                "verified_speedup_pct": None,
            }
        # 6. Check 4: Atomic Room Hourly Rate Limit Check-and-Reserve on outbound calls (NFR5.3, US-22)
        elif room_id and not get_room_rate_limiter().check_and_reserve(room_id):
            record_fallback(room_id)
            ai_step = {
                "status": "skipped",
                "error_code": "ROOM_RATE_LIMITED",
                "suggested_fix": None,
                "verified": False,
                "verified_speedup_pct": None,
            }
        else:
            # 7. Check 5: Cache Miss within Quota -> Call slot atomically reserved, execute Outbound AI Call
            try:
                if ai_fallback is not None:
                    ai_suggestion = await ai_fallback() if callable(ai_fallback) else await ai_fallback
                else:
                    prompt = f"Suggest a 1-sentence fix for code with patterns: {slow_patterns}"
                    ai_suggestion = await call_llm_with_consent(
                        prompt=prompt,
                        consent=consent,
                        ai_enabled=ai_enabled,
                        client=client,
                        room_id=room_id,
                    )

                # Store in cache
                get_analysis_cache().set(
                    cache_key,
                    {"suggested_fix": ai_suggestion},
                    room_id=room_id,
                )

                ai_step = {
                    "status": "ok",
                    "error_code": None,
                    "suggested_fix": ai_suggestion,
                    "verified": False,
                    "verified_speedup_pct": None,
                }
            except RoomRateLimitedError:
                record_fallback(room_id)
                ai_step = {
                    "status": "skipped",
                    "error_code": "ROOM_RATE_LIMITED",
                    "suggested_fix": None,
                    "verified": False,
                    "verified_speedup_pct": None,
                }
            except ConsentMissingError:
                record_fallback(room_id)
                ai_step = {
                    "status": "skipped",
                    "error_code": "CONSENT_MISSING",
                    "suggested_fix": None,
                    "verified": False,
                    "verified_speedup_pct": None,
                }
            except AIDisabledError:
                record_fallback(room_id)
                ai_step = {
                    "status": "skipped",
                    "error_code": "AI_DISABLED",
                    "suggested_fix": None,
                    "verified": False,
                    "verified_speedup_pct": None,
                }
            except LLMTimeoutError:
                if reraise_ai_errors:
                    raise
                record_fallback(room_id)
                ai_step = {
                    "status": "skipped",
                    "error_code": "PROVIDER_TIMEOUT",
                    "suggested_fix": None,
                    "verified": False,
                    "verified_speedup_pct": None,
                }
            except LLMRateLimitError:
                if reraise_ai_errors:
                    raise
                record_fallback(room_id)
                ai_step = {
                    "status": "skipped",
                    "error_code": "RATE_LIMITED_PROVIDER",
                    "suggested_fix": None,
                    "verified": False,
                    "verified_speedup_pct": None,
                }
            except LLMProviderError:
                if reraise_ai_errors:
                    raise
                record_fallback(room_id)
                ai_step = {
                    "status": "skipped",
                    "error_code": "PROVIDER_ERROR",
                    "suggested_fix": None,
                    "verified": False,
                    "verified_speedup_pct": None,
                }
            except Exception:
                if reraise_ai_errors:
                    raise
                record_fallback(room_id)
                ai_step = {
                    "status": "skipped",
                    "error_code": "PROVIDER_ERROR",
                    "suggested_fix": None,
                    "verified": False,
                    "verified_speedup_pct": None,
                }

    return {
        "task": "complexity",
        "slow_patterns": slow_patterns,
        "ai_step": ai_step,
    }


async def run_rules_before_ai_analysis(
    code: str,
    language: str,
    consent: Optional[bool] = None,
    ai_enabled: Optional[bool] = None,
    client: Optional[LLMClient] = None,
    ai_fallback: Optional[Any] = None,
    reraise_ai_errors: bool = False,
    room_id: Optional[str] = None,
    request_type: str = "complexity",
    error_output: str = "",
    cache_context: Optional[dict[str, Any]] = None,
) -> dict[str, Any]:
    """Execute rule-based pattern checks first; if any rule fires, return its finding and stop (US-67, FR36.4).

    An AI call is made only when the rules find no likely issue, and only if consent is given and AI is on.
    """
    return await run_rule_based_analysis_with_ai_fallback(
        code=code,
        language=language,
        consent=consent,
        ai_enabled=ai_enabled,
        client=client,
        ai_fallback=ai_fallback,
        reraise_ai_errors=reraise_ai_errors,
        room_id=room_id,
        request_type=request_type,
        error_output=error_output,
        cache_context=cache_context,
        rules_first=True,
    )
