"""Integration tests for AI/ML call logging (US-25, NFR8.1, NFR8.2).

These tests exercise the fully assembled FastAPI app (all middleware, exception
handlers, and routers) with the real request/response pipeline under T-18 v2
and US-17 consent gating, verifying that:
- Structured log lines with all contract §9/§21 fields are emitted.
- User-written text never appears in logs on any path.
- room_id, request_id, request_type, and language come from validated payloads.
- latency_ms is always present and numeric across success, skipped-AI,
  provider-failure, and 422 validation-failure paths.

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


def _explain_payload(
    room_id: str = "room-integration-001",
    marker: str = "",
    consent: bool = True,
    ai_enabled: bool = True,
) -> dict:
    return {
        "request_id": str(uuid.uuid4()),
        "room_id": room_id,
        "request_type": "explanation",
        "language": "python",
        "consent": consent,
        "ai_enabled": ai_enabled,
        "code": marker or "x = 1 / 0",
        "stderr": marker or "ZeroDivisionError",
        "exit_code": 1,
        "execution_time_ms": 10.0,
        "peak_memory_mb": 5.0,
        "timed_out": False,
        "oom_killed": False,
        "failure_kind": "non_zero_exit",
    }


def _complexity_payload(
    room_id: str = "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
    marker: str = "",
    consent: bool = True,
    ai_enabled: bool = True,
) -> dict:
    return {
        "request_id": str(uuid.uuid4()),
        "room_id": room_id,
        "analysis_id": str(uuid.uuid4()),
        "request_type": "complexity",
        "language": "python",
        "consent": consent,
        "ai_enabled": ai_enabled,
        "code": marker or "for i in range(10):\n    for j in range(10):\n        pass",
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [4.0, 4.2]},
        ],
    }


def _crash_payload(
    room_id: str = "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
    marker: str = "",
    consent: bool = True,
    ai_enabled: bool = True,
    language: str = "cpp",
) -> dict:
    return {
        "request_id": str(uuid.uuid4()),
        "room_id": room_id,
        "analysis_id": str(uuid.uuid4()),
        "request_type": "crash_explanation",
        "language": language,
        "consent": consent,
        "ai_enabled": ai_enabled,
        "sanitizer_output": (
            f"==4210==ERROR: AddressSanitizer: heap-buffer-overflow {marker}\n"
            "    #0 0x401233 in process_buffer /workspace/solution.cpp:14"
        ),
        "code_snippet": marker or "int val = buffer[index];",
    }


def _parse_fields(log_line: str) -> dict[str, str]:
    return dict(part.split("=", 1) for part in log_line.split() if "=" in part)


# ---------------------------------------------------------------------------
# Integration Test 1 — Full pipeline: all §9/§21 log fields present on success
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "path, payload_fn, expected_type, expected_lang",
    [
        ("/v1/explain", _explain_payload, "explanation", "python"),
        ("/v1/analyses/complexity", _complexity_payload, "complexity", "python"),
        ("/v1/analyses/crash-explanation", _crash_payload, "crash_explanation", "cpp"),
    ],
)
def test_integration_log_line_all_contract_fields_present_on_success(
    caplog, path, payload_fn, expected_type, expected_lang
):
    """Integration: successful call emits one log line with all NFR8.1 fields."""
    room_id = "Zq8-Lw2Ef6Th0Yx4Rn7JbA"
    payload = payload_fn(room_id=room_id)

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch.object(settings, "LLM_API_KEY", "integration-fake-key"):
            with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=_MOCK_LLM_OK)):
                response = client.post(path, json=payload)

    assert response.status_code == 200

    log_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "request_id=" in r.getMessage()
    ]
    assert log_records, "Integration: no structured middleware log line found"

    fields = _parse_fields(log_records[-1].getMessage())
    assert fields["request_id"] == payload["request_id"]
    assert fields["room_id"] == room_id
    assert fields["request_type"] == expected_type
    assert fields["language"] == expected_lang
    assert fields["status"] == "200"
    assert fields["error_code"] == "none"
    assert float(fields["latency_ms"]) >= 0


# ---------------------------------------------------------------------------
# Integration Test 2 — Full pipeline: marker never leaks on any path
# ---------------------------------------------------------------------------


def test_integration_marker_never_leaks_on_success_path(caplog):
    """Integration: user text (marker) in code/stderr/sanitizer fields never appears in logs."""
    with caplog.at_level(logging.DEBUG):
        with patch.object(settings, "LLM_API_KEY", "integration-fake-key"):
            with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=_MOCK_LLM_OK)):
                assert client.post("/v1/explain", json=_explain_payload(marker=_MARKER)).status_code == 200
                assert client.post("/v1/analyses/complexity", json=_complexity_payload(marker=_MARKER)).status_code == 200
                assert client.post("/v1/analyses/crash-explanation", json=_crash_payload(marker=_MARKER)).status_code == 200

    for record in caplog.records:
        assert _MARKER not in record.getMessage()


def test_integration_marker_never_leaks_on_provider_error_path(caplog):
    """Integration: user text never leaks even when the LLM call fails (502 on /explain)."""
    payload = _explain_payload(marker=_MARKER, consent=True, ai_enabled=True)

    with caplog.at_level(logging.DEBUG):
        with patch.object(settings, "LLM_API_KEY", None):
            response = client.post("/v1/explain", json=payload)

    assert response.status_code == 502

    for record in caplog.records:
        assert _MARKER not in record.getMessage()


def test_integration_marker_never_leaks_on_validation_error_path(caplog):
    """Integration: user text never leaks when schema validation fails (422)."""
    payload = _explain_payload(marker=_MARKER)
    payload.pop("exit_code")

    with caplog.at_level(logging.DEBUG):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 422

    for record in caplog.records:
        assert _MARKER not in record.getMessage()


# ---------------------------------------------------------------------------
# Integration Test 3 — Full pipeline: skipped/error error_code logged accurately
# ---------------------------------------------------------------------------


def test_integration_error_code_logged_on_consent_missing(caplog):
    """Integration: CONSENT_MISSING logged with HTTP 200 skipped AI step under T-18 v2."""
    payload = _explain_payload(consent=False, ai_enabled=True)

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 200
    assert response.json()["ai_step"]["status"] == "skipped"
    assert response.json()["ai_step"]["error_code"] == "CONSENT_MISSING"

    log_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "error_code=" in r.getMessage()
    ]
    assert log_records
    fields = _parse_fields(log_records[-1].getMessage())
    assert fields["status"] == "200"
    assert fields["error_code"] == "CONSENT_MISSING"
    assert float(fields["latency_ms"]) >= 0


def test_integration_error_code_logged_on_ai_disabled(caplog):
    """Integration: AI_DISABLED logged with HTTP 200 skipped AI step (FR35)."""
    payload = _complexity_payload(consent=True, ai_enabled=False)

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    assert response.json()["ai_step"]["status"] == "skipped"
    assert response.json()["ai_step"]["error_code"] == "AI_DISABLED"

    log_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "error_code=" in r.getMessage()
    ]
    assert log_records
    fields = _parse_fields(log_records[-1].getMessage())
    assert fields["request_type"] == "complexity"
    assert fields["status"] == "200"
    assert fields["error_code"] == "AI_DISABLED"
    assert float(fields["latency_ms"]) >= 0


def test_integration_error_code_logged_on_provider_error(caplog):
    """Integration: PROVIDER_ERROR code appears in log line when LLM key missing."""
    payload = _explain_payload(consent=True, ai_enabled=True)

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch.object(settings, "LLM_API_KEY", None):
            response = client.post("/v1/explain", json=payload)

    assert response.status_code == 502

    log_records = [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "error_code=" in r.getMessage()
    ]
    assert log_records
    fields = _parse_fields(log_records[-1].getMessage())
    assert fields["status"] == "502"
    assert fields["error_code"] == "PROVIDER_ERROR"
    assert float(fields["latency_ms"]) >= 0


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
    assert log_records
    fields = _parse_fields(log_records[-1].getMessage())
    assert fields["status"] == "422"
    assert fields["error_code"] == "INVALID_REQUEST"
    assert float(fields["latency_ms"]) >= 0
