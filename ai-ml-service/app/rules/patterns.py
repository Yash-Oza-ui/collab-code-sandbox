"""Rule-based code pattern analysis (US-67, FR36.4, FR12.3, T-18 v2).

Provides static AST and regex rule analysis for slow code patterns before any AI call.
AI-free analysis runs unconditionally, ensuring that declining consent never prevents
rule-based analysis results from returning (FR12.3).
"""

import ast
import re
from typing import Any, Optional

from app.llm import (
    AIDisabledError,
    ConsentMissingError,
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
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
) -> dict[str, Any]:
    """Execute rule-based pattern checks first, followed by consent-gated AI assistance (US-67, FR12.3, FR35, T-18 v2).

    Core Invariant (FR12.3, FR35):
    Declining consent or disabling AI does NOT affect the AI-free parts.
    External AI calls require BOTH consent == True AND ai_enabled == True.
    When consent is missing or False:
    - Rule-based analysis returns its results normally.
    - AI step is marked skipped with error_code CONSENT_MISSING.
    - Outbound LLM client is called 0 times.
    When ai_enabled is missing or False:
    - Rule-based analysis returns its results normally.
    - AI step is marked skipped with error_code AI_DISABLED.
    - Outbound LLM client is called 0 times.
    """
    # 1. AI-free rule-based analysis (always executes first)
    slow_patterns = detect_slow_patterns(code, language=language)

    # 2. Central AI permission gate (FR12.2, FR35, T-18 v2 §19)
    # External AI calls require BOTH consent == True AND ai_enabled == True
    if consent is not True:
        record_fallback(room_id)
        ai_step = {
            "status": "skipped",
            "error_code": "CONSENT_MISSING",
            "suggested_fix": None,
            "verified": False,
            "verified_speedup_pct": None,
        }
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
        # Both consent and ai_enabled are True: invoke AI fallback
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

            ai_step = {
                "status": "ok",
                "error_code": None,
                "suggested_fix": ai_suggestion,
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
