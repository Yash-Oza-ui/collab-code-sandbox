"""Tests for rules-first static analysis before AI fallback (US-67, FR36.4, FR12, FR35, T-18 v2).

Covers:
1. Seeded nested loop programs (Python, C++, Java, JS, C): rule fires, reports pattern and line, mocked LLM called 0 times.
2. Seeded repeated string concatenation programs (Python, JS, C++, Java, C): rule fires, mocked LLM called 0 times.
3. Clean program with consent=True and AI on: mocked LLM client called exactly once.
4. Clean program with consent=False: AI step skipped (CONSENT_MISSING), rule results still returned, 0 LLM calls.
5. Clean program with AI disabled (ai_enabled=False): AI step skipped (AI_DISABLED), 0 LLM calls.
6. False-positive checks: sequential (non-nested) loops in Python, C++, Java, and JS are NOT reported as nested.
7. Endpoint tests on POST /v1/analyses/complexity with rules-first activated:
   - seeded nested-loop -> 200 OK, findings returned, ai_step.status="skipped", error_code=None, 0 LLM calls.
   - seeded string-concat -> 200 OK, findings returned, ai_step.status="skipped", error_code=None, 0 LLM calls.
   - clean program -> 200 OK, ai_step.status="ok", 1 LLM call.
   - clean program, consent=False -> 200 OK, ai_step.status="skipped", error_code="CONSENT_MISSING", 0 LLM calls.
   - header X-Rules-First: true activation.
   - settings.RULES_FIRST_AI_AVOIDANCE activation.
8. Privacy audit (NFR8.2): no source code, seeded code, or prompts appear in service logs.
"""

import logging
import sys
from pathlib import Path
from unittest.mock import AsyncMock, patch
import pytest

# Ensure service root is in sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fastapi.testclient import TestClient
from app.config import settings
from app.llm.client import LLMClient
from app.main import app
from app.rules.patterns import (
    detect_slow_patterns,
    run_rule_based_analysis_with_ai_fallback,
    run_rules_before_ai_analysis,
)

client = TestClient(app)

# ============================================================================
# Seeded Sample Programs (Multi-Language)
# ============================================================================

# Seeded Nested Loops
SEEDED_PYTHON_NESTED_LOOPS = """def find_duplicates(arr):
    dups = []
    for i in range(len(arr)):
        for j in range(i + 1, len(arr)):
            if arr[i] == arr[j]:
                dups.append(arr[i])
    return dups
"""

SEEDED_CPP_NESTED_LOOPS = """#include <vector>
bool has_duplicates(const std::vector<int>& arr) {
    for (size_t i = 0; i < arr.size(); ++i) {
        for (size_t j = i + 1; j < arr.size(); ++j) {
            if (arr[i] == arr[j]) return true;
        }
    }
    return false;
}
"""

SEEDED_JAVA_NESTED_LOOPS = """class Solution {
    public boolean hasDuplicates(int[] arr) {
        for (int i = 0; i < arr.length; i++) {
            for (int j = i + 1; j < arr.length; j++) {
                if (arr[i] == arr[j]) return true;
            }
        }
        return false;
    }
}
"""

SEEDED_JS_NESTED_LOOPS = """function hasDuplicates(arr) {
    for (let i = 0; i < arr.length; i++) {
        for (let j = i + 1; j < arr.length; j++) {
            if (arr[i] === arr[j]) return true;
        }
    }
    return false;
}
"""

SEEDED_C_NESTED_LOOPS = """int find_pair(int arr[], int n) {
    for (int i = 0; i < n; i++) {
        for (int j = i + 1; j < n; j++) {
            if (arr[i] == arr[j]) return 1;
        }
    }
    return 0;
}
"""

# Seeded Repeated String Concatenation in Loop
SEEDED_PYTHON_STRING_CONCAT = """def build_string(items):
    s = ""
    for x in items:
        s += str(x)
    return s
"""

SEEDED_PYTHON_STRING_CONCAT_SELF = """def build_string_self(items):
    res = ""
    for item in items:
        res = res + str(item)
    return res
"""

SEEDED_JS_STRING_CONCAT = """function buildString(items) {
    let s = "";
    for (const x of items) {
        s += String(x);
    }
    return s;
}
"""

SEEDED_CPP_STRING_CONCAT = """#include <string>
#include <vector>
std::string build_string(const std::vector<std::string>& items) {
    std::string s = "";
    for (const auto& item : items) {
        s += item;
    }
    return s;
}
"""

SEEDED_JAVA_STRING_CONCAT = """class Builder {
    public String build(String[] items) {
        String s = "";
        for (String item : items) {
            s += item;
        }
        return s;
    }
}
"""

SEEDED_C_STRING_CONCAT = """#include <string.h>
void append_all(char* buf, char* items[], int count) {
    for (int i = 0; i < count; i++) {
        strcat(buf, items[i]);
    }
}
"""

# False-Positive Checks: Sequential (Non-Nested) Loops
SEQUENTIAL_PYTHON_LOOPS = """def process_two_stages(arr):
    total = 0
    for x in arr:
        total += x
    count = 0
    for x in arr:
        if x > 0:
            count += 1
    return total, count
"""

SEQUENTIAL_CPP_LOOPS = """int process_two_stages(const std::vector<int>& arr) {
    int total = 0;
    for (size_t i = 0; i < arr.size(); ++i) {
        total += arr[i];
    }
    int count = 0;
    for (size_t j = 0; j < arr.size(); ++j) {
        if (arr[j] > 0) count++;
    }
    return total + count;
}
"""

SEQUENTIAL_JAVA_LOOPS = """class Processor {
    public int process(int[] arr) {
        int total = 0;
        for (int i = 0; i < arr.length; i++) {
            total += arr[i];
        }
        int count = 0;
        for (int j = 0; j < arr.length; j++) {
            if (arr[j] > 0) count++;
        }
        return total + count;
    }
}
"""

SEQUENTIAL_JS_LOOPS = """function processStages(arr) {
    let total = 0;
    for (let i = 0; i < arr.length; i++) {
        total += arr[i];
    }
    let count = 0;
    for (let j = 0; j < arr.length; j++) {
        if (arr[j] > 0) count++;
    }
    return total + count;
}
"""

# Clean Programs (No slow patterns)
CLEAN_PYTHON_PROGRAM = """def find_unique_fast(arr):
    seen = set()
    for x in arr:
        seen.add(x)
    return list(seen)
"""

CLEAN_CPP_PROGRAM = """#include <unordered_set>
#include <vector>
std::unordered_set<int> find_unique(const std::vector<int>& arr) {
    std::unordered_set<int> seen;
    for (size_t i = 0; i < arr.size(); ++i) {
        seen.insert(arr[i]);
    }
    return seen;
}
"""


# ============================================================================
# 1. Pattern Detection Unit Tests (Rules across 5 DR1 Languages)
# ============================================================================


def test_rules_detect_python_nested_loops():
    """Verify AST detection catches nested loops and reports line number."""
    patterns = detect_slow_patterns(SEEDED_PYTHON_NESTED_LOOPS, language="python")
    assert len(patterns) >= 1
    p = patterns[0]
    assert p["pattern"] == "nested_loops"
    assert p["line"] == 4  # inner for loop
    assert "potential quadratic time complexity" in p["message"]


def test_rules_detect_cpp_nested_loops():
    """Verify C++ scanner catches nested loops across lines."""
    patterns = detect_slow_patterns(SEEDED_CPP_NESTED_LOOPS, language="cpp")
    assert len(patterns) >= 1
    pattern_names = [p["pattern"] for p in patterns]
    assert "nested_loops" in pattern_names
    nested = next(p for p in patterns if p["pattern"] == "nested_loops")
    assert nested["line"] == 4  # inner loop at line 4


def test_rules_detect_java_nested_loops():
    """Verify Java scanner catches nested loops."""
    patterns = detect_slow_patterns(SEEDED_JAVA_NESTED_LOOPS, language="java")
    assert any(p["pattern"] == "nested_loops" for p in patterns)


def test_rules_detect_javascript_nested_loops():
    """Verify JavaScript scanner catches nested loops."""
    patterns = detect_slow_patterns(SEEDED_JS_NESTED_LOOPS, language="javascript")
    assert any(p["pattern"] == "nested_loops" for p in patterns)


def test_rules_detect_c_nested_loops():
    """Verify C scanner catches nested loops."""
    patterns = detect_slow_patterns(SEEDED_C_NESTED_LOOPS, language="c")
    assert any(p["pattern"] == "nested_loops" for p in patterns)


def test_rules_detect_python_repeated_string_concat():
    """Verify Python AST catches repeated string concatenation in loops."""
    patterns1 = detect_slow_patterns(SEEDED_PYTHON_STRING_CONCAT, language="python")
    assert any(p["pattern"] == "repeated_string_concat" for p in patterns1)

    patterns2 = detect_slow_patterns(SEEDED_PYTHON_STRING_CONCAT_SELF, language="python")
    assert any(p["pattern"] == "repeated_string_concat" for p in patterns2)


def test_rules_detect_javascript_repeated_string_concat():
    """Verify JavaScript scanner catches repeated string concatenation in loops."""
    patterns = detect_slow_patterns(SEEDED_JS_STRING_CONCAT, language="javascript")
    assert any(p["pattern"] == "repeated_string_concat" for p in patterns)


def test_rules_detect_cpp_repeated_string_concat():
    """Verify C++ scanner catches repeated string concatenation in loops."""
    patterns = detect_slow_patterns(SEEDED_CPP_STRING_CONCAT, language="cpp")
    assert any(p["pattern"] == "repeated_string_concat" for p in patterns)


def test_rules_detect_java_repeated_string_concat():
    """Verify Java scanner catches repeated string concatenation in loops."""
    patterns = detect_slow_patterns(SEEDED_JAVA_STRING_CONCAT, language="java")
    assert any(p["pattern"] == "repeated_string_concat" for p in patterns)


def test_rules_detect_c_repeated_string_concat():
    """Verify C scanner catches strcat in loops."""
    patterns = detect_slow_patterns(SEEDED_C_STRING_CONCAT, language="c")
    assert any(p["pattern"] == "repeated_string_concat" for p in patterns)


# ============================================================================
# 2. False-Positive Checks (Sequential Loops are NOT Nested)
# ============================================================================


def test_rules_false_positive_sequential_loops_python():
    """Verify sequential loops in Python are not reported as nested."""
    patterns = detect_slow_patterns(SEQUENTIAL_PYTHON_LOOPS, language="python")
    nested = [p for p in patterns if p["pattern"] == "nested_loops"]
    assert len(nested) == 0, f"Expected no nested loops, got {nested}"


def test_rules_false_positive_sequential_loops_cpp():
    """Verify sequential loops in C++ are not reported as nested."""
    patterns = detect_slow_patterns(SEQUENTIAL_CPP_LOOPS, language="cpp")
    nested = [p for p in patterns if p["pattern"] == "nested_loops"]
    assert len(nested) == 0, f"Expected no nested loops, got {nested}"


def test_rules_false_positive_sequential_loops_java():
    """Verify sequential loops in Java are not reported as nested."""
    patterns = detect_slow_patterns(SEQUENTIAL_JAVA_LOOPS, language="java")
    nested = [p for p in patterns if p["pattern"] == "nested_loops"]
    assert len(nested) == 0, f"Expected no nested loops, got {nested}"


def test_rules_false_positive_sequential_loops_javascript():
    """Verify sequential loops in JavaScript are not reported as nested."""
    patterns = detect_slow_patterns(SEQUENTIAL_JS_LOOPS, language="javascript")
    nested = [p for p in patterns if p["pattern"] == "nested_loops"]
    assert len(nested) == 0, f"Expected no nested loops, got {nested}"


# ============================================================================
# 3. Flow Decision Tests (Rules versus AI)
# ============================================================================


@pytest.mark.anyio
async def test_rules_seeded_nested_loops_python_fires_and_zero_llm_calls():
    """Seeded Python nested loop: rule fires, mocked LLM client called 0 times (US-67)."""
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Optimize with hash map.")

    res = await run_rules_before_ai_analysis(
        code=SEEDED_PYTHON_NESTED_LOOPS,
        language="python",
        consent=True,
        ai_enabled=True,
        client=mock_llm,
    )

    assert len(res["slow_patterns"]) >= 1
    assert any(p["pattern"] == "nested_loops" for p in res["slow_patterns"])
    assert res["ai_step"]["status"] == "skipped"
    assert res["ai_step"]["error_code"] is None
    assert res["ai_step"]["suggested_fix"] is None
    assert mock_llm.complete.call_count == 0


@pytest.mark.anyio
async def test_rules_seeded_nested_loops_cpp_fires_and_zero_llm_calls():
    """Seeded C++ nested loop: rule fires, mocked LLM client called 0 times (US-67)."""
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Optimize with hash map.")

    res = await run_rules_before_ai_analysis(
        code=SEEDED_CPP_NESTED_LOOPS,
        language="cpp",
        consent=True,
        ai_enabled=True,
        client=mock_llm,
    )

    assert len(res["slow_patterns"]) >= 1
    assert any(p["pattern"] == "nested_loops" for p in res["slow_patterns"])
    assert res["ai_step"]["status"] == "skipped"
    assert res["ai_step"]["error_code"] is None
    assert mock_llm.complete.call_count == 0


@pytest.mark.anyio
async def test_rules_seeded_string_concat_python_fires_and_zero_llm_calls():
    """Seeded Python string concatenation: rule fires, mocked LLM client called 0 times (US-67)."""
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Use list join.")

    res = await run_rules_before_ai_analysis(
        code=SEEDED_PYTHON_STRING_CONCAT,
        language="python",
        consent=True,
        ai_enabled=True,
        client=mock_llm,
    )

    assert len(res["slow_patterns"]) >= 1
    assert any(p["pattern"] == "repeated_string_concat" for p in res["slow_patterns"])
    assert res["ai_step"]["status"] == "skipped"
    assert res["ai_step"]["error_code"] is None
    assert mock_llm.complete.call_count == 0


@pytest.mark.anyio
async def test_rules_seeded_string_concat_js_fires_and_zero_llm_calls():
    """Seeded JavaScript string concatenation: rule fires, mocked LLM client called 0 times (US-67)."""
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Use array join.")

    res = await run_rules_before_ai_analysis(
        code=SEEDED_JS_STRING_CONCAT,
        language="javascript",
        consent=True,
        ai_enabled=True,
        client=mock_llm,
    )

    assert len(res["slow_patterns"]) >= 1
    assert any(p["pattern"] == "repeated_string_concat" for p in res["slow_patterns"])
    assert res["ai_step"]["status"] == "skipped"
    assert res["ai_step"]["error_code"] is None
    assert mock_llm.complete.call_count == 0


@pytest.mark.anyio
async def test_rules_clean_program_consent_true_ai_on_calls_llm_once():
    """Clean program with consent true and AI on: mocked LLM client called once (US-67)."""
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Preallocate capacity.")

    res = await run_rules_before_ai_analysis(
        code=CLEAN_PYTHON_PROGRAM,
        language="python",
        consent=True,
        ai_enabled=True,
        client=mock_llm,
    )

    assert res["slow_patterns"] == []
    assert res["ai_step"]["status"] == "ok"
    assert res["ai_step"]["suggested_fix"] == "Preallocate capacity."
    assert mock_llm.complete.call_count == 1


@pytest.mark.anyio
async def test_rules_clean_program_consent_false_ai_step_skipped_and_rules_returned():
    """Clean program with consent false: AI step skipped with CONSENT_MISSING, rule results returned (US-67, FR12)."""
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Should not be called.")

    res = await run_rules_before_ai_analysis(
        code=CLEAN_PYTHON_PROGRAM,
        language="python",
        consent=False,
        ai_enabled=True,
        client=mock_llm,
    )

    assert res["slow_patterns"] == []
    assert res["ai_step"]["status"] == "skipped"
    assert res["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert res["ai_step"]["suggested_fix"] is None
    assert mock_llm.complete.call_count == 0


@pytest.mark.anyio
async def test_rules_clean_program_ai_disabled_ai_step_skipped_and_rules_returned():
    """Clean program with AI disabled: AI step skipped with AI_DISABLED, rule results returned (US-67, FR35)."""
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Should not be called.")

    res = await run_rules_before_ai_analysis(
        code=CLEAN_PYTHON_PROGRAM,
        language="python",
        consent=True,
        ai_enabled=False,
        client=mock_llm,
    )

    assert res["slow_patterns"] == []
    assert res["ai_step"]["status"] == "skipped"
    assert res["ai_step"]["error_code"] == "AI_DISABLED"
    assert res["ai_step"]["suggested_fix"] is None
    assert mock_llm.complete.call_count == 0


# ============================================================================
# 4. Endpoint Tests on POST /v1/analyses/complexity (T-18 v2 & US-67)
# ============================================================================


def test_rules_endpoint_complexity_nested_loops_skips_ai_with_zero_llm_calls():
    """Verify POST /v1/analyses/complexity with rules_first skips AI on nested loops (US-67)."""
    payload = {
        "request_id": "req-rules-001",
        "room_id": "room-rules-01",
        "analysis_id": "ana-rules-001",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "rules_first": True,
        "code": SEEDED_PYTHON_NESTED_LOOPS,
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [4.0, 4.2]},
        ],
    }

    mock_llm_complete = AsyncMock(return_value="AI fix suggestion.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert len(data["slow_patterns"]) >= 1
    assert any(p["pattern"] == "nested_loops" for p in data["slow_patterns"])
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] is None
    assert data["ai_step"]["suggested_fix"] is None
    assert mock_llm_complete.call_count == 0


def test_rules_endpoint_complexity_string_concat_skips_ai_with_zero_llm_calls():
    """Verify POST /v1/analyses/complexity with rules_first skips AI on string concat (US-67)."""
    payload = {
        "request_id": "req-rules-002",
        "room_id": "room-rules-01",
        "analysis_id": "ana-rules-002",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "rules_first": True,
        "code": SEEDED_PYTHON_STRING_CONCAT,
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [2.0, 2.1]},
        ],
    }

    mock_llm_complete = AsyncMock(return_value="AI fix suggestion.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert len(data["slow_patterns"]) >= 1
    assert any(p["pattern"] == "repeated_string_concat" for p in data["slow_patterns"])
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] is None
    assert mock_llm_complete.call_count == 0


def test_rules_endpoint_complexity_clean_program_calls_llm_once():
    """Verify POST /v1/analyses/complexity with rules_first calls LLM on clean code (US-67)."""
    payload = {
        "request_id": "req-rules-003",
        "room_id": "room-rules-01",
        "analysis_id": "ana-rules-003",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "rules_first": True,
        "code": CLEAN_PYTHON_PROGRAM,
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [2.0, 2.1]},
        ],
    }

    mock_llm_complete = AsyncMock(return_value="Clean code recommendation.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["slow_patterns"] == []
    assert data["ai_step"]["status"] == "ok"
    assert data["ai_step"]["error_code"] is None
    assert data["ai_step"]["suggested_fix"] == "Clean code recommendation."
    assert mock_llm_complete.call_count == 1


def test_rules_endpoint_complexity_clean_program_consent_false_skips_ai():
    """Verify POST /v1/analyses/complexity with clean code and consent=False returns CONSENT_MISSING (US-67, FR12)."""
    payload = {
        "request_id": "req-rules-004",
        "room_id": "room-rules-01",
        "analysis_id": "ana-rules-004",
        "request_type": "complexity",
        "language": "python",
        "consent": False,
        "ai_enabled": True,
        "rules_first": True,
        "code": CLEAN_PYTHON_PROGRAM,
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [2.0, 2.1]},
        ],
    }

    mock_llm_complete = AsyncMock(return_value="Should not run.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["slow_patterns"] == []
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert mock_llm_complete.call_count == 0


def test_rules_endpoint_complexity_header_activation():
    """Verify X-Rules-First header triggers rules-first flow without request body field (US-67)."""
    payload = {
        "request_id": "req-rules-hdr-001",
        "room_id": "room-rules-01",
        "analysis_id": "ana-rules-hdr-001",
        "request_type": "complexity",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "code": SEEDED_CPP_NESTED_LOOPS,
        "measurements": [
            {"input_size": 10, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 20, "runtimes_ms": [4.0, 4.1]},
        ],
    }

    mock_llm_complete = AsyncMock(return_value="Should not run.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post(
            "/v1/analyses/complexity",
            json=payload,
            headers={"X-Rules-First": "true"},
        )

    assert response.status_code == 200
    data = response.json()
    assert len(data["slow_patterns"]) >= 1
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] is None
    assert mock_llm_complete.call_count == 0


def test_rules_endpoint_complexity_global_setting_activation():
    """Verify settings.RULES_FIRST_AI_AVOIDANCE activates rules-first flow globally (US-67)."""
    payload = {
        "request_id": "req-rules-cfg-001",
        "room_id": "room-rules-01",
        "analysis_id": "ana-rules-cfg-001",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": SEEDED_PYTHON_NESTED_LOOPS,
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [4.0, 4.2]},
        ],
    }

    mock_llm_complete = AsyncMock(return_value="Should not run.")
    with patch.object(settings, "RULES_FIRST_AI_AVOIDANCE", True):
        with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
            response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert len(data["slow_patterns"]) >= 1
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] is None
    assert mock_llm_complete.call_count == 0


# ============================================================================
# 5. Privacy Compliance Audit (NFR8.2)
# ============================================================================


def test_rules_privacy_no_code_in_logs(caplog):
    """Verify that seeded source code and pattern text are never emitted in service logs (NFR8.2)."""
    secret_marker = "SUPER_SECRET_USER_CANARY_RULES_NFR82"
    code_with_marker = f"# {secret_marker}\ndef foo():\n    for i in range(10):\n        for j in range(10):\n            pass\n"

    payload = {
        "request_id": "req-rules-priv-001",
        "room_id": "room-rules-priv",
        "analysis_id": "ana-rules-priv-001",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "rules_first": True,
        "code": code_with_marker,
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
        ],
    }

    with caplog.at_level(logging.DEBUG):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    assert secret_marker not in caplog.text
    assert "for j in range(10)" not in caplog.text


# ============================================================================
# 6. Review Feedback Regression Tests (US-67, PR #73)
# ============================================================================


def test_regression_rules_first_is_default_on_ordinary_complexity_requests():
    """Verify rules-first runs by default when rules_first is omitted from request payload (US-67)."""
    payload = {
        "request_id": "req-reg-default-001",
        "room_id": "room-reg-default",
        "analysis_id": "ana-reg-default-001",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        # rules_first is deliberately omitted to prove default behavior
        "code": SEEDED_PYTHON_NESTED_LOOPS,
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [4.0, 4.2]},
        ],
    }

    mock_llm_complete = AsyncMock(return_value="Should not run because rules fire.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert any(p["pattern"] == "nested_loops" for p in data["slow_patterns"])
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] is None
    assert mock_llm_complete.call_count == 0, "Ordinary complexity request must evaluate rules first by default"


def test_regression_rules_first_default_allows_clean_program_to_reach_ai():
    """Verify clean program reaches AI step with consent and AI enabled under default rules-first."""
    payload = {
        "request_id": "req-reg-clean-002",
        "room_id": "room-reg-clean",
        "analysis_id": "ana-reg-clean-002",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        # rules_first omitted: defaults to True
        "code": CLEAN_PYTHON_PROGRAM,
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [1.0, 1.1]},
        ],
    }

    mock_llm_complete = AsyncMock(return_value="Algorithm is optimal O(1).")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["slow_patterns"] == []
    assert data["ai_step"]["status"] == "ok"
    assert data["ai_step"]["suggested_fix"] == "Algorithm is optimal O(1)."
    assert mock_llm_complete.call_count == 1


def test_regression_rules_first_opt_out_reaches_ai():
    """Verify explicit rules_first: false opts out of rules avoidance and reaches AI fallback."""
    payload = {
        "request_id": "req-reg-optout-003",
        "room_id": "room-reg-optout",
        "analysis_id": "ana-reg-optout-003",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "rules_first": False,
        "code": SEEDED_PYTHON_NESTED_LOOPS,
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [4.0, 4.2]},
        ],
    }

    mock_llm_complete = AsyncMock(return_value="Optimization suggestion for nested loops.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert any(p["pattern"] == "nested_loops" for p in data["slow_patterns"])
    assert data["ai_step"]["status"] == "ok"
    assert mock_llm_complete.call_count == 1


def test_regression_python_numeric_accumulation_not_string_concat():
    """Verify numeric accumulation like total += i or count += 1 is NOT reported as repeated_string_concat."""
    code_total_accum = """def compute_total(arr):
    total = 0
    for i in arr:
        total += i
    return total
"""
    patterns = detect_slow_patterns(code_total_accum, "python")
    assert not any(p["pattern"] == "repeated_string_concat" for p in patterns), (
        f"Numeric accumulation 'total += i' must not be classified as repeated_string_concat: {patterns}"
    )

    code_counter = """def count_positives(arr):
    count = 0
    for x in arr:
        if x > 0:
            count += 1
    return count
"""
    patterns = detect_slow_patterns(code_counter, "python")
    assert not any(p["pattern"] == "repeated_string_concat" for p in patterns), (
        f"Numeric increment 'count += 1' must not be classified as repeated_string_concat: {patterns}"
    )

    code_binop = """def compute_sum(arr):
    total = 0
    for i in arr:
        total = total + i
    return total
"""
    patterns = detect_slow_patterns(code_binop, "python")
    assert not any(p["pattern"] == "repeated_string_concat" for p in patterns), (
        f"Numeric addition 'total = total + i' must not be classified as repeated_string_concat: {patterns}"
    )


def test_regression_python_string_concat_accurately_detected():
    """Verify actual string concatenation in Python loops is detected."""
    code_str_call = """def stringify(items):
    s = ""
    for item in items:
        s += str(item)
    return s
"""
    patterns = detect_slow_patterns(code_str_call, "python")
    assert any(p["pattern"] == "repeated_string_concat" for p in patterns)

    code_str_literal = """def build_csv(items):
    csv = ""
    for item in items:
        csv += "data,"
    return csv
"""
    patterns = detect_slow_patterns(code_str_literal, "python")
    assert any(p["pattern"] == "repeated_string_concat" for p in patterns)

    code_fstring = """def format_all(items):
    res = ""
    for item in items:
        res += f"item:{item}"
    return res
"""
    patterns = detect_slow_patterns(code_fstring, "python")
    assert any(p["pattern"] == "repeated_string_concat" for p in patterns)


def test_regression_c_family_sequential_unbraced_loops_no_false_positive():
    """Verify sequential unbraced loops in C/C++/Java/JS are NOT reported as nested_loops."""
    # 1. C++ sequential unbraced single line
    cpp_single_line = """void run() {
    for (int i = 0; i < n; i++) x++;
    for (int j = 0; j < n; j++) y++;
}
"""
    patterns = detect_slow_patterns(cpp_single_line, "cpp")
    assert not any(p["pattern"] == "nested_loops" for p in patterns), (
        f"Single-line sequential unbraced loops falsely reported as nested: {patterns}"
    )

    # 2. C++ sequential unbraced multiline
    cpp_multiline = """void run() {
    for (int i = 0; i < n; i++)
        x++;
    for (int j = 0; j < n; j++)
        y++;
}
"""
    patterns = detect_slow_patterns(cpp_multiline, "cpp")
    assert not any(p["pattern"] == "nested_loops" for p in patterns), (
        f"Multiline sequential unbraced loops falsely reported as nested: {patterns}"
    )

    # 3. Java sequential unbraced
    java_code = """class Solution {
    void process() {
        for (int i = 0; i < n; i++)
            x++;
        for (int j = 0; j < n; j++)
            y++;
    }
}
"""
    patterns = detect_slow_patterns(java_code, "java")
    assert not any(p["pattern"] == "nested_loops" for p in patterns), (
        f"Java sequential unbraced loops falsely reported as nested: {patterns}"
    )

    # 4. JavaScript sequential unbraced
    js_code = """function process() {
    for (let i = 0; i < n; i++)
        x++;
    for (let j = 0; j < n; j++)
        y++;
}
"""
    patterns = detect_slow_patterns(js_code, "javascript")
    assert not any(p["pattern"] == "nested_loops" for p in patterns), (
        f"JS sequential unbraced loops falsely reported as nested: {patterns}"
    )

    # 5. While sequential unbraced
    while_code = """void process() {
    while (i < n) i++;
    while (j < n) j++;
}
"""
    patterns = detect_slow_patterns(while_code, "c")
    assert not any(p["pattern"] == "nested_loops" for p in patterns), (
        f"Sequential unbraced while loops falsely reported as nested: {patterns}"
    )


def test_regression_c_family_nested_unbraced_loops_detected():
    """Verify genuinely nested unbraced loops are detected."""
    cpp_nested_unbraced = """void run() {
    for (int i = 0; i < n; i++)
        for (int j = 0; j < n; j++)
            x++;
}
"""
    patterns = detect_slow_patterns(cpp_nested_unbraced, "cpp")
    assert any(p["pattern"] == "nested_loops" for p in patterns), (
        f"Nested unbraced loops must be detected: {patterns}"
    )


def test_regression_comment_stripping_string_aware():
    """Verify comment markers and braces inside string literals do not corrupt C-family code scanning."""
    code_with_string_comment_markers = """#include <string>
void run() {
    std::string s1 = "http://example.com/api";
    std::string s2 = "/* not a comment */";
    std::string s3 = "// not a comment";
    for (int i = 0; i < n; i++) x++;
    for (int j = 0; j < n; j++) y++;
}
"""
    patterns = detect_slow_patterns(code_with_string_comment_markers, "cpp")
    assert not any(p["pattern"] == "nested_loops" for p in patterns), (
        f"Comment markers in string literals corrupted parsing: {patterns}"
    )

    code_with_braces_in_strings = """#include <string>
void run() {
    std::string braces = "{{{ }}}";
    for (int i = 0; i < n; i++) {
        x++;
    }
    for (int j = 0; j < n; j++) {
        y++;
    }
}
"""
    patterns = detect_slow_patterns(code_with_braces_in_strings, "cpp")
    assert not any(p["pattern"] == "nested_loops" for p in patterns), (
        f"Braces in string literals corrupted brace depth: {patterns}"
    )

    code_with_real_commented_loops = """void run() {
    // for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) x++;
    /* for (int i = 0; i < n; i++) {
           for (int j = 0; j < n; j++) { x++; }
       } */
    int a = 1;
}
"""
    patterns = detect_slow_patterns(code_with_real_commented_loops, "c")
    assert patterns == [], f"Loops inside actual comments must be ignored: {patterns}"


