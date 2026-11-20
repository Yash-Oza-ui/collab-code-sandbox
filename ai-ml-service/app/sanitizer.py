"""Deterministic parser for C/C++ compiler sanitizer crash reports (FR39.1, FR39.2, DR5, US-73).

Parses AddressSanitizer (ASan) and UndefinedBehaviorSanitizer (UBSan) output
into error type and source line without contacting external AI services.
"""

import re
from typing import NamedTuple, Optional


class SanitizerReport(NamedTuple):
    error_type: Optional[str]
    line_number: Optional[int]
    summary: str


# ASan error patterns: e.g. "AddressSanitizer: heap-buffer-overflow" or "attempting double-free"
ASAN_TYPE_RE = re.compile(
    r"(?:AddressSanitizer|ASan):\s*(?:attempting\s+)?([a-zA-Z0-9_\-]+)",
    re.IGNORECASE,
)

# UBSan error patterns: e.g. "runtime error: signed integer overflow" or "runtime error: shift exponent 32..."
UBSAN_TYPE_RE = re.compile(
    r"runtime error:\s*(.+?)(?:\n|$)",
    re.IGNORECASE,
)

# Source line patterns:
# #0 0x... in func /workspace/solution.cpp:14
# /sandbox/code/solution.c:14:5: runtime error:
# solution.cpp:14:
LINE_PATTERNS = [
    re.compile(r"(?:#0\s+0x[0-9a-fA-F]+\s+in\s+.*?|\s+at\s+)(?:/|[a-zA-Z0-9_\-]+\.(?:cpp|c|cc|h|hpp)):(\d+)", re.IGNORECASE),
    re.compile(r"(?:^|\n)\s*(?:/|[a-zA-Z0-9_\-]+\.(?:cpp|c|cc|h|hpp)):(\d+):(?:\d+:)?\s*runtime error:", re.IGNORECASE),
    re.compile(r"(?:/|[a-zA-Z0-9_\-]+\.(?:cpp|c|cc|h|hpp)):(\d+)", re.IGNORECASE),
    re.compile(r":(\d+)\b"),
]


def extract_sanitizer_error_type(output: str) -> Optional[str]:
    """Deterministically extract the error type from sanitizer output."""
    if not output:
        return None

    # Check AddressSanitizer first
    match_asan = ASAN_TYPE_RE.search(output)
    if match_asan:
        raw_type = match_asan.group(1).strip().lower()
        if "double-free" in raw_type:
            return "double-free"
        if "use-after-free" in raw_type:
            return raw_type
        return raw_type

    # Check UndefinedBehaviorSanitizer
    match_ubsan = UBSAN_TYPE_RE.search(output)
    if match_ubsan:
        raw_type = match_ubsan.group(1).strip().lower()
        if "signed integer overflow" in raw_type:
            return "signed-integer-overflow"
        if "shift" in raw_type:
            return "shift-out-of-bounds"
        if "out of bounds" in raw_type or "index" in raw_type:
            return "index-out-of-bounds"
        if "null pointer" in raw_type:
            return "null-pointer-dereference"
        if "division by zero" in raw_type:
            return "division-by-zero"
        # Truncate at colon if present
        first_clause = raw_type.split(":")[0].strip()
        return re.sub(r"[^a-zA-Z0-9_\-]+", "-", first_clause).strip("-")

    return None


def extract_sanitizer_line_number(output: str) -> Optional[int]:
    """Extract line number from sanitizer output."""
    if not output:
        return None

    for pattern in LINE_PATTERNS:
        matches = pattern.findall(output)
        if matches:
            for m in matches:
                try:
                    line = int(m)
                    if line > 0:
                        return line
                except ValueError:
                    continue

    return None


def parse_sanitizer_report(sanitizer_output: str, exit_code: Optional[int] = None) -> SanitizerReport:
    """Parse sanitizer output deterministically without AI (FR39.2, US-73).

    Returns:
        SanitizerReport with error_type, line_number, and summary.
    """
    cleaned = (sanitizer_output or "").strip()
    if not cleaned:
        return SanitizerReport(error_type=None, line_number=None, summary="")

    error_type = extract_sanitizer_error_type(cleaned)
    line_number = extract_sanitizer_line_number(cleaned)

    # 1. Recognized sanitizer finding
    if error_type:
        if line_number is not None:
            summary = f"Detected {error_type} at line {line_number}."
        else:
            summary = f"Detected {error_type}."
        return SanitizerReport(error_type=error_type, line_number=line_number, summary=summary)

    # 2. Check if clean execution (no sanitizer finding, clean exit or success output)
    if exit_code == 0 or "SANITIZER_CLEAN" in cleaned or (exit_code is None and "ERROR:" not in cleaned and "runtime error:" not in cleaned):
        if "ERROR:" not in cleaned and "runtime error:" not in cleaned:
            return SanitizerReport(error_type=None, line_number=None, summary="")

    # 3. Ordinary crash without sanitizer findings:
    # Do NOT invent an error type. Return exit info without inventing an error type.
    if exit_code is not None and exit_code != 0:
        summary = f"Process exited with exit code {exit_code} (no sanitizer finding)."
    elif "segmentation fault" in cleaned.lower() or "sigsegv" in cleaned.lower():
        summary = "Process crashed (Segmentation fault, no sanitizer finding)."
    else:
        summary = "Process terminated without sanitizer diagnostics."

    return SanitizerReport(error_type=None, line_number=line_number, summary=summary)
