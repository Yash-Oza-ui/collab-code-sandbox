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

import math
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.slowdown import (
    SlowdownStore,
    calculate_baseline,
    compute_modified_z_score,
    detect_slowdown,
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
    h = "test-hash-del"

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
            "input_hash": "hash-inv",
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
            "input_hash": "hash-inv",
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
            "input_hash": "hash-inv",
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
    input_hash = "hash-consent-gate"

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
    input_hash = "hash-override-api"

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
    input_hash = "hash-ai-gen"

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
    input_hash = "hash-sentence-limit"

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
    input_hash = "hash-err-fallback"

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

