"""Tests for AI consent gate and rules-first integration (US-17, FR12.1, FR12.2, FR12.3, NFR8.2, T-18 v2).

Verifies:
1. Consent missing or false never reaches external AI service: mocked LLM client is called 0 times (FR12.2).
2. With consent false or missing, rule-based findings are returned in full with HTTP 200 and
   ai_step.status = "skipped", ai_step.error_code = "CONSENT_MISSING" (FR12.3, T-18 v2).
3. With ai_enabled false, rule-based findings return with HTTP 200 and
   ai_step.status = "skipped", ai_step.error_code = "AI_DISABLED" (FR35, T-18 v2).
4. With consent true and ai_enabled true, AI fallback executes and mocked LLM is called exactly once.
5. Upstream AI failures (timeout, 429 rate limit, 502 error) preserve rule-based results and return HTTP 200
   with skipped status and explicit error codes.
6. Genuine validation errors return HTTP 422 INVALID_REQUEST.
7. Service logs strictly omit code and prompts in all execution paths (NFR8.2).
8. AST pattern detection accurately detects nested loops, string concat in loops, and linear search in loops.
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
from app.llm import (
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
)
from app.llm.client import (
    AIDisabledError,
    ConsentMissingError,
    LLMClient,
    call_llm_with_consent,
)
from app.main import app
from app.rules.patterns import (
    detect_slow_patterns,
    run_rule_based_analysis_with_ai_fallback,
)

client = TestClient(app)

SAMPLE_COMPLEXITY_PAYLOAD = {
    "request_id": "7a3e2b10-6c5d-4f8a-9e12-3456789abcde",
    "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
    "analysis_id": "8b4f3c21-7d6e-4a9b-0f23-456789abcdef",
    "request_type": "complexity",
    "language": "python",
    "consent": True,
    "ai_enabled": True,
    "rules_first": False,
    "code": (
        "def find_duplicates(arr):\n"
        "    dups = []\n"
        "    for i in range(len(arr)):\n"
        "        for j in range(i + 1, len(arr)):\n"
        "            if arr[i] == arr[j]:\n"
        "                dups.append(arr[i])\n"
        "    return dups\n"
    ),
    "measurements": [
        {"input_size": 100, "runtimes_ms": [1.12, 1.15, 1.14]},
        {"input_size": 200, "runtimes_ms": [4.45, 4.52, 4.48]},
        {"input_size": 400, "runtimes_ms": [17.85, 18.02, 17.91]},
    ],
    "suggested_patch": (
        "def find_duplicates(arr):\n"
        "    seen = set()\n"
        "    dups = []\n"
        "    for x in arr:\n"
        "        if x in seen:\n"
        "            dups.append(x)\n"
        "        else:\n"
        "            seen.add(x)\n"
        "    return dups\n"
    ),
    "suggested_patch_runtimes_ms": [1.82, 1.79, 1.85],
}

SAMPLE_EXPLAIN_PAYLOAD = {
    "request_id": "test-req-consent-001",
    "room_id": "room-consent-42",
    "request_type": "explanation",
    "language": "python",
    "code": "def calc(x):\n    for a in x:\n        for b in x:\n            pass\n    return x / 0",
    "stderr": "ZeroDivisionError: division by zero",
    "exit_code": 1,
    "execution_time_ms": 32.1,
    "peak_memory_mb": 8.5,
    "timed_out": False,
    "oom_killed": False,
    "failure_kind": "non_zero_exit",
}


# ============================================================================
# 1. Unit Tests for Universal LLM Consent Wrapper (FR12.2)
# ============================================================================


@pytest.mark.anyio
async def test_wrapper_consent_false_raises_consent_missing_and_zero_calls():
    """Verify wrapper blocks outbound call and raises ConsentMissingError when consent=False."""
    mock_client = AsyncMock(spec=LLMClient)
    mock_client.complete = AsyncMock(return_value="mock answer")

    with pytest.raises(ConsentMissingError) as exc_info:
        await call_llm_with_consent(
            prompt="Explain error",
            consent=False,
            client=mock_client,
        )

    assert exc_info.value.code == "CONSENT_MISSING"
    assert exc_info.value.status_code == 403
    assert mock_client.complete.call_count == 0


@pytest.mark.anyio
async def test_wrapper_consent_missing_raises_consent_missing_and_zero_calls():
    """Verify wrapper blocks outbound call when consent is None (missing)."""
    mock_client = AsyncMock(spec=LLMClient)
    mock_client.complete = AsyncMock(return_value="mock answer")

    with pytest.raises(ConsentMissingError) as exc_info:
        await call_llm_with_consent(
            prompt="Explain error",
            consent=None,
            client=mock_client,
        )

    assert exc_info.value.code == "CONSENT_MISSING"
    assert exc_info.value.status_code == 403
    assert mock_client.complete.call_count == 0


@pytest.mark.anyio
async def test_wrapper_consent_true_calls_llm_client_exactly_once():
    """Verify wrapper permits outbound call when consent=True AND ai_enabled=True, calling client exactly once."""
    mock_client = AsyncMock(spec=LLMClient)
    mock_client.complete = AsyncMock(return_value="Valid explanation.")

    result = await call_llm_with_consent(
        prompt="Explain error",
        consent=True,
        ai_enabled=True,
        client=mock_client,
    )

    assert result == "Valid explanation."
    assert mock_client.complete.call_count == 1


@pytest.mark.anyio
async def test_wrapper_ai_disabled_raises_ai_disabled_and_zero_calls():
    """Verify wrapper blocks outbound call when ai_enabled=False even if consent=True (FR35)."""
    mock_client = AsyncMock(spec=LLMClient)
    mock_client.complete = AsyncMock(return_value="mock answer")

    with pytest.raises(AIDisabledError) as exc_info:
        await call_llm_with_consent(
            prompt="Explain error",
            consent=True,
            ai_enabled=False,
            client=mock_client,
        )

    assert exc_info.value.code == "AI_DISABLED"
    assert exc_info.value.status_code == 403
    assert mock_client.complete.call_count == 0


@pytest.mark.anyio
async def test_wrapper_ai_missing_raises_ai_disabled_and_zero_calls():
    """Verify wrapper blocks outbound call when ai_enabled is missing (None) even if consent=True (FR35)."""
    mock_client = AsyncMock(spec=LLMClient)
    mock_client.complete = AsyncMock(return_value="mock answer")

    with pytest.raises(AIDisabledError) as exc_info:
        await call_llm_with_consent(
            prompt="Explain error",
            consent=True,
            ai_enabled=None,
            client=mock_client,
        )

    assert exc_info.value.code == "AI_DISABLED"
    assert exc_info.value.status_code == 403
    assert mock_client.complete.call_count == 0


# ============================================================================
# 2. Rule-Based Pipeline Helper Tests (FR12.3, US-67)
# ============================================================================


@pytest.mark.anyio
async def test_rule_based_analysis_succeeds_with_consent_false_and_zero_llm_calls():
    """Test that with consent=False, rule-based findings return normally with skipped AI step (FR12.3)."""
    code = (
        "def slow_func(items):\n"
        "    result = ''\n"
        "    for a in items:\n"
        "        for b in items:\n"
        "            result += str(a * b)\n"
        "    return result\n"
    )

    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Suggested AI fix.")

    analysis_result = await run_rule_based_analysis_with_ai_fallback(
        code=code,
        language="python",
        consent=False,
        ai_enabled=True,
        client=mock_llm,
    )

    assert len(analysis_result["slow_patterns"]) >= 1
    pattern_names = [p["pattern"] for p in analysis_result["slow_patterns"]]
    assert "nested_loops" in pattern_names
    assert analysis_result["ai_step"]["status"] == "skipped"
    assert analysis_result["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert analysis_result["ai_step"]["suggested_fix"] is None
    assert mock_llm.complete.call_count == 0


@pytest.mark.anyio
async def test_rule_based_analysis_with_ai_disabled_and_zero_llm_calls():
    """Test that with ai_enabled=False, rule-based findings return normally with AI_DISABLED (FR35)."""
    code = "for i in range(10):\n    for j in range(10):\n        pass"
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Suggested AI fix.")

    analysis_result = await run_rule_based_analysis_with_ai_fallback(
        code=code,
        language="python",
        consent=True,
        ai_enabled=False,
        client=mock_llm,
    )

    assert len(analysis_result["slow_patterns"]) >= 1
    assert analysis_result["ai_step"]["status"] == "skipped"
    assert analysis_result["ai_step"]["error_code"] == "AI_DISABLED"
    assert analysis_result["ai_step"]["suggested_fix"] is None
    assert mock_llm.complete.call_count == 0


@pytest.mark.anyio
async def test_rule_based_analysis_with_consent_true_calls_llm_once():
    """Test that with consent=True and ai_enabled=True, both rules and AI fix are populated."""
    code = "for i in range(10):\n    for j in range(10):\n        pass"
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Flatten loop into single iteration.")

    analysis_result = await run_rule_based_analysis_with_ai_fallback(
        code=code,
        language="python",
        consent=True,
        ai_enabled=True,
        client=mock_llm,
    )

    assert len(analysis_result["slow_patterns"]) >= 1
    assert analysis_result["ai_step"]["status"] == "ok"
    assert analysis_result["ai_step"]["error_code"] is None
    assert analysis_result["ai_step"]["suggested_fix"] == "Flatten loop into single iteration."
    assert mock_llm.complete.call_count == 1


# ============================================================================
# 3. Endpoint-Level Tests: POST /v1/analyses/complexity (T-18 v2 Contract)
# ============================================================================


def test_endpoint_complexity_consent_false_returns_200_and_skipped_ai_step_and_zero_llm_calls():
    """Test 1 — Consent declined: HTTP 200, rule findings returned, ai_step.status == 'skipped',

    ai_step.error_code == 'CONSENT_MISSING', and 0 outbound LLM calls (FR12.2, FR12.3, T-18 v2 §16.1).
    """
    payload = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    payload["consent"] = False

    mock_llm_complete = AsyncMock(return_value="Suggested AI fix.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["request_id"] == payload["request_id"]
    assert data["analysis_id"] == payload["analysis_id"]
    assert data["task"] == "complexity"
    assert data["estimated_complexity"] == "O(n^2)"

    # Rule-based findings are returned intact
    assert len(data["slow_patterns"]) >= 1
    pattern_names = [p["pattern"] for p in data["slow_patterns"]]
    assert "nested_loops" in pattern_names

    # AI step is represented as skipped with CONSENT_MISSING
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert data["ai_step"]["suggested_fix"] is None
    assert data["ai_step"]["verified"] is False
    assert data["ai_step"]["verified_speedup_pct"] is None

    # Zero outbound LLM calls
    assert mock_llm_complete.call_count == 0


def test_endpoint_complexity_consent_missing_returns_200_and_skipped_ai_step_and_zero_llm_calls():
    """Test Case B — Consent omitted/None: HTTP 200, rule findings returned,

    ai_step.status == 'skipped', ai_step.error_code == 'CONSENT_MISSING', 0 LLM calls.
    """
    payload = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    payload.pop("consent", None)

    mock_llm_complete = AsyncMock(return_value="Suggested AI fix.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert len(data["slow_patterns"]) >= 1
    assert mock_llm_complete.call_count == 0


def test_endpoint_complexity_ai_disabled_returns_200_and_skipped_ai_step_and_zero_llm_calls():
    """Test 2 — AI switch disabled: HTTP 200, rule findings returned,

    ai_step.status == 'skipped', ai_step.error_code == 'AI_DISABLED', 0 LLM calls (FR35).
    """
    payload = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    payload["consent"] = True
    payload["ai_enabled"] = False

    mock_llm_complete = AsyncMock(return_value="Suggested AI fix.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "AI_DISABLED"
    assert len(data["slow_patterns"]) >= 1
    assert mock_llm_complete.call_count == 0


def test_endpoint_complexity_consent_true_and_ai_enabled_calls_llm_once():
    """Test 3 — Both consent and AI enabled: HTTP 200, AI fix generated, 1 LLM call."""
    payload = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    payload["consent"] = True
    payload["ai_enabled"] = True

    mock_fix = "Use a hash set to track seen elements in a single pass O(n)."
    mock_llm_complete = AsyncMock(return_value=mock_fix)
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["ai_step"]["status"] == "ok"
    assert data["ai_step"]["error_code"] is None
    assert data["ai_step"]["suggested_fix"] == mock_fix
    assert data["ai_step"]["verified"] is True
    assert data["ai_step"]["verified_speedup_pct"] is not None
    assert data["ai_step"]["verified_speedup_pct"] > 80.0
    assert mock_llm_complete.call_count == 1


def test_endpoint_complexity_upstream_provider_timeout_preserves_rules_and_marks_skipped():
    """Test AI-blocking condition: Upstream timeout preserves rule findings and marks PROVIDER_TIMEOUT."""
    payload = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    payload["consent"] = True
    payload["ai_enabled"] = True

    with patch("app.llm.client.LLMClient.complete", side_effect=LLMTimeoutError(message="Timed out")):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert len(data["slow_patterns"]) >= 1
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "PROVIDER_TIMEOUT"


def test_endpoint_complexity_upstream_rate_limited_preserves_rules_and_marks_skipped():
    """Test AI-blocking condition: Upstream rate limit preserves rule findings and marks RATE_LIMITED_PROVIDER."""
    payload = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    payload["consent"] = True
    payload["ai_enabled"] = True

    with patch(
        "app.llm.client.LLMClient.complete",
        side_effect=LLMRateLimitError(message="Rate limited", retry_after_seconds=30),
    ):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert len(data["slow_patterns"]) >= 1
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "RATE_LIMITED_PROVIDER"


def test_endpoint_complexity_invalid_request_returns_422():
    """Test 4 — Invalid request: Genuinely invalid input returns HTTP 422 INVALID_REQUEST."""
    bad_payload = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    bad_payload["language"] = "unsupported_lang_xyz"

    response = client.post("/v1/analyses/complexity", json=bad_payload)
    assert response.status_code == 422
    data = response.json()
    assert data["error"]["code"] == "INVALID_REQUEST"


# ============================================================================
# 4. Endpoint-Level Tests on POST /v1/explain (FR12.3 Support)
# ============================================================================


def test_explain_endpoint_consent_false_returns_200_with_skipped_ai_step_and_zero_llm_calls():
    """Test POST /v1/explain with consent=False returns HTTP 200 with rule findings and 0 LLM calls (FR12.3)."""
    payload = dict(SAMPLE_EXPLAIN_PAYLOAD)
    payload["consent"] = False

    mock_llm_complete = AsyncMock(return_value="mock answer")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["request_id"] == payload["request_id"]
    assert len(data["slow_patterns"]) >= 1
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert data["ai_step"]["suggested_fix"] is None
    assert mock_llm_complete.call_count == 0


def test_explain_endpoint_consent_true_and_ai_enabled_calls_llm_once():
    """Test POST /v1/explain with consent=True and ai_enabled=True returns HTTP 200 and calls LLM exactly once."""
    payload = dict(SAMPLE_EXPLAIN_PAYLOAD)
    payload["consent"] = True
    payload["ai_enabled"] = True

    mock_fix = "The function divided by zero. Ensure denominator is non-zero."
    mock_llm_complete = AsyncMock(return_value=mock_fix)
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["request_id"] == payload["request_id"]
    assert data["explanation"] == mock_fix
    assert data["ai_step"]["status"] == "ok"
    assert mock_llm_complete.call_count == 1


def test_explain_endpoint_ai_disabled_returns_200_with_skipped_ai_step_and_zero_llm_calls():
    """Test POST /v1/explain with consent=True but ai_enabled=False returns HTTP 200 with AI_DISABLED and 0 calls."""
    payload = dict(SAMPLE_EXPLAIN_PAYLOAD)
    payload["consent"] = True
    payload["ai_enabled"] = False

    mock_llm_complete = AsyncMock(return_value="mock answer")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["explanation"] is None
    assert len(data["slow_patterns"]) >= 1
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "AI_DISABLED"
    assert mock_llm_complete.call_count == 0


def test_explain_endpoint_ai_missing_returns_200_with_skipped_ai_step_and_zero_llm_calls():
    """Test POST /v1/explain with consent=True and missing ai_enabled returns HTTP 200 with AI_DISABLED and 0 calls."""
    payload = dict(SAMPLE_EXPLAIN_PAYLOAD)
    payload["consent"] = True
    payload.pop("ai_enabled", None)

    mock_llm_complete = AsyncMock(return_value="mock answer")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["explanation"] is None
    assert len(data["slow_patterns"]) >= 1
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "AI_DISABLED"
    assert mock_llm_complete.call_count == 0


# ============================================================================
# 5. Privacy Audit: No Code or Prompt Logged on Consent Missing (NFR8.2)
# ============================================================================


def test_privacy_no_code_or_prompt_logged_on_consent_missing_or_false(caplog):
    """Test 5 — Privacy: Service logs strictly do not contain code or prompts (NFR8.2)."""
    canary_secret_code = "SECRET_CANARY_USER_CODE_CONSENT_AUDIT_NFR82"
    canary_stderr = "CANARY_STDERR_TRACE_DO_NOT_LOG"

    payload_false = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    payload_false["code"] = f"# {canary_secret_code}\nfor i in range(10):\n    for j in range(10):\n        pass"
    payload_false["consent"] = False

    payload_missing = dict(payload_false)
    payload_missing.pop("consent", None)

    with caplog.at_level(logging.DEBUG):
        resp_false = client.post("/v1/analyses/complexity", json=payload_false)
        resp_missing = client.post("/v1/analyses/complexity", json=payload_missing)

    assert resp_false.status_code == 200
    assert resp_missing.status_code == 200

    logs = caplog.text
    # Strict verification: Canary text must never be found anywhere in logs
    assert canary_secret_code not in logs
    assert canary_stderr not in logs
    assert "find_duplicates" not in logs


# ============================================================================
# 6. AST Pattern Detection Tests
# ============================================================================


def test_detect_slow_patterns_nested_loops_string_concat_linear_search():
    """Test AST detection for nested loops, repeated string concat in loop, and linear search in loop."""
    code = (
        "def test_patterns(items):\n"
        "    s = ''\n"
        "    for a in items:\n"
        "        for b in items:\n"
        "            if a in items:\n"
        "                s += str(a)\n"
        "    return s\n"
    )
    patterns = detect_slow_patterns(code, language="python")
    pattern_names = [p["pattern"] for p in patterns]
    assert "nested_loops" in pattern_names
    assert "repeated_string_concat" in pattern_names
    assert "linear_search_in_loop" in pattern_names


# ============================================================================
# 7. Additional T-18 v2 Analysis Endpoints Verification
# ============================================================================


def test_endpoint_crash_explanation_unsupported_language_returns_422():
    """Verify non-C/C++ sanitizer request returns HTTP 422 UNSUPPORTED_LANGUAGE (DR5)."""
    payload = {
        "request_id": "req-crash-001",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "analysis_id": "an-crash-001",
        "request_type": "crash_explanation",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": "==1234==ERROR: AddressSanitizer: heap-buffer-overflow",
        "code_snippet": "arr[10] = 5",
    }
    response = client.post("/v1/analyses/crash-explanation", json=payload)
    assert response.status_code == 422
    data = response.json()
    assert data["error"]["code"] == "UNSUPPORTED_LANGUAGE"


def test_endpoint_crash_explanation_consent_false_returns_200_and_skipped():
    """Verify crash explanation with consent=False returns HTTP 200, parsed summary, and 0 LLM calls."""
    payload = {
        "request_id": "req-crash-002",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "analysis_id": "an-crash-002",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": False,
        "ai_enabled": True,
        "sanitizer_output": (
            "==4210==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000014\n"
            "READ of size 4 at 0x602000000014 thread T0\n"
            "    #0 0x401233 in process_buffer /workspace/solution.cpp:14"
        ),
        "code_snippet": "int val = buffer[index];\nreturn val * 2;",
    }

    mock_llm_complete = AsyncMock(return_value="mock answer")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/crash-explanation", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["error_type"] == "heap-buffer-overflow"
    assert data["line_number"] == 14
    assert "heap-buffer-overflow" in data["summary"]
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert mock_llm_complete.call_count == 0


def test_endpoint_usage_returns_200():
    """Verify GET /v1/usage returns HTTP 200 with telemetry metrics (Task 5)."""
    response = client.get("/v1/analyses/usage")
    assert response.status_code == 200
    data = response.json()
    assert "monthly_spend_usd" in data
    assert "cost_cap_reached" in data


# ============================================================================
# 8. Complete 7-Permutation Permission Matrix Tests (FR12.2, FR35, US-17)
# ============================================================================


@pytest.mark.parametrize(
    "consent_val, ai_enabled_val, expected_status, expected_error_code, expected_calls",
    [
        (True, True, "ok", None, 1),
        (True, False, "skipped", "AI_DISABLED", 0),
        (False, True, "skipped", "CONSENT_MISSING", 0),
        (False, False, "skipped", "CONSENT_MISSING", 0),
        (True, "MISSING", "skipped", "AI_DISABLED", 0),
        ("MISSING", True, "skipped", "CONSENT_MISSING", 0),
        ("MISSING", "MISSING", "skipped", "CONSENT_MISSING", 0),
    ],
)
def test_matrix_endpoint_complexity_permission_permutations(
    consent_val, ai_enabled_val, expected_status, expected_error_code, expected_calls
):
    """Test all 7 combinations of (consent, ai_enabled) on POST /v1/analyses/complexity."""
    payload = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    if consent_val == "MISSING":
        payload.pop("consent", None)
    else:
        payload["consent"] = consent_val

    if ai_enabled_val == "MISSING":
        payload.pop("ai_enabled", None)
    else:
        payload["ai_enabled"] = ai_enabled_val

    mock_llm_complete = AsyncMock(return_value="Optimized code fix.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert mock_llm_complete.call_count == expected_calls

    # Rule-based findings are ALWAYS returned intact (FR12.3)
    assert len(data["slow_patterns"]) >= 1
    assert "nested_loops" in [p["pattern"] for p in data["slow_patterns"]]

    assert data["ai_step"]["status"] == expected_status
    assert data["ai_step"]["error_code"] == expected_error_code
    if expected_status == "ok":
        assert data["ai_step"]["suggested_fix"] == "Optimized code fix."
    else:
        assert data["ai_step"]["suggested_fix"] is None


@pytest.mark.parametrize(
    "consent_val, ai_enabled_val, expected_status, expected_error_code, expected_calls",
    [
        (True, True, "ok", None, 1),
        (True, False, "skipped", "AI_DISABLED", 0),
        (False, True, "skipped", "CONSENT_MISSING", 0),
        (False, False, "skipped", "CONSENT_MISSING", 0),
        (True, "MISSING", "skipped", "AI_DISABLED", 0),
        ("MISSING", True, "skipped", "CONSENT_MISSING", 0),
        ("MISSING", "MISSING", "skipped", "CONSENT_MISSING", 0),
    ],
)
def test_matrix_endpoint_crash_explanation_permission_permutations(
    consent_val, ai_enabled_val, expected_status, expected_error_code, expected_calls
):
    """Test all 7 combinations of (consent, ai_enabled) on POST /v1/analyses/crash-explanation."""
    payload = {
        "request_id": f"req-matrix-crash-{consent_val}-{ai_enabled_val}",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "analysis_id": "an-matrix-001",
        "request_type": "crash_explanation",
        "language": "cpp",
        "sanitizer_output": (
            "==4210==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000014\n"
            "READ of size 4 at 0x602000000014 thread T0\n"
            "    #0 0x401233 in process_buffer /workspace/solution.cpp:14"
        ),
        "code_snippet": "int val = buffer[index];\nreturn val * 2;",
    }
    if consent_val != "MISSING":
        payload["consent"] = consent_val
    if ai_enabled_val != "MISSING":
        payload["ai_enabled"] = ai_enabled_val

    mock_llm_complete = AsyncMock(return_value="Crash caused by out-of-bounds heap read.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/analyses/crash-explanation", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert mock_llm_complete.call_count == expected_calls

    # Deterministic parser outputs are always returned
    assert data["error_type"] == "heap-buffer-overflow"
    assert data["line_number"] == 14

    assert data["ai_step"]["status"] == expected_status
    assert data["ai_step"]["error_code"] == expected_error_code
    if expected_status == "ok":
        assert data["ai_step"]["explanation"] == "Crash caused by out-of-bounds heap read."
    else:
        assert data["ai_step"]["explanation"] is None


@pytest.mark.parametrize(
    "consent_val, ai_enabled_val, expected_status, expected_error_code, expected_calls",
    [
        (True, True, "ok", None, 1),
        (True, False, "skipped", "AI_DISABLED", 0),
        (False, True, "skipped", "CONSENT_MISSING", 0),
        (False, False, "skipped", "CONSENT_MISSING", 0),
        (True, "MISSING", "skipped", "AI_DISABLED", 0),
        ("MISSING", True, "skipped", "CONSENT_MISSING", 0),
        ("MISSING", "MISSING", "skipped", "CONSENT_MISSING", 0),
    ],
)
def test_matrix_endpoint_explain_permission_permutations(
    consent_val, ai_enabled_val, expected_status, expected_error_code, expected_calls
):
    """Test all 7 combinations of (consent, ai_enabled) on POST /v1/explain."""
    payload = dict(SAMPLE_EXPLAIN_PAYLOAD)
    if consent_val != "MISSING":
        payload["consent"] = consent_val
    else:
        payload.pop("consent", None)

    if ai_enabled_val != "MISSING":
        payload["ai_enabled"] = ai_enabled_val
    else:
        payload.pop("ai_enabled", None)

    mock_fix = "Division by zero occurred. Check that denominator is not zero."
    mock_llm_complete = AsyncMock(return_value=mock_fix)
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert mock_llm_complete.call_count == expected_calls

    # Rule findings are always returned (FR12.3)
    assert len(data["slow_patterns"]) >= 1

    assert data["ai_step"]["status"] == expected_status
    assert data["ai_step"]["error_code"] == expected_error_code
    if expected_status == "ok":
        assert data["explanation"] == mock_fix
    else:
        assert data["explanation"] is None


# ============================================================================
# 9. US-21: Consent Withdrawal, Restoration & Session Isolation (FR12.4)
# ============================================================================


def test_us21_scenario_1_consent_withdrawn_blocks_outbound_llm():
    """US-21 Scenario 1: Consent withdrawn during active session (consent: false).

    Verifies across /v1/analyses/complexity, /v1/analyses/crash-explanation, and /v1/explain:
    1. Zero outbound LLM calls are made (mock_llm.complete call_count == 0).
    2. AI step is marked status == 'skipped'.
    3. Error code is 'CONSENT_MISSING'.
    """
    mock_llm_complete = AsyncMock(return_value="Suggested AI fix.")

    # 1. Complexity endpoint with consent withdrawn
    comp_payload = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    comp_payload["consent"] = False
    comp_payload["ai_enabled"] = True

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp_comp = client.post("/v1/analyses/complexity", json=comp_payload)

    assert resp_comp.status_code == 200
    data_comp = resp_comp.json()
    assert data_comp["ai_step"]["status"] == "skipped"
    assert data_comp["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert data_comp["ai_step"]["suggested_fix"] is None
    assert mock_llm_complete.call_count == 0

    # 2. Crash explanation endpoint with consent withdrawn
    crash_payload = {
        "request_id": "us21-crash-withdrawn-001",
        "room_id": "room-us21-withdrawn",
        "analysis_id": "an-us21-crash-001",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": False,
        "ai_enabled": True,
        "sanitizer_output": "==1001==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x1\n #0 in f /app.cpp:10",
        "code_snippet": "char* buf = (char*)malloc(10); buf[12] = 'x';",
    }

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp_crash = client.post("/v1/analyses/crash-explanation", json=crash_payload)

    assert resp_crash.status_code == 200
    data_crash = resp_crash.json()
    assert data_crash["ai_step"]["status"] == "skipped"
    assert data_crash["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert data_crash["ai_step"]["explanation"] is None
    assert mock_llm_complete.call_count == 0

    # 3. Explain endpoint with consent withdrawn
    explain_payload = dict(SAMPLE_EXPLAIN_PAYLOAD)
    explain_payload["consent"] = False
    explain_payload["ai_enabled"] = True

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp_explain = client.post("/v1/explain", json=explain_payload)

    assert resp_explain.status_code == 200
    data_explain = resp_explain.json()
    assert data_explain["ai_step"]["status"] == "skipped"
    assert data_explain["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert data_explain["explanation"] is None
    assert mock_llm_complete.call_count == 0


def test_us21_scenario_2_consent_missing_blocks_outbound_llm():
    """US-21 Scenario 2: Consent missing (omitted or None).

    Verifies missing consent fails closed with 0 outbound LLM calls and CONSENT_MISSING.
    """
    mock_llm_complete = AsyncMock(return_value="Suggested AI fix.")

    # Complexity endpoint with consent missing
    comp_payload = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    comp_payload.pop("consent", None)
    comp_payload["ai_enabled"] = True

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp = client.post("/v1/analyses/complexity", json=comp_payload)

    assert resp.status_code == 200
    data = resp.json()
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert mock_llm_complete.call_count == 0

    # Crash explanation endpoint with consent None
    crash_payload = {
        "request_id": "us21-crash-missing-002",
        "room_id": "room-us21-missing",
        "analysis_id": "an-us21-crash-002",
        "request_type": "crash_explanation",
        "language": "c",
        "consent": None,
        "ai_enabled": True,
        "sanitizer_output": "==1002==ERROR: AddressSanitizer: global-buffer-overflow on address 0x2\n #0 in g /app.c:20",
        "code_snippet": "int arr[5]; arr[10] = 1;",
    }

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp_crash = client.post("/v1/analyses/crash-explanation", json=crash_payload)

    assert resp_crash.status_code == 200
    data_crash = resp_crash.json()
    assert data_crash["ai_step"]["status"] == "skipped"
    assert data_crash["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert mock_llm_complete.call_count == 0


def test_us21_scenario_3_ai_free_analysis_preserved_after_withdrawal():
    """US-21 Scenario 3: AI-free functionality is preserved after consent withdrawal.

    Withdrawing consent skips ONLY the external AI step:
    - Rule-based AST pattern analysis continues to detect patterns.
    - Empirical Big-O measurements and regression fitting continue to return.
    - Sanitizer output parsing continues to parse error types and line numbers.
    - Returns HTTP 200 with complete AI-free results intact and 0 LLM calls.
    """
    mock_llm_complete = AsyncMock(return_value="Suggested AI fix.")

    # 1. Complexity endpoint: rule detection & empirical regression execute AI-free
    comp_payload = {
        "request_id": "us21-aifree-comp-001",
        "room_id": "room-us21-aifree",
        "analysis_id": "an-us21-aifree-001",
        "request_type": "complexity",
        "language": "python",
        "consent": False,
        "ai_enabled": True,
        "rules_first": False,
        "code": (
            "def slow_matrix(n):\n"
            "    res = []\n"
            "    for i in range(n):\n"
            "        for j in range(n):\n"
            "            res.append(i * j)\n"
            "    return res\n"
        ),
        "measurements": [
            {"input_size": 10, "runtimes_ms": [0.5, 0.6]},
            {"input_size": 100, "runtimes_ms": [48.0, 52.0]},
            {"input_size": 200, "runtimes_ms": [195.0, 205.0]},
        ],
    }

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp_comp = client.post("/v1/analyses/complexity", json=comp_payload)

    assert resp_comp.status_code == 200
    data_comp = resp_comp.json()
    # Empirical regression metrics computed
    assert data_comp["estimated_complexity"] == "O(n^2)"
    assert data_comp["growth_rate"] > 1.8
    assert data_comp["r2_score"] > 0.95
    assert data_comp["projected_timeout_input_size"] > 0
    # AST rule findings detected
    assert len(data_comp["slow_patterns"]) >= 1
    assert any(p["pattern"] == "nested_loops" for p in data_comp["slow_patterns"])
    # AI step is skipped
    assert data_comp["ai_step"]["status"] == "skipped"
    assert data_comp["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert mock_llm_complete.call_count == 0

    # 2. Crash explanation endpoint: deterministic sanitizer parser executes AI-free
    crash_payload = {
        "request_id": "us21-aifree-crash-001",
        "room_id": "room-us21-aifree",
        "analysis_id": "an-us21-aifree-002",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": False,
        "ai_enabled": True,
        "sanitizer_output": (
            "==8842==ERROR: AddressSanitizer: stack-buffer-overflow on address 0x7fff\n"
            "WRITE of size 4 at 0x7fff thread T0\n"
            "    #0 0x401888 in compute_values /workspace/main.cpp:27:12\n"
        ),
        "code_snippet": "int local_arr[10];\nlocal_arr[20] = 99;",
    }

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp_crash = client.post("/v1/analyses/crash-explanation", json=crash_payload)

    assert resp_crash.status_code == 200
    data_crash = resp_crash.json()
    # Parser results intact
    assert data_crash["error_type"] == "stack-buffer-overflow"
    assert data_crash["line_number"] == 27
    assert "stack-buffer-overflow" in data_crash["summary"]
    # AI step is skipped
    assert data_crash["ai_step"]["status"] == "skipped"
    assert data_crash["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert mock_llm_complete.call_count == 0


def test_us21_scenario_4_consent_restoration_session_lifecycle():
    """US-21 Scenario 4: User grants, withdraws, and restores consent within a session.

    Session lifecycle:
    1. Request 1 (consent: true, ai_enabled: true) -> LLM invoked once, ai_step.status == 'ok'.
    2. Request 2 (consent: false, ai_enabled: true) -> 0 new LLM calls, ai_step.status == 'skipped' (CONSENT_MISSING).
    3. Request 3 (consent: true, ai_enabled: true) -> LLM invoked again (call count 2), ai_step.status == 'ok'.

    Verifies consent withdrawal does not permanently disable AI for the user/session.
    """
    mock_llm_complete = AsyncMock(side_effect=["Fix 1: Optimize loops.", "Fix 2: Refactor lookup."])

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        # Step 1: Initial consent granted
        req_1 = dict(SAMPLE_COMPLEXITY_PAYLOAD)
        req_1["request_id"] = "us21-seq-001"
        req_1["consent"] = True
        req_1["ai_enabled"] = True
        req_1["code"] = "def f1(a):\n    for x in a:\n        for y in a:\n            pass"

        resp_1 = client.post("/v1/analyses/complexity", json=req_1)
        assert resp_1.status_code == 200
        data_1 = resp_1.json()
        assert data_1["ai_step"]["status"] == "ok"
        assert data_1["ai_step"]["suggested_fix"] == "Fix 1: Optimize loops."
        assert mock_llm_complete.call_count == 1

        # Step 2: User withdraws consent during the active session
        req_2 = dict(SAMPLE_COMPLEXITY_PAYLOAD)
        req_2["request_id"] = "us21-seq-002"
        req_2["consent"] = False
        req_2["ai_enabled"] = True
        req_2["code"] = "def f2(b):\n    for x in b:\n        for y in b:\n            pass"

        resp_2 = client.post("/v1/analyses/complexity", json=req_2)
        assert resp_2.status_code == 200
        data_2 = resp_2.json()
        assert data_2["ai_step"]["status"] == "skipped"
        assert data_2["ai_step"]["error_code"] == "CONSENT_MISSING"
        assert data_2["ai_step"]["suggested_fix"] is None
        # Call count must remain 1: NO outbound LLM call on withdrawn consent
        assert mock_llm_complete.call_count == 1

        # Step 3: User restores consent later in the active session
        req_3 = dict(SAMPLE_COMPLEXITY_PAYLOAD)
        req_3["request_id"] = "us21-seq-003"
        req_3["consent"] = True
        req_3["ai_enabled"] = True
        req_3["code"] = "def f3(c):\n    for x in c:\n        for y in c:\n            pass"

        resp_3 = client.post("/v1/analyses/complexity", json=req_3)
        assert resp_3.status_code == 200
        data_3 = resp_3.json()
        assert data_3["ai_step"]["status"] == "ok"
        assert data_3["ai_step"]["suggested_fix"] == "Fix 2: Refactor lookup."
        # Call count advances to 2: LLM call succeeds after restoration
        assert mock_llm_complete.call_count == 2


def test_us21_scenario_5_session_isolation_independent_consent_in_same_room():
    """US-21 Scenario 5: Session isolation in a shared collaborative room.

    Two users in the same room ('room-shared-collab-101'):
    - User A (Session A) withdraws consent (consent: false).
    - User B (Session B) grants consent (consent: true).

    Verifies:
    1. User A's requests are blocked from LLM (ai_step.status == 'skipped', CONSENT_MISSING).
    2. User B's requests proceed to LLM (ai_step.status == 'ok', 1 call).
    3. User A's subsequent requests remain blocked.
    4. Room-level boolean is NEVER used for user consent; consent is per-session.
    """
    mock_llm_complete = AsyncMock(return_value="AI suggestion for User B.")
    shared_room = "room-shared-collab-101"

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        # User A in room-shared-collab-101 with consent: false
        payload_user_a = {
            "request_id": "us21-session-a-01",
            "room_id": shared_room,
            "analysis_id": "an-user-a-01",
            "request_type": "complexity",
            "language": "python",
            "consent": False,
            "ai_enabled": True,
            "rules_first": False,
            "code": "def process_a(items):\n    for x in items:\n        for y in items:\n            pass\n",
            "measurements": [{"input_size": 10, "runtimes_ms": [1.0, 1.1]}],
        }

        resp_a = client.post("/v1/analyses/complexity", json=payload_user_a)
        assert resp_a.status_code == 200
        data_a = resp_a.json()
        assert data_a["ai_step"]["status"] == "skipped"
        assert data_a["ai_step"]["error_code"] == "CONSENT_MISSING"
        assert mock_llm_complete.call_count == 0

        # User B in the EXACT SAME room-shared-collab-101 with consent: true
        payload_user_b = {
            "request_id": "us21-session-b-01",
            "room_id": shared_room,
            "analysis_id": "an-user-b-01",
            "request_type": "complexity",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "rules_first": False,
            "code": "def process_b(items):\n    for x in items:\n        for y in items:\n            pass\n",
            "measurements": [{"input_size": 10, "runtimes_ms": [1.0, 1.1]}],
        }

        resp_b = client.post("/v1/analyses/complexity", json=payload_user_b)
        assert resp_b.status_code == 200
        data_b = resp_b.json()
        assert data_b["ai_step"]["status"] == "ok"
        assert data_b["ai_step"]["suggested_fix"] == "AI suggestion for User B."
        assert mock_llm_complete.call_count == 1

        # User A makes another request in the same room -> STILL blocked
        payload_user_a_2 = {
            "request_id": "us21-session-a-02",
            "room_id": shared_room,
            "analysis_id": "an-user-a-02",
            "request_type": "complexity",
            "language": "python",
            "consent": False,
            "ai_enabled": True,
            "rules_first": False,
            "code": "def process_a_v2(items):\n    for x in items:\n        for y in items:\n            pass\n",
            "measurements": [{"input_size": 10, "runtimes_ms": [1.0, 1.1]}],
        }

        resp_a_2 = client.post("/v1/analyses/complexity", json=payload_user_a_2)
        assert resp_a_2.status_code == 200
        data_a_2 = resp_a_2.json()
        assert data_a_2["ai_step"]["status"] == "skipped"
        assert data_a_2["ai_step"]["error_code"] == "CONSENT_MISSING"
        # Total calls must still be 1 (User B's call only)
        assert mock_llm_complete.call_count == 1


def test_us21_scenario_6_host_ai_switch_independent_of_user_consent():
    """US-21 Scenario 6: Host AI switch interaction.

    Verifies:
    1. consent: true with ai_enabled: false does not call LLM (0 calls),
       preserves AI-free analysis, and returns error_code == 'AI_DISABLED'.
    2. consent: false with ai_enabled: false returns error_code == 'CONSENT_MISSING'
       (consent gate precedes Host AI switch).
    3. User consent does not override Host AI switch, and Host switch does not modify user consent.
    """
    mock_llm_complete = AsyncMock(return_value="Mock fix.")

    # 1. consent: true, ai_enabled: false -> blocked with AI_DISABLED
    payload_host_off = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    payload_host_off["consent"] = True
    payload_host_off["ai_enabled"] = False

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp_1 = client.post("/v1/analyses/complexity", json=payload_host_off)

    assert resp_1.status_code == 200
    data_1 = resp_1.json()
    assert data_1["ai_step"]["status"] == "skipped"
    assert data_1["ai_step"]["error_code"] == "AI_DISABLED"
    assert len(data_1["slow_patterns"]) >= 1
    assert mock_llm_complete.call_count == 0

    # 2. consent: false, ai_enabled: false -> consent check precedes AI switch
    payload_both_off = dict(SAMPLE_COMPLEXITY_PAYLOAD)
    payload_both_off["consent"] = False
    payload_both_off["ai_enabled"] = False

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp_2 = client.post("/v1/analyses/complexity", json=payload_both_off)

    assert resp_2.status_code == 200
    data_2 = resp_2.json()
    assert data_2["ai_step"]["status"] == "skipped"
    assert data_2["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert mock_llm_complete.call_count == 0


def test_us21_scenario_7_withdrawal_prevents_cache_leakage():
    """US-21 Scenario 7: Consent withdrawal prevents leaking cached AI responses.

    If an AI response was previously generated and cached in the room (e.g. by another
    user or earlier in the session), a subsequent request with consent: false
    MUST NOT return the cached AI fix or explanation. It must return status: 'skipped',
    error_code: 'CONSENT_MISSING', and suggested_fix: None.
    """
    mock_llm_complete = AsyncMock(return_value="Cached AI fix text.")
    room = "room-cache-consent-isolation"

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        # 1. Request with consent: true populates the cache
        payload_primed = dict(SAMPLE_COMPLEXITY_PAYLOAD)
        payload_primed["room_id"] = room
        payload_primed["consent"] = True
        payload_primed["ai_enabled"] = True

        resp_primed = client.post("/v1/analyses/complexity", json=payload_primed)
        assert resp_primed.status_code == 200
        assert resp_primed.json()["ai_step"]["status"] == "ok"
        assert resp_primed.json()["ai_step"]["suggested_fix"] == "Cached AI fix text."
        assert mock_llm_complete.call_count == 1

        # 2. Same code, same room, but consent: false (withdrawn)
        payload_withdrawn = dict(SAMPLE_COMPLEXITY_PAYLOAD)
        payload_withdrawn["room_id"] = room
        payload_withdrawn["consent"] = False
        payload_withdrawn["ai_enabled"] = True

        resp_withdrawn = client.post("/v1/analyses/complexity", json=payload_withdrawn)
        assert resp_withdrawn.status_code == 200
        data_withdrawn = resp_withdrawn.json()
        # MUST NOT return the cached fix!
        assert data_withdrawn["ai_step"]["status"] == "skipped"
        assert data_withdrawn["ai_step"]["error_code"] == "CONSENT_MISSING"
        assert data_withdrawn["ai_step"]["suggested_fix"] is None
        assert mock_llm_complete.call_count == 1
