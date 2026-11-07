"""Rule-based code pattern analysis (US-67, FR36.4, FR12.3).

Provides static AST and regex rule analysis for slow code patterns before any AI call.
AI-free analysis runs unconditionally, ensuring that declining consent never prevents
rule-based analysis results from returning (FR12.3).
"""

import ast
import re
from typing import Any, Optional

from app.llm.client import (
    ConsentMissingError,
    LLMClient,
    call_llm_with_consent,
)


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
                                    "message": "Nested loop detected: potential quadratic time complexity O(n^2).",
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
                                    "message": "Repeated accumulation in loop: consider using list append and join.",
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
                                            "message": "Linear search in loop: consider using a hash set for O(1) lookup.",
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
                        "message": "Nested loop detected: potential quadratic time complexity O(n^2).",
                    }
                )
            if re.search(r"\+=\s*[\"']", line) or re.search(r"\+=\s*\w+", line):
                patterns.append(
                    {
                        "pattern": "repeated_string_concat",
                        "line": idx,
                        "message": "Repeated accumulation in loop: consider using buffer or join.",
                    }
                )

    return patterns


async def run_rule_based_analysis_with_ai_fallback(
    code: str,
    language: str,
    consent: Optional[bool] = None,
    client: Optional[LLMClient] = None,
    ai_fallback: Optional[Any] = None,
) -> dict[str, Any]:
    """Execute rule-based pattern checks first, followed by consent-gated AI assistance (US-67, FR12.3).

    Core Invariant (FR12.3):
    Declining consent does NOT affect the AI-free parts.
    When consent is missing or False:
    - Rule-based analysis returns its results normally.
    - AI step is marked skipped with error_code CONSENT_MISSING.
    - Outbound LLM client is called 0 times.
    """
    # 1. AI-free rule-based analysis (always executes first)
    slow_patterns = detect_slow_patterns(code, language=language)

    # 2. AI step (consent-gated via central wrapper)
    ai_step: dict[str, Any]
    try:
        if consent is not True:
            # Enforce gate: no LLM call can proceed without explicit consent
            raise ConsentMissingError(
                message="User consent is required before code or execution context can be sent to external AI.",
                status_code=403,
                code="CONSENT_MISSING",
            )

        if ai_fallback is not None:
            ai_suggestion = await ai_fallback() if callable(ai_fallback) else await ai_fallback
        else:
            prompt = f"Suggest a 1-sentence fix for code with patterns: {slow_patterns}"
            ai_suggestion = await call_llm_with_consent(
                prompt=prompt,
                consent=consent,
                client=client,
            )

        ai_step = {
            "status": "ok",
            "suggested_fix": ai_suggestion,
            "error_code": None,
        }
    except ConsentMissingError:
        # FR12.3: Declining consent does NOT fail the rule-based analysis; findings are preserved
        ai_step = {
            "status": "skipped",
            "suggested_fix": None,
            "error_code": "CONSENT_MISSING",
        }

    return {
        "task": "pattern_analysis",
        "slow_patterns": slow_patterns,
        "ai_step": ai_step,
    }
