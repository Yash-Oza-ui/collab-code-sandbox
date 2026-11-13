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
from app.counters import record_fallback
from app.llm.client import LLMClient


def detect_slow_patterns(code: str, language: str = "python") -> list[dict[str, Any]]:
    """Detect common performance bottlenecks using deterministic AST and regex rules (US-67).

    Rules:
    1. Nested loops: loop within a loop (potential O(n^2) or worse).
    2. Repeated string concatenation in a loop: += on string in loop body.
    3. Linear search in loop: membership testing (`in` / `not in`) in loop body.
    """
    patterns: list[dict[str, Any]] = []

    if language.lower() == "python":
        try:
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
                        if isinstance(child, ast.AugAssign) and isinstance(child.op, ast.Add):
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
        except SyntaxError:
            # Fall back to regex if code has syntax errors
            pass

    # Regex fallbacks for non-Python or unparseable code
    if not patterns:
        lines = code.splitlines()
        for idx, line in enumerate(lines, start=1):
            if re.search(r"for\s*\(.*\)\s*\{?\s*for\s*\(", line) or re.search(r"for\s+.*:\s*for\s+.*:", line):
                patterns.append(
                    {
                        "pattern": "nested_loops",
                        "line": idx,
                        "message": f"Nested loop detected at line {idx}: potential quadratic time complexity O(n^2).",
                    }
                )
            if re.search(r"\+=\s*[\"']", line) or re.search(r"\+=\s*\w+", line):
                patterns.append(
                    {
                        "pattern": "repeated_string_concat",
                        "line": idx,
                        "message": f"Repeated accumulation in loop at line {idx}: consider using buffer or join.",
                    }
                )

    return patterns


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
) -> dict[str, Any]:
    """Execute rule-based pattern checks first, followed by controlled AI assistance (US-22, US-67, FR12.3, FR35, T-18 v2).

    Mandatory Order of Checks (US-22):
    1. Consent (US-17, FR12.2) -> CONSENT_MISSING (skipped AI step, 0 calls)
    2. Host AI Switch (FR35) -> AI_DISABLED (skipped AI step, 0 calls)
    3. Code Cache (NFR5.2, Decision 11) -> Cache Hit returns cached suggestion (does NOT count as call)
    4. Room Hourly Rate Limit (NFR5.3) -> ROOM_RATE_LIMITED (skipped AI step, 0 calls)
    5. Outbound AI Call: records call on rate limiter, calls LLM, stores result in cache.
    """
    # 1. AI-free rule-based analysis (always executes first)
    slow_patterns = detect_slow_patterns(code, language=language)

    # 2. Check 1: User Consent (FR12.2)
    if consent is not True:
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "CONSENT_MISSING",
            "suggested_fix": None,
            "verified": False,
            "verified_speedup_pct": None,
        }
    # 3. Check 2: Host AI Switch (FR35)
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
        # 4. Check 3: Normalized Code Cache (NFR5.2, FR40.3, Decision 11)
        # Cache hits answer immediately with $0 cost and do not count toward rate limits
        cache_key = compute_cache_key(
            code=code,
            language=language,
            request_type=request_type,
            error_output=error_output,
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
        # 5. Check 4: Atomic Room Hourly Rate Limit Check-and-Reserve on outbound calls (NFR5.3, US-22)
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
            # 6. Check 5: Cache Miss within Quota -> Call slot atomically reserved, execute Outbound AI Call
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
