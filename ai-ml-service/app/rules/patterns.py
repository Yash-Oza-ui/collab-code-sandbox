"""Rule-based code pattern analysis (US-21, US-67, FR36.4, FR12.3, FR12.4, NFR5.2, NFR5.3, US-22, T-18 v2).

Provides static AST and regex rule analysis for slow code patterns before any AI call.
Enforces the mandatory AI control sequence (US-21, US-22):
1. User Consent & Withdrawal (US-17, US-21)
2. Host AI Switch (FR35)
3. Room Hourly Rate Limit (NFR5.3)
4. Normalized SHA-256 Code Cache (NFR5.2, Decision 11) - cache hits avoid outbound calls
5. Outbound AI call + caching result
"""

import ast
import re
from collections import deque
from typing import Any, Iterator, Optional

from app.cache import compute_cache_key, get_analysis_cache
from app.counters import record_fallback
from app.limiter import get_room_rate_limiter
from app.llm import (
    AIDisabledError,
    ConsentMissingError,
    CostCapReachedError,
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
    RoomRateLimitedError,
    call_llm_with_consent,
)
from app.llm.client import LLMClient

# Regex definitions for C-family languages (C++, C, Java, JavaScript)
_C_STRINGS_AND_COMMENTS_RE = re.compile(
    r"(?P<raw_str>R\"([a-zA-Z0-9_]{0,16})\(.*?\)\2\")|"
    r"(?P<d_str>\"(?:\\.|[^\"\\])*\")|"
    r"(?P<s_str>\x27(?:\\.|[^\x27\\])*\x27)|"
    r"(?P<t_str>`(?:\\.|[^`\\])*`)|"
    r"(?P<block_cmt>/\*.*?\*/)|"
    r"(?P<line_cmt>//[^\r\n]*)",
    flags=re.DOTALL,
)

_C_FAMILY_TOKEN_RE = re.compile(
    r"(?P<LOOP>\b(?:for\s+await|for|while|do)\b)|"
    r"(?P<LPAREN>\()|"
    r"(?P<RPAREN>\))|"
    r"(?P<LBRACE>\{)|"
    r"(?P<RBRACE>\})|"
    r"(?P<SEMI>;)|"
    r"(?P<NEWLINE>\n)|"
    r"(?P<OTHER>[^\s\(\)\{\};]+)"
)

_C_FAMILY_STRING_CONCAT_RE = re.compile(
    r"(?:"
    r"\b(?:str|string|s|res|result|buf|buffer|output|text|line|msg|message)\s*\+=\s*"
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


def _strip_c_family_comments_preserving_strings(code: str) -> str:
    """Strip comments in C-family code in a string-aware manner, preserving line numbering."""
    def replacer(match: re.Match) -> str:
        if match.group("block_cmt"):
            return "\n" * match.group(0).count("\n")
        elif match.group("line_cmt"):
            return ""
        # String/char literal: replace content with empty quotes while preserving newlines
        raw = match.group(0)
        newlines = raw.count("\n")
        return ("\n" * newlines) + "\"\""

    return _C_STRINGS_AND_COMMENTS_RE.sub(replacer, code)


def _detect_c_family_slow_patterns(code: str, language: str = "cpp") -> list[dict[str, Any]]:
    """Detect slow patterns in C, C++, Java, and JavaScript using string-aware lexical scanning (US-67)."""
    clean_code = _strip_c_family_comments_preserving_strings(code)
    clean_lines = clean_code.splitlines()
    patterns: list[dict[str, Any]] = []

    # Stack of active loops:
    # {"start_line": int, "is_do": bool, "is_braced": bool, "brace_depth": int, "state": str, "paren_depth": int}
    active_loops: list[dict[str, Any]] = []
    brace_depth = 0
    current_line = 1
    pos = 0
    loop_body_lines: set[int] = set()

    for m in _C_FAMILY_TOKEN_RE.finditer(clean_code):
        tok_type = m.lastgroup
        tok_val = m.group(0)

        # Track line numbering
        tok_start = m.start()
        current_line += clean_code[pos:tok_start].count("\n")
        pos = tok_start

        if tok_type == "NEWLINE":
            current_line += 1
            pos = m.end()
            continue

        pos = m.end()

        # If any active loop is executing its body, record this line as part of a loop body
        if any(loop["state"] == "BODY" for loop in active_loops):
            loop_body_lines.add(current_line)

        if tok_type == "LOOP":
            loop_word = tok_val.strip()
            if loop_word == "do":
                if active_loops:
                    patterns.append(
                        {
                            "pattern": "nested_loops",
                            "line": current_line,
                            "message": f"Nested loop detected at line {current_line}: potential quadratic time complexity O(n^2).",
                        }
                    )
                active_loops.append(
                    {
                        "start_line": current_line,
                        "is_do": True,
                        "is_braced": False,
                        "brace_depth": brace_depth,
                        "state": "WAIT_BODY",
                        "paren_depth": 0,
                    }
                )
            elif (
                loop_word == "while"
                and active_loops
                and active_loops[-1].get("is_do")
                and active_loops[-1]["state"] == "DO_TAIL"
            ):
                # Tail while condition of do-while loop
                active_loops[-1]["state"] = "DO_TAIL_HEADER"
                active_loops[-1]["paren_depth"] = 0
            else:
                # Regular for / while
                if active_loops and active_loops[-1]["state"] == "WAIT_BODY":
                    active_loops[-1]["is_braced"] = False
                    active_loops[-1]["state"] = "BODY"

                if active_loops:
                    patterns.append(
                        {
                            "pattern": "nested_loops",
                            "line": current_line,
                            "message": f"Nested loop detected at line {current_line}: potential quadratic time complexity O(n^2).",
                        }
                    )

                active_loops.append(
                    {
                        "start_line": current_line,
                        "is_do": False,
                        "is_braced": False,
                        "brace_depth": brace_depth,
                        "state": "HEADER",
                        "paren_depth": 0,
                    }
                )

        elif tok_type == "LPAREN":
            if active_loops and active_loops[-1]["state"] in ("HEADER", "DO_TAIL_HEADER"):
                active_loops[-1]["paren_depth"] += 1

        elif tok_type == "RPAREN":
            if active_loops and active_loops[-1]["state"] in ("HEADER", "DO_TAIL_HEADER"):
                active_loops[-1]["paren_depth"] -= 1
                if active_loops[-1]["paren_depth"] <= 0:
                    if active_loops[-1]["state"] == "DO_TAIL_HEADER":
                        active_loops.pop()
                    else:
                        active_loops[-1]["state"] = "WAIT_BODY"

        elif tok_type == "LBRACE":
            brace_depth += 1
            if active_loops and active_loops[-1]["state"] == "WAIT_BODY":
                active_loops[-1]["is_braced"] = True
                active_loops[-1]["brace_depth"] = brace_depth
                active_loops[-1]["state"] = "BODY"

        elif tok_type == "RBRACE":
            while active_loops and active_loops[-1]["is_braced"] and brace_depth <= active_loops[-1]["brace_depth"]:
                top = active_loops.pop()
                if top.get("is_do"):
                    top["state"] = "DO_TAIL"
                    active_loops.append(top)
                    break
                while active_loops and not active_loops[-1]["is_braced"]:
                    active_loops.pop()
            brace_depth -= 1

        elif tok_type == "SEMI":
            # Statement boundary: closes unbraced single-statement loop
            if active_loops and not active_loops[-1]["is_braced"] and active_loops[-1]["state"] == "BODY":
                while active_loops and not active_loops[-1]["is_braced"]:
                    active_loops.pop()

        elif tok_type == "OTHER":
            if active_loops and active_loops[-1]["state"] == "WAIT_BODY":
                active_loops[-1]["is_braced"] = False
                active_loops[-1]["state"] = "BODY"

    # Scan lines inside loop bodies for string concatenation and linear search
    seen_concat_lines: set[int] = set()
    seen_search_lines: set[int] = set()
    for l_num in sorted(loop_body_lines):
        if 1 <= l_num <= len(clean_lines):
            line_text = clean_lines[l_num - 1]
            if _C_FAMILY_STRING_CONCAT_RE.search(line_text) and l_num not in seen_concat_lines:
                seen_concat_lines.add(l_num)
                patterns.append(
                    {
                        "pattern": "repeated_string_concat",
                        "line": l_num,
                        "message": f"Repeated accumulation in loop at line {l_num}: consider using buffer or join.",
                    }
                )
            if _C_FAMILY_LINEAR_SEARCH_RE.search(line_text) and l_num not in seen_search_lines:
                seen_search_lines.add(l_num)
                patterns.append(
                    {
                        "pattern": "linear_search_in_loop",
                        "line": l_num,
                        "message": f"Linear search in loop at line {l_num}: consider using a hash set for O(1) lookup.",
                    }
                )

    return patterns


_NESTED_SCOPE_TYPES = (
    ast.FunctionDef,
    ast.AsyncFunctionDef,
    ast.ClassDef,
    ast.Lambda,
)


def _walk_local_scope(node: ast.AST) -> Iterator[ast.AST]:
    """Traverse AST nodes under `node` without entering nested function, class, or lambda scopes (US-67)."""
    queue: deque[ast.AST] = deque([node])
    while queue:
        current = queue.popleft()
        for child in ast.iter_child_nodes(current):
            if not isinstance(child, _NESTED_SCOPE_TYPES):
                yield child
                queue.append(child)


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

            # Pre-scan assignments to classify variable types initialized before loops
            str_vars: set[str] = set()
            num_vars: set[str] = set()
            for n in ast.walk(tree):
                if isinstance(n, ast.Assign):
                    for t in n.targets:
                        if isinstance(t, ast.Name):
                            if isinstance(n.value, ast.Constant):
                                if isinstance(n.value.value, str):
                                    str_vars.add(t.id)
                                elif isinstance(n.value.value, (int, float, complex)):
                                    num_vars.add(t.id)
                            elif isinstance(n.value, ast.Call) and getattr(n.value.func, "id", "") == "str":
                                str_vars.add(t.id)

            STRING_HINT_NAMES = {"str", "string", "text", "msg", "message", "line", "buf", "buffer", "out_str", "result_str"}

            for node in ast.walk(tree):
                # Rule 1: Nested loops
                if isinstance(node, (ast.For, ast.AsyncFor, ast.While)):
                    for child in _walk_local_scope(node):
                        if isinstance(child, (ast.For, ast.AsyncFor, ast.While)):
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
                if isinstance(node, (ast.For, ast.AsyncFor, ast.While)):
                    for child in _walk_local_scope(node):
                        target_id: Optional[str] = None
                        value_node: Optional[ast.AST] = None

                        if isinstance(child, ast.AugAssign) and isinstance(child.op, ast.Add):
                            if isinstance(child.target, ast.Name):
                                target_id = child.target.id
                            value_node = child.value
                        elif isinstance(child, ast.Assign) and isinstance(child.value, ast.BinOp) and isinstance(child.value.op, ast.Add):
                            if len(child.targets) == 1 and isinstance(child.targets[0], ast.Name):
                                target_id = child.targets[0].id
                            if isinstance(child.value.left, ast.Name) and child.value.left.id == target_id:
                                value_node = child.value.right
                            elif isinstance(child.value.right, ast.Name) and child.value.right.id == target_id:
                                value_node = child.value.left

                        if target_id is not None and value_node is not None:
                            is_concat = False
                            # String literal or formatted string
                            if isinstance(value_node, ast.Constant) and isinstance(value_node.value, str):
                                is_concat = True
                            elif isinstance(value_node, ast.JoinedStr):
                                is_concat = True
                            elif isinstance(value_node, ast.Call) and getattr(value_node.func, "id", "") in ("str", "format"):
                                is_concat = True
                            elif isinstance(value_node, ast.Call) and isinstance(value_node.func, ast.Attribute) and value_node.func.attr in ("join", "format", "decode"):
                                is_concat = True
                            # Explicit numeric literal or call is NOT string concatenation
                            elif isinstance(value_node, ast.Constant) and isinstance(value_node.value, (int, float, complex, bool)):
                                is_concat = False
                            elif target_id in num_vars and not (isinstance(value_node, ast.Name) and value_node.id in str_vars):
                                is_concat = False
                            elif target_id in str_vars or target_id in STRING_HINT_NAMES:
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
                if isinstance(node, (ast.For, ast.AsyncFor, ast.While)):
                    for child in _walk_local_scope(node):
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
    """Execute rule-based pattern checks first, followed by controlled AI assistance (US-21, US-22, US-67, FR12.3, FR12.4, FR35, T-18 v2).

    When rules_first is True (US-67, FR36.4):
    1. If any slow pattern rule fires, report its findings and stop (0 outbound AI calls).
    2. If none fires, proceed to the AI step subject to consent (FR12, FR12.4, US-21) and the AI switch (FR35).

    Mandatory Order of Checks (US-21, US-22):
    1. Consent & Withdrawal (US-17, US-21, FR12.2, FR12.4) -> CONSENT_MISSING (skipped AI step, 0 calls)
    2. Host AI Switch (FR35) -> AI_DISABLED (skipped AI step, 0 calls)
    3. Code Cache (NFR5.2, Decision 11) -> Cache Hit returns cached suggestion (does NOT count as call)
    4. Room Hourly Rate Limit (NFR5.3) -> ROOM_RATE_LIMITED (skipped AI step, 0 calls)
    5. Outbound AI Call: records call on rate limiter, calls LLM, stores result in cache.
    """
    # 1. AI-free rule-based analysis (always executes first unconditionally)
    slow_patterns = detect_slow_patterns(code, language=language)

    # 2. Check 1: User Consent (FR12.2, FR12.4, US-17, US-21)
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
    # 4. Check 3: Rules-First Slow Pattern Detection (US-67, FR36.4)
    # Before calling external AI, if simple rules detect a likely issue, report it and stop (0 LLM calls)
    elif rules_first and slow_patterns:
        ai_step = {
            "status": "skipped",
            "error_code": None,
            "suggested_fix": None,
            "verified": False,
            "verified_speedup_pct": None,
        }
    else:
        # 5. Check 4: Normalized Code Cache (NFR5.2, FR40.3, Decision 11)
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
            except CostCapReachedError:
                if reraise_ai_errors:
                    raise
                record_fallback(room_id)
                ai_step = {
                    "status": "skipped",
                    "error_code": "COST_CAP_REACHED",
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
