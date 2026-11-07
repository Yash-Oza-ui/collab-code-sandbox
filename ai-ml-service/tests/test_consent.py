"""Tests for AI consent gate and rules-first integration (US-17, FR12.1, FR12.2, FR12.3, NFR8.2).

Verifies:
1. Consent missing or false never reaches external AI service: mocked LLM client is called 0 times.
2. Consent false or missing returns the contract consent-missing response (HTTP 403, code: CONSENT_MISSING).
3. Consent true calls the mocked LLM client exactly once.
4. With consent false, rule-based / AI-free analysis returns normally (FR12.3).
5. Service logs strictly omit code and prompts in all execution paths, including consent-missing (NFR8.2).
6. Endpoint-level integration tests proving POST /v1/explain invokes rules-first helper,
   executes rule-based analysis, and preserves findings internally with 0 LLM calls on consent=False.
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
from app.llm.client import (
    ConsentMissingError,
    LLMClient,
    call_llm_with_consent,
    get_default_llm_client,
)
from app.main import app
from app.rules.patterns import (
    detect_slow_patterns,
    run_rule_based_analysis_with_ai_fallback,
)

client = TestClient(app)

SAMPLE_PAYLOAD = {
    "request_id": "test-req-consent-001",
    "room_id": "room-consent-42",
    "request_type": "explanation",
    "language": "python",
    "code": "def calc(x):\n    return x / 0",
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
    """Verify wrapper permits outbound call when consent=True and calls client exactly once."""
    mock_client = AsyncMock(spec=LLMClient)
    mock_client.complete = AsyncMock(return_value="Valid explanation.")

    result = await call_llm_with_consent(
        prompt="Explain error",
        consent=True,
        client=mock_client,
    )

    assert result == "Valid explanation."
    assert mock_client.complete.call_count == 1


# ============================================================================
# 2. Endpoint Tests with Mocked LLM Client Counting Outbound Calls
# ============================================================================


def test_endpoint_consent_false_returns_consent_missing_and_zero_llm_calls():
    """Test POST /v1/explain with consent=False returns HTTP 403 and calls LLM client 0 times."""
    payload = dict(SAMPLE_PAYLOAD)
    payload["consent"] = False

    mock_llm_complete = AsyncMock(return_value="mocked AI response")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 403
    data = response.json()
    assert data["request_id"] == payload["request_id"]
    assert "error" in data
    assert data["error"]["code"] == "CONSENT_MISSING"
    assert "User consent is required" in data["error"]["message"]
    assert data["error"]["retry_after_seconds"] is None

    # LLM client must never have been called
    assert mock_llm_complete.call_count == 0


def test_endpoint_consent_missing_returns_consent_missing_and_zero_llm_calls():
    """Test POST /v1/explain with consent key omitted returns HTTP 403 and calls LLM client 0 times."""
    payload = dict(SAMPLE_PAYLOAD)
    # Ensure 'consent' key is omitted from JSON request body
    payload.pop("consent", None)

    mock_llm_complete = AsyncMock(return_value="mocked AI response")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 403
    data = response.json()
    assert data["request_id"] == payload["request_id"]
    assert "error" in data
    assert data["error"]["code"] == "CONSENT_MISSING"
    assert "User consent is required" in data["error"]["message"]

    # LLM client must never have been called
    assert mock_llm_complete.call_count == 0


def test_endpoint_consent_none_returns_consent_missing_and_zero_llm_calls():
    """Test POST /v1/explain with consent=None (null) returns HTTP 403 and calls LLM client 0 times."""
    payload = dict(SAMPLE_PAYLOAD)
    payload["consent"] = None

    mock_llm_complete = AsyncMock(return_value="mocked AI response")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 403
    data = response.json()
    assert data["error"]["code"] == "CONSENT_MISSING"
    assert mock_llm_complete.call_count == 0


def test_endpoint_consent_true_calls_llm_client_exactly_once():
    """Test POST /v1/explain with consent=True calls mocked LLM client exactly once."""
    payload = dict(SAMPLE_PAYLOAD)
    payload["consent"] = True

    mock_explanation = "The function divided by zero at line 2. Fix by adding a zero check."
    mock_llm_complete = AsyncMock(return_value=mock_explanation)

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["request_id"] == payload["request_id"]
    assert data["explanation"] == mock_explanation

    # Mocked client must be called exactly once
    assert mock_llm_complete.call_count == 1


# ============================================================================
# 3. Rule-Based / AI-Free Continuity Tests (FR12.3)
# ============================================================================


@pytest.mark.anyio
async def test_rule_based_analysis_succeeds_with_consent_false_and_zero_llm_calls():
    """Test that with consent=False, the AI-free / rule-based part still returns normally (FR12.3)."""
    python_code_with_slow_patterns = (
        "def slow_func(items):\n"
        "    result = ''\n"
        "    for a in items:\n"
        "        for b in items:\n"
        "            result += str(a * b)\n"
        "    return result\n"
    )

    # 1. Deterministic pattern detection returns findings without any external call
    patterns = detect_slow_patterns(python_code_with_slow_patterns, language="python")
    assert len(patterns) >= 1
    pattern_names = [p["pattern"] for p in patterns]
    assert "nested_loops" in pattern_names

    # 2. Pipeline with consent=False returns rule-based results intact with ai_step skipped
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Suggested AI fix.")

    analysis_result = await run_rule_based_analysis_with_ai_fallback(
        code=python_code_with_slow_patterns,
        language="python",
        consent=False,
        client=mock_llm,
    )

    assert analysis_result["task"] == "pattern_analysis"
    assert len(analysis_result["slow_patterns"]) >= 1
    # AI step is safely skipped due to missing consent
    assert analysis_result["ai_step"]["status"] == "skipped"
    assert analysis_result["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert analysis_result["ai_step"]["suggested_fix"] is None

    # The external LLM is never called
    assert mock_llm.complete.call_count == 0


@pytest.mark.anyio
async def test_rule_based_analysis_with_consent_true_calls_llm_once():
    """Test that with consent=True, both rule findings and AI fix are populated."""
    code = "for i in range(10):\n    for j in range(10):\n        pass"
    mock_llm = AsyncMock(spec=LLMClient)
    mock_llm.complete = AsyncMock(return_value="Flatten loop into single iteration.")

    analysis_result = await run_rule_based_analysis_with_ai_fallback(
        code=code,
        language="python",
        consent=True,
        client=mock_llm,
    )

    assert len(analysis_result["slow_patterns"]) >= 1
    assert analysis_result["ai_step"]["status"] == "ok"
    assert analysis_result["ai_step"]["suggested_fix"] == "Flatten loop into single iteration."
    assert mock_llm.complete.call_count == 1


# ============================================================================
# 4. Privacy Audit: No Code or Prompt Logged on Consent Missing (NFR8.2)
# ============================================================================


def test_privacy_no_code_or_prompt_logged_on_consent_missing_or_false(caplog):
    """Test service logs strictly do not contain code or prompts when consent is missing or false."""
    canary_secret_code = "SECRET_CANARY_USER_CODE_CONSENT_AUDIT"
    canary_stderr = "CANARY_STDERR_TRACE_DO_NOT_LOG"

    payload_false = dict(SAMPLE_PAYLOAD)
    payload_false["code"] = f"# {canary_secret_code}\nprint('hello')"
    payload_false["stderr"] = canary_stderr
    payload_false["consent"] = False

    payload_missing = dict(payload_false)
    payload_missing.pop("consent", None)

    with caplog.at_level(logging.DEBUG):
        resp_false = client.post("/v1/explain", json=payload_false)
        resp_missing = client.post("/v1/explain", json=payload_missing)

    assert resp_false.status_code == 403
    assert resp_missing.status_code == 403

    logs = caplog.text
    # Strict verification: Canary text must never be found anywhere in logs
    assert canary_secret_code not in logs
    assert canary_stderr not in logs
    assert "calc(x)" not in logs


# ============================================================================
# 5. Endpoint-Level Rules-First Integration Tests (FR12.3, Reviewer Requirement)
# ============================================================================


def test_endpoint_invokes_rules_first_helper_when_consent_false_and_zero_llm_calls():
    """Endpoint integration test proving POST /v1/explain invokes rules-first helper,

    executes rule-based analysis when consent=False, preserves findings internally,
    returns HTTP 403 CONSENT_MISSING, and makes 0 outbound LLM calls (FR12.3, US-17).
    """
    python_code_with_slow_patterns = (
        "def slow_func(items):\n"
        "    result = ''\n"
        "    for a in items:\n"
        "        for b in items:\n"
        "            result += str(a * b)\n"
        "    return result\n"
    )

    payload = dict(SAMPLE_PAYLOAD)
    payload["code"] = python_code_with_slow_patterns
    payload["consent"] = False

    mock_llm_complete = AsyncMock(return_value="mock answer")
    captured_analysis = []

    original_helper = run_rule_based_analysis_with_ai_fallback

    async def spy_helper(*args, **kwargs):
        res = await original_helper(*args, **kwargs)
        captured_analysis.append(res)
        return res

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        with patch("app.routers.explain.run_rule_based_analysis_with_ai_fallback", side_effect=spy_helper):
            response = client.post("/v1/explain", json=payload)

    # 1. Verify HTTP 403 CONSENT_MISSING response (Version 0 contract)
    assert response.status_code == 403
    data = response.json()
    assert data["request_id"] == payload["request_id"]
    assert data["error"]["code"] == "CONSENT_MISSING"

    # 2. Verify the endpoint actually invoked the rules-first helper
    assert len(captured_analysis) == 1
    res = captured_analysis[0]

    # 3. Verify rule-based analysis was executed and findings were preserved internally
    assert len(res["slow_patterns"]) >= 1
    pattern_names = [p["pattern"] for p in res["slow_patterns"]]
    assert "nested_loops" in pattern_names
    assert res["ai_step"]["status"] == "skipped"
    assert res["ai_step"]["error_code"] == "CONSENT_MISSING"

    # 4. Verify external LLM function was called 0 times
    assert mock_llm_complete.call_count == 0


def test_endpoint_invokes_rules_first_helper_when_consent_true_and_calls_llm_once():
    """Endpoint integration test proving POST /v1/explain invokes rules-first helper,

    runs rule-based analysis, and calls LLM client exactly once when consent=True (FR12.3, US-17).
    """
    python_code_with_slow_patterns = (
        "def slow_func(items):\n"
        "    result = ''\n"
        "    for a in items:\n"
        "        for b in items:\n"
        "            result += str(a * b)\n"
        "    return result\n"
    )

    payload = dict(SAMPLE_PAYLOAD)
    payload["code"] = python_code_with_slow_patterns
    payload["consent"] = True

    mock_llm_complete = AsyncMock(return_value="Suggested AI explanation of the error.")
    captured_analysis = []

    original_helper = run_rule_based_analysis_with_ai_fallback

    async def spy_helper(*args, **kwargs):
        res = await original_helper(*args, **kwargs)
        captured_analysis.append(res)
        return res

    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        with patch("app.routers.explain.run_rule_based_analysis_with_ai_fallback", side_effect=spy_helper):
            response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200
    data = response.json()
    assert data["request_id"] == payload["request_id"]
    assert data["explanation"] == "Suggested AI explanation of the error."

    # Verify rules-first helper was called
    assert len(captured_analysis) == 1
    assert len(captured_analysis[0]["slow_patterns"]) >= 1

    # Verify LLM complete was called (by generate_explanation)
    assert mock_llm_complete.call_count == 1


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

