"""Comprehensive tests for AI Request Control: Code Normalization, SHA-256 Caching, and Room Rate Limiting.

Traceability: NFR5.1, NFR5.2, NFR5.3, NFR8.2, US-22, US-56, FR32, T-18 v2 Decision 11.

Verifies:
1. Normalization across DR1 languages (Python, C++, C, Java, JavaScript):
   - Formatting-only changes (comments, extra blank lines, whitespace) yield identical cache keys (cache hit).
   - Semantic changes (identifiers, literals, operators) yield different cache keys (cache miss).
   - Python semantic indentation changes produce different cache keys.
   - Comment characters within string literals are strictly preserved.
2. Endpoint caching:
   - Subsequent calls with identical normalized code reuse cached results without invoking LLM.
   - Cache hits do not consume room rate limit quota.
3. Per-room hourly rate limiting:
   - Exceeding room quota yields skipped AI step with error_code 'ROOM_RATE_LIMITED' without crashing.
   - Quotas are strictly scoped per room; separate rooms remain unthrottled.
4. Room lifecycle cleanup (US-56, FR32):
   - DELETE /v1/rooms/{room_id}/state purges room cache entries and resets rate limits.
5. Privacy invariant (NFR5.2, NFR8.2):
   - Only 64-character SHA-256 hexadecimal digests are stored as cache keys.
   - No user source code or stderr appears in cache keys, counter metadata, or logs.
"""

import logging
import re
import sys
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.cache import (
    AnalysisCache,
    compute_cache_key,
    get_analysis_cache,
    normalize_code,
)
from app.config import settings
from app.limiter import RoomRateLimiter, get_room_rate_limiter
from app.main import app

client = TestClient(app)


# ============================================================================
# 1. Code Normalization & Cache Key Generation Tests (NFR5.2, DR1)
# ============================================================================


def test_python_normalization_comments_and_whitespace():
    """Verify Python code normalizer removes comments and collapses whitespace without altering logic."""
    code_raw = (
        "# Top level comment\n"
        "def compute(x, y):\n"
        "    # Inside function comment\n"
        "    result  =  x  *  y  # inline comment\n"
        "\n\n"
        "    return  result\n"
    )
    code_formatted = (
        "def compute(x, y):\n"
        "    result = x * y\n"
        "    return result\n"
    )
    code_different_logic = (
        "def compute(x, y):\n"
        "    result = x + y\n"
        "    return result\n"
    )

    key_raw = compute_cache_key(code_raw, "python")
    key_formatted = compute_cache_key(code_formatted, "python")
    key_different = compute_cache_key(code_different_logic, "python")

    assert key_raw == key_formatted, "Formatting/comment differences must yield identical cache keys"
    assert key_raw != key_different, "Semantic change must yield a different cache key"
    assert re.match(r"^[a-f0-9]{64}$", key_raw), "Cache key must be a valid 64-char SHA-256 hex digest"


def test_python_normalization_semantic_indentation_preserved():
    """Verify Python indentation carrying semantic block meaning changes cache key."""
    # Logic 1: return is inside loop
    code_inside = (
        "def check(items):\n"
        "    for item in items:\n"
        "        if item > 0:\n"
        "            return item\n"
    )
    # Logic 2: return is outside loop
    code_outside = (
        "def check(items):\n"
        "    for item in items:\n"
        "        if item > 0:\n"
        "            pass\n"
        "    return item\n"
    )

    key1 = compute_cache_key(code_inside, "python")
    key2 = compute_cache_key(code_outside, "python")
    assert key1 != key2, "Python indentation change altering syntax must yield different cache keys"


def test_python_normalization_string_literals_with_comment_markers_preserved():
    """Verify comments inside Python string literals are NOT stripped."""
    code1 = 'msg = "# This is not a comment"\nprint(msg)\n'
    code2 = 'msg = "# This is another string"\nprint(msg)\n'
    code3 = 'msg = "# This is not a comment" # Real comment\nprint(msg)\n'

    key1 = compute_cache_key(code1, "python")
    key2 = compute_cache_key(code2, "python")
    key3 = compute_cache_key(code3, "python")

    assert key1 != key2, "Altering string literal containing # must alter cache key"
    assert key1 == key3, "Inline comment after string literal must be stripped"


def test_python_normalization_docstrings_preserved():
    """Verify Python docstrings are strictly preserved during normalization (runtime observable via __doc__, NFR5.2, US-22)."""
    with_doc = (
        'def square(n):\n'
        '    """Compute square of n."""\n'
        '    return n * n\n'
    )
    without_doc = (
        'def square(n):\n'
        '    return n * n\n'
    )
    different_doc = (
        'def square(n):\n'
        '    """Different docstring."""\n'
        '    return n * n\n'
    )
    with_doc_reformatted = (
        '# Module level comment\n'
        'def square(n):\n'
        '    # Inside function comment\n'
        '    """Compute square of n."""\n'
        '\n\n'
        '    return n * n\n'
    )
    assigned_triple1 = 's = """hello # keep"""\nreturn s\n'
    assigned_triple2 = 's = """hello # changed"""\nreturn s\n'

    # Docstring vs no docstring must yield DIFFERENT cache keys
    assert compute_cache_key(with_doc, "python") != compute_cache_key(without_doc, "python")
    # Different docstrings must yield DIFFERENT cache keys
    assert compute_cache_key(with_doc, "python") != compute_cache_key(different_doc, "python")
    # Formatting/comment changes around the same docstring must yield IDENTICAL cache keys (cache hit)
    assert compute_cache_key(with_doc, "python") == compute_cache_key(with_doc_reformatted, "python")
    # Assigned multiline strings remain distinct
    assert compute_cache_key(assigned_triple1, "python") != compute_cache_key(assigned_triple2, "python")


def test_cache_key_differentiates_error_output_and_request_type():
    """Verify cache key incorporates both request_type and error_output where it matters (NFR5.2)."""
    code = "int a[2]; a[5] = 1;"
    k_comp = compute_cache_key(code, "cpp", request_type="complexity", error_output="")
    k_crash1 = compute_cache_key(code, "cpp", request_type="crash_explanation", error_output="heap-buffer-overflow line 1")
    k_crash2 = compute_cache_key(code, "cpp", request_type="crash_explanation", error_output="stack-buffer-overflow line 2")

    assert len({k_comp, k_crash1, k_crash2}) == 3


def test_c_cpp_normalization_comments_and_whitespace():
    """Verify C and C++ comments (// and /* */) and spacing are normalized."""
    cpp_raw = (
        "// Global comment\n"
        "int   binary_search ( int arr [ ] , int n ) {\n"
        "    /* Multi-line\n"
        "       comment */\n"
        "    int   low = 0 ; \n"
        "    int high = n - 1;\n"
        "    return   - 1 ; \n"
        "}"
    )
    cpp_formatted = (
        "int binary_search(int arr[], int n) {\n"
        "    int low = 0;\n"
        "    int high = n - 1;\n"
        "    return -1;\n"
        "}"
    )
    cpp_different = (
        "int binary_search(int arr[], int n) {\n"
        "    int low = 1;\n"
        "    int high = n - 1;\n"
        "    return -1;\n"
        "}"
    )

    key_raw = compute_cache_key(cpp_raw, "cpp")
    key_fmt = compute_cache_key(cpp_formatted, "cpp")
    key_diff = compute_cache_key(cpp_different, "cpp")

    assert key_raw == key_fmt, "C++ formatting/comment differences must yield identical cache keys"
    assert key_raw != key_diff, "C++ logic change must yield different cache key"

    # Also test for C
    assert compute_cache_key(cpp_raw, "c") == compute_cache_key(cpp_formatted, "c")


def test_c_cpp_string_literals_with_comment_markers_preserved():
    """Verify C/C++ string literals containing // or /* are preserved verbatim."""
    cpp1 = 'const char* s = "// not a comment";'
    cpp2 = 'const char* s = "/* not a comment */";'
    cpp3 = 'const char* s = "// not a comment"; // actual comment'

    key1 = compute_cache_key(cpp1, "cpp")
    key2 = compute_cache_key(cpp2, "cpp")
    key3 = compute_cache_key(cpp3, "cpp")

    assert key1 != key2
    assert key1 == key3


def test_java_and_javascript_normalization():
    """Verify Java and JavaScript normalizations with comments and template strings."""
    java1 = "public class A { /* comment */ int x = 10; // comment\n }"
    java2 = "public class A {\n    int   x = 10;\n}"
    assert compute_cache_key(java1, "java") == compute_cache_key(java2, "java")

    js1 = "const fn = () => { /* comment */ return `// template string`; };"
    js2 = "const fn = () => {\n  return `// template string`;\n}; // trailing"
    assert compute_cache_key(js1, "javascript") == compute_cache_key(js2, "javascript")


def test_javascript_asi_and_statement_boundaries_preserved():
    """Verify JavaScript line breaks affecting program semantics (ASI) are preserved (NFR5.2, US-22)."""
    # 1. Chandan's regression example: return\n x vs return x
    js_return_newline = (
        "function f() {\n"
        "    return\n"
        "    x;\n"
        "}"
    )
    js_return_same_line = (
        "function f() {\n"
        "    return x;\n"
        "}"
    )
    js_return_newline_reformatted = (
        "function f() {\n"
        "\n"
        "    // Comment before return\n"
        "    return\n"
        "    // Comment between return and x\n"
        "    x;\n"
        "\n"
        "}"
    )

    key_ret_nl = compute_cache_key(js_return_newline, "javascript")
    key_ret_same = compute_cache_key(js_return_same_line, "javascript")
    key_ret_nl_fmt = compute_cache_key(js_return_newline_reformatted, "javascript")

    assert key_ret_nl != key_ret_same, "return\\nx and return x have different semantics and must produce different cache keys"
    assert key_ret_nl == key_ret_nl_fmt, "Formatting/comment variations of return\\nx must produce identical cache keys"

    # 2. Statements without semicolons must NOT be merged into invalid syntax
    js_no_semi1 = "let a = 1\nlet b = 2"
    js_no_semi2 = "let a = 1\n\n// inline comment\nlet b = 2"
    js_no_semi_diff = "let a = 2\nlet b = 1"

    key_ns1 = compute_cache_key(js_no_semi1, "javascript")
    key_ns2 = compute_cache_key(js_no_semi2, "javascript")
    key_ns_diff = compute_cache_key(js_no_semi_diff, "javascript")

    assert key_ns1 == key_ns2, "Blank lines and comments between non-semicolon statements must yield identical cache keys"
    assert key_ns1 != key_ns_diff, "Semantic value change in non-semicolon statements must alter cache key"

    # 3. Restricted keywords: throw/break with newline
    js_throw_nl = "function g() {\n    throw\n    new Error('fail');\n}"
    js_throw_same = "function g() {\n    throw new Error('fail');\n}"
    assert compute_cache_key(js_throw_nl, "javascript") != compute_cache_key(js_throw_same, "javascript")

    js_break_nl = "while(true) {\n    break\n    loop1;\n}"
    js_break_same = "while(true) {\n    break loop1;\n}"
    assert compute_cache_key(js_break_nl, "javascript") != compute_cache_key(js_break_same, "javascript")

    # 4. Multi-line comment with newline acts as LineTerminator in JS ASI
    js_block_comment_nl = "function f() {\n    return /*\n    */ x;\n}"
    assert compute_cache_key(js_block_comment_nl, "javascript") == key_ret_nl


# ============================================================================
# 2. Endpoint Caching Behavior Tests (NFR5.2, T-18 v2)
# ============================================================================


def test_endpoint_complexity_cache_hit_avoids_llm_invocation():
    """Test that second complexity analysis with formatting change hits cache and calls LLM once."""
    room = "room-test-cache-001"
    req1 = {
        "request_id": "req-cache-1",
        "room_id": room,
        "analysis_id": "an-1",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": (
            "def slow_func(arr):\n"
            "    # Comment 1\n"
            "    for i in arr:\n"
            "        for j in arr:\n"
            "            pass\n"
        ),
        "measurements": [
            {"input_size": 10, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 20, "runtimes_ms": [4.0, 4.2]},
        ],
    }
    req2 = {
        "request_id": "req-cache-2",
        "room_id": room,
        "analysis_id": "an-2",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": (
            "def slow_func(arr):\n"
            "    \n"
            "    # Completely different comment\n"
            "    for  i  in  arr :\n"
            "        for  j  in  arr :\n"
            "            pass\n"
        ),
        "measurements": [
            {"input_size": 10, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 20, "runtimes_ms": [4.0, 4.2]},
        ],
    }

    mock_llm = AsyncMock(return_value="Optimize nested loops with a set lookup.")
    with patch("app.llm.client.LLMClient.complete", mock_llm):
        # 1. First call: Cache Miss -> LLM called
        res1 = client.post("/v1/analyses/complexity", json=req1)
        assert res1.status_code == 200
        assert res1.json()["ai_step"]["status"] == "ok"
        assert res1.json()["ai_step"]["suggested_fix"] == "Optimize nested loops with a set lookup."
        assert mock_llm.call_count == 1

        # 2. Second call (formatting-only difference): Cache Hit -> LLM NOT called again
        res2 = client.post("/v1/analyses/complexity", json=req2)
        assert res2.status_code == 200
        assert res2.json()["ai_step"]["status"] == "ok"
        assert res2.json()["ai_step"]["suggested_fix"] == "Optimize nested loops with a set lookup."
        assert mock_llm.call_count == 1, "Cache hit must NOT invoke LLM"


def test_endpoint_complexity_cache_miss_on_code_logic_change():
    """Test that modifying code logic misses cache and triggers a new LLM invocation."""
    room = "room-test-cache-002"
    req1 = {
        "request_id": "req-logic-1",
        "room_id": room,
        "analysis_id": "an-1",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "for i in arr:\n    for j in arr:\n        pass\n",
        "measurements": [
            {"input_size": 10, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 20, "runtimes_ms": [4.0, 4.2]},
        ],
    }
    req2 = {
        "request_id": "req-logic-2",
        "room_id": room,
        "analysis_id": "an-2",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "for i in arr:\n    pass\n",  # Different logic
        "measurements": [
            {"input_size": 10, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 20, "runtimes_ms": [4.0, 4.2]},
        ],
    }

    mock_llm = AsyncMock(return_value="Fix recommendation.")
    with patch("app.llm.client.LLMClient.complete", mock_llm):
        res1 = client.post("/v1/analyses/complexity", json=req1)
        assert res1.status_code == 200
        assert mock_llm.call_count == 1

        res2 = client.post("/v1/analyses/complexity", json=req2)
        assert res2.status_code == 200
        assert mock_llm.call_count == 2, "Logic change must result in cache miss and 2nd LLM call"


def test_endpoint_crash_explanation_caching():
    """Test caching on POST /v1/analyses/crash-explanation."""
    room = "room-test-crash-001"
    req1 = {
        "request_id": "req-crash-1",
        "room_id": room,
        "analysis_id": "an-crash-1",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": "==123==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x1 at main.cpp:42",
        "code_snippet": "int main() {\n  // comment\n  int arr[5];\n  arr[10] = 1;\n}\n",
    }
    req2 = {
        "request_id": "req-crash-2",
        "room_id": room,
        "analysis_id": "an-crash-2",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": "==123==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x1 at main.cpp:42",
        "code_snippet": "int main() {\n  /* different comment */\n  int  arr [ 5 ] ;\n  arr [ 10 ]  =  1 ;\n}\n",
    }

    mock_llm = AsyncMock(return_value="Array index 10 exceeds buffer size 5 at line 42.")
    with patch("app.llm.client.LLMClient.complete", mock_llm):
        res1 = client.post("/v1/analyses/crash-explanation", json=req1)
        assert res1.status_code == 200
        assert res1.json()["ai_step"]["status"] == "ok"
        assert mock_llm.call_count == 1

        res2 = client.post("/v1/analyses/crash-explanation", json=req2)
        assert res2.status_code == 200
        assert res2.json()["ai_step"]["status"] == "ok"
        assert mock_llm.call_count == 1, "Identical normalized crash report must be served from cache"


# ============================================================================
# 3. Room Hourly Rate Limiting Tests (NFR5.3, US-22)
# ============================================================================


def test_room_hourly_rate_limiting_enforcement():
    """Verify that requests exceeding room rate limit return skipped AI step ROOM_RATE_LIMITED without crashing."""
    room_throttled = "room-throttle-test"
    room_other = "room-other-test"

    # Set rate limit to 2 calls per hour for testing
    with patch.object(settings, "ROOM_HOURLY_LIMIT", 2):
        mock_llm = AsyncMock(return_value="Mocked AI suggestion.")
        with patch("app.llm.client.LLMClient.complete", mock_llm):
            # Call 1 on room_throttled: allowed
            r1 = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "r-1",
                    "room_id": room_throttled,
                    "analysis_id": "a-1",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "x = 1\n",
                },
            )
            assert r1.status_code == 200
            assert r1.json()["ai_step"]["status"] == "ok"

            # Call 2 on room_throttled: allowed (reaches limit)
            r2 = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "r-2",
                    "room_id": room_throttled,
                    "analysis_id": "a-2",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "x = 2\n",
                },
            )
            assert r2.status_code == 200
            assert r2.json()["ai_step"]["status"] == "ok"

            # Call 3 on room_throttled: EXCEEDS LIMIT -> skipped step with ROOM_RATE_LIMITED
            r3 = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "r-3",
                    "room_id": room_throttled,
                    "analysis_id": "a-3",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "x = 3\n",
                },
            )
            assert r3.status_code == 200, "Must return HTTP 200 envelope with skipped AI step (not crash)"
            data3 = r3.json()
            assert data3["ai_step"]["status"] == "skipped"
            assert data3["ai_step"]["error_code"] == "ROOM_RATE_LIMITED"
            assert data3["ai_step"]["suggested_fix"] is None
            assert mock_llm.call_count == 2, "Throttled request must NOT reach LLM"

            # Call on room_other: must NOT be throttled (independent room quota)
            r_other = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "r-other",
                    "room_id": room_other,
                    "analysis_id": "a-other",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "x = 100\n",
                },
            )
            assert r_other.status_code == 200
            assert r_other.json()["ai_step"]["status"] == "ok"
            assert mock_llm.call_count == 3


def test_cache_hit_does_not_consume_rate_limit_quota():
    """Verify that cache hits do not increment the room rate limiter."""
    room = "room-cache-no-quota"
    with patch.object(settings, "ROOM_HOURLY_LIMIT", 1):
        mock_llm = AsyncMock(return_value="Optimization fix.")
        with patch("app.llm.client.LLMClient.complete", mock_llm):
            code = "def f():\n    return 42\n"

            # 1. First call uses quota
            r1 = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "r-1",
                    "room_id": room,
                    "analysis_id": "a-1",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": code,
                },
            )
            assert r1.json()["ai_step"]["status"] == "ok"
            assert mock_llm.call_count == 1
            assert get_room_rate_limiter().get_count(room) == 1

            # 2. Second call with formatting-only variation: cache hit -> allowed even though limit is 1!
            r2 = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "r-2",
                    "room_id": room,
                    "analysis_id": "a-2",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "def f():\n    # Just a comment\n\n    return   42\n",
                },
            )
            assert r2.json()["ai_step"]["status"] == "ok"
            assert mock_llm.call_count == 1, "Cache hit must not call LLM"
            assert get_room_rate_limiter().get_count(room) == 1, "Cache hit must not increment rate counter"


def test_control_check_precedence_consent_ai_switch_cache_rate_limit():
    """Verify gate precedence: Consent -> Host AI Switch -> Cache -> Room Rate Limit."""
    room = "room-precedence"
    with patch.object(settings, "ROOM_HOURLY_LIMIT", 1):
        mock_llm = AsyncMock(return_value="Cached fix.")
        with patch("app.llm.client.LLMClient.complete", mock_llm):
            # Exhaust room limit with cached_code
            client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "p-0",
                    "room_id": room,
                    "analysis_id": "a-0",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "x = 1\n",
                },
            )
            assert mock_llm.call_count == 1

            # 1. Over-limit room with consent=False -> CONSENT_MISSING (checked before limit)
            r_no_consent = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "p-1",
                    "room_id": room,
                    "analysis_id": "a-1",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": False,
                    "ai_enabled": True,
                    "code": "y = 2\n",
                },
            )
            assert r_no_consent.json()["ai_step"]["error_code"] == "CONSENT_MISSING"

            # 2. Over-limit room with ai_enabled=False -> AI_DISABLED (checked before limit)
            r_no_ai = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "p-2",
                    "room_id": room,
                    "analysis_id": "a-2",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": False,
                    "code": "y = 2\n",
                },
            )
            assert r_no_ai.json()["ai_step"]["error_code"] == "AI_DISABLED"

            # 3. Over-limit room with uncached code -> ROOM_RATE_LIMITED
            r_limited = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "p-3",
                    "room_id": room,
                    "analysis_id": "a-3",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "y = 2\n",
                },
            )
            assert r_limited.json()["ai_step"]["error_code"] == "ROOM_RATE_LIMITED"
            assert mock_llm.call_count == 1


def test_explain_endpoint_caching_and_rate_limiting():
    """Verify POST /v1/explain participates in normalized code caching and room rate limiting."""
    room = "room-explain-control"
    with patch.object(settings, "ROOM_HOURLY_LIMIT", 1):
        mock_llm = AsyncMock(return_value="Division by zero caused the crash.")
        with patch("app.llm.client.LLMClient.complete", mock_llm):
            # 1. First request succeeds & populates cache
            r1 = client.post(
                "/v1/explain",
                json={
                    "request_id": "ex-1",
                    "room_id": room,
                    "request_type": "explanation",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "x = 1 / 0\n",
                    "stderr": "ZeroDivisionError: division by zero",
                    "exit_code": 1,
                    "execution_time_ms": 10.0,
                    "peak_memory_mb": 5.0,
                },
            )
            assert r1.status_code == 200
            assert r1.json()["ai_step"]["status"] == "ok"
            assert mock_llm.call_count == 1

            # 2. Formatting change hits cache
            r2 = client.post(
                "/v1/explain",
                json={
                    "request_id": "ex-2",
                    "room_id": room,
                    "request_type": "explanation",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "x  =  1 / 0  # comment\n",
                    "stderr": "ZeroDivisionError: division by zero",
                    "exit_code": 1,
                    "execution_time_ms": 10.0,
                    "peak_memory_mb": 5.0,
                },
            )
            assert r2.status_code == 200
            assert r2.json()["ai_step"]["status"] == "ok"
            assert mock_llm.call_count == 1

            # 3. Different error output misses cache and hits rate limit (limit=1)
            r3 = client.post(
                "/v1/explain",
                json={
                    "request_id": "ex-3",
                    "room_id": room,
                    "request_type": "explanation",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "y = int('abc')\n",
                    "stderr": "ValueError: invalid literal",
                    "exit_code": 1,
                    "execution_time_ms": 10.0,
                    "peak_memory_mb": 5.0,
                },
            )
            assert r3.status_code == 200
            assert r3.json()["ai_step"]["status"] == "skipped"
            assert r3.json()["ai_step"]["error_code"] == "ROOM_RATE_LIMITED"
            assert mock_llm.call_count == 1


# ============================================================================
# 4. Room Lifecycle Cleanup & No Bare AI Routes Tests (US-56, FR32, NFR5.1)
# ============================================================================


def test_no_bare_ai_routes_exposed():
    """Verify NFR5.1: no route exposes a bare AI prompt/completion call outside an analysis flow."""
    paths = {route.path for route in app.routes}
    forbidden_bare_paths = {"/v1/llm", "/v1/complete", "/v1/chat", "/v1/prompt", "/v1/generate"}
    assert paths.isdisjoint(forbidden_bare_paths)
    # All analysis routes require structured request_type / analysis fields
    assert "/v1/analyses/complexity" in paths
    assert "/v1/analyses/crash-explanation" in paths
    assert "/v1/explain" in paths
    assert "/v1/rooms/{room_id}/state" in paths


def test_delete_room_state_clears_cache_and_resets_rate_limiter():
    """Verify DELETE /v1/rooms/{room_id}/state clears BOTH room cache entries and rate limit history."""
    room = "room-cleanup-target"
    with patch.object(settings, "ROOM_HOURLY_LIMIT", 1):
        mock_llm = AsyncMock(return_value="Fix.")
        with patch("app.llm.client.LLMClient.complete", mock_llm):
            # 1. Exhaust limit & populate cache with a = 1
            r1 = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "r-1",
                    "room_id": room,
                    "analysis_id": "a-1",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "a = 1\n",
                },
            )
            assert r1.json()["ai_step"]["status"] == "ok"
            assert mock_llm.call_count == 1
            assert get_room_rate_limiter().get_count(room) == 1
            assert len(get_analysis_cache().get_all_keys()) == 1

            # 2. Second request with new code b = 2 is throttled
            r_throttled = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "r-2",
                    "room_id": room,
                    "analysis_id": "a-2",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "b = 2\n",
                },
            )
            assert r_throttled.json()["ai_step"]["error_code"] == "ROOM_RATE_LIMITED"

            # 3. Call DELETE /v1/rooms/{room_id}/state
            del_res = client.delete(f"/v1/rooms/{room}/state")
            assert del_res.status_code == 200
            assert del_res.json() == {"room_id": room, "status": "cleared"}

            # Both rate limit count AND cache entries for this room must be dropped
            assert get_room_rate_limiter().get_count(room) == 0
            assert len(get_analysis_cache().get_all_keys()) == 0

            # 4. Re-sending original code `a = 1` must now miss cache (call_count increments to 2)
            r_after = client.post(
                "/v1/analyses/complexity",
                json={
                    "request_id": "r-3",
                    "room_id": room,
                    "analysis_id": "a-3",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "a = 1\n",
                },
            )
            assert r_after.status_code == 200
            assert r_after.json()["ai_step"]["status"] == "ok"
            assert mock_llm.call_count == 2, "Deleted room cache entry must trigger fresh LLM call"


# ============================================================================
# 5. Privacy Invariant Tests (NFR5.2, NFR8.2)
# ============================================================================


def test_cache_stores_strictly_sha256_digests_only():
    """Verify cache keys strictly match 64-character hex format and never contain raw code."""
    cache = get_analysis_cache()
    canary_code = "SECRET_CANARY_USER_CODE_XYZ_12345"
    key = compute_cache_key(canary_code, "python")

    cache.set(key, {"suggested_fix": "Fix"})
    stored_keys = cache.get_all_keys()

    for k in stored_keys:
        assert re.match(r"^[a-f0-9]{64}$", k), f"Stored key '{k}' must be pure SHA-256 hex"
        assert canary_code not in k, "Canary user code must never appear in cache key"


def test_privacy_audit_no_code_in_logs_during_caching_and_throttling(caplog):
    """Verify logs contain NO user code or stderr across cache hits, misses, or throttles (NFR8.2)."""
    canary = "CANARY_SECRET_TOKEN_DO_NOT_LOG"
    room = "room-privacy-audit"

    mock_llm = AsyncMock(return_value="Suggested fix.")
    with caplog.at_level(logging.DEBUG):
        with patch.object(settings, "ROOM_HOURLY_LIMIT", 1):
            with patch("app.llm.client.LLMClient.complete", mock_llm):
                # 1. Miss
                client.post(
                    "/v1/analyses/complexity",
                    json={
                        "request_id": "p-1",
                        "room_id": room,
                        "analysis_id": "a-1",
                        "request_type": "complexity",
                        "language": "python",
                        "consent": True,
                        "ai_enabled": True,
                        "code": f"code = '{canary}'\n",
                    },
                )
                # 2. Throttled
                client.post(
                    "/v1/analyses/complexity",
                    json={
                        "request_id": "p-2",
                        "room_id": room,
                        "analysis_id": "a-2",
                        "request_type": "complexity",
                        "language": "python",
                        "consent": True,
                        "ai_enabled": True,
                        "code": f"other = '{canary}'\n",
                    },
                )
                # 3. Clear room state
                client.delete(f"/v1/rooms/{room}/state")

    captured_logs = caplog.text
    assert canary not in captured_logs, "User code canary was leaked to logs"


# ============================================================================
# 6. Chandan Review Regression Tests (PR #103, US-22)
# ============================================================================


def test_c_cpp_preprocessor_boundaries_preserved():
    """Verify C/C++ preprocessor directives preserve line boundaries and continuation lines (US-22)."""
    # 1. Chandan's regression example: #define X 1\nX vs #define X 1 X
    code_directive_then_code = "#define X 1\nX"
    code_directive_single_line = "#define X 1 X"
    code_directive_continuation = "#define X \\\n    1\nX"
    code_directive_continuation_spaces = "#define X    \\\n        1\nX"
    code_directive_reformatted = "#   define   X    1  // comment\n    X"

    key_two_lines = compute_cache_key(code_directive_then_code, "cpp")
    key_single_line = compute_cache_key(code_directive_single_line, "cpp")
    key_cont = compute_cache_key(code_directive_continuation, "cpp")
    key_cont_spaces = compute_cache_key(code_directive_continuation_spaces, "cpp")
    key_reformatted = compute_cache_key(code_directive_reformatted, "cpp")

    assert key_two_lines != key_single_line, (
        "#define X 1\\nX and #define X 1 X have different semantics and must produce different cache keys"
    )
    assert key_two_lines == key_cont, (
        "Preprocessor line continuation #define X \\\\\\n1 must normalize identically to #define X 1\\nX"
    )
    assert key_two_lines == key_cont_spaces, (
        "Whitespace differences in continuation lines must produce identical cache keys"
    )
    assert key_two_lines == key_reformatted, (
        "Indentation and comment variations in directive must produce identical cache keys"
    )

    # 2. Multiple preprocessor directives
    multi_directives = "#include <iostream>\n#include <vector>\nint main() { return 0; }"
    multi_directives_single_line = "#include <iostream> #include <vector>\nint main() { return 0; }"
    multi_directives_blank_lines = "#include <iostream>\n\n\n#include <vector>\n\nint main() { return 0; }"

    key_multi = compute_cache_key(multi_directives, "cpp")
    key_multi_single = compute_cache_key(multi_directives_single_line, "cpp")
    key_multi_blank = compute_cache_key(multi_directives_blank_lines, "cpp")

    assert key_multi != key_multi_single, (
        "Multiple directives on separate lines must not collapse to single line"
    )
    assert key_multi == key_multi_blank, (
        "Extra blank lines between directives must normalize identically"
    )


def test_c_cpp_operator_token_boundaries_preserved():
    """Verify operator token boundaries are preserved and not merged across adjacent operators (US-22)."""
    # 1. Chandan's regression example: a + ++b vs a++ + b
    cpp_binary_then_pre_inc = "a + ++b"
    cpp_post_inc_then_binary = "a++ + b"
    cpp_binary_then_unary = "a + +b"
    cpp_post_inc_then_pre_inc = "a++ + ++b"
    cpp_all_binary_unary = "a + + + b"

    key_b_pre = compute_cache_key(cpp_binary_then_pre_inc, "cpp")
    key_post_b = compute_cache_key(cpp_post_inc_then_binary, "cpp")
    key_b_unary = compute_cache_key(cpp_binary_then_unary, "cpp")
    key_post_pre = compute_cache_key(cpp_post_inc_then_pre_inc, "cpp")
    key_all = compute_cache_key(cpp_all_binary_unary, "cpp")

    assert key_b_pre != key_post_b, "a + ++b and a++ + b must produce different cache keys"
    assert key_b_pre != key_b_unary, "a + ++b and a + +b must produce different cache keys"
    assert key_post_b != key_b_unary, "a++ + b and a + +b must produce different cache keys"
    assert key_post_pre != key_b_pre, "a++ + ++b and a + ++b must produce different cache keys"
    assert key_b_unary != key_all, "a + +b and a + + + b must produce different cache keys"

    # Formatting variations must still hit cache
    assert compute_cache_key("a   +   ++  b", "cpp") == key_b_pre
    assert compute_cache_key("a++   +   b", "cpp") == key_post_b
    assert compute_cache_key("a   +   +  b", "cpp") == key_b_unary

    # 2. Subtraction and decrement operators
    cpp_sub_pre_dec = "a - --b"
    cpp_post_dec_sub = "a-- - b"
    cpp_sub_unary_neg = "a - -b"

    key_sub_pre = compute_cache_key(cpp_sub_pre_dec, "cpp")
    key_post_sub = compute_cache_key(cpp_post_dec_sub, "cpp")
    key_sub_neg = compute_cache_key(cpp_sub_unary_neg, "cpp")

    assert key_sub_pre != key_post_sub, "a - --b and a-- - b must produce different cache keys"
    assert key_sub_pre != key_sub_neg, "a - --b and a - -b must produce different cache keys"
    assert compute_cache_key("a   -   --  b", "cpp") == key_sub_pre

    # 3. Pointer dereference and increment
    key_ptr_pre1 = compute_cache_key("* ++p", "cpp")
    key_ptr_pre2 = compute_cache_key("*++p", "cpp")
    key_ptr_add = compute_cache_key("* + +p", "cpp")

    assert key_ptr_pre1 == key_ptr_pre2, "* ++p and *++p must normalize identically"
    assert key_ptr_pre1 != key_ptr_add, "* ++p and * + +p must produce different cache keys"

    # 4. Bitwise AND vs Logical AND
    assert compute_cache_key("& &x", "cpp") != compute_cache_key("&& x", "cpp")

    # 5. Shift vs comparison
    assert compute_cache_key("a < < b", "cpp") != compute_cache_key("a << b", "cpp")
    assert compute_cache_key("a > > b", "cpp") != compute_cache_key("a >> b", "cpp")

    # 6. Assignment vs equality
    assert compute_cache_key("a = = b", "cpp") != compute_cache_key("a == b", "cpp")
    assert compute_cache_key("a + = b", "cpp") != compute_cache_key("a += b", "cpp")


def test_comment_removal_aware_of_raw_strings_text_blocks_and_regex():
    """Verify comment-like content inside C++ raw strings, Java text blocks, and JS regex is preserved (US-22)."""
    # 1. C++ raw string literal
    cpp_raw1 = 'const char* s = R"(// not a comment /* still not */ "quotes")";'
    cpp_raw2 = 'const char* s = R"(// CHANGED /* still not */ "quotes")";'
    cpp_raw1_reformatted = 'const char*   s  =  R"(// not a comment /* still not */ "quotes")"  ; // trailing'

    key_cpp_raw1 = compute_cache_key(cpp_raw1, "cpp")
    key_cpp_raw2 = compute_cache_key(cpp_raw2, "cpp")
    key_cpp_raw1_fmt = compute_cache_key(cpp_raw1_reformatted, "cpp")

    assert key_cpp_raw1 != key_cpp_raw2, "Changing comment-like text inside C++ raw string must change cache key"
    assert key_cpp_raw1 == key_cpp_raw1_fmt, "Reformatting outside C++ raw string must yield identical cache key"

    # 2. Java text block (triple double quotes)
    java_block1 = (
        'String block = """\n'
        '    // not a comment\n'
        '    /* nor is this */\n'
        '    int x = 10;\n'
        '""";'
    )
    java_block2 = (
        'String block = """\n'
        '    // CHANGED\n'
        '    /* nor is this */\n'
        '    int x = 10;\n'
        '""";'
    )
    java_block1_reformatted = (
        'String   block   =   """\n'
        '    // not a comment\n'
        '    /* nor is this */\n'
        '    int x = 10;\n'
        '"""  ;  // trailing comment'
    )

    key_java1 = compute_cache_key(java_block1, "java")
    key_java2 = compute_cache_key(java_block2, "java")
    key_java1_fmt = compute_cache_key(java_block1_reformatted, "java")

    assert key_java1 != key_java2, "Changing comment-like text inside Java text block must change cache key"
    assert key_java1 == key_java1_fmt, "Reformatting outside Java text block must yield identical cache key"

    # 3. JavaScript regular expression literals with [//] and [/*]
    js_regex1 = "const re = /[//]/;"
    js_regex2 = "const re = /[/*]/;"
    js_regex1_reformatted = "const   re  =  /[//]/  ; // trailing comment"
    js_regex_flags1 = "const re = /[a-z]/g;"
    js_regex_flags2 = "const re = /[a-z]/i;"

    key_js_reg1 = compute_cache_key(js_regex1, "javascript")
    key_js_reg2 = compute_cache_key(js_regex2, "javascript")
    key_js_reg1_fmt = compute_cache_key(js_regex1_reformatted, "javascript")
    key_js_flags1 = compute_cache_key(js_regex_flags1, "javascript")
    key_js_flags2 = compute_cache_key(js_regex_flags2, "javascript")

    assert key_js_reg1 != key_js_reg2, (
        "/[//]/ and /[/*]/ must produce different cache keys"
    )
    assert key_js_reg1 == key_js_reg1_fmt, (
        "Reformatting outside JS regex literal must produce identical cache key"
    )
    assert key_js_flags1 != key_js_flags2, (
        "Different regex flags must produce different cache keys"
    )


def test_sanitizer_output_differentiates_crash_explanation_cache_key():
    """Verify different sanitizer outputs produce different cache keys even with identical error_type and line (US-22)."""
    code = "int val = buffer[index];"
    err_sig = "heap-buffer-overflow:14"

    sanitizer_output_read = (
        "==4210==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000010\n"
        "READ of size 4 at 0x602000000010 thread T0\n"
        "    #0 0x401233 in process /workspace/solution.cpp:14"
    )
    sanitizer_output_write = (
        "==4210==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000050\n"
        "WRITE of size 8 at 0x602000000050 thread T1\n"
        "    #0 0x401233 in process /workspace/solution.cpp:14"
    )

    key_read = compute_cache_key(
        code=code,
        language="cpp",
        request_type="crash_explanation",
        error_signature=err_sig,
        error_output=sanitizer_output_read,
    )
    key_write = compute_cache_key(
        code=code,
        language="cpp",
        request_type="crash_explanation",
        error_signature=err_sig,
        error_output=sanitizer_output_write,
    )

    assert key_read != key_write, (
        "Different sanitizer outputs with the same error type and line must produce DIFFERENT cache keys"
    )

    # Endpoint verification: different sanitizer output triggers distinct LLM call
    room = "room-sanitizer-diff-001"
    req_read = {
        "request_id": "req-crash-1",
        "room_id": room,
        "analysis_id": "an-c1",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": sanitizer_output_read,
        "code_snippet": code,
    }
    req_write = {
        "request_id": "req-crash-2",
        "room_id": room,
        "analysis_id": "an-c2",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": sanitizer_output_write,
        "code_snippet": code,
    }

    mock_llm = AsyncMock(return_value="Crash caused by out of bounds buffer access.")
    with patch("app.llm.client.LLMClient.complete", mock_llm):
        res1 = client.post("/v1/analyses/crash-explanation", json=req_read)
        assert res1.status_code == 200
        assert res1.json()["ai_step"]["status"] == "ok"
        assert mock_llm.call_count == 1

        # Request 2 has identical error type & line (heap-buffer-overflow:14), but different sanitizer output:
        # Must NOT hit the cache from Request 1! Must make a second LLM call!
        res2 = client.post("/v1/analyses/crash-explanation", json=req_write)
        assert res2.status_code == 200
        assert res2.json()["ai_step"]["status"] == "ok"
        assert mock_llm.call_count == 2, (
            "Different sanitizer output must NOT reuse the previous crash explanation cache entry"
        )

        # Repeating Request 1 must hit cache and NOT invoke LLM a 3rd time
        res1_repeat = client.post("/v1/analyses/crash-explanation", json=req_read)
        assert res1_repeat.status_code == 200
        assert res1_repeat.json()["ai_step"]["status"] == "ok"
        assert mock_llm.call_count == 2, "Identical request must hit cache"


def test_atomic_rate_limiter_check_and_reserve_concurrency():
    """Verify RoomRateLimiter.check_and_reserve operates atomically under high concurrency (US-22)."""
    import concurrent.futures

    limiter = RoomRateLimiter(window_seconds=3600.0)
    room = "room-concurrency-test"
    quota = 5
    num_threads = 50

    results: list[bool] = []

    def attempt_reserve() -> bool:
        return limiter.check_and_reserve(room, limit=quota)

    with concurrent.futures.ThreadPoolExecutor(max_workers=num_threads) as executor:
        futures = [executor.submit(attempt_reserve) for _ in range(num_threads)]
        for f in concurrent.futures.as_completed(futures):
            results.append(f.result())

    success_count = sum(1 for r in results if r is True)
    rejected_count = sum(1 for r in results if r is False)

    assert success_count == quota, f"Expected exactly {quota} reservations, got {success_count}"
    assert rejected_count == num_threads - quota, f"Expected {num_threads - quota} rejections, got {rejected_count}"
    assert limiter.get_count(room) == quota, f"Recorded count in limiter must be {quota}"


def test_endpoint_concurrency_at_quota_boundary():
    """Verify concurrent requests at quota boundary return HTTP 200 with skipped ROOM_RATE_LIMITED (US-22)."""
    import concurrent.futures

    room = "room-endpoint-quota-boundary"
    quota = 3
    num_requests = 12

    # Clear room state
    client.delete(f"/v1/rooms/{room}/state")

    mock_llm = AsyncMock(return_value="Suggested optimization fix.")

    def send_request(idx: int) -> dict:
        req = {
            "request_id": f"req-concurrent-{idx}",
            "room_id": room,
            "analysis_id": f"an-concurrent-{idx}",
            "request_type": "complexity",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "code": f"def func_{idx}():\n    for i in range(10):\n        for j in range(10):\n            x = {idx}\n",
            "measurements": [
                {"input_size": 10, "runtimes_ms": [1.0, 1.1]},
                {"input_size": 20, "runtimes_ms": [4.0, 4.2]},
            ],
        }
        res = client.post("/v1/analyses/complexity", json=req)
        return {"status_code": res.status_code, "body": res.json()}

    with patch.object(settings, "ROOM_HOURLY_LIMIT", quota):
        with patch("app.llm.client.LLMClient.complete", mock_llm):
            with concurrent.futures.ThreadPoolExecutor(max_workers=num_requests) as executor:
                futures = [executor.submit(send_request, i) for i in range(num_requests)]
                responses = [f.result() for f in concurrent.futures.as_completed(futures)]

    # All requests must return HTTP 200
    for r in responses:
        assert r["status_code"] == 200, f"Expected HTTP 200, got {r['status_code']}"

    ok_responses = [r for r in responses if r["body"]["ai_step"]["status"] == "ok"]
    skipped_responses = [r for r in responses if r["body"]["ai_step"]["status"] == "skipped"]

    assert len(ok_responses) == quota, (
        f"Expected exactly {quota} successful AI steps, got {len(ok_responses)}"
    )
    assert len(skipped_responses) == num_requests - quota, (
        f"Expected {num_requests - quota} skipped AI steps, got {len(skipped_responses)}"
    )

    for r in skipped_responses:
        assert r["body"]["ai_step"]["error_code"] == "ROOM_RATE_LIMITED", (
            f"Expected error_code ROOM_RATE_LIMITED, got {r['body']['ai_step']['error_code']}"
        )

    # Verify limiter count matches quota
    assert get_room_rate_limiter().get_count(room) == quota
