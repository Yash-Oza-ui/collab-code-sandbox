"""Tests for C/C++ crash explanation flow (US-74, Issue #187, FR39.3, FR40.2, DR5, T-18 v2 §16.4).

Verifies:
- Successful AI explanation and suggested fix follow the exact response schema.
- Programmatic 3-sentence limit enforcement (FR13.1).
- Dedicated prompt construction: contains deterministic sanitizer summary and localized code ONLY.
- Privacy & canary isolation: unique marker outside the relevant crash section never appears in prompt.
- Raw sanitizer output and container metadata (exit_code, failure_kind, etc.) are excluded from prompt.
- rules_first=False behavior: code containing loops/slow-patterns does NOT get intercepted by rules-first.
- Consent missing -> status: "skipped", error_code: "CONSENT_MISSING", 0 model calls, deterministic summary preserved.
- AI disabled -> status: "skipped", error_code: "AI_DISABLED", 0 model calls, deterministic summary preserved.
- Cost cap reached -> status: "skipped", error_code: "COST_CAP_REACHED", 0 model calls, deterministic summary preserved.
- Cache hit -> 0 repeated paid calls, cached explanation and fix preserved.
- Unsupported language (e.g. Python, Java, JS) -> HTTP 422 UNSUPPORTED_LANGUAGE, 0 model calls.
- C and C++ languages are fully supported.
"""

from unittest.mock import AsyncMock, patch
from fastapi.testclient import TestClient
import pytest

from app.cache import get_analysis_cache
from app.config import settings
from app.counters import clear_room, record_model_call
from app.llm.explainer import split_sentences
from app.main import app
from app.rules.patterns import detect_slow_patterns

client = TestClient(app)

SAMPLE_ASAN_CRASH = """
==4210==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000014 at pc 0x000000401233 bp 0x7ffd0123 sp 0x7ffd0120
READ of size 4 at 0x602000000014 thread T0
    #0 0x401233 in process_buffer /workspace/solution.cpp:14
    #1 0x401450 in main /workspace/solution.cpp:28
"""

SAMPLE_UBSAN_CRASH = """
/sandbox/code/solution.c:20:12: runtime error: shift exponent 32 is too large for 32-bit type 'int'
"""


@pytest.fixture(autouse=True)
def clean_test_cache():
    """Clear memory caches and room counters between test runs."""
    get_analysis_cache().clear()


# ============================================================================
# 1. Successful Explanation and Fix (Schema & Sentence Limit)
# ============================================================================

def test_crash_explanation_success_with_fix():
    """Verify successful crash explanation returns expected schema with explanation and fix."""
    room_id = "room-us74-success-01"
    clear_room(room_id)

    code = "int val = buffer[index];\nreturn val * 2;\n"
    llm_mock_text = (
        "Explanation: The program attempted to read 4 bytes past the end of the allocated buffer at line 14. "
        "Ensure the index variable is strictly less than the allocated buffer size.\n"
        "Suggested Fix: Add a boundary check: if (index >= buffer_size) return -1;"
    )

    mock_complete = AsyncMock(return_value=llm_mock_text)

    payload = {
        "request_id": "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d",
        "room_id": room_id,
        "analysis_id": "6b7c8d9e-0f1a-2b3c-4d5e-6f7a8b9c0d1e",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": SAMPLE_ASAN_CRASH,
        "code_snippet": code,
    }

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp = client.post("/v1/analyses/crash-explanation", json=payload)

    assert resp.status_code == 200
    data = resp.json()
    assert data["request_id"] == "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d"
    assert data["analysis_id"] == "6b7c8d9e-0f1a-2b3c-4d5e-6f7a8b9c0d1e"
    assert data["task"] == "crash_explanation"
    assert data["error_type"] == "heap-buffer-overflow"
    assert data["line_number"] == 14
    assert data["summary"] == "Detected heap-buffer-overflow at line 14."
    assert data["ai_step"]["status"] == "ok"
    assert data["ai_step"]["error_code"] is None
    assert (
        data["ai_step"]["explanation"]
        == "The program attempted to read 4 bytes past the end of the allocated buffer at line 14. "
        "Ensure the index variable is strictly less than the allocated buffer size."
    )
    assert data["ai_step"]["suggested_fix"] == "Add a boundary check: if (index >= buffer_size) return -1;"
    assert mock_complete.call_count == 1


def test_crash_explanation_enforces_three_sentence_limit():
    """Verify that a verbose LLM response is programmatically capped at at most 3 sentences (FR13.1)."""
    room_id = "room-us74-sentences-01"
    clear_room(room_id)

    code = "int val = buffer[index];"
    llm_verbose_text = (
        "Explanation: First sentence explains the buffer overflow on line 14. "
        "Second sentence provides technical context regarding heap boundaries. "
        "Third sentence details the risk of memory corruption. "
        "Fourth sentence should be trimmed away. "
        "Fifth sentence must never appear in the final response.\n"
        "Fix: if (index >= size) return;"
    )

    mock_complete = AsyncMock(return_value=llm_verbose_text)

    payload = {
        "request_id": "req-sentences-01",
        "room_id": room_id,
        "analysis_id": "ana-sentences-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": SAMPLE_ASAN_CRASH,
        "code_snippet": code,
    }

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp = client.post("/v1/analyses/crash-explanation", json=payload)

    assert resp.status_code == 200
    data = resp.json()
    assert data["ai_step"]["status"] == "ok"

    explanation = data["ai_step"]["explanation"]
    sentences = split_sentences(explanation)
    assert len(sentences) == 3
    assert "First sentence explains" in explanation
    assert "Third sentence details" in explanation
    assert "Fourth sentence" not in explanation
    assert "Fifth sentence" not in explanation
    assert data["ai_step"]["suggested_fix"] == "if (index >= size) return;"


# ============================================================================
# 2. Prompt Scope, Data Minimization, and Canary Marker Isolation
# ============================================================================

def test_crash_explanation_prompt_contains_summary_and_relevant_code_only():
    """Verify prompt contains sanitizer summary and localized snippet, without raw ASan output or canary leakage."""
    room_id = "room-us74-canary-01"
    clear_room(room_id)

    # 50 lines with unique canary markers far from the crash line (line 25)
    lines = [f"// line {i}: normal code execution" for i in range(1, 51)]
    lines[0] = "// SECRET_TOP_CANARY_MARKER_DO_NOT_LEAK"
    lines[24] = "int x = buffer[index]; // line 25: crashing line"
    lines[49] = "// SECRET_BOTTOM_CANARY_MARKER_DO_NOT_LEAK"
    full_code = "\n".join(lines)

    asan_output = (
        "==9999==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000010\n"
        "READ of size 4 at 0x602000000010 thread T0\n"
        "    #0 0x401222 in process_node /workspace/solution.cpp:25\n"
        "    #1 0x401400 in main /workspace/solution.cpp:45\n"
    )

    captured_prompt = {}

    async def mock_complete(prompt, *args, **kwargs):
        captured_prompt["prompt"] = prompt
        return "Explanation: Out of bounds read at line 25.\nFix: Validate index before access."

    payload = {
        "request_id": "req-canary-01",
        "room_id": room_id,
        "analysis_id": "ana-canary-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": asan_output,
        "code_snippet": full_code,
    }

    with patch("app.llm.client.LLMClient.complete", side_effect=mock_complete):
        resp = client.post("/v1/analyses/crash-explanation", json=payload)

    assert resp.status_code == 200
    prompt_sent = captured_prompt.get("prompt", "")

    # 1. Deterministic sanitizer summary MUST be present
    assert "Detected heap-buffer-overflow at line 25." in prompt_sent

    # 2. Localized crashing line MUST be present
    assert "int x = buffer[index]; // line 25: crashing line" in prompt_sent

    # 3. Privacy: Canary markers outside the window MUST NOT appear in prompt
    assert "SECRET_TOP_CANARY_MARKER_DO_NOT_LEAK" not in prompt_sent
    assert "SECRET_BOTTOM_CANARY_MARKER_DO_NOT_LEAK" not in prompt_sent

    # 4. Data minimization: Raw ASan stack trace MUST NOT leak into prompt
    assert "==9999==ERROR: AddressSanitizer" not in prompt_sent
    assert "READ of size 4 at 0x602000000010 thread T0" not in prompt_sent
    assert "#0 0x401222 in process_node" not in prompt_sent

    # 5. Container metadata (from retired T-16 prompt) MUST NOT appear in prompt
    assert "Failure Kind:" not in prompt_sent
    assert "Exit Code:" not in prompt_sent
    assert "Timed Out:" not in prompt_sent
    assert "Out of Memory" not in prompt_sent


# ============================================================================
# 3. rules_first=False Behavior (Never Intercepted by Slow Patterns)
# ============================================================================

def test_crash_explanation_with_loops_does_not_trigger_rules_first():
    """Verify C++ code with loops (matching detect_slow_patterns) proceeds to AI and is not intercepted by rules-first."""
    room_id = "room-us74-loop-01"
    clear_room(room_id)

    loop_code = (
        "void process(int n) {\n"
        "    for (int i = 0; i < n; i++) {\n"
        "        for (int j = 0; j < n; j++) {\n"
        "            buf[i * n + j] = 0; // line 14: crash\n"
        "        }\n"
        "    }\n"
        "}\n"
    )

    # Confirm that detect_slow_patterns detects patterns on this code
    patterns = detect_slow_patterns(loop_code, language="cpp")
    assert len(patterns) > 0, "Code must contain detectable slow patterns to prove rules_first=False"

    mock_complete = AsyncMock(
        return_value="Explanation: Memory overflow at line 14.\nFix: Check buffer allocation size."
    )

    payload = {
        "request_id": "req-loop-01",
        "room_id": room_id,
        "analysis_id": "ana-loop-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": SAMPLE_ASAN_CRASH,
        "code_snippet": loop_code,
    }

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp = client.post("/v1/analyses/crash-explanation", json=payload)

    assert resp.status_code == 200
    data = resp.json()
    # Must NOT be intercepted by Stage 2 rules (which would return status: skipped)
    assert data["ai_step"]["status"] == "ok"
    assert data["ai_step"]["explanation"] == "Memory overflow at line 14."
    assert mock_complete.call_count == 1


# ============================================================================
# 4. Consent and AI Switch Gating
# ============================================================================

def test_crash_explanation_consent_missing():
    """Verify consent=False returns skipped CONSENT_MISSING with 0 model calls while preserving summary."""
    room_id = "room-us74-consent-01"
    clear_room(room_id)

    mock_complete = AsyncMock()

    payload = {
        "request_id": "req-consent-01",
        "room_id": room_id,
        "analysis_id": "ana-consent-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": False,
        "ai_enabled": True,
        "sanitizer_output": SAMPLE_ASAN_CRASH,
        "code_snippet": "int x = buf[i];",
    }

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp = client.post("/v1/analyses/crash-explanation", json=payload)

    assert resp.status_code == 200
    data = resp.json()
    assert data["summary"] == "Detected heap-buffer-overflow at line 14."
    assert data["error_type"] == "heap-buffer-overflow"
    assert data["line_number"] == 14
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert data["ai_step"]["explanation"] is None
    assert data["ai_step"]["suggested_fix"] is None
    assert mock_complete.call_count == 0


def test_crash_explanation_ai_disabled():
    """Verify ai_enabled=False returns skipped AI_DISABLED with 0 model calls while preserving summary."""
    room_id = "room-us74-aidisabled-01"
    clear_room(room_id)

    mock_complete = AsyncMock()

    payload = {
        "request_id": "req-ai-off-01",
        "room_id": room_id,
        "analysis_id": "ana-ai-off-01",
        "request_type": "crash_explanation",
        "language": "c",
        "consent": True,
        "ai_enabled": False,
        "sanitizer_output": SAMPLE_UBSAN_CRASH,
        "code_snippet": "int x = 1 << 32;",
    }

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp = client.post("/v1/analyses/crash-explanation", json=payload)

    assert resp.status_code == 200
    data = resp.json()
    assert data["summary"] == "Detected shift-out-of-bounds at line 20."
    assert data["error_type"] == "shift-out-of-bounds"
    assert data["line_number"] == 20
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "AI_DISABLED"
    assert data["ai_step"]["explanation"] is None
    assert data["ai_step"]["suggested_fix"] is None
    assert mock_complete.call_count == 0


# ============================================================================
# 5. Cost Cap and Caching
# ============================================================================

def test_crash_explanation_cost_cap_reached():
    """Verify cost cap enforcement returns skipped COST_CAP_REACHED with 0 model calls."""
    room_id = "room-us74-costcap-01"
    clear_room(room_id)

    from app.counters import get_tracker
    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.0
    record_model_call(room_id, tokens=1000000)

    mock_complete = AsyncMock()

    payload = {
        "request_id": "req-costcap-01",
        "room_id": room_id,
        "analysis_id": "ana-costcap-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": SAMPLE_ASAN_CRASH,
        "code_snippet": "int x = buf[14];",
    }

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp = client.post("/v1/analyses/crash-explanation", json=payload)

    assert resp.status_code == 200
    data = resp.json()
    assert data["summary"] == "Detected heap-buffer-overflow at line 14."
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "COST_CAP_REACHED"
    assert data["ai_step"]["explanation"] is None
    assert data["ai_step"]["suggested_fix"] is None
    assert mock_complete.call_count == 0


def test_crash_explanation_cache_hit():
    """Verify cache hit returns cached explanation and suggested_fix without repeated paid model call."""
    room_id = "room-us74-cache-01"
    clear_room(room_id)

    code = "int x = buf[i];\n"
    mock_complete = AsyncMock(
        return_value="Explanation: Heap buffer overflow at line 14.\nFix: Add boundary check."
    )

    payload = {
        "request_id": "req-cache-01",
        "room_id": room_id,
        "analysis_id": "ana-cache-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": SAMPLE_ASAN_CRASH,
        "code_snippet": code,
    }

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        # 1. First call: populates cache
        r1 = client.post("/v1/analyses/crash-explanation", json=payload)
        assert r1.status_code == 200
        assert r1.json()["ai_step"]["status"] == "ok"
        assert mock_complete.call_count == 1

        # 2. Second call: identical request must hit cache
        r2 = client.post("/v1/analyses/crash-explanation", json={**payload, "request_id": "req-cache-02"})
        assert r2.status_code == 200
        d2 = r2.json()
        assert d2["ai_step"]["status"] == "ok"
        assert d2["ai_step"]["explanation"] == "Heap buffer overflow at line 14."
        assert d2["ai_step"]["suggested_fix"] == "Add boundary check."
        assert mock_complete.call_count == 1, "Cache hit must not invoke LLM again"


# ============================================================================
# 6. Language Gating (DR5)
# ============================================================================

def test_crash_explanation_unsupported_language_rejection():
    """Verify unsupported language (e.g. Python, Java, JS) returns HTTP 422 UNSUPPORTED_LANGUAGE."""
    for lang in ["python", "java", "javascript"]:
        payload = {
            "request_id": f"req-unsup-{lang}",
            "room_id": "room-us74-lang",
            "analysis_id": f"ana-unsup-{lang}",
            "request_type": "crash_explanation",
            "language": lang,
            "consent": True,
            "ai_enabled": True,
            "sanitizer_output": SAMPLE_ASAN_CRASH,
            "code_snippet": "print('hello')",
        }
        resp = client.post("/v1/analyses/crash-explanation", json=payload)
        assert resp.status_code == 422
        data = resp.json()
        assert data["error"]["code"] == "UNSUPPORTED_LANGUAGE"
        assert lang in data["error"]["message"]


def test_crash_explanation_c_language_supported():
    """Verify C language is fully supported for crash explanation."""
    room_id = "room-us74-clang-01"
    clear_room(room_id)

    mock_complete = AsyncMock(
        return_value="Explanation: Shift exponent exceeds 32 bits at line 20.\nFix: Cast to 64-bit integer."
    )

    payload = {
        "request_id": "req-clang-01",
        "room_id": room_id,
        "analysis_id": "ana-clang-01",
        "request_type": "crash_explanation",
        "language": "c",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": SAMPLE_UBSAN_CRASH,
        "code_snippet": "int x = 1 << 32;",
    }

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp = client.post("/v1/analyses/crash-explanation", json=payload)

    assert resp.status_code == 200
    data = resp.json()
    assert data["task"] == "crash_explanation"
    assert data["error_type"] == "shift-out-of-bounds"
    assert data["line_number"] == 20
    assert data["ai_step"]["status"] == "ok"
    assert data["ai_step"]["suggested_fix"] == "Cast to 64-bit integer."
    assert mock_complete.call_count == 1


def test_crash_explanation_cache_key_ignores_aslr_and_pid():
    """Verify ASLR addresses and PIDs in sanitizer output do not pollute the cache key (Item 5)."""
    room_id = "room-us74-cache-aslr-01"
    clear_room(room_id)

    mock_complete = AsyncMock(
        return_value="Explanation: Buffer overflow detected.\nSuggested Fix: Add boundary check."
    )

    sanitizer_run_1 = (
        "==12345==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000014\n"
        "READ of size 4 at 0x602000000014 thread T0\n"
        "    #0 0x401234 in main main.cpp:14\n"
    )
    sanitizer_run_2 = (
        "==98765==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x7ffd01234567\n"
        "READ of size 4 at 0x7ffd01234567 thread T0\n"
        "    #0 0x509876 in main main.cpp:14\n"
    )

    code = "int main() {\n  int arr[10];\n  return arr[14];\n}\n"

    payload_1 = {
        "request_id": "req-aslr-01",
        "room_id": room_id,
        "analysis_id": "ana-aslr-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": sanitizer_run_1,
        "code_snippet": code,
    }

    payload_2 = {
        "request_id": "req-aslr-02",
        "room_id": room_id,
        "analysis_id": "ana-aslr-02",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": sanitizer_run_2,
        "code_snippet": code,
    }

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp1 = client.post("/v1/analyses/crash-explanation", json=payload_1)
        resp2 = client.post("/v1/analyses/crash-explanation", json=payload_2)

    assert resp1.status_code == 200
    assert resp2.status_code == 200
    assert resp2.json()["ai_step"]["status"] == "ok"
    # Stage 1 Cache Hit: LLM should be called only ONCE despite different PIDs and addresses
    assert mock_complete.call_count == 1


def test_crash_explanation_neutral_fallback_for_ubsan():
    """Verify non-buffer UBSan crashes do not receive 'Add boundary check' when unstructured (Item 6)."""
    room_id = "room-us74-ubsan-fallback-01"
    clear_room(room_id)

    # Output without explicit Fix: header
    mock_complete = AsyncMock(
        return_value="The signed integer overflow occurred because values exceeded 32-bit limits."
    )

    ubsan_output = (
        "main.cpp:25:5: runtime error: signed integer overflow: 2147483647 + 1 cannot be represented in type 'int'\n"
    )

    payload = {
        "request_id": "req-ubsan-fallback-01",
        "room_id": room_id,
        "analysis_id": "ana-ubsan-fallback-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": ubsan_output,
        "code_snippet": "int a = 2147483647;\nint b = a + 1;",
    }

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp = client.post("/v1/analyses/crash-explanation", json=payload)

    assert resp.status_code == 200
    data = resp.json()
    assert data["ai_step"]["status"] == "ok"
    suggested_fix = data["ai_step"]["suggested_fix"]
    assert suggested_fix is not None
    assert "boundary check" not in suggested_fix.lower()
    assert "undefined arithmetic" in suggested_fix.lower() or "inspect line" in suggested_fix.lower()
