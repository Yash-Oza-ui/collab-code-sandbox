"""Integration tests for AI consent withdrawal and session lifecycle (US-21, FR12.4, NFR8.2, T-18 v2).

Exercises the fully assembled FastAPI application (all middleware, routers, and exception handlers)
to verify that:
1. Withdrawing consent during an active session (consent: false) immediately blocks external LLM dispatch
   with 0 outbound HTTP requests.
2. AI-free functionality (empirical regression, AST pattern detection, AddressSanitizer parsing)
   remains fully functional and returns HTTP 200 with complete results.
3. Skipped AI step returns status: "skipped" and error_code: "CONSENT_MISSING".
4. Restoring consent (consent: true) in a subsequent request re-enables external AI calls without permanent blocking.
5. Independent sessions within the same room maintain isolated consent states (no shared room-level boolean).
6. Service logs strictly omit code, prompts, and confidential markers during withdrawal (NFR8.2).

Run from ai-ml-service/:
    pytest tests/integration/test_consent_withdrawal_integration.py -v
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
                    "parts": [{"text": "Execution optimization fix. Use a hash map. Linear complexity achieved."}],
                }
            }
        ]
    },
)

_CANARY_MARKER = "XINTEGRATION_CANARY_US21_CONFIDENTIAL_MARKER"


def _parse_log_fields(message: str) -> dict[str, str]:
    """Parse space-separated key=value fields emitted by the logging middleware."""
    fields = {}
    for token in message.strip().split():
        if "=" in token:
            k, v = token.split("=", 1)
            fields[k] = v
    return fields


def _get_middleware_records(caplog) -> list[logging.LogRecord]:
    """Return all log records emitted by the ai_ml_service HTTP middleware."""
    return [
        r
        for r in caplog.records
        if r.name == "ai_ml_service" and "request_id=" in r.getMessage()
    ]


def test_integration_consent_withdrawal_complexity_endpoint(caplog):
    """Integration: POST /v1/analyses/complexity with consent withdrawn (US-21).

    Verifies:
    - HTTP 200 OK.
    - Zero outbound HTTP calls dispatched to external LLM provider.
    - AI-free empirical regression & AST rule findings returned intact.
    - ai_step.status == 'skipped', ai_step.error_code == 'CONSENT_MISSING'.
    - Structured middleware log line records status=200 and error_code=CONSENT_MISSING.
    - Canary code marker never leaks into logs (NFR8.2).
    """
    mock_post = AsyncMock(return_value=_MOCK_LLM_OK)
    req_id = str(uuid.uuid4())
    room_id = "room-integ-us21-comp"

    payload = {
        "request_id": req_id,
        "room_id": room_id,
        "analysis_id": str(uuid.uuid4()),
        "request_type": "complexity",
        "language": "python",
        "consent": False,
        "ai_enabled": True,
        "rules_first": False,
        "code": (
            f"def find_duplicates(arr):\n"
            f"    # { _CANARY_MARKER }\n"
            f"    for i in range(len(arr)):\n"
            f"        for j in range(len(arr)):\n"
            f"            pass\n"
        ),
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [4.0, 4.2]},
            {"input_size": 400, "runtimes_ms": [16.0, 16.5]},
        ],
    }

    with caplog.at_level(logging.DEBUG):
        with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
            with patch("httpx.AsyncClient.post", new=mock_post):
                response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    data = response.json()

    # 1. Zero outbound HTTP requests to external LLM provider
    assert mock_post.call_count == 0

    # 2. AI step is skipped with CONSENT_MISSING
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert data["ai_step"]["suggested_fix"] is None

    # 3. AI-free findings preserved
    assert data["estimated_complexity"] == "O(n^2)"
    assert data["growth_rate"] > 1.8
    assert len(data["slow_patterns"]) >= 1
    assert any(p["pattern"] == "nested_loops" for p in data["slow_patterns"])

    # 4. Structured log verification
    records = _get_middleware_records(caplog)
    assert len(records) == 1
    fields = _parse_log_fields(records[0].getMessage())
    assert fields["request_id"] == req_id
    assert fields["room_id"] == room_id
    assert fields["request_type"] == "complexity"
    assert fields["language"] == "python"
    assert fields["status"] == "200"
    assert fields["error_code"] == "CONSENT_MISSING"
    assert float(fields["latency_ms"]) >= 0

    # 5. Privacy audit: canary code marker must never appear in logs
    assert _CANARY_MARKER not in caplog.text


def test_integration_consent_withdrawal_crash_explanation(caplog):
    """Integration: POST /v1/analyses/crash-explanation with consent withdrawn (US-21).

    Verifies:
    - HTTP 200 OK.
    - Zero outbound HTTP calls dispatched to external LLM provider.
    - Deterministic AddressSanitizer parser results returned intact.
    - ai_step.status == 'skipped', ai_step.error_code == 'CONSENT_MISSING'.
    - Canary stderr marker never leaks into logs (NFR8.2).
    """
    mock_post = AsyncMock(return_value=_MOCK_LLM_OK)
    req_id = str(uuid.uuid4())
    room_id = "room-integ-us21-crash"

    payload = {
        "request_id": req_id,
        "room_id": room_id,
        "analysis_id": str(uuid.uuid4()),
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": False,
        "ai_enabled": True,
        "sanitizer_output": (
            f"==5021==ERROR: AddressSanitizer: heap-use-after-free on address 0x6000\n"
            f"READ of size 4 at 0x6000 thread T0 // { _CANARY_MARKER }\n"
            f"    #0 0x401444 in run_task /workspace/app.cpp:42"
        ),
        "code_snippet": f"delete ptr;\nint val = ptr->data; // { _CANARY_MARKER }",
    }

    with caplog.at_level(logging.DEBUG):
        with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
            with patch("httpx.AsyncClient.post", new=mock_post):
                response = client.post("/v1/analyses/crash-explanation", json=payload)

    assert response.status_code == 200
    data = response.json()

    # Zero outbound HTTP calls
    assert mock_post.call_count == 0

    # Sanitizer parsing preserved
    assert data["error_type"] == "heap-use-after-free"
    assert data["line_number"] == 42
    assert "heap-use-after-free" in data["summary"]

    # AI step is skipped
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert data["ai_step"]["explanation"] is None

    # Logging verification
    records = _get_middleware_records(caplog)
    assert len(records) == 1
    fields = _parse_log_fields(records[0].getMessage())
    assert fields["request_id"] == req_id
    assert fields["room_id"] == room_id
    assert fields["error_code"] == "CONSENT_MISSING"

    # Privacy audit
    assert _CANARY_MARKER not in caplog.text


def test_integration_consent_restoration_lifecycle():
    """Integration: Full session lifecycle (grant -> withdraw -> restore) under real FastAPI pipeline (US-21).

    Simulates an active user session:
    1. Request 1: User has granted consent (consent: true) -> Outbound HTTP call made, AI step ok.
    2. Request 2: User withdraws consent (consent: false) -> 0 outbound calls, AI step skipped (CONSENT_MISSING).
    3. Request 3: User restores consent (consent: true) -> Outbound HTTP call made, AI step ok.
    """
    mock_post = AsyncMock(return_value=_MOCK_LLM_OK)
    room_id = "room-integ-session-lifecycle"

    with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
        with patch("httpx.AsyncClient.post", new=mock_post):
            # Step 1: Initial consent granted
            req_1 = {
                "request_id": str(uuid.uuid4()),
                "room_id": room_id,
                "analysis_id": str(uuid.uuid4()),
                "request_type": "complexity",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "rules_first": False,
                "code": "def func1(items):\n    return [x for x in items]",
                "measurements": [{"input_size": 10, "runtimes_ms": [1.0, 1.2]}],
            }
            res_1 = client.post("/v1/analyses/complexity", json=req_1)
            assert res_1.status_code == 200
            assert res_1.json()["ai_step"]["status"] == "ok"
            assert mock_post.call_count == 1

            # Step 2: Consent withdrawn during the session
            req_2 = {
                "request_id": str(uuid.uuid4()),
                "room_id": room_id,
                "analysis_id": str(uuid.uuid4()),
                "request_type": "complexity",
                "language": "python",
                "consent": False,
                "ai_enabled": True,
                "rules_first": False,
                "code": "def func2(items):\n    return [x * 2 for x in items]",
                "measurements": [{"input_size": 10, "runtimes_ms": [1.0, 1.2]}],
            }
            res_2 = client.post("/v1/analyses/complexity", json=req_2)
            assert res_2.status_code == 200
            assert res_2.json()["ai_step"]["status"] == "skipped"
            assert res_2.json()["ai_step"]["error_code"] == "CONSENT_MISSING"
            # Crucial: Outbound call count remains 1 (zero new calls dispatched)
            assert mock_post.call_count == 1

            # Step 3: Consent restored during the same session
            req_3 = {
                "request_id": str(uuid.uuid4()),
                "room_id": room_id,
                "analysis_id": str(uuid.uuid4()),
                "request_type": "complexity",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "rules_first": False,
                "code": "def func3(items):\n    return [x * 3 for x in items]",
                "measurements": [{"input_size": 10, "runtimes_ms": [1.0, 1.2]}],
            }
            res_3 = client.post("/v1/analyses/complexity", json=req_3)
            assert res_3.status_code == 200
            assert res_3.json()["ai_step"]["status"] == "ok"
            # Call count advances to 2 (LLM call re-enabled)
            assert mock_post.call_count == 2


def test_integration_session_isolation_in_same_room():
    """Integration: Session isolation in a shared collaborative room (US-21).

    User A (Session A) withdraws consent (consent: false).
    User B (Session B) keeps consent (consent: true).
    Both users operate in the exact same room 'room-integ-shared-collab'.

    Verifies:
    - User A's requests are blocked with CONSENT_MISSING and 0 outbound calls.
    - User B's requests proceed to outbound LLM and return status 'ok'.
    - User A's subsequent requests continue to be blocked.
    """
    mock_post = AsyncMock(return_value=_MOCK_LLM_OK)
    shared_room = "room-integ-shared-collab"

    with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
        with patch("httpx.AsyncClient.post", new=mock_post):
            # User A in shared room: consent false
            req_user_a = {
                "request_id": str(uuid.uuid4()),
                "room_id": shared_room,
                "analysis_id": str(uuid.uuid4()),
                "request_type": "complexity",
                "language": "python",
                "consent": False,
                "ai_enabled": True,
                "rules_first": False,
                "code": "def user_a_work(x):\n    return x + 1",
                "measurements": [{"input_size": 10, "runtimes_ms": [0.5, 0.6]}],
            }
            res_a = client.post("/v1/analyses/complexity", json=req_user_a)
            assert res_a.status_code == 200
            assert res_a.json()["ai_step"]["status"] == "skipped"
            assert res_a.json()["ai_step"]["error_code"] == "CONSENT_MISSING"
            assert mock_post.call_count == 0

            # User B in same room: consent true
            req_user_b = {
                "request_id": str(uuid.uuid4()),
                "room_id": shared_room,
                "analysis_id": str(uuid.uuid4()),
                "request_type": "complexity",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "rules_first": False,
                "code": "def user_b_work(y):\n    return y * 2",
                "measurements": [{"input_size": 10, "runtimes_ms": [0.5, 0.6]}],
            }
            res_b = client.post("/v1/analyses/complexity", json=req_user_b)
            assert res_b.status_code == 200
            assert res_b.json()["ai_step"]["status"] == "ok"
            assert mock_post.call_count == 1

            # User A makes another request: remains blocked
            req_user_a_followup = {
                "request_id": str(uuid.uuid4()),
                "room_id": shared_room,
                "analysis_id": str(uuid.uuid4()),
                "request_type": "complexity",
                "language": "python",
                "consent": False,
                "ai_enabled": True,
                "rules_first": False,
                "code": "def user_a_work_2(x):\n    return x + 2",
                "measurements": [{"input_size": 10, "runtimes_ms": [0.5, 0.6]}],
            }
            res_a_2 = client.post("/v1/analyses/complexity", json=req_user_a_followup)
            assert res_a_2.status_code == 200
            assert res_a_2.json()["ai_step"]["status"] == "skipped"
            assert res_a_2.json()["ai_step"]["error_code"] == "CONSENT_MISSING"
            # No new outbound calls dispatched
            assert mock_post.call_count == 1

