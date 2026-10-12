"""Unit tests for scaffold router stubs."""

import sys
from pathlib import Path

# Ensure service root is in sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def test_anomaly_stub():
    """Test POST /v1/anomaly baseline stub returns 200 with is_anomalous=False."""
    payload = {
        "request_id": "test-req-001",
        "room_id": "test-room-01",
        "request_type": "anomaly",
        "language": "python",
        "consent": True,
        "execution_time_ms": 250.0,
        "peak_memory_mb": 15.0,
        "stdout_bytes": 100,
        "stderr_bytes": 0,
        "exit_code": 0,
        "timed_out": False,
        "oom_killed": False,
    }
    response = client.post("/v1/anomaly", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["request_id"] == "test-req-001"
    assert data["is_anomalous"] is False
    assert data["score"] == 0.0
    assert data["reasons"] == []


def test_explain_stub_with_consent():
    """Test POST /v1/explain stub returns 200 when user has consented."""
    payload = {
        "request_id": "test-req-002",
        "room_id": "test-room-01",
        "request_type": "explanation",
        "language": "cpp",
        "consent": True,
        "code": "int main() { return 1; }",
        "stderr": "Assertion failed",
        "exit_code": 1,
        "execution_time_ms": 120.0,
        "peak_memory_mb": 4.0,
        "timed_out": False,
        "oom_killed": False,
        "failure_kind": "non_zero_exit",
    }
    response = client.post("/v1/explain", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["request_id"] == "test-req-002"
    assert "explanation" in data
    assert len(data["explanation"]) > 0


def test_explain_stub_without_consent():
    """Test POST /v1/explain returns HTTP 403 when consent is False (FR12.2)."""
    payload = {
        "request_id": "test-req-003",
        "room_id": "test-room-01",
        "request_type": "explanation",
        "language": "cpp",
        "consent": False,
        "code": "int main() { return 1; }",
        "stderr": "",
        "exit_code": 1,
        "execution_time_ms": 50.0,
        "peak_memory_mb": 3.0,
        "timed_out": False,
        "oom_killed": False,
    }
    response = client.post("/v1/explain", json=payload)
    assert response.status_code == 403
    data = response.json()
    assert data["error"]["code"] == "CONSENT_MISSING"


def test_review_stub_with_consent():
    """Test POST /v1/review stub returns 200 when user has consented."""
    payload = {
        "request_id": "test-req-004",
        "room_id": "test-room-01",
        "request_type": "review",
        "language": "python",
        "consent": True,
        "code": "print('hello')",
        "stderr": "",
        "exit_code": 0,
        "execution_time_ms": 30.0,
        "peak_memory_mb": 8.0,
        "timed_out": False,
        "oom_killed": False,
    }
    response = client.post("/v1/review", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["request_id"] == "test-req-004"
    assert "summary" in data


def test_review_stub_without_consent():
    """Test POST /v1/review returns HTTP 403 when consent is False (FR12.2)."""
    payload = {
        "request_id": "test-req-005",
        "room_id": "test-room-01",
        "request_type": "review",
        "language": "python",
        "consent": False,
        "code": "print('hello')",
        "stderr": "",
        "exit_code": 0,
        "execution_time_ms": 30.0,
        "peak_memory_mb": 8.0,
        "timed_out": False,
        "oom_killed": False,
    }
    
    response = client.post("/v1/review", json=payload)
    assert response.status_code == 403
    data = response.json()
    assert data["error"]["code"] == "CONSENT_MISSING"


def test_logging_excludes_raw_code(caplog):
    """Test that application logs never contain raw user code (NFR8.2)."""
    import logging
    caplog.set_level(logging.INFO, logger="ai_ml_service")
    
    unique_code_string = "STRICTLY_CONFIDENTIAL_USER_CODE_998877"
    payload = {
        "request_id": "test-log-001",
        "room_id": "test-room-01",
        "request_type": "review",
        "language": "python",
        "consent": True,
        "code": unique_code_string,
        "stderr": "",
        "exit_code": 0,
        "execution_time_ms": 10.0,
        "peak_memory_mb": 5.0,
        "timed_out": False,
        "oom_killed": False,
    }
    
    response = client.post("/v1/review", json=payload)
    assert response.status_code == 200
    
    # Verify the request was logged, but the raw code was stripped
    assert "Request: POST /v1/review" in caplog.text
    assert unique_code_string not in caplog.text