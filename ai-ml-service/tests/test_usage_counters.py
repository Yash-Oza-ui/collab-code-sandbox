"""Tests for AI usage counters and telemetry (FR40.1, US-75, NFR8.2, T-18 v2).

Verifies:
1. Counter interface (record_model_call, record_cache_hit, record_fallback, clear_room, get_room_usage, get_total_usage).
2. Multi-room isolation: two rooms with a mix of calls, cache hits, and fallbacks verify per-room and total aggregation.
3. Token accumulation: tokens correctly extracted from mocked Gemini (usageMetadata) and OpenAI (usage) provider responses.
4. Privacy Invariant (NFR8.2 marker test): unique canary marker in all user-text fields never appears in counter store.
5. Room teardown (US-56): clear_room removes targeted room counters while leaving other rooms intact.
6. Contract endpoints: GET /v1/usage, GET /v1/rooms/{room_id}/usage, and DELETE /v1/rooms/{room_id}/state.
7. Thread safety: concurrent updates across threads aggregate without data race.
"""

import json
import reprlib
import sys
import threading
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi.testclient import TestClient

# Ensure service root is in sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.cache import AnalysisCache, clear_analysis_cache, compute_cache_key
from app.config import settings
from app.counters import (
    UsageTracker,
    clear_room,
    get_room_usage,
    get_total_usage,
    get_tracker,
    record_cache_hit,
    record_fallback,
    record_model_call,
    reset_counters,
)
from app.llm.client import LLMClient
from app.main import app

client = TestClient(app)


@pytest.fixture(autouse=True)
def clean_counter_state():
    """Reset counters and cache before and after each test."""
    reset_counters()
    clear_analysis_cache()
    yield
    reset_counters()
    clear_analysis_cache()


# ============================================================================
# 1. Interface and Basic Counting Tests
# ============================================================================


def test_initial_state_empty():
    """Verify that a fresh counter starts with zero metrics."""
    usage = get_total_usage()
    assert usage["total_model_calls"] == 0
    assert usage["total_tokens_used"] == 0
    assert usage["total_cache_hits"] == 0
    assert usage["total_fallback_activations"] == 0
    assert usage["monthly_spend_usd"] == 0.0
    assert usage["monthly_budget_usd"] == 10.0
    assert usage["alert_80_percent_triggered"] is False
    assert usage["cost_cap_reached"] is False
    assert usage["rooms_tracked"] == 0


def test_single_room_records_events():
    """Verify recording model call, cache hit, and fallback for one room."""
    record_model_call("room-alpha", tokens=120)
    record_cache_hit("room-alpha")
    record_fallback("room-alpha")

    room = get_room_usage("room-alpha")
    assert room["room_id"] == "room-alpha"
    assert room["model_calls"] == 1
    assert room["tokens_used"] == 120
    assert room["cache_hits"] == 1
    assert room["fallback_activations"] == 1

    total = get_total_usage()
    assert total["total_model_calls"] == 1
    assert total["total_tokens_used"] == 120
    assert total["total_cache_hits"] == 1
    assert total["total_fallback_activations"] == 1
    assert total["rooms_tracked"] == 1


# ============================================================================
# 2. Multi-Room Tracking & Aggregation Test
# ============================================================================


def test_two_rooms_mixed_events_per_room_and_total():
    """Test: two rooms, a mix of calls, cache hits and fallbacks: per-room and total counts are right."""
    room_1 = "room-test-001"
    room_2 = "room-test-002"

    # Room 1 events
    record_model_call(room_1, tokens=50)
    record_model_call(room_1, tokens=75)
    record_cache_hit(room_1)
    record_fallback(room_1)

    # Room 2 events
    record_model_call(room_2, tokens=200)
    record_cache_hit(room_2)
    record_cache_hit(room_2)
    record_cache_hit(room_2)
    record_fallback(room_2)
    record_fallback(room_2)

    # Check Room 1
    r1 = get_room_usage(room_1)
    assert r1["model_calls"] == 2
    assert r1["tokens_used"] == 125
    assert r1["cache_hits"] == 1
    assert r1["fallback_activations"] == 1

    # Check Room 2
    r2 = get_room_usage(room_2)
    assert r2["model_calls"] == 1
    assert r2["tokens_used"] == 200
    assert r2["cache_hits"] == 3
    assert r2["fallback_activations"] == 2

    # Check Total Aggregates
    tot = get_total_usage()
    assert tot["total_model_calls"] == 3
    assert tot["total_tokens_used"] == 325
    assert tot["total_cache_hits"] == 4
    assert tot["total_fallback_activations"] == 3
    assert tot["rooms_tracked"] == 2
    # Spend = 325 * 0.0000035 = 0.0011375 -> rounded to 0.0011
    assert tot["monthly_spend_usd"] == 0.0011


# ============================================================================
# 3. Provider Token Extraction (Gemini & OpenAI)
# ============================================================================


@pytest.mark.anyio
async def test_tokens_accumulate_from_gemini_provider_response():
    """Test: token counts add up from (mocked) Gemini provider responses (usageMetadata)."""
    client_instance = LLMClient()
    room_id = "room-gemini-test"

    # Mock Gemini HTTP response with usageMetadata
    gemini_payload_1 = {
        "candidates": [{"content": {"parts": [{"text": "First Gemini Fix"}]}}],
        "usageMetadata": {
            "promptTokenCount": 100,
            "candidatesTokenCount": 45,
            "totalTokenCount": 145,
        },
    }
    gemini_payload_2 = {
        "candidates": [{"content": {"parts": [{"text": "Second Gemini Fix"}]}}],
        "usageMetadata": {
            "promptTokenCount": 80,
            "candidatesTokenCount": 20,
            "totalTokenCount": 100,
        },
    }

    mock_resp_1 = MagicMock()
    mock_resp_1.status_code = 200
    mock_resp_1.json.return_value = gemini_payload_1

    mock_resp_2 = MagicMock()
    mock_resp_2.status_code = 200
    mock_resp_2.json.return_value = gemini_payload_2

    with patch("httpx.AsyncClient.post", side_effect=[mock_resp_1, mock_resp_2]):
        out1 = await client_instance.complete("prompt 1", api_key="test-key", model="gemini-1.5-flash", room_id=room_id)
        out2 = await client_instance.complete("prompt 2", api_key="test-key", model="gemini-1.5-flash", room_id=room_id)

    assert out1 == "First Gemini Fix"
    assert out2 == "Second Gemini Fix"

    room = get_room_usage(room_id)
    assert room["model_calls"] == 2
    assert room["tokens_used"] == 245  # 145 + 100
    assert get_total_usage()["total_tokens_used"] == 245


@pytest.mark.anyio
async def test_tokens_accumulate_from_openai_provider_response():
    """Test: token counts add up from (mocked) OpenAI provider responses (usage)."""
    client_instance = LLMClient()
    room_id = "room-openai-test"

    openai_payload = {
        "choices": [{"message": {"content": "OpenAI optimization answer"}}],
        "usage": {
            "prompt_tokens": 60,
            "completion_tokens": 30,
            "total_tokens": 90,
        },
    }

    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = openai_payload

    with patch("httpx.AsyncClient.post", return_value=mock_resp):
        out = await client_instance.complete("prompt", api_key="test-key", model="gpt-4o", room_id=room_id)

    assert out == "OpenAI optimization answer"
    room = get_room_usage(room_id)
    assert room["model_calls"] == 1
    assert room["tokens_used"] == 90
    assert get_total_usage()["total_tokens_used"] == 90


# ============================================================================
# 4. Strict Privacy Invariant / Marker Test (NFR8.2)
# ============================================================================


@pytest.mark.anyio
async def test_counter_store_never_holds_raw_code_or_user_text():
    """Test (NFR8.2): with a unique marker in every user-text field, the counter store contains no marker."""
    canary = "CANARY_MARKER_987654321_DO_NOT_STORE_CODE_OR_PROMPT"
    room_id = "room-marker-test-88"

    # 1. Directly record operations with standard numeric counts
    record_model_call(room_id, tokens=42)
    record_cache_hit(room_id)
    record_fallback(room_id)

    # 2. Call LLM complete with user text containing the canary marker
    client_instance = LLMClient()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "candidates": [{"content": {"parts": [{"text": f"Fixing {canary}"}]}}],
        "usageMetadata": {"totalTokenCount": 50},
    }
    with patch("httpx.AsyncClient.post", return_value=mock_resp):
        await client_instance.complete(
            prompt=f"Please optimize this sensitive code: {canary}",
            api_key="test-key",
            model="gemini-1.5-flash",
            room_id=room_id,
        )

    tracker = get_tracker()

    # 3. Comprehensive inspection of tracker memory structures
    tracker_dict_dump = json.dumps(
        {
            "totals": {
                "calls": tracker._total_model_calls,
                "tokens": tracker._total_tokens_used,
                "cache": tracker._total_cache_hits,
                "fallback": tracker._total_fallback_activations,
            },
            "rooms": tracker._room_counters,
        }
    )

    # Assert canary marker is absent from all stored counter structures
    assert canary not in tracker_dict_dump
    assert canary not in str(tracker.__dict__)
    assert canary not in repr(tracker._room_counters)

    # Verify that every value stored in the room counter is strictly numeric
    for rid, stats in tracker._room_counters.items():
        assert isinstance(rid, str)
        for metric_name, val in stats.items():
            assert isinstance(val, int), f"Metric {metric_name} in room {rid} must be int, got {type(val)}"

    # Also verify the user-facing outputs are purely metadata/numbers
    room_usage = get_room_usage(room_id)
    total_usage = get_total_usage()
    assert canary not in json.dumps(room_usage)
    assert canary not in json.dumps(total_usage)


# ============================================================================
# 5. Room Teardown / clear_room Test (US-56, FR32)
# ============================================================================


def test_clear_room_removes_one_room_and_leaves_others():
    """Test: clear_room removes one room's counters and leaves the others intact (US-56)."""
    room_a = "room-to-delete"
    room_b = "room-to-keep"

    record_model_call(room_a, tokens=100)
    record_fallback(room_a)

    record_model_call(room_b, tokens=250)
    record_cache_hit(room_b)

    assert get_total_usage()["rooms_tracked"] == 2

    # Clear room A
    deleted = clear_room(room_a)
    assert deleted is True

    # Room A state is reset to zeroes / removed
    usage_a = get_room_usage(room_a)
    assert usage_a["model_calls"] == 0
    assert usage_a["tokens_used"] == 0

    # Room B state is completely intact
    usage_b = get_room_usage(room_b)
    assert usage_b["model_calls"] == 1
    assert usage_b["tokens_used"] == 250
    assert usage_b["cache_hits"] == 1

    # Total tracked rooms reduced to 1, while historical total tokens remain intact
    total = get_total_usage()
    assert total["rooms_tracked"] == 1
    assert total["total_model_calls"] == 2
    assert total["total_tokens_used"] == 350

    # Calling clear_room again returns False
    assert clear_room(room_a) is False


# ============================================================================
# 6. HTTP Endpoint Tests (Contract §16.5)
# ============================================================================


def test_endpoint_get_usage():
    """Test GET /v1/usage returns contract §16.5 schema."""
    record_model_call("room-http-01", tokens=8450)
    record_cache_hit("room-http-01")
    record_fallback("room-http-01")
    record_fallback("room-http-01")

    response = client.get("/v1/usage")
    assert response.status_code == 200
    data = response.json()

    assert data["total_model_calls"] == 1
    assert data["total_tokens_used"] == 8450
    assert data["total_cache_hits"] == 1
    assert data["total_fallback_activations"] == 2
    assert data["monthly_spend_usd"] == 0.0296
    assert data["monthly_budget_usd"] == 10.0
    assert data["alert_80_percent_triggered"] is False
    assert data["cost_cap_reached"] is False
    assert data["rooms_tracked"] == 1


def test_endpoint_get_room_usage():
    """Test GET /v1/rooms/{room_id}/usage returns room metrics."""
    record_model_call("room-xyz", tokens=500)
    record_cache_hit("room-xyz")

    response = client.get("/v1/rooms/room-xyz/usage")
    assert response.status_code == 200
    data = response.json()
    assert data["room_id"] == "room-xyz"
    assert data["model_calls"] == 1
    assert data["tokens_used"] == 500
    assert data["cache_hits"] == 1
    assert data["fallback_activations"] == 0


def test_endpoint_delete_room_state():
    """Test DELETE /v1/rooms/{room_id}/state clears room state (US-56, contract §16.5)."""
    room_id = "room-lifecycle-009"
    record_model_call(room_id, tokens=300)
    assert get_room_usage(room_id)["model_calls"] == 1

    response = client.delete(f"/v1/rooms/{room_id}/state")
    assert response.status_code == 200
    data = response.json()
    assert data["room_id"] == room_id
    assert data["status"] == "cleared"

    # Verify room is now cleared
    assert get_room_usage(room_id)["model_calls"] == 0
    assert get_total_usage()["rooms_tracked"] == 0


# ============================================================================
# 7. Thread Safety Test
# ============================================================================


def test_thread_safety_concurrent_updates():
    """Verify that multiple threads recording counters concurrently do not cause race conditions."""
    num_threads = 10
    increments_per_thread = 50

    def worker(thread_idx: int):
        room = f"room-thread-{thread_idx % 2}"
        for _ in range(increments_per_thread):
            record_model_call(room, tokens=10)
            record_cache_hit(room)
            record_fallback(room)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(num_threads)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    total = get_total_usage()
    expected_calls = num_threads * increments_per_thread
    expected_tokens = expected_calls * 10
    assert total["total_model_calls"] == expected_calls
    assert total["total_tokens_used"] == expected_tokens
    assert total["total_cache_hits"] == expected_calls
    assert total["total_fallback_activations"] == expected_calls
# ============================================================================
# 8. Endpoint Cache Integration, Room Isolation, Error/Null Guards (PR #99)
# ============================================================================


def test_complexity_endpoint_cache_hit_skips_llm_and_records_hit():
    """Repeated complexity request with equivalent normalized code hits cache, skips LLM, and records cache_hits."""
    room_id = "room-cache-comp-01"
    gemini_resp = MagicMock()
    gemini_resp.status_code = 200
    gemini_resp.json.return_value = {
        "candidates": [{"content": {"parts": [{"text": "Use a hash set for O(n) lookup."}]}}],
        "usageMetadata": {"totalTokenCount": 120},
    }

    payload_1 = {
        "request_id": "req-cache-1",
        "room_id": room_id,
        "analysis_id": "ana-cache-1",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def f(arr):\n    for x in arr:\n        for y in arr:\n            pass",
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [4.0, 4.1]},
        ],
    }
    # Formatting & comment-only change should normalize to the same SHA-256 key
    payload_2 = {
        **payload_1,
        "request_id": "req-cache-2",
        "analysis_id": "ana-cache-2",
        "code": "# comment should be ignored\ndef f(arr):\n    for x in arr:\n        for y in arr:\n            pass\n\n",
    }

    with (
        patch.object(settings, "LLM_API_KEY", "test-key"),
        patch.object(settings, "LLM_MODEL", "gemini-1.5-flash"),
        patch("httpx.AsyncClient.post", return_value=gemini_resp) as mock_post,
    ):
        r1 = client.post("/v1/analyses/complexity", json=payload_1)
        assert r1.status_code == 200
        assert r1.json()["ai_step"]["status"] == "ok"
        assert r1.json()["ai_step"]["suggested_fix"] == "Use a hash set for O(n) lookup."
        assert mock_post.call_count == 1

        # Second call hits cache -> skips outbound LLM call
        r2 = client.post("/v1/analyses/complexity", json=payload_2)
        assert r2.status_code == 200
        assert r2.json()["ai_step"]["status"] == "ok"
        assert r2.json()["ai_step"]["suggested_fix"] == "Use a hash set for O(n) lookup."
        assert mock_post.call_count == 1

        # Consent=False must bypass cache and record fallback without returning cached suggestion
        payload_no_consent = {**payload_1, "request_id": "req-cache-3", "consent": False}
        r3 = client.post("/v1/analyses/complexity", json=payload_no_consent)
        assert r3.status_code == 200
        assert r3.json()["ai_step"]["status"] == "skipped"
        assert r3.json()["ai_step"]["error_code"] == "CONSENT_MISSING"
        assert r3.json()["ai_step"]["suggested_fix"] is None
        assert mock_post.call_count == 1

    room_usage = get_room_usage(room_id)
    assert room_usage["model_calls"] == 1
    assert room_usage["tokens_used"] == 120
    assert room_usage["cache_hits"] == 1
    assert room_usage["fallback_activations"] == 1

    total = get_total_usage()
    assert total["total_model_calls"] == 1
    assert total["total_cache_hits"] == 1
    assert total["total_fallback_activations"] == 1


def test_crash_explanation_endpoint_cache_hit_skips_llm_and_records_hit():
    """Repeated crash-explanation request hits cache, skips LLM, and records cache_hits."""
    room_id = "room-cache-crash-01"
    gemini_resp = MagicMock()
    gemini_resp.status_code = 200
    gemini_resp.json.return_value = {
        "candidates": [{"content": {"parts": [{"text": "Buffer read exceeds allocated bounds at line 14."}]}}],
        "usageMetadata": {"totalTokenCount": 95},
    }

    payload = {
        "request_id": "req-crash-1",
        "room_id": room_id,
        "analysis_id": "ana-crash-1",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": "==4210==ERROR: AddressSanitizer: heap-buffer-overflow\n    #0 0x401233 in f /solution.cpp:14",
        "code_snippet": "int x = buf[i];",
    }

    with (
        patch.object(settings, "LLM_API_KEY", "test-key"),
        patch.object(settings, "LLM_MODEL", "gemini-1.5-flash"),
        patch("httpx.AsyncClient.post", return_value=gemini_resp) as mock_post,
    ):
        r1 = client.post("/v1/analyses/crash-explanation", json=payload)
        assert r1.status_code == 200
        assert r1.json()["ai_step"]["status"] == "ok"
        assert mock_post.call_count == 1

        r2 = client.post("/v1/analyses/crash-explanation", json={**payload, "request_id": "req-crash-2"})
        assert r2.status_code == 200
        assert r2.json()["ai_step"]["status"] == "ok"
        assert r2.json()["ai_step"]["explanation"] == r1.json()["ai_step"]["explanation"]
        assert mock_post.call_count == 1

    room_usage = get_room_usage(room_id)
    assert room_usage["model_calls"] == 1
    assert room_usage["tokens_used"] == 95
    assert room_usage["cache_hits"] == 1


def test_cache_clear_room_does_not_evict_other_room():
    """Clearing cache for one room leaves identical-hash cache entries for other rooms intact."""
    cache = AnalysisCache()
    key = compute_cache_key("print(1)", "python", "complexity")
    cache.set(key, {"suggested_fix": "fix"}, room_id="room-alpha")
    cache.set(key, {"suggested_fix": "fix"}, room_id="room-beta")

    cache.clear_room("room-alpha")

    assert cache.get(key, room_id="room-alpha") is None
    assert cache.get(key, room_id="room-beta") == {"suggested_fix": "fix"}
    assert get_room_usage("room-alpha")["cache_hits"] == 0
    assert get_room_usage("room-beta")["cache_hits"] == 1


def test_explain_http_errors_do_not_increment_fallback_counter():
    """POST /v1/explain returns HTTP 502/504/429 on AI errors without incrementing fallback_activations."""
    room_id = "room-explain-err"
    payload = {
        "request_id": "req-explain-1",
        "room_id": room_id,
        "request_type": "explanation",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "x = 1 / 0",
        "stderr": "ZeroDivisionError",
        "exit_code": 1,
        "execution_time_ms": 12.0,
        "peak_memory_mb": 4.0,
    }

    bad_resp = MagicMock()
    bad_resp.status_code = 502

    with (
        patch.object(settings, "LLM_API_KEY", "test-key"),
        patch.object(settings, "LLM_MODEL", "gemini-1.5-flash"),
        patch("httpx.AsyncClient.post", return_value=bad_resp),
    ):
        resp = client.post("/v1/explain", json=payload)
        assert resp.status_code == 502

    assert get_room_usage(room_id)["fallback_activations"] == 0
    assert get_total_usage()["total_fallback_activations"] == 0


@pytest.mark.anyio
async def test_llm_client_handles_null_usage_and_null_token_fields():
    """Provider JSON with explicit null usage/usageMetadata or null token subfields must not 500."""
    client_instance = LLMClient()

    gemini_null_usage = MagicMock()
    gemini_null_usage.status_code = 200
    gemini_null_usage.json.return_value = {
        "candidates": [{"content": {"parts": [{"text": "Gemini null usage ok"}]}}],
        "usageMetadata": None,
    }

    openai_null_fields = MagicMock()
    openai_null_fields.status_code = 200
    openai_null_fields.json.return_value = {
        "choices": [{"message": {"content": "OpenAI null token fields ok"}}],
        "usage": {
            "total_tokens": None,
            "prompt_tokens": None,
            "completion_tokens": 17,
        },
    }

    with patch("httpx.AsyncClient.post", side_effect=[gemini_null_usage, openai_null_fields]):
        out1 = await client_instance.complete(
            "p1", api_key="test-key", model="gemini-1.5-flash", room_id="room-null-1"
        )
        out2 = await client_instance.complete(
            "p2", api_key="test-key", model="gpt-4o", room_id="room-null-2"
        )

    assert out1 == "Gemini null usage ok"
    assert out2 == "OpenAI null token fields ok"
    assert get_room_usage("room-null-1")["tokens_used"] == 0
    assert get_room_usage("room-null-2")["tokens_used"] == 17


@pytest.mark.anyio
async def test_null_room_id_does_not_create_synthetic_default_room():
    """Calls with room_id=None increment totals without creating a 'default' room entry."""
    record_model_call(room_id=None, tokens=40)
    record_cache_hit(room_id=None)
    record_fallback(room_id=None)

    client_instance = LLMClient()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "candidates": [{"content": {"parts": [{"text": "No room id"}]}}],
        "usageMetadata": {"totalTokenCount": 10},
    }
    with patch("httpx.AsyncClient.post", return_value=mock_resp):
        await client_instance.complete("p", api_key="test-key", model="gemini-1.5-flash", room_id=None)

    tracker = get_tracker()
    assert "default" not in tracker._room_counters
    total = get_total_usage()
    assert total["rooms_tracked"] == 0
    assert total["total_model_calls"] == 2
    assert total["total_tokens_used"] == 50
    assert total["total_cache_hits"] == 1
    assert total["total_fallback_activations"] == 1


def test_usage_tracker_sources_budget_and_cost_from_settings():
    """UsageTracker falls back to app.config.settings when explicit overrides are omitted."""
    with (
        patch.object(settings, "MONTHLY_BUDGET_USD", 25.0),
        patch.object(settings, "TOKEN_COST_PER_TOKEN", 0.001),
    ):
        tracker = UsageTracker()
        tracker.record_model_call("room-cfg", tokens=20000)
        usage = tracker.get_total_usage()
        assert usage["monthly_budget_usd"] == 25.0
        assert usage["monthly_spend_usd"] == 20.0
        assert usage["alert_80_percent_triggered"] is True
        assert usage["cost_cap_reached"] is False
