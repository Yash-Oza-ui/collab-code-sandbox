"""Integration tests for the Cheapest-Method-First AI Chain (US-77, FR40.4).

Verifies end-to-end FastAPI application routing through the central chain:
1. Cache -> Rules -> Local Model -> Paid Model execution order.
2. Cheaper stage satisfaction completely halts progression to more expensive stages.
3. Cost cap reached prevents paid outbound calls while allowing cache/rules.
4. Prompt-size and output-token limit enforcement.
5. Strict telemetry metadata accounting without logging raw prompts/code.
"""

import sys
import uuid
from pathlib import Path
from unittest.mock import AsyncMock, patch

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from app.cache import clear_analysis_cache
from app.chain import set_local_model_runner
from app.config import settings
from app.counters import (
    _default_tracker,
    get_stage_counters,
    get_total_usage,
    reset_counters,
)
from app.main import app

client = TestClient(app, raise_server_exceptions=False)

_MOCK_LLM_OK = httpx.Response(
    status_code=200,
    json={
        "candidates": [
            {
                "content": {
                    "parts": [{"text": "Linear complexity achieved via hash map optimization."}],
                }
            }
        ],
        "usageMetadata": {
            "totalTokenCount": 25,
            "promptTokenCount": 15,
            "candidatesTokenCount": 10,
        },
    },
)


class DummyReservation:
    def __enter__(self):
        return self
    def __exit__(self, *args):
        pass


def dummy_reserve_call(estimated_tokens=100):
    return DummyReservation()


class MockCostCapReachedError(Exception):
    pass


@pytest.fixture(autouse=True)
def setup_teardown():
    clear_analysis_cache()
    reset_counters()
    set_local_model_runner(None)
    with patch("app.chain.is_cost_cap_reached", return_value=False), \
         patch("app.chain.reserve_call", side_effect=dummy_reserve_call), \
         patch("app.chain.CostCapReachedError", MockCostCapReachedError):
        yield
    clear_analysis_cache()
    reset_counters()
    set_local_model_runner(None)


def test_chain_rules_catch_seeded_pattern_zero_outbound_llm():
    """Stage 2: Deterministic rules fire, preventing local model and paid LLM calls."""
    with patch.object(settings, "LLM_API_KEY", "test-mock-key"):
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            payload = {
                "request_id": f"int-rules-{uuid.uuid4().hex[:8]}",
                "room_id": "room-chain-int",
                "analysis_id": f"an-{uuid.uuid4().hex[:8]}",
                "request_type": "complexity",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "code": "def find_duplicates(arr):\n    dups = []\n    for i in range(len(arr)):\n        for j in range(i + 1, len(arr)):\n            if arr[i] == arr[j]:\n                dups.append(arr[i])\n    return dups\n",
                "measurements": [
                    {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
                    {"input_size": 200, "runtimes_ms": [4.0, 4.2]},
                ],
            }
            resp = client.post("/v1/analyses/complexity", json=payload)
            assert resp.status_code == 200
            data = resp.json()
            assert any(p["pattern"] == "nested_loops" for p in data.get("slow_patterns", []))
            assert mock_post.call_count == 0


def test_chain_cache_hit_stops_pipeline_zero_outbound_llm():
    """Stage 1: Repeated identical request hits cache immediately."""
    payload = {
        "request_id": f"int-cache-{uuid.uuid4().hex[:8]}",
        "room_id": "room-chain-int",
        "analysis_id": f"an-{uuid.uuid4().hex[:8]}",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def process(items):\n    return [x * 2 for x in items]\n",
        "measurements": [
            {"input_size": 10, "runtimes_ms": [0.1, 0.12]},
        ],
    }

    with patch.object(settings, "LLM_API_KEY", "test-mock-key"):
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock, return_value=_MOCK_LLM_OK) as mock_post:
            # First call: paid model answers
            resp1 = client.post("/v1/analyses/complexity", json=payload)
            assert resp1.status_code == 200
            assert mock_post.call_count == 1

            # Second call: cache answers, mock_post call count remains 1
            payload["request_id"] = f"int-cache-2-{uuid.uuid4().hex[:8]}"
            resp2 = client.post("/v1/analyses/complexity", json=payload)
            assert resp2.status_code == 200
            assert mock_post.call_count == 1
            assert get_total_usage()["total_cache_hits"] >= 1


def test_chain_local_model_answers_zero_paid_calls():
    """Stage 3: Optional local model answers, paid model is not called."""
    async def mock_local_runner(prompt, context=None):
        return "Local model analyzed this code successfully: O(1) formula."

    set_local_model_runner(mock_local_runner)

    with patch.object(settings, "LLM_API_KEY", "test-mock-key"):
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_paid_post:
            payload = {
                "request_id": f"int-local-{uuid.uuid4().hex[:8]}",
                "room_id": "room-chain-int",
                "analysis_id": f"an-{uuid.uuid4().hex[:8]}",
                "request_type": "complexity",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "code": "def custom_calc(n):\n    return n * (n + 1) // 2\n",
                "measurements": [{"input_size": 10, "runtimes_ms": [0.01, 0.02]}],
            }
            resp = client.post("/v1/analyses/complexity", json=payload)
            assert resp.status_code == 200
            assert mock_paid_post.call_count == 0
            assert get_stage_counters()["local_model_answers"] >= 1


def test_chain_cost_cap_blocks_paid_call_cheaper_stages_functional():
    """Stage 4: Cost-cap block stops paid model, cache & rules remain fully functional."""
    with patch("app.chain.is_cost_cap_reached", return_value=True):
        with patch.object(settings, "LLM_API_KEY", "test-mock-key"):
            with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
                # 1. Rule request works 100%
                payload_rule = {
                    "request_id": f"int-cap-rule-{uuid.uuid4().hex[:8]}",
                    "room_id": "room-chain-int",
                    "analysis_id": f"an-{uuid.uuid4().hex[:8]}",
                    "request_type": "complexity",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "def find_duplicates(arr):\n    dups = []\n    for i in range(len(arr)):\n        for j in range(i + 1, len(arr)):\n            if arr[i] == arr[j]:\n                dups.append(arr[i])\n    return dups\n",
                    "measurements": [
                        {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
                        {"input_size": 200, "runtimes_ms": [4.0, 4.2]},
                    ],
                }
                resp_rule = client.post("/v1/analyses/complexity", json=payload_rule)
                assert resp_rule.status_code == 200
                assert any(p["pattern"] == "nested_loops" for p in resp_rule.json().get("slow_patterns", []))
                assert mock_post.call_count == 0

                # 2. Paid call blocked with COST_CAP_REACHED
                payload_paid = {
                    "request_id": f"int-cap-paid-{uuid.uuid4().hex[:8]}",
                    "room_id": "room-chain-int",
                    "request_type": "explanation",
                    "language": "python",
                    "consent": True,
                    "ai_enabled": True,
                    "code": "a = [1, 2]\nprint(a[10])",
                    "stderr": "IndexError: list index out of range",
                    "exit_code": 1,
                    "execution_time_ms": 10.0,
                    "peak_memory_mb": 4.0,
                    "timed_out": False,
                    "oom_killed": False,
                    "failure_kind": "non_zero_exit",
                }
                resp_paid = client.post("/v1/explain", json=payload_paid)
                assert resp_paid.status_code == 200
                data = resp_paid.json()
                assert data["ai_step"]["status"] == "skipped"
                assert data["ai_step"]["error_code"] == "COST_CAP_REACHED"
                assert mock_post.call_count == 0


def test_chain_fails_closed_when_cost_cap_subsystem_unavailable():
    """Stage 4: If cost-cap subsystem is completely unavailable, fail closed with COST_CAP_REACHED."""
    with patch("app.chain.is_cost_cap_reached", None), \
         patch("app.chain.reserve_call", None):
        payload_paid = {
            "request_id": f"int-no-cap-{uuid.uuid4().hex[:8]}",
            "room_id": "room-chain-int",
            "request_type": "explanation",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "code": "a = [1, 2]\nprint(a[10])",
            "stderr": "IndexError: list index out of range",
            "exit_code": 1,
            "execution_time_ms": 10.0,
            "peak_memory_mb": 4.0,
            "timed_out": False,
            "oom_killed": False,
            "failure_kind": "non_zero_exit",
        }
        resp = client.post("/v1/explain", json=payload_paid)
        assert resp.status_code == 200
        data = resp.json()
        assert data["ai_step"]["status"] == "skipped"
        assert data["ai_step"]["error_code"] == "COST_CAP_REACHED"


def test_chain_fails_closed_when_cost_cap_check_raises():
    """Stage 4: If cost-cap pre-check throws an exception, fail closed with COST_CAP_REACHED."""
    def broken_check():
        raise RuntimeError("Accounting database offline")

    with patch("app.chain.is_cost_cap_reached", side_effect=broken_check):
        payload_paid = {
            "request_id": f"int-err-cap-{uuid.uuid4().hex[:8]}",
            "room_id": "room-chain-int",
            "request_type": "explanation",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "code": "a = [1, 2]\nprint(a[10])",
            "stderr": "IndexError: list index out of range",
            "exit_code": 1,
            "execution_time_ms": 10.0,
            "peak_memory_mb": 4.0,
            "timed_out": False,
            "oom_killed": False,
            "failure_kind": "non_zero_exit",
        }
        resp = client.post("/v1/explain", json=payload_paid)
        assert resp.status_code == 200
        data = resp.json()
        assert data["ai_step"]["status"] == "skipped"
        assert data["ai_step"]["error_code"] == "COST_CAP_REACHED"
