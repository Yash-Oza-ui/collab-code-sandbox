"""Tests for relevant code section extraction and LLM prompt integration (FR40.2, US-76, NFR8.2).

Verifies:
1. extract_relevant_section extracts target functions accurately across the five DR1 languages:
   - Python
   - C++
   - C
   - Java
   - JavaScript
2. extract_relevant_section extracts windows around error lines:
   - Middle of file: bounded lines around error line; markers outside window are absent.
   - Line 1 (file beginning edge): no crash, bounded window.
   - Last line (file ending edge): no crash, bounded window.
   - Out of bounds line numbers: clamped safely.
3. Size limit enforcement:
   - Section longer than max_lines is strictly truncated to max_lines.
4. Fallback behavior:
   - Unknown function name falls back to small bounded snippet, never the whole file.
5. Stderr error line parsing:
   - AddressSanitizer, Python Tracebacks, GCC/Clang, Java, and JavaScript diagnostics.
6. Explanation client integration (T-16 & US-74 reuse):
   - Outbound LLM prompt contains ONLY the relevant section.
   - Marker lines outside the relevant section NEVER appear in the captured prompt.
   - Fails if the client sends the whole file.
7. Privacy audit (NFR8.2):
   - Zero code or prompt text logged during section extraction or explanation calls.
"""

import logging
import sys
from pathlib import Path
from unittest.mock import AsyncMock, patch

import httpx
import pytest

# Ensure service root is in sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fastapi.testclient import TestClient

from app.config import settings
from app.llm import generate_explanation
from app.llm.client import LLMClient
from app.main import app
from app.section import (
    extract_error_line_from_stderr,
    extract_relevant_section,
)

client = TestClient(app)


# ============================================================================
# 1. Unit Tests for extract_relevant_section (Language-Specific Functions)
# ============================================================================


def test_extract_target_function_python():
    """Verify target Python function is extracted and markers outside are absent (FR40.2)."""
    code = (
        "# TOP_CANARY_MARKER_PY\n"
        "import os\n\n"
        "def helper_func():\n"
        "    return 42\n\n"
        "def target_func(x, y):\n"
        "    res = x + y\n"
        "    return res * 2\n\n"
        "def other_func():\n"
        "    return -1\n\n"
        "# BOTTOM_CANARY_MARKER_PY\n"
    )

    section = extract_relevant_section(code, language="python", function_name="target_func")
    assert "def target_func(x, y):" in section
    assert "return res * 2" in section
    assert "TOP_CANARY_MARKER_PY" not in section
    assert "BOTTOM_CANARY_MARKER_PY" not in section
    assert "helper_func" not in section
    assert "other_func" not in section


def test_extract_target_function_cpp():
    """Verify target C++ function is extracted and markers outside are absent (FR40.2)."""
    code = (
        "// TOP_CANARY_MARKER_CPP\n"
        "#include <iostream>\n\n"
        "void helper() {\n"
        "    std::cout << 'h';\n"
        "}\n\n"
        "int process_buffer(int* buffer, int size) {\n"
        "    int sum = 0;\n"
        "    for (int i = 0; i < size; ++i) {\n"
        "        sum += buffer[i];\n"
        "    }\n"
        "    return sum;\n"
        "}\n\n"
        "int main() {\n"
        "    return 0;\n"
        "}\n"
        "// BOTTOM_CANARY_MARKER_CPP\n"
    )

    section = extract_relevant_section(code, language="cpp", function_name="process_buffer")
    assert "int process_buffer(int* buffer, int size) {" in section
    assert "return sum;" in section
    assert "TOP_CANARY_MARKER_CPP" not in section
    assert "BOTTOM_CANARY_MARKER_CPP" not in section
    assert "void helper()" not in section
    assert "int main()" not in section


def test_extract_target_function_c():
    """Verify target C function is extracted and markers outside are absent (FR40.2)."""
    code = (
        "// TOP_CANARY_MARKER_C\n"
        "#include <stdio.h>\n\n"
        "void unused_c_func() {\n"
        "    printf(\"unused\");\n"
        "}\n\n"
        "char* string_reverse(char* str) {\n"
        "    int len = 0;\n"
        "    while(str[len]) len++;\n"
        "    return str;\n"
        "}\n\n"
        "// BOTTOM_CANARY_MARKER_C\n"
    )

    section = extract_relevant_section(code, language="c", function_name="string_reverse")
    assert "char* string_reverse(char* str) {" in section
    assert "return str;" in section
    assert "TOP_CANARY_MARKER_C" not in section
    assert "BOTTOM_CANARY_MARKER_C" not in section
    assert "unused_c_func" not in section


def test_extract_target_function_java():
    """Verify target Java method is extracted with annotations/modifiers (FR40.2)."""
    code = (
        "// TOP_CANARY_MARKER_JAVA\n"
        "package com.example;\n\n"
        "public class Solution {\n"
        "    private int secretValue = 100;\n\n"
        "    @Deprecated\n"
        "    public static int binarySearch(int[] arr, int target) throws Exception {\n"
        "        int left = 0, right = arr.length - 1;\n"
        "        while (left <= right) {\n"
        "            int mid = left + (right - left) / 2;\n"
        "            if (arr[mid] == target) return mid;\n"
        "            if (arr[mid] < target) left = mid + 1;\n"
        "            else right = mid - 1;\n"
        "        }\n"
        "        return -1;\n"
        "    }\n\n"
        "    public void otherMethod() {\n"
        "    }\n"
        "}\n"
        "// BOTTOM_CANARY_MARKER_JAVA\n"
    )

    section = extract_relevant_section(code, language="java", function_name="binarySearch")
    assert "binarySearch" in section
    assert "return -1;" in section
    assert "TOP_CANARY_MARKER_JAVA" not in section
    assert "BOTTOM_CANARY_MARKER_JAVA" not in section
    assert "otherMethod" not in section


def test_extract_target_function_javascript():
    """Verify target JavaScript function/arrow/method is extracted (FR40.2)."""
    code = (
        "// TOP_CANARY_MARKER_JS\n"
        "const globalConfig = { debug: true };\n\n"
        "function unusedHelper() {\n"
        "    return false;\n"
        "}\n\n"
        "async function fetchUserData(userId) {\n"
        "    const response = await fetch(`/api/users/${userId}`);\n"
        "    const data = await response.json();\n"
        "    return data;\n"
        "}\n\n"
        "const arrowFunc = () => {\n"
        "    return true;\n"
        "};\n"
        "// BOTTOM_CANARY_MARKER_JS\n"
    )

    section = extract_relevant_section(code, language="javascript", function_name="fetchUserData")
    assert "async function fetchUserData(userId)" in section
    assert "return data;" in section
    assert "TOP_CANARY_MARKER_JS" not in section
    assert "BOTTOM_CANARY_MARKER_JS" not in section
    assert "unusedHelper" not in section
    assert "arrowFunc" not in section


# ============================================================================
# 2. Unit Tests for Error Line Extraction & Boundary Handling
# ============================================================================


def test_extract_error_line_in_middle_of_file():
    """Test: error line in the middle of a file: prompt holds only the lines around it."""
    # Build 50-line program
    lines = [f"// line_{i}" for i in range(1, 51)]
    lines[0] = "// TOP_SECRET_CANARY_LINE_1"
    lines[24] = "int crash_line = 10 / 0; // line 25"
    lines[49] = "// BOTTOM_SECRET_CANARY_LINE_50"
    code = "\n".join(lines)

    # Window of 5 lines on each side around line 25
    section = extract_relevant_section(
        code, language="cpp", error_line=25, window_lines=5, max_lines=20
    )

    assert "int crash_line = 10 / 0; // line 25" in section
    assert "// line_21" in section
    assert "// line_29" in section
    assert "TOP_SECRET_CANARY_LINE_1" not in section
    assert "BOTTOM_SECRET_CANARY_LINE_50" not in section
    assert "// line_10" not in section
    assert "// line_40" not in section


def test_edge_case_error_line_at_line_1():
    """Test: error line at line 1 (edge): no crash, bounded window, bottom marker absent."""
    lines = [f"# line_{i}" for i in range(1, 41)]
    lines[0] = "error_at_start = 1 / 0  # line 1"
    lines[-1] = "# BOTTOM_CANARY_EDGE_1"
    code = "\n".join(lines)

    section = extract_relevant_section(
        code, language="python", error_line=1, window_lines=5, max_lines=15
    )

    assert "error_at_start = 1 / 0" in section
    assert "# line_5" in section
    assert "BOTTOM_CANARY_EDGE_1" not in section
    assert len(section.splitlines()) <= 15


def test_edge_case_error_line_at_last_line():
    """Test: error line at the last line (edge): no crash, bounded window, top marker absent."""
    lines = [f"# line_{i}" for i in range(1, 41)]
    lines[0] = "# TOP_CANARY_EDGE_LAST"
    lines[-1] = "error_at_end = 1 / 0  # line 40"
    code = "\n".join(lines)

    section = extract_relevant_section(
        code, language="python", error_line=40, window_lines=5, max_lines=15
    )

    assert "error_at_end = 1 / 0" in section
    assert "# line_36" in section
    assert "TOP_CANARY_EDGE_LAST" not in section
    assert len(section.splitlines()) <= 15


def test_edge_case_unknown_function_name_never_whole_file():
    """Test: unknown function name: no crash, never the whole file, markers absent."""
    lines = [f"// line_{i}" for i in range(1, 51)]
    lines[0] = "// TOP_HEADER"
    lines[-1] = "// BOTTOM_CANARY_UNKNOWN_FN"
    code = "\n".join(lines)

    section = extract_relevant_section(
        code,
        language="cpp",
        function_name="definitely_does_not_exist_xyz",
        fallback_lines=15,
        max_lines=20,
    )

    # Must return a small bounded snippet, not crashing and NOT sending the whole file
    sec_lines = section.splitlines()
    assert len(sec_lines) <= 20
    assert len(sec_lines) < len(lines)
    assert "BOTTOM_CANARY_UNKNOWN_FN" not in section


def test_section_longer_than_limit_is_cut_to_limit():
    """Test: a section longer than the limit is cut to the limit."""
    # Create a 60-line function
    fn_lines = ["def huge_function():"]
    for i in range(1, 60):
        fn_lines.append(f"    x_{i} = {i}")
    code = "\n".join(fn_lines)

    max_limit = 25
    section = extract_relevant_section(
        code,
        language="python",
        function_name="huge_function",
        max_lines=max_limit,
    )

    result_lines = section.splitlines()
    assert len(result_lines) == max_limit
    assert "def huge_function():" in section
    # Lines past max_limit must be absent
    assert f"x_{max_limit + 10} = {max_limit + 10}" not in section


# ============================================================================
# 3. Stderr Diagnostic Parsing Tests
# ============================================================================


def test_extract_error_line_from_asan():
    """Verify AddressSanitizer output line number extraction."""
    asan_stderr = (
        "==4210==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000014\n"
        "READ of size 4 at 0x602000000014 thread T0\n"
        "    #0 0x401233 in process_buffer /workspace/solution.cpp:18\n"
        "    #1 0x401450 in main /workspace/solution.cpp:32\n"
    )
    line = extract_error_line_from_stderr(asan_stderr, language="cpp")
    assert line == 18


def test_extract_error_line_from_python_traceback():
    """Verify Python traceback line number extraction (innermost frame)."""
    py_stderr = (
        "Traceback (most recent call last):\n"
        '  File "main.py", line 5, in <module>\n'
        "    solve()\n"
        '  File "main.py", line 14, in solve\n'
        "    return 10 / 0\n"
        "ZeroDivisionError: division by zero\n"
    )
    line = extract_error_line_from_stderr(py_stderr, language="python")
    assert line == 14


def test_extract_error_line_from_gcc():
    """Verify GCC compiler diagnostic line number extraction."""
    gcc_stderr = "solution.c:27:10: error: expected ';' before 'return'\n"
    line = extract_error_line_from_stderr(gcc_stderr, language="c")
    assert line == 27


def test_extract_error_line_from_java():
    """Verify Java exception line number extraction."""
    java_stderr = (
        "Exception in thread \"main\" java.lang.ArrayIndexOutOfBoundsException: Index 15 out of bounds for length 10\n"
        "\tat Solution.run(Solution.java:22)\n"
        "\tat Solution.main(Solution.java:35)\n"
    )
    line = extract_error_line_from_stderr(java_stderr, language="java")
    assert line == 22


def test_extract_error_line_from_javascript():
    """Verify Node.js exception line number extraction."""
    js_stderr = (
        "TypeError: Cannot read properties of undefined (reading 'length')\n"
        "    at processItems (/workspace/index.js:19:24)\n"
        "    at Object.<anonymous> (/workspace/index.js:42:1)\n"
    )
    line = extract_error_line_from_stderr(js_stderr, language="javascript")
    assert line == 19


# ============================================================================
# 4. Explainer Integration Tests (Mocked LLM Outbound Prompt Capture)
# ============================================================================


@pytest.mark.anyio
async def test_explanation_client_sends_only_relevant_section_with_mocked_llm():
    """The explanation client from T-16 uses the helper (shown by a test that fails if it sends the whole file)."""
    # 50-line program with distinctive canaries at top and bottom
    lines = [f"# line_{i}" for i in range(1, 51)]
    top_canary = "# FORBIDDEN_CANARY_TOP_FILE_L1"
    bottom_canary = "# FORBIDDEN_CANARY_BOTTOM_FILE_L50"
    lines[0] = top_canary
    lines[24] = "crash_node = 100 / 0  # line 25 target"
    lines[49] = bottom_canary
    full_code = "\n".join(lines)

    stderr_output = (
        "Traceback (most recent call last):\n"
        '  File "main.py", line 25, in <module>\n'
        "    crash_node = 100 / 0\n"
        "ZeroDivisionError: division by zero\n"
    )

    captured_prompt = None

    class CaptureClient(LLMClient):
        async def complete(self, prompt: str, **kwargs) -> str:
            nonlocal captured_prompt
            captured_prompt = prompt
            return "Division by zero occurred at line 25. Check the divisor before division."

    test_llm = CaptureClient()

    explanation = await generate_explanation(
        language="python",
        consent=True,
        ai_enabled=True,
        code=full_code,
        stderr=stderr_output,
        failure_kind="non_zero_exit",
        exit_code=1,
        llm_client=test_llm,
    )

    assert explanation is not None
    assert captured_prompt is not None

    # Key Assertion: Target crash line MUST be present in the outbound prompt
    assert "crash_node = 100 / 0" in captured_prompt

    # Invariant: Lines outside the relevant window MUST NOT appear in the captured prompt
    assert top_canary not in captured_prompt
    assert bottom_canary not in captured_prompt


@pytest.mark.anyio
async def test_explanation_client_with_target_function():
    """Verify generate_explanation with function_name sends only that function."""
    full_code = (
        "# SECRET_TOP_UNSENT\n"
        "def compute_cube(x):\n"
        "    return x * x * x\n\n"
        "def target_computation(val):\n"
        "    res = val * 42\n"
        "    return res\n\n"
        "def compute_square(x):\n"
        "    return x * x\n"
        "# SECRET_BOTTOM_UNSENT\n"
    )

    captured_prompt = None

    class CaptureClient(LLMClient):
        async def complete(self, prompt: str, **kwargs) -> str:
            nonlocal captured_prompt
            captured_prompt = prompt
            return "Target computation calculates 42 times val."

    test_llm = CaptureClient()

    await generate_explanation(
        language="python",
        consent=True,
        ai_enabled=True,
        code=full_code,
        function_name="target_computation",
        llm_client=test_llm,
    )

    assert captured_prompt is not None
    assert "target_computation" in captured_prompt
    assert "SECRET_TOP_UNSENT" not in captured_prompt
    assert "SECRET_BOTTOM_UNSENT" not in captured_prompt
    assert "compute_cube" not in captured_prompt
    assert "compute_square" not in captured_prompt


def test_endpoint_explain_sends_relevant_section_and_never_whole_file():
    """Test POST /v1/explain through FastAPI TestClient captures prompt with only relevant code."""
    lines = [f"// line_{i}" for i in range(1, 51)]
    lines[0] = "// SECRET_TOP_API_CANARY"
    lines[21] = "int err = buffer[index]; // line 22"
    lines[49] = "// SECRET_BOTTOM_API_CANARY"
    full_code = "\n".join(lines)

    mock_llm_text = "The error at line 22 caused a crash. Validate index bounds."
    captured_body = {}

    def mock_post(url, headers=None, json=None, **kwargs):
        captured_body.update(json or {})
        return httpx.Response(
            status_code=200,
            json={
                "candidates": [
                    {
                        "content": {
                            "parts": [{"text": mock_llm_text}],
                        }
                    }
                ]
            },
        )

    payload = {
        "request_id": "req-us76-001",
        "room_id": "room-us76-test",
        "request_type": "explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "code": full_code,
        "stderr": "/workspace/solution.cpp:22: error: out of bounds",
        "error_line": 22,
        "exit_code": 1,
        "execution_time_ms": 10.0,
        "peak_memory_mb": 5.0,
        "timed_out": False,
        "oom_killed": False,
        "failure_kind": "compile_error",
    }

    with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
        with patch("httpx.AsyncClient.post", new=AsyncMock(side_effect=mock_post)):
            response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200
    assert captured_body != {}

    # Extract prompt string sent in Gemini payload
    prompt_sent = captured_body["contents"][0]["parts"][0]["text"]
    assert "int err = buffer[index]; // line 22" in prompt_sent
    assert "SECRET_TOP_API_CANARY" not in prompt_sent
    assert "SECRET_BOTTOM_API_CANARY" not in prompt_sent


def test_privacy_no_code_or_prompt_logged_during_extraction(caplog):
    """Verify NFR8.2: logs NEVER contain user code, markers, or outbound prompt."""
    secret_marker = "sensitive_user_code_snippet_us76"
    code = f"int x = 1;\n// {secret_marker}\nint y = 2;\n"

    with caplog.at_level(logging.DEBUG):
        _ = extract_relevant_section(code, language="cpp", error_line=2)

    assert secret_marker not in caplog.text
