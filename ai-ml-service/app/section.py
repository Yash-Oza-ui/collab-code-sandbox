"""Relevant code section extraction helper (FR40.2, US-76, NFR8.2).

Extracts only the code section relevant to an external AI request:
- Target function body for analysis/optimization tasks.
- Small bounded window of lines around a reported error line (compiler, runtime, sanitizer).
- Bounded fallback when no section can be identified, guaranteeing that a full file is never sent.
"""

import ast
import logging
import re
from typing import Optional, Tuple

from app.config import settings

logger = logging.getLogger(__name__)


def extract_error_line_from_stderr(stderr: str, language: Optional[str] = None) -> Optional[int]:
    """Parse compiler, runtime, or sanitizer error output to identify the reported error line."""
    if not stderr or not stderr.strip():
        return None

    # 1. AddressSanitizer / UndefinedBehaviorSanitizer (US-73, US-74):
    # e.g.: "/workspace/solution.cpp:14" or "solution.cpp:14:5"
    asan_matches = re.findall(r"(?:/|[a-zA-Z0-9_\-]+\.(?:cpp|c|cc|h|hpp)):(\d+)", stderr)
    if asan_matches:
        try:
            return int(asan_matches[0])
        except ValueError:
            pass

    # 2. Python Traceback:
    # e.g.: File "solution.py", line 14, in func
    py_matches = re.findall(r'File ".*?", line (\d+)', stderr)
    if py_matches:
        try:
            return int(py_matches[-1])  # deepest/innermost stack frame
        except ValueError:
            pass

    # 3. GCC / Clang compiler diagnostics:
    # e.g.: solution.c:14:5: error: ...
    gcc_matches = re.findall(r"(?:[a-zA-Z0-9_\-]+\.[a-zA-Z0-9]+):(\d+):(?:\d+:)?\s*(?:error|warning|fatal)", stderr, re.IGNORECASE)
    if gcc_matches:
        try:
            return int(gcc_matches[0])
        except ValueError:
            pass

    # 4. Java stack trace:
    # e.g.: at Main.process(Main.java:14)
    java_matches = re.findall(r"\((?:[A-Za-z0-9_$]+\.java):(\d+)\)", stderr)
    if java_matches:
        try:
            return int(java_matches[0])
        except ValueError:
            pass

    # 5. JavaScript / Node.js stack trace:
    # e.g.: at /workspace/solution.js:14:5 or at Object.<anonymous> (index.js:14:5)
    js_matches = re.findall(r"(?:[A-Za-z0-9_\-/]+\.js):(\d+):\d+", stderr)
    if js_matches:
        try:
            return int(js_matches[0])
        except ValueError:
            pass

    # 6. Generic fallback: line <number> or :<number>
    generic_matches = re.findall(r"\bline\s+(\d+)\b", stderr, re.IGNORECASE)
    if generic_matches:
        try:
            return int(generic_matches[0])
        except ValueError:
            pass

    generic_colon = re.findall(r":(\d+)\b", stderr)
    if generic_colon:
        try:
            return int(generic_colon[0])
        except ValueError:
            pass

    return None


def _find_python_function_lines(code: str, function_name: str) -> Optional[Tuple[int, int]]:
    """Find 1-based start and end line numbers of a Python function."""
    try:
        tree = ast.parse(code)
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == function_name:
                start_line = node.lineno
                end_line = getattr(node, "end_lineno", start_line)
                return start_line, end_line
    except Exception:
        pass

    # Fallback: indentation-based scanning if code has syntax errors
    pattern = re.compile(
        r"^[ \t]*(?:async\s+)?def\s+" + re.escape(function_name) + r"\s*\(",
        re.MULTILINE,
    )
    match = pattern.search(code)
    if not match:
        return None

    lines = code.splitlines()
    char_pos = match.start()
    start_line = code[:char_pos].count("\n") + 1
    start_idx = start_line - 1
    def_line = lines[start_idx]
    base_indent = len(def_line) - len(def_line.lstrip())

    end_idx = start_idx
    for idx in range(start_idx + 1, len(lines)):
        line = lines[idx]
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            end_idx = idx
            continue
        line_indent = len(line) - len(line.lstrip())
        if line_indent <= base_indent:
            break
        end_idx = idx

    return start_line, end_idx + 1


def _find_braced_function_lines(
    code: str, signature_match_start: int, lines: list[str]
) -> Optional[Tuple[int, int]]:
    """Find start and end lines of a braced function body starting from signature_match_start."""
    open_brace_idx = code.find("{", signature_match_start)
    if open_brace_idx == -1:
        return None

    start_line = code[:signature_match_start].count("\n") + 1

    depth = 0
    in_string: Optional[str] = None
    in_line_comment = False
    in_block_comment = False
    i = open_brace_idx
    code_len = len(code)

    while i < code_len:
        c = code[i]

        if in_line_comment:
            if c == "\n":
                in_line_comment = False
            i += 1
            continue

        if in_block_comment:
            if c == "*" and i + 1 < code_len and code[i + 1] == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue

        if in_string is not None:
            if c == "\\":
                i += 2
                continue
            if c == in_string:
                in_string = None
            i += 1
            continue

        # Check comment starts
        if c == "/" and i + 1 < code_len:
            next_c = code[i + 1]
            if next_c == "/":
                in_line_comment = True
                i += 2
                continue
            elif next_c == "*":
                in_block_comment = True
                i += 2
                continue

        # Check string starts
        if c in ('"', "'", "`"):
            in_string = c
            i += 1
            continue

        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                end_line = code[:i].count("\n") + 1
                return start_line, end_line

        i += 1

    # Unclosed brace: return start_line to end of code
    return start_line, len(lines)


def _find_function_lines(code: str, language: str, function_name: str) -> Optional[Tuple[int, int]]:
    """Locate the 1-based start and end lines of a function across the five supported languages (DR1)."""
    lang = (language or "").lower()
    lines = code.splitlines()

    if lang == "python":
        return _find_python_function_lines(code, function_name)

    pattern: Optional[re.Pattern] = None

    if lang in ("cpp", "c"):
        # Match C/C++ return type, qualifiers, name, parameters, modifiers, {
        pattern = re.compile(
            r"(?:^|\n)[ \t]*(?:(?:inline|static|virtual|explicit|constexpr|const|extern|friend)\s+)*"
            + r"(?:[\w:*&<> \t]+\s+)?"
            + re.escape(function_name)
            + r"\s*\([^;{()]*\)\s*(?:const|noexcept|\w+)*\s*\{"
        )
    elif lang == "java":
        # Match Java annotations, access modifiers, return type, name, parameters, throws, {
        pattern = re.compile(
            r"(?:^|\n)[ \t]*(?:(?:@\w+(?:\([^)]*\))?\s+)*)"
            + r"(?:(?:public|protected|private|static|final|synchronized|abstract|default|native)\s+)*"
            + r"(?:<[\w,\s?]+>\s+)?"
            + r"(?:[\w<>\[\], \t]+\s+)?"
            + re.escape(function_name)
            + r"\s*\([^;{()]*\)\s*(?:throws\s+[\w,\s]+)?\s*\{"
        )
    elif lang == "javascript":
        # Match JS function declaration, arrow assigned to variable, or class/object method
        pattern = re.compile(
            r"(?:^|\n)[ \t]*(?:(?:async\s+)?function\s+"
            + re.escape(function_name)
            + r"\s*\([^;{()]*\)\s*\{|"
            + r"(?:const|let|var)\s+"
            + re.escape(function_name)
            + r"\s*=\s*(?:async\s*)?(?:function\s*\([^;{()]*\)\s*|\([^;{()]*\)\s*=>\s*)\{|"
            + r"(?:async\s+)?"
            + re.escape(function_name)
            + r"\s*\([^;{()]*\)\s*\{)"
        )

    if pattern:
        match = pattern.search(code)
        if match:
            start_pos = match.start()
            if code[start_pos] == "\n":
                start_pos += 1
            res = _find_braced_function_lines(code, start_pos, lines)
            if res:
                return res

    # Generic fallback: function_name(...) followed by {
    generic_pat = re.compile(r"\b" + re.escape(function_name) + r"\s*\([^;{()]*\)[^{;]*\{")
    match = generic_pat.search(code)
    if match:
        start_pos = match.start()
        res = _find_braced_function_lines(code, start_pos, lines)
        if res:
            return res

    return None


def extract_relevant_section(
    code: str,
    language: str,
    function_name: Optional[str] = None,
    error_line: Optional[int] = None,
    window_lines: Optional[int] = None,
    max_lines: Optional[int] = None,
    fallback_lines: Optional[int] = None,
) -> str:
    """Extract only the relevant code section (target function or error window) with strict size bounds (FR40.2, US-76).

    Args:
        code: Full source code.
        language: Programming language (cpp, python, c, java, javascript).
        function_name: Optional name of the target function to extract.
        error_line: Optional 1-based line number of a reported failure/crash.
        window_lines: Number of lines on each side of the error line (default: settings.SECTION_WINDOW_LINES).
        max_lines: Maximum total lines allowed in the returned section (default: settings.SECTION_MAX_LINES).
        fallback_lines: Bounded line limit if no section can be identified (default: settings.SECTION_FALLBACK_LINES).

    Returns:
        The extracted code snippet string, never exceeding max_lines, and never the whole file when
        the file exceeds the bounded limits.
    """
    if not code:
        return ""

    lines = code.splitlines()
    total_lines = len(lines)
    if total_lines == 0:
        return ""

    win = window_lines if window_lines is not None else settings.SECTION_WINDOW_LINES
    limit = max_lines if max_lines is not None else settings.SECTION_MAX_LINES
    fallback = fallback_lines if fallback_lines is not None else settings.SECTION_FALLBACK_LINES
    bounded_fallback_count = min(fallback, limit)

    # 1. Attempt target function extraction if requested
    if function_name and function_name.strip():
        func_range = _find_function_lines(code, language, function_name.strip())
        if func_range:
            start_line, end_line = func_range
            start_idx = max(0, start_line - 1)
            end_idx = min(total_lines, end_line)
            fn_lines = lines[start_idx:end_idx]

            # If function exceeds max_lines, truncate to max_lines
            if len(fn_lines) > limit:
                if error_line is not None and start_line <= error_line <= end_line:
                    # Center around error_line within the function
                    local_err = error_line - start_line
                    half = limit // 2
                    sub_start = max(0, local_err - half)
                    sub_end = min(len(fn_lines), sub_start + limit)
                    if sub_end - sub_start < limit:
                        sub_start = max(0, sub_end - limit)
                    fn_lines = fn_lines[sub_start:sub_end]
                else:
                    fn_lines = fn_lines[:limit]

            return "\n".join(fn_lines)

    # 2. Extract window around error_line if provided
    if error_line is not None:
        clamped_line = max(1, min(error_line, total_lines))
        target_idx = clamped_line - 1

        start_idx = max(0, target_idx - win)
        end_idx = min(total_lines, target_idx + win + 1)
        selected = lines[start_idx:end_idx]

        # Enforce max_lines bound
        if len(selected) > limit:
            half = limit // 2
            new_start = max(0, target_idx - half)
            new_end = min(total_lines, new_start + limit)
            if new_end - new_start < limit:
                new_start = max(0, new_end - limit)
            selected = lines[new_start:new_end]

        return "\n".join(selected)

    # 3. Fallback bounded part when no section or function is found (FR40.2):
    # Returns a small bounded part, never the whole file when the file exceeds the bound.
    bounded_count = min(total_lines, bounded_fallback_count)
    return "\n".join(lines[:bounded_count])
