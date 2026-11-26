"""Unit tests for Slowdown Detection (US-68, FR37, T-18 v2 §16.2).

Deterministic seeded tests covering:
1. Normal baseline followed by 10x slowdown: flagged.
2. 5x slowdown: not flagged.
3. Changing configured slowdown factor changes decision.
4. Too little history: no flag and explicit baseline establishing result.
5. MAD = 0: documented safe behavior (division-by-zero protection, no false alarms).
6. Noisy but normal runtime variation: not flagged.
7. Different input hashes: baselines remain separate.
8. Different rooms: measurements remain isolated.
9. DELETE /v1/rooms/{room_id}/state deletes that room's measurements only.
10. Invalid/missing measurements handled safely.
11. Consent false/absent and AI disabled: statistics-only analysis still works.
12. Integration with actual US-52 measurement/request structure.
"""

import concurrent.futures
import json
import math
from typing import Any
import numpy as np
from pydantic import ValidationError
import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.slowdown import (
    SlowdownAnalysisRequest,
    SlowdownAnalysisResponse,
    SlowdownStore,
    calculate_baseline,
    detect_slowdown,
    generate_slowdown_explanation,
    get_slowdown_store,
)


@pytest.fixture(autouse=True)
def clean_slowdown_store():
    """Ensure a clean measurement store before and after each test."""
    store = get_slowdown_store()
    store.clear_all()
    yield
    store.clear_all()


# ============================================================================
# Statistical Unit Tests (Deterministic Seeded)
# ============================================================================


def test_baseline_calculation_deterministic():
    """Verify median, MAD, and sample count calculation."""
    # Seeded data with known median and MAD
    # [95.0, 98.0, 100.0, 102.0, 105.0] -> median = 100.0
    # abs deviations: [5, 2, 0, 2, 5] -> sorted: [0, 2, 2, 5, 5] -> median = 2.0
    data = [{"execution_time_ms": t} for t in [95.0, 98.0, 100.0, 102.0, 105.0]]
    median, mad, count, abs_devs = calculate_baseline(data)
    assert median == pytest.approx(100.0)
    assert mad == pytest.approx(2.0)
    assert count == 5
    assert len(abs_devs) == 5


def test_baseline_calculation_with_invalid_records():
    """Verify calculate_baseline skips invalid or corrupted items safely."""
    data = [
        {"execution_time_ms": 100.0},
        {"execution_time_ms": None},
        {"execution_time_ms": -50.0},
        {"execution_time_ms": float("nan")},
        {"execution_time_ms": float("inf")},
        "not-a-dict",
        {"other_key": 10.0},
        {"execution_time_ms": 102.0},
        {"execution_time_ms": 98.0},
    ]
    median, mad, count, abs_devs = calculate_baseline(data)
    assert count == 3
    assert median == pytest.approx(100.0)
    assert mad == pytest.approx(2.0)


# ============================================================================
# Core 12 Requirement Tests
# ============================================================================


def test_1_normal_baseline_followed_by_10x_slowdown_flagged():
    """1. Normal baseline followed by a 10x slowdown: flagged (is_slowdown=True)."""
    store = SlowdownStore()
    room_id = "test-room-10x"
    input_hash = "sha256-hash-10x"

    # Seed 5 normal runs around 100ms
    np.random.seed(42)
    normal_times = [100.0, 102.0, 98.0, 101.0, 99.0]
    for t in normal_times:
        store.record_measurement(room_id, input_hash, t, 15.0)

    # Current run is 10x slower: 1000.0ms
    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=18.0,
        consent=True,
        ai_enabled=True,
        store=store,
    )

    assert res["is_slowdown"] is True
    assert res["slowdown_factor"] == pytest.approx(10.0, rel=1e-2)
    assert res["baseline"].median_ms == pytest.approx(100.0)
    assert res["baseline"].sample_count == 5
    assert "10.0x slower" in res["message"] or "10x slower" in res["message"]


def test_2_five_x_slowdown_not_flagged():
    """2. 5x slowdown: not flagged (fails condition 2: factor >= 8.0)."""
    store = SlowdownStore()
    room_id = "test-room-5x"
    input_hash = "sha256-hash-5x"

    normal_times = [100.0, 102.0, 98.0, 101.0, 99.0]
    for t in normal_times:
        store.record_measurement(room_id, input_hash, t, 15.0)

    # 5x slowdown: 500.0ms
    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=500.0,
        peak_memory_mb=15.0,
        consent=True,
        ai_enabled=True,
        store=store,
    )

    assert res["is_slowdown"] is False
    assert res["slowdown_factor"] == pytest.approx(5.0, rel=1e-2)
    assert res["message"] == "Execution runtime is within normal parameters."


def test_3_changing_configured_slowdown_factor_changes_decision():
    """3. Changing the configured slowdown factor changes the decision."""
    store = SlowdownStore()
    room_id = "test-room-config"
    input_hash = "sha256-hash-config"

    normal_times = [100.0, 102.0, 98.0, 101.0, 99.0]
    for t in normal_times:
        store.record_measurement(room_id, input_hash, t, 15.0)

    # Run at 5x (500ms): with default factor threshold 8.0 -> not flagged
    res_default = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=500.0,
        peak_memory_mb=15.0,
        factor_threshold=8.0,
        store=store,
    )
    assert res_default["is_slowdown"] is False

    # With factor threshold configured to 4.0 -> flagged!
    res_custom = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=500.0,
        peak_memory_mb=15.0,
        factor_threshold=4.0,
        store=store,
    )
    assert res_custom["is_slowdown"] is True
    assert res_custom["slowdown_factor"] == pytest.approx(5.0, rel=1e-2)


def test_4_too_little_history_baseline_establishing():
    """4. Too little history: no flag and explicit insufficient-history result."""
    store = SlowdownStore()
    room_id = "test-room-short"
    input_hash = "sha256-hash-short"

    # Only 1 earlier run in store; minimum required is 3
    store.record_measurement(room_id, input_hash, 100.0, 12.0)

    # Send run 2: should record and report baseline establishing
    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=105.0,
        peak_memory_mb=12.5,
        min_samples=3,
        store=store,
    )

    assert res["is_slowdown"] is False
    assert res["slowdown_factor"] == 1.0
    assert res["baseline"].sample_count == 2
    assert "Baseline establishing (2 of 3 minimum samples collected)" in res["message"]
    assert res["ai_step"].status == "skipped"


def test_5_mad_zero_safe_handling():
    """5. MAD = 0: documented safe behavior (no division by zero, no false alarms)."""
    store = SlowdownStore()
    room_id = "test-room-mad0"
    input_hash = "sha256-hash-mad0"

    # Baseline with completely identical runs: MAD is 0.0
    for _ in range(5):
        store.record_measurement(room_id, input_hash, 100.0, 10.0)

    # Case A: Slight variation (101.0 ms) -> NOT flagged (no false alarm)
    res_slight = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=101.0,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res_slight["is_slowdown"] is False
    assert res_slight["baseline"].mad_ms == 0.0

    # Case B: Identical time (100.0 ms) -> NOT flagged
    res_exact = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=100.0,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res_exact["is_slowdown"] is False

    # Case C: Genuine 10x regression (1000.0 ms) -> FLAGGED safely without ZeroDivisionError
    res_severe = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res_severe["is_slowdown"] is True
    assert res_severe["slowdown_factor"] == pytest.approx(10.0)
    assert res_severe["baseline"].mad_ms == 0.0


def test_6_noisy_normal_runtime_variation_not_flagged():
    """6. Noisy but normal runtime variation: not flagged."""
    store = SlowdownStore()
    room_id = "test-room-noisy"
    input_hash = "sha256-hash-noisy"

    # Natural execution noise (+- 10%)
    np.random.seed(123)
    normal_times = [90.0, 105.0, 95.0, 110.0, 100.0, 98.0, 102.0]
    for t in normal_times:
        store.record_measurement(room_id, input_hash, t, 12.0)

    # Next run is 112.0 ms (normal upward noise, ~1.12x median)
    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=112.0,
        peak_memory_mb=12.0,
        store=store,
    )

    assert res["is_slowdown"] is False
    assert res["slowdown_factor"] < 2.0
    assert res["message"] == "Execution runtime is within normal parameters."


def test_7_different_input_hashes_baselines_remain_separate():
    """7. Different input hashes: baselines remain separate."""
    store = SlowdownStore()
    room_id = "test-room-hashes"
    hash_a = "input-hash-A"
    hash_b = "input-hash-B"

    # Build established baseline for hash_A
    for t in [100.0, 102.0, 98.0, 101.0]:
        store.record_measurement(room_id, hash_a, t, 10.0)

    # hash_B has no history yet; send a run for hash_B
    res_b = detect_slowdown(
        room_id=room_id,
        input_hash=hash_b,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        min_samples=3,
        store=store,
    )

    # hash_B is in baseline establishing phase, not compared against hash_A!
    assert res_b["is_slowdown"] is False
    assert res_b["baseline"].sample_count == 1
    assert "Baseline establishing" in res_b["message"]

    # hash_A baseline remains unaffected
    history_a = store.get_history(room_id, hash_a)
    assert len(history_a) == 4


def test_8_different_rooms_measurements_remain_isolated():
    """8. Different rooms: measurements remain isolated."""
    store = SlowdownStore()
    room_1 = "room-alpha"
    room_2 = "room-beta"
    shared_hash = "common-input-hash"

    # Room 1 has established baseline around 100ms
    for t in [100.0, 101.0, 99.0, 102.0]:
        store.record_measurement(room_1, shared_hash, t, 10.0)

    # Room 2 has no runs for that hash yet
    res_room2 = detect_slowdown(
        room_id=room_2,
        input_hash=shared_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        min_samples=3,
        store=store,
    )

    # Room 2 starts its own baseline establishing, completely isolated from Room 1
    assert res_room2["is_slowdown"] is False
    assert res_room2["baseline"].sample_count == 1
    assert len(store.get_history(room_1, shared_hash)) == 4
    assert len(store.get_history(room_2, shared_hash)) == 1


def test_9_delete_room_state_deletes_that_room_only():
    """9. DELETE /v1/rooms/{room_id}/state deletes that room's measurements only."""
    client = TestClient(app)
    room_1 = "room-delete-target"
    room_2 = "room-keep-intact"
    h = "d1e2f3a4b5c60718293a4b5c6d7e8f90d1e2f3a4b5c60718293a4b5c6d7e8f90"

    # Establish baselines in both rooms via API
    for _ in range(3):
        client.post(
            "/v1/analyses/slowdown",
            json={
                "request_id": "r1-init",
                "room_id": room_1,
                "analysis_id": "a1",
                "request_type": "slowdown",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "input_hash": h,
                "current_run": {
                    "execution_time_ms": 100.0,
                    "peak_memory_mb": 15.0,
                    "code": "print('hello')",
                },
            },
        )
        client.post(
            "/v1/analyses/slowdown",
            json={
                "request_id": "r2-init",
                "room_id": room_2,
                "analysis_id": "a2",
                "request_type": "slowdown",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "input_hash": h,
                "current_run": {
                    "execution_time_ms": 100.0,
                    "peak_memory_mb": 15.0,
                    "code": "print('hello')",
                },
            },
        )

    store = get_slowdown_store()
    assert len(store.get_history(room_1, h)) == 3
    assert len(store.get_history(room_2, h)) == 3

    # Delete room 1 state
    resp = client.delete(f"/v1/rooms/{room_1}/state")
    assert resp.status_code == 200
    assert resp.json() == {"room_id": room_1, "status": "cleared"}

    # Room 1 is completely wiped, Room 2 is completely intact
    assert len(store.get_history(room_1, h)) == 0
    assert len(store.get_history(room_2, h)) == 3


def test_10_invalid_and_missing_measurements_handled_safely():
    """10. Invalid/missing measurements are handled safely (422 INVALID_REQUEST)."""
    client = TestClient(app)

    # Sub-case A: Negative execution time
    resp_neg = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-inv-1",
            "room_id": "room-inv",
            "analysis_id": "ana-inv-1",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "current_run": {
                "execution_time_ms": -10.0,
                "peak_memory_mb": 10.0,
                "code": "pass",
            },
        },
    )
    assert resp_neg.status_code == 422
    assert resp_neg.json()["error"]["code"] == "INVALID_REQUEST"

    # Sub-case B: Negative peak memory
    resp_mem = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-inv-2",
            "room_id": "room-inv",
            "analysis_id": "ana-inv-2",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "current_run": {
                "execution_time_ms": 100.0,
                "peak_memory_mb": -5.0,
                "code": "pass",
            },
        },
    )
    assert resp_mem.status_code == 422
    assert resp_mem.json()["error"]["code"] == "INVALID_REQUEST"

    # Sub-case C: Missing current_run object
    resp_miss = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-inv-3",
            "room_id": "room-inv",
            "analysis_id": "ana-inv-3",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        },
    )
    assert resp_miss.status_code == 422
    assert resp_miss.json()["error"]["code"] == "INVALID_REQUEST"

    # Sub-case D: Empty input_hash
    resp_hash = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-inv-4",
            "room_id": "room-inv",
            "analysis_id": "ana-inv-4",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": "",
            "current_run": {
                "execution_time_ms": 100.0,
                "peak_memory_mb": 10.0,
                "code": "pass",
            },
        },
    )
    assert resp_hash.status_code == 422
    assert resp_hash.json()["error"]["code"] == "INVALID_REQUEST"


def test_11_consent_false_absent_and_ai_disabled_statistics_work():
    """11. Consent false/absent and AI disabled: statistics-only analysis still works."""
    client = TestClient(app)
    room_id = "room-consent-gate"
    input_hash = "1111111111111111111111111111111111111111111111111111111111111111"

    # Establish baseline with 3 runs
    for _ in range(3):
        client.post(
            "/v1/analyses/slowdown",
            json={
                "request_id": "init",
                "room_id": room_id,
                "analysis_id": "init-a",
                "request_type": "slowdown",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "input_hash": input_hash,
                "current_run": {
                    "execution_time_ms": 100.0,
                    "peak_memory_mb": 15.0,
                    "code": "pass",
                },
            },
        )

    # Sub-case A: consent=False -> 10x slowdown still detected!
    resp_consent_false = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-cf",
            "room_id": room_id,
            "analysis_id": "ana-cf",
            "request_type": "slowdown",
            "language": "python",
            "consent": False,
            "ai_enabled": True,
            "input_hash": input_hash,
            "current_run": {
                "execution_time_ms": 1000.0,
                "peak_memory_mb": 15.0,
                "code": "pass",
            },
        },
    )
    assert resp_consent_false.status_code == 200
    data_cf = resp_consent_false.json()
    assert data_cf["is_slowdown"] is True
    assert data_cf["slowdown_factor"] == 10.0
    assert data_cf["ai_step"]["status"] == "skipped"
    assert data_cf["ai_step"]["error_code"] == "CONSENT_MISSING"

    # Sub-case B: consent absent (null/omitted) -> still works!
    resp_consent_absent = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-ca",
            "room_id": room_id,
            "analysis_id": "ana-ca",
            "request_type": "slowdown",
            "language": "python",
            "input_hash": input_hash,
            "current_run": {
                "execution_time_ms": 1000.0,
                "peak_memory_mb": 15.0,
                "code": "pass",
            },
        },
    )
    assert resp_consent_absent.status_code == 200
    data_ca = resp_consent_absent.json()
    assert data_ca["is_slowdown"] is True
    assert data_ca["ai_step"]["status"] == "skipped"
    assert data_ca["ai_step"]["error_code"] == "CONSENT_MISSING"

    # Sub-case C: ai_enabled=False -> still works!
    resp_ai_disabled = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "req-ad",
            "room_id": room_id,
            "analysis_id": "ana-ad",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": False,
            "input_hash": input_hash,
            "current_run": {
                "execution_time_ms": 1000.0,
                "peak_memory_mb": 15.0,
                "code": "pass",
            },
        },
    )
    assert resp_ai_disabled.status_code == 200
    data_ad = resp_ai_disabled.json()
    assert data_ad["is_slowdown"] is True
    assert data_ad["ai_step"]["status"] == "skipped"
    assert data_ad["ai_step"]["error_code"] == "AI_DISABLED"


def test_12_us52_measurement_request_structure_integration():
    """12. Integration with actual US-52 measurement/request structure."""
    client = TestClient(app)
    room_id = "Zq8-Lw2Ef6Th0Yx4Rn7JbA"
    input_hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    # Exact payload structure from US-52 and docs/examples/v2-analysis-slowdown-request.json
    payload = {
        "request_id": "1c2d3e4f-5a6b-7c8d-9e0f-1a2b3c4d5e6f",
        "room_id": room_id,
        "analysis_id": "2d3e4f5a-6b7c-8d9e-0f1a-2b3c4d5e6f7a",
        "request_type": "slowdown",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "input_hash": input_hash,
        "current_run": {
            "execution_time_ms": 1420.0,
            "peak_memory_mb": 18.5,
            "code": "def process_data(items):\n    time.sleep(1.4)\n    return sorted(items)",
        },
    }

    # First request: baseline establishing
    resp = client.post("/v1/analyses/slowdown", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["task"] == "slowdown"
    assert data["input_hash"] == input_hash
    assert data["is_slowdown"] is False
    assert data["slowdown_factor"] == 1.0
    assert data["baseline"]["sample_count"] == 1
    assert data["baseline"]["median_ms"] == 1420.0
    assert "Baseline establishing" in data["message"]


def test_slowdown_run_not_recorded_to_baseline():
    """Verify an abnormal slowdown run is NOT recorded into normal baseline history."""
    store = SlowdownStore()
    room_id = "room-poison-check"
    input_hash = "hash-poison-check"

    # 3 normal runs around 100ms
    for t in [100.0, 100.0, 100.0]:
        store.record_measurement(room_id, input_hash, t, 10.0)

    # 10x regression run: 1000.0ms
    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res["is_slowdown"] is True

    # History should still only have the 3 normal runs!
    history = store.get_history(room_id, input_hash)
    assert len(history) == 3
    for m in history:
        assert m["execution_time_ms"] == 100.0


# ============================================================================
# Review Feedback Tests: Overrides, AI Explanation, Contract Criteria, Deferred Diffs
# ============================================================================


def test_override_validation_rejects_invalid_values():
    """Verify validate_threshold_overrides strictly rejects min_samples <= 0, non-integers, negative, and non-finite thresholds."""
    from fastapi import HTTPException
    from app.slowdown import validate_threshold_overrides

    # min_samples = 0
    with pytest.raises(HTTPException) as exc_0:
        validate_threshold_overrides(min_samples_param=0)
    assert exc_0.value.status_code == 422
    assert exc_0.value.detail["code"] == "INVALID_REQUEST"

    # min_samples < 0
    with pytest.raises(HTTPException) as exc_neg_min:
        validate_threshold_overrides(min_samples_param="-2")
    assert exc_neg_min.value.status_code == 422
    assert exc_neg_min.value.detail["code"] == "INVALID_REQUEST"

    # min_samples float string
    with pytest.raises(HTTPException) as exc_float_min:
        validate_threshold_overrides(min_samples_param="1.5")
    assert exc_float_min.value.status_code == 422
    assert exc_float_min.value.detail["code"] == "INVALID_REQUEST"

    # min_samples non-numeric
    with pytest.raises(HTTPException) as exc_str_min:
        validate_threshold_overrides(min_samples_param="abc")
    assert exc_str_min.value.status_code == 422

    # factor_threshold negative
    with pytest.raises(HTTPException) as exc_neg_factor:
        validate_threshold_overrides(factor_param="-5.0")
    assert exc_neg_factor.value.status_code == 422
    assert exc_neg_factor.value.detail["code"] == "INVALID_REQUEST"

    # factor_threshold zero
    with pytest.raises(HTTPException) as exc_zero_factor:
        validate_threshold_overrides(factor_param="0.0")
    assert exc_zero_factor.value.status_code == 422

    # factor_threshold non-finite (NaN, Inf)
    with pytest.raises(HTTPException) as exc_nan_factor:
        validate_threshold_overrides(factor_param="nan")
    assert exc_nan_factor.value.status_code == 422
    assert exc_nan_factor.value.detail["code"] == "INVALID_REQUEST"

    with pytest.raises(HTTPException) as exc_inf_factor:
        validate_threshold_overrides(factor_param="inf")
    assert exc_inf_factor.value.status_code == 422

    # outlier_threshold zero or negative
    with pytest.raises(HTTPException) as exc_zero_outlier:
        validate_threshold_overrides(outlier_param="0.0")
    assert exc_zero_outlier.value.status_code == 422
    assert exc_zero_outlier.value.detail["code"] == "INVALID_REQUEST"

    with pytest.raises(HTTPException) as exc_neg_outlier:
        validate_threshold_overrides(outlier_param="-1.5")
    assert exc_neg_outlier.value.status_code == 422

    # outlier_threshold non-finite
    with pytest.raises(HTTPException) as exc_nan_outlier:
        validate_threshold_overrides(outlier_param="nan")
    assert exc_nan_outlier.value.status_code == 422

    # Valid values accepted
    f, o, m = validate_threshold_overrides(
        factor_param="4.5", outlier_param="2.5", min_samples_param="4"
    )
    assert f == pytest.approx(4.5)
    assert o == pytest.approx(2.5)
    assert m == 4


def test_api_threshold_overrides_endpoint_validation():
    """Verify endpoint returns HTTP 422 INVALID_REQUEST when invalid query params or headers are passed."""
    client = TestClient(app)
    room_id = "test-override-api"
    input_hash = "2222222222222222222222222222222222222222222222222222222222222222"

    base_payload = {
        "request_id": "req-ovr-1",
        "room_id": room_id,
        "analysis_id": "ana-ovr-1",
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
    }

    # Query param min_samples=0
    resp_0 = client.post("/v1/analyses/slowdown?min_samples=0", json=base_payload)
    assert resp_0.status_code == 422
    assert resp_0.json()["error"]["code"] == "INVALID_REQUEST"

    # Query param factor_threshold=-2.0
    resp_neg_f = client.post("/v1/analyses/slowdown?factor_threshold=-2.0", json=base_payload)
    assert resp_neg_f.status_code == 422
    assert resp_neg_f.json()["error"]["code"] == "INVALID_REQUEST"

    # Query param outlier_threshold=nan
    resp_nan = client.post("/v1/analyses/slowdown?outlier_threshold=nan", json=base_payload)
    assert resp_nan.status_code == 422
    assert resp_nan.json()["error"]["code"] == "INVALID_REQUEST"

    # Header x-min-samples=-1
    resp_hdr = client.post(
        "/v1/analyses/slowdown",
        headers={"x-min-samples": "-1"},
        json=base_payload,
    )
    assert resp_hdr.status_code == 422
    assert resp_hdr.json()["error"]["code"] == "INVALID_REQUEST"


def test_condition_1_statistical_outlier_contract_alignment():
    """Verify statistical outlier condition adheres to T_curr > median + 3 * MAD (§16.2)."""
    store = SlowdownStore()
    room_id = "room-stat-cond"
    input_hash = "hash-stat-cond"

    # Seed baseline: [96.0, 98.0, 100.0, 102.0, 104.0]
    # Median = 100.0, deviations = [4, 2, 0, 2, 4], MAD = 2.0
    for t in [96.0, 98.0, 100.0, 102.0, 104.0]:
        store.record_measurement(room_id, input_hash, t, 10.0)

    # Threshold: median + 3 * MAD = 100 + 3 * 2.0 = 106.0
    # Current runtime = 105.0: below 106.0 -> Condition 1 fails -> is_slowdown=False
    res_below = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=105.0,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res_below["is_slowdown"] is False

    # Current runtime = 500.0 (5x): exceeds 106.0 (Condition 1 passes), but fails Condition 2 (5 < 8) -> is_slowdown=False
    res_5x = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=500.0,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res_5x["is_slowdown"] is False

    # Current runtime = 1000.0 (10x): exceeds 106.0 AND 10x >= 8x -> BOTH conditions pass -> is_slowdown=True
    res_10x = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res_10x["is_slowdown"] is True


def test_ai_explanation_generated_when_slowdown_detected():
    """Verify AI explanation is generated via LLM when slowdown detected, consent=True, ai_enabled=True (§16.2)."""
    from unittest.mock import AsyncMock, patch
    client = TestClient(app)
    room_id = "room-ai-gen"
    input_hash = "3333333333333333333333333333333333333333333333333333333333333333"

    # Establish baseline with 3 normal runs
    for i in range(3):
        resp = client.post(
            "/v1/analyses/slowdown",
            json={
                "request_id": f"req-base-{i}",
                "room_id": room_id,
                "analysis_id": f"ana-base-{i}",
                "request_type": "slowdown",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "input_hash": input_hash,
                "current_run": {
                    "execution_time_ms": 100.0,
                    "peak_memory_mb": 10.0,
                    "code": "print('ok')",
                },
            },
        )
        assert resp.status_code == 200

    # 4th run: 12x slowdown regression (1200ms) with LLM mocked
    slowdown_payload = {
        "request_id": "req-slow-ai-1",
        "room_id": room_id,
        "analysis_id": "ana-slow-ai-1",
        "request_type": "slowdown",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "input_hash": input_hash,
        "current_run": {
            "execution_time_ms": 1200.0,
            "peak_memory_mb": 15.0,
            "code": "import time\ntime.sleep(1.2)",
        },
    }

    mock_complete = AsyncMock(
        return_value="Runtime regression caused by blocking sleep. Line 2 suspends execution for 1.2s. Remove sleep to restore performance."
    )
    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp_slow = client.post("/v1/analyses/slowdown", json=slowdown_payload)

    assert resp_slow.status_code == 200
    data_slow = resp_slow.json()
    assert data_slow["is_slowdown"] is True
    assert data_slow["ai_step"]["status"] == "ok"
    assert data_slow["ai_step"]["error_code"] is None
    assert data_slow["ai_step"]["explanation"] is not None
    assert "sleep" in data_slow["ai_step"]["explanation"]
    assert mock_complete.await_count == 1

    # Second identical regression: served from cache (0 additional LLM calls)
    slowdown_payload_2 = dict(slowdown_payload)
    slowdown_payload_2["request_id"] = "req-slow-ai-2"
    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp_cached = client.post("/v1/analyses/slowdown", json=slowdown_payload_2)
    assert resp_cached.status_code == 200
    data_cached = resp_cached.json()
    assert data_cached["ai_step"]["status"] == "ok"
    assert data_cached["ai_step"]["explanation"] == data_slow["ai_step"]["explanation"]
    # LLM was NOT called again due to caching (NFR5.2)
    assert mock_complete.await_count == 1


def test_ai_explanation_sentence_limit_enforced():
    """Verify that an LLM response with more than 3 sentences is trimmed to at most 3 sentences (FR13.1)."""
    from unittest.mock import AsyncMock, patch
    client = TestClient(app)
    room_id = "room-sentence-limit"
    input_hash = "4444444444444444444444444444444444444444444444444444444444444444"

    store = get_slowdown_store()
    for _ in range(3):
        store.record_measurement(room_id, input_hash, 100.0, 10.0)

    long_response = (
        "Sentence one explains the regression. "
        "Sentence two provides context on latency. "
        "Sentence three suggests an optimization fix. "
        "Sentence four should be stripped out. "
        "Sentence five must not appear."
    )
    mock_complete = AsyncMock(return_value=long_response)
    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp = client.post(
            "/v1/analyses/slowdown",
            json={
                "request_id": "req-sent-1",
                "room_id": room_id,
                "analysis_id": "ana-sent-1",
                "request_type": "slowdown",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "input_hash": input_hash,
                "current_run": {
                    "execution_time_ms": 1000.0,
                    "peak_memory_mb": 15.0,
                    "code": "time.sleep(1.0)",
                },
            },
        )
    assert resp.status_code == 200
    data = resp.json()
    assert data["ai_step"]["status"] == "ok"
    expl = data["ai_step"]["explanation"]
    assert "Sentence one" in expl
    assert "Sentence three" in expl
    assert "Sentence four" not in expl
    assert "Sentence five" not in expl


def test_ai_explanation_falls_back_gracefully_on_provider_error():
    """Verify that if LLM provider fails, ai_step status is skipped with PROVIDER_ERROR without crashing analysis."""
    from unittest.mock import AsyncMock, patch
    from app.llm.client import LLMProviderError
    client = TestClient(app)
    room_id = "room-err-fallback"
    input_hash = "5555555555555555555555555555555555555555555555555555555555555555"

    store = get_slowdown_store()
    for _ in range(3):
        store.record_measurement(room_id, input_hash, 100.0, 10.0)

    mock_complete = AsyncMock(side_effect=LLMProviderError("Upstream failure", code="PROVIDER_ERROR"))
    with patch("app.llm.client.LLMClient.complete", mock_complete):
        resp = client.post(
            "/v1/analyses/slowdown",
            json={
                "request_id": "req-err-1",
                "room_id": room_id,
                "analysis_id": "ana-err-1",
                "request_type": "slowdown",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "input_hash": input_hash,
                "current_run": {
                    "execution_time_ms": 1000.0,
                    "peak_memory_mb": 15.0,
                    "code": "time.sleep(1.0)",
                },
            },
        )
    assert resp.status_code == 200
    data = resp.json()
    assert data["is_slowdown"] is True
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "PROVIDER_ERROR"
    assert data["ai_step"]["explanation"] is None


def test_deferred_scope_changed_lines_returns_empty_list():
    """Verify changed_lines is an empty list per US-68 scope (FR37.3 deferred to US-69)."""
    store = SlowdownStore()
    room_id = "room-deferred-diff"
    input_hash = "hash-deferred-diff"

    for _ in range(3):
        store.record_measurement(room_id, input_hash, 100.0, 10.0)

    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res["changed_lines"] == []


# ============================================================================
# Regression Tests for Review Findings Remediation (US-68)
# ============================================================================


def test_zero_baseline_cases_produce_finite_valid_json():
    """Verify zero-baseline cases produce finite, mathematically sound, schema-compliant JSON numbers (Finding 2).

    Tested scenarios:
    1. baseline_median == 0.0 and execution_time_ms == 0.0 -> factor 1.0, not flagged.
    2. baseline_median == 0.0 and execution_time_ms == 0.01 (sub-ms noise) -> factor 0.01, not flagged.
    3. baseline_median == 0.0 and execution_time_ms == 12.0 (>= 8x) -> factor 12.0, flagged, finite message.
    4. baseline_median > 0.0 and execution_time_ms > 0.0 -> standard ratio.
    5. Serialization check: model_dump_json() and json.dumps(allow_nan=False) succeed without Infinity/null.
    6. HTTP endpoint verification: returns valid JSON numbers matching slowdown-response.json schema.
    """
    store = SlowdownStore()
    room_id = "room-zero-base"
    input_hash = "0000000000000000000000000000000000000000000000000000000000000000"

    # Seed baseline with 3 runs of 0.0 ms
    for _ in range(3):
        store.record_measurement(room_id, input_hash, 0.0, 10.0)

    # 1. runtime == 0.0
    res_0 = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=0.0,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res_0["is_slowdown"] is False
    assert res_0["slowdown_factor"] == 1.0
    assert not math.isinf(res_0["slowdown_factor"])
    assert not math.isnan(res_0["slowdown_factor"])
    assert "within normal parameters" in res_0["message"]

    # 2. sub-millisecond execution runtime (0.01 ms) must not trigger false alarm
    res_sub = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=0.01,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res_sub["is_slowdown"] is False
    assert res_sub["slowdown_factor"] == 0.01
    assert not math.isinf(res_sub["slowdown_factor"])

    # 3. significant execution runtime (12.0 ms) exceeds 8.0x floor threshold -> flagged with finite factor
    res_slow = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=12.0,
        peak_memory_mb=10.0,
        store=store,
    )
    assert res_slow["is_slowdown"] is True
    assert res_slow["slowdown_factor"] == 12.0
    assert not math.isinf(res_slow["slowdown_factor"])
    assert not math.isnan(res_slow["slowdown_factor"])
    assert "12.0x slower" in res_slow["message"]

    # 4. Response model validation and JSON serialization check
    response_model = SlowdownAnalysisResponse(
        request_id="req-zb-1",
        analysis_id="ana-zb-1",
        task="slowdown",
        input_hash=input_hash,
        is_slowdown=res_slow["is_slowdown"],
        slowdown_factor=res_slow["slowdown_factor"],
        baseline=res_slow["baseline"],
        changed_lines=res_slow["changed_lines"],
        message=res_slow["message"],
        ai_step=res_slow["ai_step"],
    )
    # Pydantic JSON dump must produce a number, never null
    json_str = response_model.model_dump_json()
    assert '"slowdown_factor":12.0' in json_str or '"slowdown_factor": 12.0' in json_str
    # Strict RFC 8259 JSON dumps must not raise ValueError
    strict_dump = json.dumps(response_model.model_dump(), allow_nan=False)
    assert "Infinity" not in strict_dump
    assert "NaN" not in strict_dump

    # 5. Full HTTP endpoint test
    client = TestClient(app)
    endpoint_room = "room-zero-base-api"
    test_sha_hash = "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff"

    for _ in range(3):
        resp = client.post(
            "/v1/analyses/slowdown",
            json={
                "request_id": "init-zb",
                "room_id": endpoint_room,
                "analysis_id": "init-zb-a",
                "request_type": "slowdown",
                "language": "python",
                "consent": True,
                "ai_enabled": True,
                "input_hash": test_sha_hash,
                "current_run": {
                    "execution_time_ms": 0.0,
                    "peak_memory_mb": 10.0,
                    "code": "pass",
                },
            },
        )
        assert resp.status_code == 200

    # 4th run: 0.0 ms -> normal
    resp_normal = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "test-zb-0",
            "room_id": endpoint_room,
            "analysis_id": "test-zb-0-a",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": test_sha_hash,
            "current_run": {
                "execution_time_ms": 0.0,
                "peak_memory_mb": 10.0,
                "code": "pass",
            },
        },
    )
    assert resp_normal.status_code == 200
    data_0 = resp_normal.json()
    assert data_0["is_slowdown"] is False
    assert data_0["slowdown_factor"] == 1.0

    # 5th run: 15.0 ms -> flagged
    resp_slow_api = client.post(
        "/v1/analyses/slowdown",
        json={
            "request_id": "test-zb-15",
            "room_id": endpoint_room,
            "analysis_id": "test-zb-15-a",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": test_sha_hash,
            "current_run": {
                "execution_time_ms": 15.0,
                "peak_memory_mb": 10.0,
                "code": "pass",
            },
        },
    )
    assert resp_slow_api.status_code == 200
    data_15 = resp_slow_api.json()
    assert data_15["is_slowdown"] is True
    assert data_15["slowdown_factor"] == 15.0
    assert "15.0x slower" in data_15["message"]


def test_concurrency_atomic_per_room_and_hash_no_poisoning():
    """Verify atomic per-(room_id, input_hash) synchronization in SlowdownStore (Finding 3).

    Verifies:
    1. Multiple threads submitting the threshold 3rd baseline run:
       Exactly 1 thread records the baseline establishing run (sample count 3).
       All other concurrent threads evaluate against the established baseline of 3.
    2. Concurrent abnormal runaway runs do NOT poison normal baseline history.
    3. Different rooms and hashes do not block each other (independent concurrency).
    """
    store = SlowdownStore()
    room_id = "room-concurrent-sync"
    input_hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

    # Pre-populate with 2 baseline runs
    store.record_measurement(room_id, input_hash, 100.0, 10.0)
    store.record_measurement(room_id, input_hash, 100.0, 10.0)

    # Phase 1: 10 threads concurrently invoke detect_slowdown for the same (room_id, input_hash)
    # when sample_count == 2. Exactly 1 thread must establish baseline sample #3.
    # The other 9 threads must evaluate against the established baseline of 3.
    results_phase1 = []

    def call_detect(t: float):
        return detect_slowdown(
            room_id=room_id,
            input_hash=input_hash,
            execution_time_ms=t,
            peak_memory_mb=10.0,
            store=store,
        )

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(call_detect, 100.0) for _ in range(10)]
        for f in concurrent.futures.as_completed(futures):
            results_phase1.append(f.result())

    # Exactly 1 thread must have observed sample_count < 3 (completing the baseline to 3)
    establishing_results = [
        r for r in results_phase1 if "Baseline establishing" in r["message"]
    ]
    # The remaining 9 threads must have evaluated against the established baseline
    established_results = [
        r for r in results_phase1 if "Baseline establishing" not in r["message"]
    ]

    assert len(establishing_results) == 1
    assert len(established_results) == 9

    # Phase 2: Concurrent abnormal runaway runs do NOT poison established baseline history
    # 10 threads: 1 runaway run (10,000ms) and 9 normal runs (100ms) concurrently
    results_phase2 = []
    run_times = [10000.0] + [100.0] * 9

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(call_detect, t) for t in run_times]
        for f in concurrent.futures.as_completed(futures):
            results_phase2.append(f.result())

    # The 10,000ms runaway run must be detected as slowdown
    slowdown_results = [r for r in results_phase2 if r["is_slowdown"] is True]
    assert len(slowdown_results) == 1
    assert slowdown_results[0]["slowdown_factor"] >= 8.0

    # Anti-poisoning check: the 10,000ms abnormal run must NEVER be present in the history
    history = store.get_history(room_id, input_hash)
    for m in history:
        assert m["execution_time_ms"] < 1000.0, "Abnormal runaway run poisoned the baseline history!"

    # Multi-room / Multi-hash isolation under concurrency:
    # Concurrent execution across different rooms / hashes completes without deadlock
    other_hash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    def cross_work(h):
        return detect_slowdown(
            room_id="other-room",
            input_hash=h,
            execution_time_ms=50.0,
            peak_memory_mb=5.0,
            store=store,
        )

    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
        fs = [executor.submit(cross_work, other_hash) for _ in range(5)]
        for f in concurrent.futures.as_completed(fs):
            assert f.result() is not None


@pytest.mark.anyio
async def test_ai_explanation_cache_key_differentiation_for_distinct_runtimes():
    """Verify AI explanation cache key prevents collisions across differing runtime contexts (Finding 4).

    Identical code and identical rounded slowdown factor (e.g. 10.0x), but differing runtime metrics
    (1000ms vs 5000ms) produce distinct cache keys, ensuring each gets its own explanation.
    """
    from unittest.mock import AsyncMock, patch

    room_id = "room-cache-diff"
    code = "def slow_func():\n    time.sleep(1)"
    language = "python"

    mock_complete = AsyncMock(
        side_effect=[
            "Explanation for 1000ms run regression.",
            "Explanation for 5000ms run regression.",
        ]
    )

    with patch("app.llm.client.LLMClient.complete", mock_complete):
        # Request 1: 1000.0ms runtime, 100.0ms baseline (10.0x)
        step_1 = await generate_slowdown_explanation(
            code=code,
            language=language,
            execution_time_ms=1000.0,
            baseline_median_ms=100.0,
            slowdown_factor=10.0,
            room_id=room_id,
            consent=True,
            ai_enabled=True,
        )
        assert step_1.status == "ok"
        assert step_1.explanation == "Explanation for 1000ms run regression."
        assert mock_complete.await_count == 1

        # Request 2: 5000.0ms runtime, 500.0ms baseline (10.0x)
        # Identical code and factor (10.0x), but different absolute times!
        # Must be a CACHE MISS and invoke LLM for second distinct explanation.
        step_2 = await generate_slowdown_explanation(
            code=code,
            language=language,
            execution_time_ms=5000.0,
            baseline_median_ms=500.0,
            slowdown_factor=10.0,
            room_id=room_id,
            consent=True,
            ai_enabled=True,
        )
        assert step_2.status == "ok"
        assert step_2.explanation == "Explanation for 5000ms run regression."
        assert mock_complete.await_count == 2

        # Request 3: Re-request with exact same metrics as Request 1
        # Must be a CACHE HIT (0 additional LLM calls)
        step_3 = await generate_slowdown_explanation(
            code=code,
            language=language,
            execution_time_ms=1000.0,
            baseline_median_ms=100.0,
            slowdown_factor=10.0,
            room_id=room_id,
            consent=True,
            ai_enabled=True,
        )
        assert step_3.status == "ok"
        assert step_3.explanation == "Explanation for 1000ms run regression."
        assert mock_complete.await_count == 2


def test_input_hash_validation_strict_sha256_hex():
    """Verify strict 64-character hexadecimal SHA-256 validation for input_hash (Finding 6).

    Rejects:
    - Too short (63 chars)
    - Too long (65 chars)
    - Non-hex characters
    - Empty string
    - Non-string types

    Accepts:
    - 64 hex characters lowercase
    - 64 hex characters uppercase (normalized to lowercase)
    """
    client = TestClient(app)
    room_id = "room-hash-val"

    def make_payload(h: Any) -> dict[str, Any]:
        return {
            "request_id": "req-hash-test",
            "room_id": room_id,
            "analysis_id": "ana-hash-test",
            "request_type": "slowdown",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "input_hash": h,
            "current_run": {
                "execution_time_ms": 100.0,
                "peak_memory_mb": 10.0,
                "code": "pass",
            },
        }

    # 1. Valid 64-char lowercase hex -> 200 OK
    valid_lower = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    resp_valid = client.post("/v1/analyses/slowdown", json=make_payload(valid_lower))
    assert resp_valid.status_code == 200
    assert resp_valid.json()["input_hash"] == valid_lower

    # 2. Valid 64-char uppercase hex -> 200 OK (normalized to lowercase)
    valid_upper = valid_lower.upper()
    resp_upper = client.post("/v1/analyses/slowdown", json=make_payload(valid_upper))
    assert resp_upper.status_code == 200
    assert resp_upper.json()["input_hash"] == valid_lower

    # 3. Too short: 63 hex chars -> 422 INVALID_REQUEST
    resp_short = client.post("/v1/analyses/slowdown", json=make_payload("a" * 63))
    assert resp_short.status_code == 422
    assert resp_short.json()["error"]["code"] == "INVALID_REQUEST"

    # 4. Too long: 65 hex chars -> 422 INVALID_REQUEST
    resp_long = client.post("/v1/analyses/slowdown", json=make_payload("a" * 65))
    assert resp_long.status_code == 422
    assert resp_long.json()["error"]["code"] == "INVALID_REQUEST"

    # 5. Non-hex characters (64 chars with invalid character 'g' or 'z') -> 422 INVALID_REQUEST
    non_hex = "g" + "0" * 63
    resp_non_hex = client.post("/v1/analyses/slowdown", json=make_payload(non_hex))
    assert resp_non_hex.status_code == 422
    assert resp_non_hex.json()["error"]["code"] == "INVALID_REQUEST"

    # 6. Empty string -> 422 INVALID_REQUEST
    resp_empty = client.post("/v1/analyses/slowdown", json=make_payload(""))
    assert resp_empty.status_code == 422
    assert resp_empty.json()["error"]["code"] == "INVALID_REQUEST"

    # 7. Non-string integer -> 422 INVALID_REQUEST
    resp_int = client.post("/v1/analyses/slowdown", json=make_payload(123456789))
    assert resp_int.status_code == 422
    assert resp_int.json()["error"]["code"] == "INVALID_REQUEST"

    # 8. Direct SlowdownAnalysisRequest Pydantic model validation
    with pytest.raises(ValidationError) as exc_info:
        SlowdownAnalysisRequest(**make_payload("short-hash"))
    assert "64-character hexadecimal SHA-256 digest" in str(exc_info.value)


# ============================================================================
# US-69: Highlight Changed Lines in a Slowdown Run (FR37.3, Sprint 5)
# Tests 1 through 12
# ============================================================================


def test_us69_test1_flagged_run_after_changed_line():
    """Test 1: Seed normal runs with the same input hash. Submit flagged run with 1 modified line.

    Verify:
    - is_slowdown == True
    - Exactly the expected current line is highlighted (1-based)
    - No source line text returned
    - Removed-line count is correct (0)
    """
    store = SlowdownStore()
    room_id = "test-room-us69-t1"
    input_hash = "a" * 64
    code_v1 = "a = 10\nb = 20\nprint(a + b)"

    # Seed 3 normal runs around 100ms
    for _ in range(3):
        res = detect_slowdown(
            room_id=room_id,
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code=code_v1,
            store=store,
        )
        assert res["is_slowdown"] is False

    # Current run: 10x slower (1000ms), line 2 modified (b = 50)
    code_v2 = "a = 10\nb = 50\nprint(a + b)"
    res_slow = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=12.0,
        code=code_v2,
        store=store,
    )

    assert res_slow["is_slowdown"] is True
    assert res_slow["slowdown_factor"] == pytest.approx(10.0, rel=1e-2)
    assert res_slow["comparison_available"] is True
    assert res_slow["removed_lines_count"] == 0

    # Line 2 highlighted
    changed = res_slow["changed_lines"]
    assert len(changed) == 1
    assert changed[0].line == 2

    # Verify no source text attribute or content field
    assert not hasattr(changed[0], "content")


def test_us69_test2_no_earlier_normal_run():
    """Test 2: Submit a flagged run for an input hash with no earlier normal run.

    Verify:
    - is_slowdown == True
    - Highlight list is empty
    - Response clearly states no earlier normal run to compare
    - Entire file is not highlighted
    """
    store = SlowdownStore()
    room_id = "test-room-us69-t2"
    input_hash = "b" * 64

    # Seed measurements with NO code (or line hashes)
    for _ in range(3):
        store.record_measurement(room_id, input_hash, 100.0, 10.0)

    # Flagged run with code
    code_curr = "def slow():\n    import time\n    time.sleep(2)"
    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1200.0,
        peak_memory_mb=15.0,
        code=code_curr,
        store=store,
    )

    assert res["is_slowdown"] is True
    assert res["changed_lines"] == []
    assert res["comparison_available"] is False
    assert "No earlier normal run to compare." in res["comparison_note"]
    assert "No earlier normal run to compare." in res["message"]


def test_us69_test3_unflagged_run():
    """Test 3: Unflagged run returns no highlights."""
    store = SlowdownStore()
    room_id = "test-room-us69-t3"
    input_hash = "c" * 64
    code_v1 = "a = 10\nb = 20\nprint(a + b)"

    for _ in range(3):
        detect_slowdown(
            room_id=room_id,
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code=code_v1,
            store=store,
        )

    # Normal run (102ms), changed code
    code_v2 = "a = 10\nb = 30\nprint(a + b)"
    res_normal = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=102.0,
        peak_memory_mb=10.1,
        code=code_v2,
        store=store,
    )

    assert res_normal["is_slowdown"] is False
    assert res_normal["changed_lines"] == []
    assert res_normal["comparison_available"] is False
    assert res_normal["message"] == "Execution runtime is within normal parameters."


def test_us69_test4_flagged_run_does_not_poison_baseline():
    """Test 4: After a flagged run, verify the flagged run did not become the normal baseline.

    The next flagged run must compare against the last NORMAL run, not the earlier flagged run.
    """
    store = SlowdownStore()
    room_id = "test-room-us69-t4"
    input_hash = "d" * 64

    # Baseline: line 1 = 'x = 1', line 2 = 'y = 2'
    code_normal = "x = 1\ny = 2\nprint(x + y)"
    for _ in range(3):
        detect_slowdown(
            room_id=room_id,
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code=code_normal,
            store=store,
        )

    # Flagged run 1: line 2 = 'y = 999' (runtime 1000ms)
    code_flagged_1 = "x = 1\ny = 999\nprint(x + y)"
    res_slow_1 = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        code=code_flagged_1,
        store=store,
    )
    assert res_slow_1["is_slowdown"] is True
    assert [c.line for c in res_slow_1["changed_lines"]] == [2]

    # Flagged run 2: line 1 changed to 'x = 50', line 2 = 'y = 2' (matches normal code!)
    # If compared against flagged run 1: both line 1 and 2 would be different.
    # Because it compares against the last NORMAL run: only line 1 is different!
    code_flagged_2 = "x = 50\ny = 2\nprint(x + y)"
    res_slow_2 = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1200.0,
        peak_memory_mb=10.0,
        code=code_flagged_2,
        store=store,
    )
    assert res_slow_2["is_slowdown"] is True
    assert [c.line for c in res_slow_2["changed_lines"]] == [1]


def test_us69_test5_consent_disabled():
    """Test 5: Changed-line comparison works when consent=False (no AI dependency)."""
    store = SlowdownStore()
    room_id = "test-room-us69-t5"
    input_hash = "e" * 64
    code_v1 = "def f():\n    return 1"

    for _ in range(3):
        detect_slowdown(
            room_id=room_id,
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code=code_v1,
            consent=False,
            ai_enabled=True,
            store=store,
        )

    code_v2 = "def f():\n    return 999"
    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        code=code_v2,
        consent=False,
        ai_enabled=True,
        store=store,
    )

    assert res["is_slowdown"] is True
    assert [c.line for c in res["changed_lines"]] == [2]
    assert res["ai_step"].status == "skipped"
    assert res["ai_step"].error_code == "CONSENT_MISSING"


def test_us69_test6_host_ai_switch_disabled():
    """Test 6: Changed-line comparison works when ai_enabled=False."""
    store = SlowdownStore()
    room_id = "test-room-us69-t6"
    input_hash = "f" * 64
    code_v1 = "def f():\n    return 1"

    for _ in range(3):
        detect_slowdown(
            room_id=room_id,
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code=code_v1,
            consent=True,
            ai_enabled=False,
            store=store,
        )

    code_v2 = "def f():\n    return 999"
    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        code=code_v2,
        consent=True,
        ai_enabled=False,
        store=store,
    )

    assert res["is_slowdown"] is True
    assert [c.line for c in res["changed_lines"]] == [2]
    assert res["ai_step"].status == "skipped"
    assert res["ai_step"].error_code == "AI_DISABLED"


def test_us69_test7_room_isolation():
    """Test 7: Room isolation - Room A cannot access or compare against Room B's line hashes."""
    store = SlowdownStore()
    input_hash = "1" * 64
    code_a = "x = 100\nprint(x)"
    code_b = "y = 200\nprint(y)"

    # Seed Room A
    for _ in range(3):
        detect_slowdown(
            room_id="room-A",
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code=code_a,
            store=store,
        )

    # Seed Room B
    for _ in range(3):
        detect_slowdown(
            room_id="room-B",
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code=code_b,
            store=store,
        )

    # Slowdown in Room A with line 1 modified
    res_a = detect_slowdown(
        room_id="room-A",
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        code="x = 999\nprint(x)",
        store=store,
    )
    assert res_a["is_slowdown"] is True
    assert [c.line for c in res_a["changed_lines"]] == [1]

    # Verify Room A did not compare against Room B's code
    room_a_hashes = store.get_last_normal_line_hashes("room-A", input_hash)
    room_b_hashes = store.get_last_normal_line_hashes("room-B", input_hash)
    assert room_a_hashes != room_b_hashes


def test_us69_test8_room_cleanup():
    """Test 8: Room cleanup clears room's line hashes and leaves other rooms intact."""
    store = SlowdownStore()
    input_hash = "2" * 64

    for _ in range(3):
        detect_slowdown(
            room_id="room-cleanup-1",
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code="a = 1",
            store=store,
        )
        detect_slowdown(
            room_id="room-cleanup-2",
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code="b = 2",
            store=store,
        )

    assert store.get_last_normal_line_hashes("room-cleanup-1", input_hash) is not None
    assert store.get_last_normal_line_hashes("room-cleanup-2", input_hash) is not None

    # Clear room 1
    store.clear_room("room-cleanup-1")

    assert store.get_last_normal_line_hashes("room-cleanup-1", input_hash) is None
    assert store.get_history("room-cleanup-1", input_hash) == []

    # Room 2 remains intact
    assert store.get_last_normal_line_hashes("room-cleanup-2", input_hash) is not None
    assert len(store.get_history("room-cleanup-2", input_hash)) == 3


def test_us69_test9_no_raw_code_storage():
    """Test 9: Verify unique source marker is NEVER stored in SlowdownStore in-memory structures."""
    store = SlowdownStore()
    room_id = "test-room-us69-privacy"
    input_hash = "3" * 64
    marker = "US69_SECRET_MARKER_MUST_NEVER_APPEAR"
    code = f"x = '{marker}'\nprint(x)"

    for _ in range(3):
        detect_slowdown(
            room_id=room_id,
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code=code,
            store=store,
        )

    # Stringify the entire store internal dicts
    store_repr = str(store._rooms) + str(store._normal_line_hashes)
    assert marker not in store_repr, "Raw code marker was found inside SlowdownStore!"


def test_us69_test10_metadata_only_logs_and_counters(caplog):
    """Test 10: Verify unique source marker never appears in captured logs or usage counters."""
    import logging
    client = TestClient(app)
    room_id = "Zq8-Lw2Ef6Th0Yx4Rn7JbA"
    input_hash = "4" * 64
    marker = "US69_CANARY_MARKER_MUST_NEVER_APPEAR"
    code = f"def run():\n    val = '{marker}'\n    return val"

    with caplog.at_level(logging.DEBUG):
        resp = client.post(
            "/v1/analyses/slowdown",
            json={
                "request_id": "req-priv-01",
                "room_id": room_id,
                "analysis_id": "ana-priv-01",
                "request_type": "slowdown",
                "language": "python",
                "consent": False,
                "ai_enabled": False,
                "input_hash": input_hash,
                "current_run": {
                    "execution_time_ms": 100.0,
                    "peak_memory_mb": 10.0,
                    "code": code,
                },
            },
        )
        assert resp.status_code == 200

        # Check logs
        assert marker not in caplog.text, "Secret marker appeared in service logs!"

        # Check returned response body
        resp_text = resp.text
        assert marker not in resp_text, "Secret marker appeared in HTTP response body!"

        # Check usage endpoint
        usage_resp = client.get("/v1/usage")
        assert marker not in usage_resp.text, "Secret marker appeared in usage telemetry!"


def test_us69_test11_open_mode_module_support():
    """Test 11: Verify module_id is properly populated when supplied."""
    store = SlowdownStore()
    room_id = "test-room-us69-t11"
    input_hash = "5" * 64
    code_v1 = "import util\nprint(util.foo())"

    for _ in range(3):
        detect_slowdown(
            room_id=room_id,
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code=code_v1,
            module_id="mod_entry",
            store=store,
        )

    code_v2 = "import util\nprint(util.foo())\nprint('done')"
    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        code=code_v2,
        module_id="mod_entry",
        store=store,
    )

    assert res["is_slowdown"] is True
    assert len(res["changed_lines"]) == 1
    assert res["changed_lines"][0].line == 3
    assert res["changed_lines"][0].module_id == "mod_entry"


def test_us69_test12_regression_and_removed_lines():
    """Test 12: Verify removed lines count is accurate and reported in comparison_note."""
    store = SlowdownStore()
    room_id = "test-room-us69-t12"
    input_hash = "6" * 64
    code_v1 = "a = 1\nb = 2\nc = 3\nd = 4\nprint('done')"

    for _ in range(3):
        detect_slowdown(
            room_id=room_id,
            input_hash=input_hash,
            execution_time_ms=100.0,
            peak_memory_mb=10.0,
            code=code_v1,
            store=store,
        )

    # Code removes line 2 and modifies line 4 (which becomes line 3 in current code)
    code_v2 = "a = 1\nc = 3\nd = 999\nprint('done')"
    res = detect_slowdown(
        room_id=room_id,
        input_hash=input_hash,
        execution_time_ms=1000.0,
        peak_memory_mb=10.0,
        code=code_v2,
        store=store,
    )

    assert res["is_slowdown"] is True
    assert res["removed_lines_count"] == 1
    assert "1 line removed" in res["comparison_note"]
    assert [c.line for c in res["changed_lines"]] == [3]


