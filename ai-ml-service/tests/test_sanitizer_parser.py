"""Tests for deterministic sanitizer report parsing (FR39.1, FR39.2, DR5, US-73)."""

from fastapi.testclient import TestClient
import pytest

from app.main import app
from app.sanitizer import (
    extract_sanitizer_error_type,
    extract_sanitizer_line_number,
    parse_sanitizer_report,
)


# ============================================================================
# Representative GCC and Clang Sanitizer Reports (US-73 Acceptance Criteria)
# ============================================================================

SAMPLE_HEAP_BUFFER_OVERFLOW = """
==4210==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000014 at pc 0x000000401233 bp 0x7ffd0123 sp 0x7ffd0120
READ of size 4 at 0x602000000014 thread T0
    #0 0x401233 in process_buffer /workspace/solution.cpp:14
    #1 0x401450 in main /workspace/solution.cpp:28
    #2 0x7f1234 in __libc_start_main (/lib/x86_64-linux-gnu/libc.so.6+0x29d90)
"""

SAMPLE_STACK_BUFFER_OVERFLOW = """
==5120==ERROR: AddressSanitizer: stack-buffer-overflow on address 0x7ffe12345678 at pc 0x0000004011aa bp 0x7ffe1234 sp 0x7ffe1230
WRITE of size 4 at 0x7ffe12345678 thread T0
    #0 0x4011aa in vulnerable_copy /workspace/solution.c:20
    #1 0x4012bb in main /workspace/solution.c:35
"""

SAMPLE_GLOBAL_BUFFER_OVERFLOW = """
==6001==ERROR: AddressSanitizer: global-buffer-overflow on address 0x0000004040a4 at pc 0x000000401150 bp 0x7fff1122 sp 0x7fff1120
READ of size 4 at 0x0000004040a4 thread T0
    #0 0x401150 in read_global /workspace/solution.cpp:8
    #1 0x401300 in main /workspace/solution.cpp:16
"""

SAMPLE_USE_AFTER_FREE = """
==7032==ERROR: AddressSanitizer: heap-use-after-free on address 0x602000000010 at pc 0x000000401222 bp 0x7fff3344 sp 0x7fff3340
READ of size 4 at 0x602000000010 thread T0
    #0 0x401222 in read_node /workspace/solution.cpp:32
    #1 0x401400 in main /workspace/solution.cpp:45
"""

SAMPLE_DOUBLE_FREE = """
==8192==ERROR: AddressSanitizer: attempting double-free on 0x602000000010 in thread T0:
    #0 0x7f8812 in free (/usr/lib/x86_64-linux-gnu/libasan.so.6+0xb2123)
    #1 0x401290 in clean_memory /workspace/solution.c:25
    #2 0x401350 in main /workspace/solution.c:40
"""

SAMPLE_UBSAN_SIGNED_OVERFLOW = """
/sandbox/code/solution.c:14:5: runtime error: signed integer overflow: 2147483647 + 1 cannot be represented in type 'int'
"""

SAMPLE_UBSAN_SHIFT_OUT_OF_BOUNDS = """
/sandbox/code/solution.c:20:12: runtime error: shift exponent 32 is too large for 32-bit type 'int'
"""

SAMPLE_UBSAN_INDEX_OUT_OF_BOUNDS = """
/sandbox/code/solution.cpp:12:3: runtime error: index 10 out of bounds for type 'int[5]'
"""

SAMPLE_UBSAN_NULL_DEREFERENCE = """
/sandbox/code/solution.c:15:1: runtime error: null pointer dereference
"""

SAMPLE_ORDINARY_CRASH = """
Segmentation fault (core dumped)
"""

SAMPLE_CLEAN_OUTPUT = """
All 10 tests passed successfully.
Execution completed.
"""


# ============================================================================
# Unit Tests: Parser Direct Behavior
# ============================================================================

def test_parse_asan_heap_buffer_overflow():
    res = parse_sanitizer_report(SAMPLE_HEAP_BUFFER_OVERFLOW)
    assert res.error_type == "heap-buffer-overflow"
    assert res.line_number == 14
    assert res.summary == "Detected heap-buffer-overflow at line 14."


def test_parse_asan_stack_buffer_overflow():
    res = parse_sanitizer_report(SAMPLE_STACK_BUFFER_OVERFLOW)
    assert res.error_type == "stack-buffer-overflow"
    assert res.line_number == 20
    assert res.summary == "Detected stack-buffer-overflow at line 20."


def test_parse_asan_global_buffer_overflow():
    res = parse_sanitizer_report(SAMPLE_GLOBAL_BUFFER_OVERFLOW)
    assert res.error_type == "global-buffer-overflow"
    assert res.line_number == 8
    assert res.summary == "Detected global-buffer-overflow at line 8."


def test_parse_asan_use_after_free():
    res = parse_sanitizer_report(SAMPLE_USE_AFTER_FREE)
    assert res.error_type == "heap-use-after-free"
    assert res.line_number == 32
    assert res.summary == "Detected heap-use-after-free at line 32."


def test_parse_asan_double_free():
    res = parse_sanitizer_report(SAMPLE_DOUBLE_FREE)
    assert res.error_type == "double-free"
    assert res.line_number == 25
    assert res.summary == "Detected double-free at line 25."


def test_parse_ubsan_signed_overflow():
    res = parse_sanitizer_report(SAMPLE_UBSAN_SIGNED_OVERFLOW)
    assert res.error_type == "signed-integer-overflow"
    assert res.line_number == 14
    assert res.summary == "Detected signed-integer-overflow at line 14."


def test_parse_ubsan_shift_out_of_bounds():
    res = parse_sanitizer_report(SAMPLE_UBSAN_SHIFT_OUT_OF_BOUNDS)
    assert res.error_type == "shift-out-of-bounds"
    assert res.line_number == 20
    assert res.summary == "Detected shift-out-of-bounds at line 20."


def test_parse_ubsan_index_out_of_bounds():
    res = parse_sanitizer_report(SAMPLE_UBSAN_INDEX_OUT_OF_BOUNDS)
    assert res.error_type == "index-out-of-bounds"
    assert res.line_number == 12
    assert res.summary == "Detected index-out-of-bounds at line 12."


def test_parse_ubsan_null_dereference():
    res = parse_sanitizer_report(SAMPLE_UBSAN_NULL_DEREFERENCE)
    assert res.error_type == "null-pointer-dereference"
    assert res.line_number == 15
    assert res.summary == "Detected null-pointer-dereference at line 15."


def test_parse_clean_run_returns_empty_summary():
    """A clean program returns an empty error summary, not an error."""
    res1 = parse_sanitizer_report("")
    assert res1.error_type is None
    assert res1.line_number is None
    assert res1.summary == ""

    res2 = parse_sanitizer_report(SAMPLE_CLEAN_OUTPUT, exit_code=0)
    assert res2.error_type is None
    assert res2.line_number is None
    assert res2.summary == ""


def test_parse_ordinary_crash_without_sanitizer_finding():
    """Ordinary crash without sanitizer findings returns exit info without inventing an error type."""
    res = parse_sanitizer_report(SAMPLE_ORDINARY_CRASH, exit_code=139)
    assert res.error_type is None
    assert res.line_number is None
    assert "exit code 139" in res.summary or "Segmentation fault" in res.summary
    assert "no sanitizer finding" in res.summary


# ============================================================================
# API Endpoint Integration Tests: POST /v1/analyses/crash-explanation
# ============================================================================

client = TestClient(app)


def test_endpoint_unsupported_language_rejection():
    """Non-C/C++ language returns 422 UNSUPPORTED_LANGUAGE (DR5)."""
    payload = {
        "request_id": "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "analysis_id": "6b7c8d9e-0f1a-2b3c-4d5e-6f7a8b9c0d1e",
        "request_type": "crash_explanation",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": SAMPLE_HEAP_BUFFER_OVERFLOW,
        "code_snippet": "v[5] = 10",
    }
    resp = client.post("/v1/analyses/crash-explanation", json=payload)
    assert resp.status_code == 422
    data = resp.json()
    assert data["error"]["code"] == "UNSUPPORTED_LANGUAGE"


def test_endpoint_parses_ubsan_signed_overflow():
    """Endpoint returns parsed UBSan error_type, line_number, and summary deterministically."""
    payload = {
        "request_id": "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "analysis_id": "6b7c8d9e-0f1a-2b3c-4d5e-6f7a8b9c0d1e",
        "request_type": "crash_explanation",
        "language": "c",
        "consent": False,
        "ai_enabled": False,
        "sanitizer_output": SAMPLE_UBSAN_SIGNED_OVERFLOW,
        "code_snippet": "int val = 2147483647 + 1;",
    }
    resp = client.post("/v1/analyses/crash-explanation", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["task"] == "crash_explanation"
    assert data["error_type"] == "signed-integer-overflow"
    assert data["line_number"] == 14
    assert data["summary"] == "Detected signed-integer-overflow at line 14."
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"


def test_endpoint_clean_execution_returns_empty_summary():
    """Endpoint handles clean execution without inventing errors."""
    payload = {
        "request_id": "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "analysis_id": "6b7c8d9e-0f1a-2b3c-4d5e-6f7a8b9c0d1e",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": "All tests passed.\nSANITIZER_CLEAN",
        "code_snippet": "int main() { return 0; }",
    }
    resp = client.post("/v1/analyses/crash-explanation", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["summary"] == ""
    assert data["error_type"] is None
    assert data["line_number"] is None
    assert data["ai_step"]["status"] == "ok"
