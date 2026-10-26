"""Tests for failure explanation prototype (T-16, FR13.1, T-07 contract).

Verifies:
- Explanation endpoint behind T-07 contract calls LLM API (mocked) and returns T-07 shape.
- 3-sentence limit enforcement in prompt and programmatic trimmer.
- Plain prose output without markdown.
- Provider errors, timeouts, and rate limits return contract error envelopes.
- Unset API key returns clean contract error shape (HTTP 502 PROVIDER_ERROR).
- Privacy compliance (NFR8.2): Raw source code and stderr are never logged.
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
from app.llm.explainer import (
    clean_markdown,
    enforce_sentence_limit,
    split_sentences,
)
from app.llm.prompts import EXPLAIN_SYSTEM_INSTRUCTION, build_explanation_prompt
from app.main import app

client = TestClient(app)

SAMPLE_PAYLOAD = {
    "request_id": "req-t16-001",
    "room_id": "room-xyz-42",
    "request_type": "explanation",
    "language": "python",
    "consent": True,
    "code": "def divide(a, b):\n    return a / b\nprint(divide(10, 0))",
    "stderr": "ZeroDivisionError: division by zero",
    "exit_code": 1,
    "execution_time_ms": 45.2,
    "peak_memory_mb": 12.4,
    "timed_out": False,
    "oom_killed": False,
    "failure_kind": "non_zero_exit",
}


# ============================================================================
# 1. Prompt & Sentence Trimmer Unit Tests
# ============================================================================


def test_build_explanation_prompt_contains_constraints_and_metadata():
    """Verify prompt builder includes context and explicit 3-sentence rule."""
    prompt = build_explanation_prompt(
        language="python",
        failure_kind="non_zero_exit",
        exit_code=1,
        stderr="ZeroDivisionError: division by zero",
        timed_out=False,
        oom_killed=False,
        code="print(1 / 0)",
    )
    assert "at most three sentences" in prompt.lower()
    assert "Language: python" in prompt
    assert "Failure Kind: non_zero_exit" in prompt
    assert "Exit Code: 1" in prompt
    assert "ZeroDivisionError" in prompt
    assert "print(1 / 0)" in prompt


def test_clean_markdown():
    """Verify markdown tags, bolding, and backticks are cleanly stripped."""
    raw = "**Compile error**: The variable `count` was `#not declared`. - Check line 4."
    cleaned = clean_markdown(raw)
    assert "**" not in cleaned
    assert "`" not in cleaned
    assert "#" not in cleaned
    assert cleaned == "Compile error: The variable count was not declared. Check line 4."


def test_sentence_splitting_and_enforcement():
    """Verify sentence splitting and strict 3-sentence cap enforcement."""
    # 1 sentence
    s1 = "The program failed due to an uncaught exception."
    assert len(split_sentences(s1)) == 1
    assert enforce_sentence_limit(s1, max_sentences=3) == s1

    # 3 sentences
    s3 = "Compilation failed on line 12. A closing semicolon was missing. Add the semicolon to build successfully."
    assert len(split_sentences(s3)) == 3
    assert enforce_sentence_limit(s3, max_sentences=3) == s3

    # 5 sentences -> must be trimmed to 3
    s5 = (
        "Sentence one describes the crash. "
        "Sentence two describes the stack trace. "
        "Sentence three provides the fix. "
        "Sentence four is redundant extra details. "
        "Sentence five is a conversational conclusion."
    )
    trimmed = enforce_sentence_limit(s5, max_sentences=3)
    sentences = split_sentences(trimmed)
    assert len(sentences) == 3
    assert sentences[0] == "Sentence one describes the crash."
    assert sentences[1] == "Sentence two describes the stack trace."
    assert sentences[2] == "Sentence three provides the fix."


# ============================================================================
# 2. Endpoint Contract & Response Shape Tests (with Mocked LLM)
# ============================================================================


@pytest.mark.anyio
async def test_explain_endpoint_success_gemini_format():
    """Test successful POST /v1/explain returns exact T-07 contract shape."""
    mock_llm_text = (
        "The code raised a ZeroDivisionError because divide was called with zero. "
        "Division by zero is undefined in Python. "
        "Add a check to verify the denominator is non-zero before dividing."
    )

    mock_gemini_response = httpx.Response(
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

    with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
        with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=mock_gemini_response)):
            response = client.post("/v1/explain", json=SAMPLE_PAYLOAD)

    assert response.status_code == 200
    data = response.json()

    # Exact T-07 contract shape: {"request_id": str, "explanation": str}
    assert "request_id" in data
    assert "explanation" in data
    assert data["request_id"] == SAMPLE_PAYLOAD["request_id"]
    assert data["explanation"] == mock_llm_text

    # Sentence limit check
    assert len(split_sentences(data["explanation"])) <= 3


@pytest.mark.anyio
async def test_explain_endpoint_enforces_three_sentences_on_verbose_llm():
    """Test that if the LLM produces >3 sentences, the response is trimmed to 3."""
    verbose_text = (
        "First sentence explains the error. "
        "Second sentence explains the cause. "
        "Third sentence explains the solution. "
        "Fourth sentence provides an unnecessary extra tip."
    )

    mock_response = httpx.Response(
        status_code=200,
        json={
            "candidates": [
                {
                    "content": {
                        "parts": [{"text": verbose_text}],
                    }
                }
            ]
        },
    )

    with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
        with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=mock_response)):
            response = client.post("/v1/explain", json=SAMPLE_PAYLOAD)

    assert response.status_code == 200
    data = response.json()
    sentences = split_sentences(data["explanation"])
    assert len(sentences) == 3
    assert "Fourth sentence" not in data["explanation"]


# ============================================================================
# 3. Error Handling & Contract Error Envelope Tests
# ============================================================================


def test_explain_unset_api_key_returns_contract_error_shape():
    """Test unset API key returns HTTP 502 with PROVIDER_ERROR in contract shape."""
    with patch.object(settings, "LLM_API_KEY", None):
        response = client.post("/v1/explain", json=SAMPLE_PAYLOAD)

    assert response.status_code == 502
    data = response.json()
    assert data["request_id"] == SAMPLE_PAYLOAD["request_id"]
    assert "error" in data
    assert data["error"]["code"] == "PROVIDER_ERROR"
    assert "LLM API key is not configured" in data["error"]["message"]
    assert data["error"]["retry_after_seconds"] is None


@pytest.mark.anyio
async def test_explain_provider_timeout_returns_504_contract_error():
    """Test upstream timeout returns HTTP 504 PROVIDER_TIMEOUT."""
    with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
        with patch(
            "httpx.AsyncClient.post",
            side_effect=httpx.TimeoutException("Connection timed out"),
        ):
            response = client.post("/v1/explain", json=SAMPLE_PAYLOAD)

    assert response.status_code == 504
    data = response.json()
    assert data["request_id"] == SAMPLE_PAYLOAD["request_id"]
    assert data["error"]["code"] == "PROVIDER_TIMEOUT"
    assert "timed out" in data["error"]["message"].lower()


@pytest.mark.anyio
async def test_explain_provider_http_500_returns_502_contract_error():
    """Test upstream 500 error returns HTTP 502 PROVIDER_ERROR."""
    mock_err_response = httpx.Response(
        status_code=500,
        text="Internal Server Error in model service",
    )

    with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
        with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=mock_err_response)):
            response = client.post("/v1/explain", json=SAMPLE_PAYLOAD)

    assert response.status_code == 502
    data = response.json()
    assert data["request_id"] == SAMPLE_PAYLOAD["request_id"]
    assert data["error"]["code"] == "PROVIDER_ERROR"


@pytest.mark.anyio
async def test_explain_provider_rate_limited_returns_429_contract_error():
    """Test upstream 429 returns HTTP 429 RATE_LIMITED_PROVIDER with retry_after."""
    mock_429_response = httpx.Response(
        status_code=429,
        headers={"Retry-After": "45"},
        text="Resource exhausted",
    )

    with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
        with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=mock_429_response)):
            response = client.post("/v1/explain", json=SAMPLE_PAYLOAD)

    assert response.status_code == 429
    data = response.json()
    assert data["request_id"] == SAMPLE_PAYLOAD["request_id"]
    assert data["error"]["code"] == "RATE_LIMITED_PROVIDER"
    assert data["error"]["retry_after_seconds"] == 45


def test_explain_missing_consent_returns_403_consent_missing():
    """Test payload with consent=False returns HTTP 403 CONSENT_MISSING (FR12)."""
    no_consent_payload = dict(SAMPLE_PAYLOAD)
    no_consent_payload["consent"] = False

    response = client.post("/v1/explain", json=no_consent_payload)
    assert response.status_code == 403
    data = response.json()
    assert data["request_id"] == no_consent_payload["request_id"]
    assert data["error"]["code"] == "CONSENT_MISSING"


# ============================================================================
# 4. Privacy Audit Test (NFR8.2)
# ============================================================================


@pytest.mark.anyio
async def test_privacy_no_raw_code_or_stderr_logged(caplog):
    """Test service logs NEVER contain raw source code or stderr (NFR8.2)."""
    secret_canary_code = "canary_user_code_do_not_log"
    secret_canary_stderr = "canary_stderr_trace_do_not_log"

    payload = dict(SAMPLE_PAYLOAD)
    payload["code"] = f"print('{secret_canary_code}')"
    payload["stderr"] = secret_canary_stderr

    mock_response = httpx.Response(
        status_code=200,
        json={"candidates": [{"content": {"parts": [{"text": "Execution crashed. Check inputs."}]}}]},
    )

    with caplog.at_level(logging.DEBUG):
        with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
            with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=mock_response)):
                response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200

    # Ensure none of the log messages contain the raw code or stderr canary strings
    captured_logs = caplog.text
    assert secret_canary_code not in captured_logs
    assert secret_canary_stderr not in captured_logs
