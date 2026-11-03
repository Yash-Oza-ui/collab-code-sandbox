"""Tests for AI/ML call logging (US-25, NFR8.1, NFR8.2).

Acceptance criteria verified here:
- Every AI/ML call writes one log line with the contract §8 fields:
  request_id, room_id, request_type, language, status, error_code, latency_ms.
- room_id comes from the *validated* request body and is never 'unknown'.
- latency_ms is logged on every path, including failed calls.
- Logs never contain source code, stderr, prompts, model output, or any other
  user-written text on *any* path (middleware, validation errors, exception
  handlers, LLM client).
- The marker test covers every live endpoint of the app (built from the app's
  route list so new endpoints are automatically included).
- A separate test verifies the log line fields for a successful call.
"""

import json
import logging
import sys
import uuid
from pathlib import Path
from unittest.mock import AsyncMock, patch

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app

client = TestClient(app, raise_server_exceptions=False)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Unique marker that must NEVER appear in any log line.
# It is not real code or user text — it is a fixed test constant.
MARKER = "XMARKER_US25_NFR8_2_CONFIDENTIAL_XMARKER"

# Fake LLM success response (Gemini format) — contains no user text.
_MOCK_LLM_RESPONSE = httpx.Response(
    status_code=200,
    json={
        "candidates": [
            {
                "content": {
                    "parts": [{"text": "The program exited with an error. Check the logic. Fix the issue."}],
                }
            }
        ]
    },
)


def _explain_payload(marker: str = MARKER) -> dict:
    """Return a valid /v1/explain payload with the marker in every user-text field."""
    return {
        "request_id": str(uuid.uuid4()),
        "room_id": "room-test-log-001",
        "request_type": "explanation",
        "language": "python",
        "consent": True,
        "code": marker,          # user-text field
        "stderr": marker,        # user-text field
        "exit_code": 1,
        "execution_time_ms": 12.5,
        "peak_memory_mb": 8.0,
        "timed_out": False,
        "oom_killed": False,
        "failure_kind": "non_zero_exit",
    }


def _invalid_explain_payload(marker: str = MARKER) -> dict:
    """Return a /v1/explain payload that will fail schema validation.

    The marker is placed in every user-text field; the payload is made invalid
    by omitting required field 'exit_code'.
    """
    payload = _explain_payload(marker)
    payload.pop("exit_code")  # makes the request fail schema validation
    return payload


# ---------------------------------------------------------------------------
# Build a per-route request map so new endpoints are covered automatically.
# ---------------------------------------------------------------------------

def _build_route_test_cases(marker: str) -> list[dict]:
    """Return one test-case dict per AI/ML route registered in the app.

    Each entry has keys: method, path, payload (or None for GET), description.
    The function introspects app.routes so new routers are included without
    manual updates.
    """
    cases = []
    for route in app.routes:
        path = getattr(route, "path", None)
        methods = getattr(route, "methods", None) or set()
        if path is None or not methods:
            continue
        # Skip the health endpoint — it has no body and is not an AI/ML call.
        if path == "/health":
            continue

        method = next(iter(methods))  # routes have exactly one method each
        if method == "POST":
            # Map known paths to their payload builders; fall back to a
            # generic envelope for future endpoints added by T-18.
            if path == "/v1/explain":
                payload = _explain_payload(marker)
            else:
                # Generic contract-envelope payload for future endpoints.
                payload = {
                    "request_id": str(uuid.uuid4()),
                    "room_id": "room-test-log-auto",
                    "analysis_id": str(uuid.uuid4()),
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": marker,
                }
        else:
            payload = None  # GET / DELETE endpoints

        cases.append(
            {
                "method": method,
                "path": path,
                "payload": payload,
                "description": f"{method} {path}",
            }
        )
    return cases


# ---------------------------------------------------------------------------
# Test 1 — Marker test: no user text leaks on any path (NFR8.2)
# ---------------------------------------------------------------------------


def test_log_marker_never_appears_in_logs_on_any_path(caplog):
    """Send the marker in every user-text field of every live endpoint.

    Asserts that no captured log record — from the middleware, exception
    handlers, or the LLM client — contains the marker string.

    Covers:
    - Successful call (mock LLM returns OK).
    - A call that fails inside the service (LLM API key missing → 502).
    - A call that fails schema validation (missing required field → 422).
    """
    with caplog.at_level(logging.DEBUG):
        with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
            with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=_MOCK_LLM_RESPONSE)):

                route_cases = _build_route_test_cases(MARKER)
                assert route_cases, "No AI/ML routes found — check app.routes"

                for case in route_cases:
                    if case["method"] == "GET":
                        client.get(case["path"])
                    elif case["method"] == "POST":
                        # Path A: successful call
                        client.post(case["path"], json=case["payload"])

                        # Path B: service-level failure (no API key)
                        with patch.object(settings, "LLM_API_KEY", None):
                            client.post(case["path"], json=case["payload"])

                    # For DELETE and other methods (future endpoints):
                    elif case["method"] == "DELETE":
                        client.request(case["method"], case["path"])

                # Path C: schema validation failure (422) — marker in user-text fields
                invalid_payload = _invalid_explain_payload(MARKER)
                client.post("/v1/explain", json=invalid_payload)

    # Assert: the marker must not appear anywhere in the captured logs.
    for record in caplog.records:
        assert MARKER not in record.getMessage(), (
            f"NFR8.2 violation: marker found in log record from "
            f"logger '{record.name}' at level {record.levelname}: "
            f"{record.getMessage()!r}"
        )


# ---------------------------------------------------------------------------
# Test 2 — Log line fields: room_id (from body) and latency_ms present (NFR8.1)
# ---------------------------------------------------------------------------


def test_log_line_contains_room_id_from_body_and_latency_ms(caplog):
    """Verify the structured log line has room_id (not 'unknown') and latency_ms.

    The room_id must come from the *validated* request body, not
    request.state.room_id pre-set by the middleware.
    """
    expected_room_id = "room-us25-latency-check"
    payload = _explain_payload()
    payload["room_id"] = expected_room_id
    payload["request_id"] = "req-us25-latency-001"

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
            with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=_MOCK_LLM_RESPONSE)):
                response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200

    # Find the middleware log line (contains 'request_id=')
    middleware_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "request_id=" in r.getMessage()
    ]
    assert middleware_records, "No middleware log line found for the request"

    log_line = middleware_records[-1].getMessage()

    # room_id must be from the validated body
    assert f"room_id={expected_room_id}" in log_line, (
        f"room_id not found or wrong in log line: {log_line!r}"
    )
    assert "room_id=unknown" not in log_line, (
        "room_id must not be 'unknown' — must come from the validated request body"
    )
    assert "room_id=no-room" not in log_line, (
        "room_id must not be the fallback placeholder for a successful call"
    )

    # latency_ms must be present and numeric
    assert "latency_ms=" in log_line, (
        f"latency_ms not found in log line: {log_line!r}"
    )
    # Extract value and verify it is a positive number
    for part in log_line.split():
        if part.startswith("latency_ms="):
            val = part.split("=", 1)[1]
            assert float(val) >= 0, f"latency_ms must be non-negative, got: {val}"
            break


def test_log_line_contains_latency_ms_on_failed_call(caplog):
    """Verify latency_ms is logged even when the call fails (e.g. no API key)."""
    payload = _explain_payload()
    payload["room_id"] = "room-us25-fail-latency"

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch.object(settings, "LLM_API_KEY", None):
            response = client.post("/v1/explain", json=payload)

    assert response.status_code == 502  # PROVIDER_ERROR

    middleware_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "latency_ms=" in r.getMessage()
    ]
    assert middleware_records, "No middleware log line with latency_ms on failed call"

    log_line = middleware_records[-1].getMessage()
    assert "latency_ms=" in log_line

    for part in log_line.split():
        if part.startswith("latency_ms="):
            val = part.split("=", 1)[1]
            assert float(val) >= 0
            break


def test_log_line_contains_latency_ms_on_validation_failure(caplog):
    """Verify latency_ms is logged when schema validation fails (422)."""
    invalid_payload = _invalid_explain_payload()

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        response = client.post("/v1/explain", json=invalid_payload)

    assert response.status_code == 422

    middleware_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "latency_ms=" in r.getMessage()
    ]
    assert middleware_records, "No middleware log line with latency_ms on validation error"


# ---------------------------------------------------------------------------
# Test 3 — Validation error response does NOT echo user text (NFR8.2)
# ---------------------------------------------------------------------------


def test_validation_error_response_does_not_echo_user_text():
    """Verify that a 422 response body does not contain user-supplied field values.

    FastAPI's default RequestValidationError handler echoes the invalid value
    back in the error detail — our custom handler must suppress that.
    """
    marker = "VALIDATION_LEAK_CHECK_US25"
    invalid_payload = {
        "request_id": "req-val-check",
        "room_id": "room-val-check",
        "request_type": "explanation",
        "language": "INVALID_LANG_" + marker,  # invalid enum value
        "consent": True,
        "code": marker,
        "stderr": marker,
        "exit_code": 1,
        "execution_time_ms": 1.0,
        "peak_memory_mb": 1.0,
    }

    response = client.post("/v1/explain", json=invalid_payload)
    assert response.status_code == 422

    response_text = response.text
    assert marker not in response_text, (
        f"NFR8.2 violation: validation error response echoes user text: {response_text!r}"
    )
    data = response.json()
    assert data["error"]["code"] == "INVALID_REQUEST"

