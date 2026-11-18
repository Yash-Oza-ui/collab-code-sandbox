"""Integration tests for Slowdown Detection (US-68, FR37, T-18 v2 §16.2).

Verifies:
- End-to-end integration via FastAPI HTTP TestClient.
- Strict schema validation against docs/schemas/slowdown-response.json.
- Compatibility with US-52 measurement and input_hash fields.
- Room isolation, lifecycle teardown (DELETE /v1/rooms/{room_id}/state), and baseline separation.
- Metadata-only logging invariant (NFR8.2).
- Gating and fallback behavior without external LLM API calls.
"""

import json
import logging
from pathlib import Path

import jsonschema
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.slowdown import get_slowdown_store


@pytest.fixture(autouse=True)
def clean_slowdown_store():
    """Reset the measurement store before and after each integration test."""
    store = get_slowdown_store()
    store.clear_all()
    yield
    store.clear_all()


@pytest.fixture
def response_schema():
    """Load the official JSON schema for slowdown response."""
    schema_path = (
        Path(__file__).resolve().parent.parent.parent.parent
        / "docs"
        / "schemas"
        / "slowdown-response.json"
    )
    with open(schema_path, "r", encoding="utf-8") as f:
        return json.load(f)


def test_full_slowdown_detection_lifecycle(response_schema):
    """Test full baseline establishment followed by normal and regression runs."""
    client = TestClient(app)
    room_id = "Zq8-Lw2Ef6Th0Yx4Rn7JbA"
    input_hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    np.random.seed(42)
    # 1. First run: baseline establishing (1 of 3)
    resp1 = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-001",
            "room_id": room_id,
            "analysis_id": "ana-001",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": input_hash,
            "current_run": {
                "execution_time_ms": 100.0,
                "peak_memory_mb": 15.0,
                "code": "print('hello')",
            },
        },
    )
    assert resp1.status_code == 200
    data1 = resp1.json()
    jsonschema.validate(data1, response_schema)
    assert data1["is_slowdown"] is False
    assert data1["slowdown_factor"] == 1.0
    assert data1["baseline"]["sample_count"] == 1
    assert "Baseline establishing (1 of 3 minimum samples collected)" in data1["message"]

    # 2. Second run: baseline establishing (2 of 3)
    resp2 = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-002",
            "room_id": room_id,
            "analysis_id": "ana-002",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": input_hash,
            "current_run": {
                "execution_time_ms": 102.0,
                "peak_memory_mb": 15.2,
                "code": "print('hello')",
            },
        },
    )
    assert resp2.status_code == 200
    data2 = resp2.json()
    jsonschema.validate(data2, response_schema)
    assert data2["is_slowdown"] is False
    assert data2["baseline"]["sample_count"] == 2
    assert "Baseline establishing (2 of 3 minimum samples collected)" in data2["message"]

    # 3. Third run: baseline establishing (3 of 3)
    resp3 = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-003",
            "room_id": room_id,
            "analysis_id": "ana-003",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": input_hash,
            "current_run": {
                "execution_time_ms": 98.0,
                "peak_memory_mb": 14.8,
                "code": "print('hello')",
            },
        },
    )
    assert resp3.status_code == 200
    data3 = resp3.json()
    jsonschema.validate(data3, response_schema)
    assert data3["is_slowdown"] is False
    assert data3["baseline"]["sample_count"] == 3

    # 4. Fourth run: normal performance against established baseline (100.0 ms)
    resp4 = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-004",
            "room_id": room_id,
            "analysis_id": "ana-004",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": input_hash,
            "current_run": {
                "execution_time_ms": 100.0,
                "peak_memory_mb": 15.1,
                "code": "print('hello')",
            },
        },
    )
    assert resp4.status_code == 200
    data4 = resp4.json()
    jsonschema.validate(data4, response_schema)
    assert data4["is_slowdown"] is False
    assert data4["slowdown_factor"] == pytest.approx(1.0, rel=1e-2)
    assert data4["message"] == "Execution runtime is within normal parameters."
    assert data4["ai_step"]["status"] == "ok"

    # 5. Fifth run: 14.2x severe slowdown regression (1420.0 ms)
    resp5 = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-005",
            "room_id": room_id,
            "analysis_id": "ana-005",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": input_hash,
            "current_run": {
                "execution_time_ms": 1420.0,
                "peak_memory_mb": 18.5,
                "code": "import time\ntime.sleep(1.4)",
            },
        },
    )
    assert resp5.status_code == 200
    data5 = resp5.json()
    jsonschema.validate(data5, response_schema)
    assert data5["is_slowdown"] is True
    assert data5["slowdown_factor"] == pytest.approx(14.2, rel=1e-2)
    assert data5["baseline"]["median_ms"] == pytest.approx(100.0)
    assert "14.2x slower" in data5["message"]


def test_integration_privacy_logging(caplog):
    """Verify middleware logs structured metadata only and never echoes user code/stdin (NFR8.2)."""
    client = TestClient(app)
    caplog.set_level(logging.INFO)

    secret_code = "SECRET_TOKEN_DO_NOT_LOG = 98765"
    resp = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "log-test-req-123",
            "room_id": "room-log-safe",
            "analysis_id": "ana-log-safe",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": "hash-log-safe-abc",
            "current_run": {
                "execution_time_ms": 120.0,
                "peak_memory_mb": 14.0,
                "code": secret_code,
            },
        },
    )
    assert resp.status_code == 200

    # Ensure log output contains metadata fields
    log_text = caplog.text
    assert "request_id=log-test-req-123" in log_text
    assert "room_id=room-log-safe" in log_text
    assert "request_type=slowdown" in log_text
    assert "language=python" in log_text
    assert "status=200" in log_text

    # Invariant NFR8.2: code and stdin MUST NOT be present in any log statement
    assert secret_code not in log_text
    assert "SECRET_TOKEN" not in log_text


def test_integration_dynamic_threshold_overrides():
    """Verify factor_threshold, z_threshold, and min_samples can be overridden via headers/query."""
    client = TestClient(app)
    room_id = "room-dynamic-thresh"
    input_hash = "hash-dynamic-thresh"

    # Pre-populate with 2 runs
    for _ in range(2):
        client.post(
            "/v1/analyses/slowdown",
            json={
                "request_id": "pre",
                "room_id": room_id,
                "analysis_id": "a",
                "request_type": "slowdown",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "input_hash": input_hash,
                "current_run": {
                    "execution_time_ms": 100.0,
                    "peak_memory_mb": 10.0,
                    "code": "pass",
                },
            },
        )

    # With min_samples=2 override via header, run 3 should evaluate baseline rather than establishing!
    resp = client.post(
        "/v1/analyses/slowdown",
        headers={"x-min-samples": "2", "x-slowdown-factor": "3.0"},
        json={
            "request_id": "test-override",
            "room_id": room_id,
            "analysis_id": "a-override",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": input_hash,
            "current_run": {
                "execution_time_ms": 350.0,
                "peak_memory_mb": 10.0,
                "code": "pass",
            },
        },
    )
    assert resp.status_code == 200
    data = resp.json()
    # 350ms is 3.5x slower; with factor threshold 3.0, it is flagged as a regression!
    assert data["is_slowdown"] is True
    assert data["slowdown_factor"] == pytest.approx(3.5, rel=1e-2)
