"""Integration tests for AI/ML call logging (US-25, NFR8.1, NFR8.2).

Sprint 2 definition of done: integration tests for new logging behaviour.

These tests exercise the fully assembled FastAPI app (all middleware, exception
handlers, and routers) with the real request/response pipeline, verifying that:
- Structured log lines with all contract §8 fields are emitted.
- User-written text never appears in logs on any path.
- room_id comes from the validated request body (never 'unknown').
- latency_ms is always present and numeric.

Run from ai-ml-service/:
    pytest tests/integration/test_logging_integration.py -v
"""

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
# Test fixtures
# ---------------------------------------------------------------------------

_MOCK_LLM_OK = httpx.Response(
    status_code=200,
    json={
        "candidates": [
            {
                "content": {
                    "parts": [{"text": "Execution failed due to an error. Review the code. Fix the issue."}],
                }
            }
        ]
    },
)

_MARKER = "XINTEGRATION_MARKER_US25_NFR8_2"


def _explain_payload(room_id: str = "room-integration-001", marker: str = "") -> dict:
    return {
        "request_id": str(uuid.uuid4()),
        "room_id": room_id,
        "request_type": "explanation",
        "language": "python",
        "consent": True,
        "code": marker or "x = 1 / 0",
        "stderr": marker or "ZeroDivisionError",
        "exit_code": 1,
        "execution_time_ms": 10.0,
        "peak_memory_mb": 5.0,
        "timed_out": False,
        "oom_killed": False,
        "failure_kind": "non_zero_exit",
    }


# ---------------------------------------------------------------------------
# Integration Test 1 — Full pipeline: all §8 log fields present on success
# ---------------------------------------------------------------------------


def test_integration_log_line_all_contract_fields_present_on_success(caplog):
    """Integration: successful call emits one log line with all NFR8.1 fields.

    Verifies contract §8 fields: request_id, room_id, request_type, language,
    status, error_code, latency_ms.
    """
    room_id = "room-integration-success-001"
    payload = _explain_payload(room_id=room_id)

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch.object(settings, "LLM_API_KEY", "integration-fake-key"):
            with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=_MOCK_LLM_OK)):
                response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200

    log_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "request_id=" in r.getMessage()
    ]
    assert log_records, "Integration: no structured middleware log line found"

    log_line = log_records[-1].getMessage()

    # Verify all required §8 fields are present.
    assert "request_id=" in log_line, f"Missing request_id in: {log_line!r}"
    assert f"room_id={room_id}" in log_line, f"Wrong/missing room_id in: {log_line!r}"
    assert "request_type=" in log_line, f"Missing request_type in: {log_line!r}"
    assert "language=" in log_line, f"Missing language in: {log_line!r}"
    assert "status=200" in log_line, f"Missing status in: {log_line!r}"
    assert "error_code=none" in log_line, f"Missing error_code in: {log_line!r}"
    assert "latency_ms=" in log_line, f"Missing latency_ms in: {log_line!r}"

    # room_id must never be the placeholder values.
    assert "room_id=unknown" not in log_line
    assert "room_id=no-room" not in log_line

    # latency_ms must be a non-negative number.
    for part in log_line.split():
        if part.startswith("latency_ms="):
            assert float(part.split("=", 1)[1]) >= 0
            break


# ---------------------------------------------------------------------------
# Integration Test 2 — Full pipeline: marker never leaks on any path
# ---------------------------------------------------------------------------


def test_integration_marker_never_leaks_on_success_path(caplog):
    """Integration: user text (marker) in code/stderr never appears in logs."""
    payload = _explain_payload(marker=_MARKER)

    with caplog.at_level(logging.DEBUG):
        with patch.object(settings, "LLM_API_KEY", "integration-fake-key"):
            with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=_MOCK_LLM_OK)):
                response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200

    for record in caplog.records:
        assert _MARKER not in record.getMessage(), (
            f"Integration NFR8.2 violation on success path: marker found in "
            f"'{record.name}' {record.levelname}: {record.getMessage()!r}"
        )


def test_integration_marker_never_leaks_on_provider_error_path(caplog):
    """Integration: user text never leaks even when the LLM call fails (502)."""
    payload = _explain_payload(marker=_MARKER)

    with caplog.at_level(logging.DEBUG):
        with patch.object(settings, "LLM_API_KEY", None):
            response = client.post("/v1/explain", json=payload)

    assert response.status_code == 502

    for record in caplog.records:
        assert _MARKER not in record.getMessage(), (
            f"Integration NFR8.2 violation on error path: marker found in "
            f"'{record.name}' {record.levelname}: {record.getMessage()!r}"
        )


def test_integration_marker_never_leaks_on_validation_error_path(caplog):
    """Integration: user text never leaks when schema validation fails (422)."""
    # Make the payload fail validation by dropping a required field.
    payload = _explain_payload(marker=_MARKER)
    payload.pop("exit_code")

    with caplog.at_level(logging.DEBUG):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 422

    for record in caplog.records:
        assert _MARKER not in record.getMessage(), (
            f"Integration NFR8.2 violation on validation error path: marker found in "
            f"'{record.name}' {record.levelname}: {record.getMessage()!r}"
        )


# ---------------------------------------------------------------------------
# Integration Test 3 — Full pipeline: error_code logged correctly on failure
# ---------------------------------------------------------------------------


def test_integration_error_code_logged_on_consent_missing(caplog):
    """Integration: CONSENT_MISSING error code appears in log line on 403."""
    payload = _explain_payload()
    payload["consent"] = False

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 403

    log_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "error_code=" in r.getMessage()
    ]
    assert log_records, "No middleware log with error_code for consent failure"
    log_line = log_records[-1].getMessage()
    assert "error_code=CONSENT_MISSING" in log_line, (
        f"Expected error_code=CONSENT_MISSING in: {log_line!r}"
    )
    assert "latency_ms=" in log_line


def test_integration_error_code_logged_on_provider_error(caplog):
    """Integration: PROVIDER_ERROR code appears in log line when LLM key missing."""
    payload = _explain_payload()

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch.object(settings, "LLM_API_KEY", None):
            response = client.post("/v1/explain", json=payload)

    assert response.status_code == 502

    log_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "error_code=" in r.getMessage()
    ]
    assert log_records
    log_line = log_records[-1].getMessage()
    assert "error_code=PROVIDER_ERROR" in log_line, (
        f"Expected error_code=PROVIDER_ERROR in: {log_line!r}"
    )
    assert "latency_ms=" in log_line


def test_integration_error_code_logged_on_validation_failure(caplog):
    """Integration: INVALID_REQUEST appears in log line on validation error (422)."""
    payload = _explain_payload()
    payload.pop("exit_code")

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 422

    log_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "error_code=" in r.getMessage()
    ]
    assert log_records, "No middleware log with error_code for validation failure"
    log_line = log_records[-1].getMessage()
    assert "error_code=INVALID_REQUEST" in log_line, (
        f"Expected error_code=INVALID_REQUEST in: {log_line!r}"
    )

