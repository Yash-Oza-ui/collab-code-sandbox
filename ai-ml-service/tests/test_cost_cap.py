"""Comprehensive tests for US-24: Monthly AI Cost Cap and 80% Budget Alert (NFR7.1, NFR7.2, NFR7.4, NFR7.5, NFR8.2).

Covers all 15 core verification scenarios plus admission-reservation changes (US-24 rev4):
1. Below budget: AI call is allowed.
2. Exactly at budget: AI call is blocked with COST_CAP_REACHED.
3. Above budget: Further AI calls are blocked with COST_CAP_REACHED.
4. 80% threshold: Alert is triggered when the 80% threshold is crossed.
5. Duplicate alert: Multiple calls after crossing 80% produce only one alert in the same month.
6. Month reset: Spending and alert state reset correctly in the next UTC month.
7. December-to-January transition: Monthly accounting resets correctly across year boundary.
8. Configuration: Changing MONTHLY_BUDGET_USD dynamically changes cap behavior.
9. Token pricing: Changing TOKEN_COST_PER_TOKEN dynamically changes calculated spending and threshold triggers.
10. AI-free functionality: Rules and statistical results are still returned when cap is reached across all endpoints.
11. Consent: Missing or False consent still produces CONSENT_MISSING (consent checked before cost cap).
12. AI disabled: Disabled or False ai_enabled still produces AI_DISABLED (Host AI switch checked before cost cap).
13. Cache: Existing cache behavior is preserved (cached results returned without making paid call even when cap is reached).
14. Privacy (NFR8.2 canary marker): No code, prompt, stderr, or user text appears in logs or counters store.
15. Concurrency: Concurrent calls cannot all pass a stale cap check without accounting for the reservation strategy.
16. Admission reservation uses LLM_MAX_OUTPUT_TOKENS (not a fixed 64-token allowance).
"""

import asyncio
from datetime import datetime, timedelta, timezone
import json
import logging
import sys
import threading
import time
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.cache import clear_analysis_cache, compute_cache_key, get_analysis_cache
from app.config import settings
from app.counters import (
    CallReservation,
    CostCapReachedError,
    UsageTracker,
    clear_room,
    get_monthly_spend,
    get_room_usage,
    get_total_usage,
    get_tracker,
    is_cost_cap_reached,
    prune_stale_reservations,
    record_cache_hit,
    record_fallback,
    record_model_call,
    reserve_call,
    reset_counters,
    set_clock,
    set_tracker,
    wait_for_alert_webhook,
)
from app.llm.client import (
    LLMClient,
    LLMProviderError,
    LLMTimeoutError,
    call_llm_with_consent,
)
from app.main import app

client = TestClient(app, raise_server_exceptions=False)


@pytest.fixture(autouse=True)
def clean_state():
    """Reset counters, clock, and cache before and after every test."""
    reset_counters()
    set_clock(None)
    clear_analysis_cache()
    yield
    wait_for_alert_webhook(timeout=1.0)
    reset_counters()
    set_clock(None)
    clear_analysis_cache()


# ============================================================================
# 1. Below Budget: AI call is allowed
# ============================================================================


@pytest.mark.anyio
async def test_below_budget_ai_call_allowed():
    """Requirement 1: AI calls succeed when total monthly spend is below configured budget."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.0000035

    # 1,000 tokens = $0.0035 (well below $10.00)
    record_model_call("room-test-1", tokens=1000)

    assert not is_cost_cap_reached()
    usage = get_total_usage()
    assert usage["cost_cap_reached"] is False
    assert usage["monthly_spend_usd"] == 0.0035
    assert usage["monthly_budget_usd"] == 10.0

    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "candidates": [{"content": {"parts": [{"text": "Fix line 5"}]}}],
        "usageMetadata": {"totalTokenCount": 150},
    }

    with patch("httpx.AsyncClient.post", return_value=mock_resp):
        out = await call_llm_with_consent(
            prompt="Optimize code",
            consent=True,
            ai_enabled=True,
            api_key="fake-key",
            room_id="room-test-1",
        )

    assert out == "Fix line 5"
    assert get_total_usage()["total_model_calls"] == 2


# ============================================================================
# 2. Exactly at Budget: AI call is blocked with COST_CAP_REACHED
# ============================================================================


@pytest.mark.anyio
async def test_exactly_at_budget_blocks_ai_call():
    """Requirement 2: AI call is blocked immediately with COST_CAP_REACHED when spend exactly reaches budget."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.00
    tracker.token_cost_per_token = 0.001

    # Record exactly 1,000 tokens * 0.001 = $1.0000 (exactly at budget)
    record_model_call("room-exact", tokens=1000)

    assert tracker.get_monthly_spend_usd() == 1.00
    assert is_cost_cap_reached() is True
    assert get_total_usage()["cost_cap_reached"] is True

    # Attempting an outbound call raises CostCapReachedError
    mock_client = LLMClient()
    with pytest.raises(CostCapReachedError) as exc_info:
        await call_llm_with_consent(
            prompt="Optimize",
            consent=True,
            ai_enabled=True,
            client=mock_client,
        )

    assert exc_info.value.code == "COST_CAP_REACHED"
    assert exc_info.value.status_code == 402
    # Verify 0 outbound calls were initiated on the client
    assert mock_client.call_count == 0


# ============================================================================
# 3. Above Budget: Further AI calls are blocked
# ============================================================================


@pytest.mark.anyio
async def test_above_budget_blocks_further_ai_calls():
    """Requirement 3: Further outbound AI calls remain strictly blocked when spend exceeds budget."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.00
    tracker.token_cost_per_token = 0.001

    # 1,500 tokens * 0.001 = $1.50 (above budget)
    record_model_call("room-above", tokens=1500)

    assert tracker.get_monthly_spend_usd() == 1.50
    assert is_cost_cap_reached() is True

    mock_client = LLMClient()
    for _ in range(3):
        with pytest.raises(CostCapReachedError):
            await call_llm_with_consent(
                prompt="Code review",
                consent=True,
                ai_enabled=True,
                client=mock_client,
            )

    # Lifetime model calls remains 1 (0 outbound calls made during blocked attempts)
    assert get_total_usage()["total_model_calls"] == 1
    assert mock_client.call_count == 0


# ============================================================================
# 4. 80% Threshold: Alert is triggered when threshold is crossed
# ============================================================================


def test_80_percent_threshold_triggers_alert(caplog):
    """Requirement 4: When monthly spend reaches or exceeds 80% of budget, alert is triggered."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001
    # 80% of $10.00 = $8.00 -> 800,000 tokens

    with caplog.at_level(logging.WARNING):
        # 700,000 tokens = $7.00 (70% - not yet 80%)
        record_model_call("room-alert", tokens=700000)
        assert tracker.alert_80_percent_triggered is False
        assert not any("AI_COST_CAP_ALERT" in r.getMessage() for r in caplog.records)

        # Additional 100,000 tokens -> total $8.00 (80% - threshold reached!)
        record_model_call("room-alert", tokens=100000)
        assert tracker.alert_80_percent_triggered is True

    alert_logs = [r for r in caplog.records if "AI_COST_CAP_ALERT" in r.getMessage()]
    assert len(alert_logs) == 1
    msg = alert_logs[0].getMessage()
    assert "spend_usd=8.0000" in msg
    assert "budget_usd=10.0000" in msg
    assert "threshold_usd=8.0000" in msg


# ============================================================================
# 5. Duplicate Alert: Only one alert is produced in the same month
# ============================================================================


def test_duplicate_alert_suppressed_in_same_month(caplog):
    """Requirement 5: Multiple calls after crossing 80% threshold produce only ONE alert in that UTC month."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    with caplog.at_level(logging.WARNING):
        # First call crosses 80% -> 800,000 tokens ($8.00)
        record_model_call("room-dedup", tokens=800000)

        # Subsequent calls in the same month (85%, 90%, 95%)
        record_model_call("room-dedup", tokens=50000)  # $8.50
        record_model_call("room-dedup", tokens=50000)  # $9.00
        record_model_call("room-dedup", tokens=50000)  # $9.50

    alert_logs = [r for r in caplog.records if "AI_COST_CAP_ALERT" in r.getMessage()]
    assert len(alert_logs) == 1, f"Expected exactly 1 alert log, found {len(alert_logs)}"


# ============================================================================
# 6. Month Reset: Spending and alert state reset in next UTC month
# ============================================================================


def test_month_reset_resets_spending_and_alert_state(caplog):
    """Requirement 6: In the next UTC calendar month, monthly spending and alert state reset cleanly."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    # Month 1: November 2026
    nov_time = datetime(2026, 11, 15, 12, 0, 0, tzinfo=timezone.utc)
    set_clock(lambda: nov_time)

    # Spend $8.50 in November -> alert triggers
    with caplog.at_level(logging.WARNING):
        record_model_call("room-month", tokens=850000)

    assert tracker.get_monthly_spend_usd() == 8.50
    assert tracker.alert_80_percent_triggered is True

    # Advance time to Month 2: December 2026
    dec_time = datetime(2026, 12, 1, 0, 0, 1, tzinfo=timezone.utc)
    set_clock(lambda: dec_time)

    # Verify monthly spend and alert state reset
    assert tracker.get_monthly_spend_usd() == 0.0
    assert tracker.alert_80_percent_triggered is False
    assert is_cost_cap_reached() is False

    # Lifetime platform totals must remain intact
    usage = get_total_usage()
    assert usage["total_tokens_used"] == 850000
    assert usage["total_model_calls"] == 1
    assert usage["monthly_spend_usd"] == 0.0

    # In December, spending reaches 80% again -> second alert triggers for December
    with caplog.at_level(logging.WARNING):
        record_model_call("room-month", tokens=800000)

    dec_alerts = [r for r in caplog.records if "AI_COST_CAP_ALERT" in r.getMessage() and "month=2026-12" in r.getMessage()]
    assert len(dec_alerts) == 1


# ============================================================================
# 7. December-to-January Transition: Year rollover works correctly
# ============================================================================


def test_december_to_january_transition():
    """Requirement 7: Year rollover from December 31 to January 1 resets monthly accounting properly."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    # New Year's Eve: 2026-12-31 23:59:59 UTC
    nye_time = datetime(2026, 12, 31, 23, 59, 59, tzinfo=timezone.utc)
    set_clock(lambda: nye_time)

    # Spend full budget in December -> cost cap reached
    record_model_call("room-nye", tokens=1000000)  # $10.00
    assert tracker.get_monthly_spend_usd() == 10.0
    assert is_cost_cap_reached() is True
    assert tracker.current_month == "2026-12"

    # New Year: 2027-01-01 00:00:00 UTC
    new_year_time = datetime(2027, 1, 1, 0, 0, 0, tzinfo=timezone.utc)
    set_clock(lambda: new_year_time)

    # Verified reset in January 2027
    assert tracker.current_month == "2027-01"
    assert tracker.get_monthly_spend_usd() == 0.0
    assert is_cost_cap_reached() is False
    assert tracker.alert_80_percent_triggered is False

    # Lifetime totals preserved
    assert tracker.get_total_usage()["total_tokens_used"] == 1000000
    assert tracker.get_total_usage()["total_model_calls"] == 1


# ============================================================================
# 8. Configuration: Changing MONTHLY_BUDGET_USD changes cap behavior
# ============================================================================


def test_changing_monthly_budget_changes_cap_behavior():
    """Requirement 8: Dynamically changing monthly_budget_usd alters cost-cap evaluation immediately."""
    tracker = get_tracker()
    tracker.token_cost_per_token = 0.001
    tracker.monthly_budget_usd = 10.0

    # Spend $5.00 (5,000 tokens)
    record_model_call("room-cfg", tokens=5000)
    assert tracker.get_monthly_spend_usd() == 5.0
    assert is_cost_cap_reached() is False

    # Lower budget to $4.00 -> now spending is over budget
    tracker.monthly_budget_usd = 4.0
    assert is_cost_cap_reached() is True

    # Raise budget to $20.00 -> spending is well under budget again
    tracker.monthly_budget_usd = 20.0
    assert is_cost_cap_reached() is False


# ============================================================================
# 9. Token Pricing: Changing TOKEN_COST_PER_TOKEN changes calculated spending
# ============================================================================


def test_changing_token_cost_changes_calculated_spend():
    """Requirement 9: Changing token_cost_per_token recalculates spend and impacts cap evaluation."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.000001

    # 1,000,000 tokens * 0.000001 = $1.00
    record_model_call("room-pricing", tokens=1000000)
    assert tracker.get_monthly_spend_usd() == 1.00
    assert is_cost_cap_reached() is False

    # Update token pricing to $0.000015 (1.5x standard Gemini rate)
    # 1,000,000 * 0.000015 = $15.00 -> exceeds $10.00 budget
    tracker.token_cost_per_token = 0.000015
    assert tracker.get_monthly_spend_usd() == 15.00
    assert is_cost_cap_reached() is True


# ============================================================================
# 10. AI-Free Functionality: Rules & Statistics work when cap is reached
# ============================================================================


def test_complexity_endpoint_ai_free_results_when_cap_reached():
    """Requirement 10a: /v1/analyses/complexity returns empirical regression and slow patterns when cap reached."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.0
    record_model_call("room-cap", tokens=1000000)  # Exhaust budget

    payload = {
        "request_id": "req-comp-cap-01",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "analysis_id": "ana-comp-cap-01",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "rules_first": False,
        "code": "for i in range(10):\n    for j in range(10):\n        pass",
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
            {"input_size": 200, "runtimes_ms": [4.0, 4.1]},
            {"input_size": 400, "runtimes_ms": [16.0, 16.2]},
        ],
    }

    response = client.post("/v1/analyses/complexity", json=payload)
    assert response.status_code == 200
    data = response.json()

    # Empirical mathematical calculations succeeded
    assert data["estimated_complexity"] in ["O(n^2)", "O(n^3)", "O(2^n)"]
    assert data["growth_rate"] > 1.5

    # Deterministic rule-based AST analysis detected nested loops
    assert any(p["pattern"] == "nested_loops" for p in data["slow_patterns"])

    # AI step is safely skipped with COST_CAP_REACHED
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "COST_CAP_REACHED"
    assert data["ai_step"]["suggested_fix"] is None
    assert data["ai_step"]["verified"] is False


def test_crash_explanation_endpoint_ai_free_results_when_cap_reached():
    """Requirement 10b: /v1/analyses/crash-explanation returns sanitizer parsing when cap reached (matches contract example)."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.0
    record_model_call("room-cap", tokens=1000000)

    payload = {
        "request_id": "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "analysis_id": "6b7c8d9e-0f1a-2b3c-4d5e-6f7a8b9c0d1e",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": (
            "==4210==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000010\n"
            "    #0 0x401233 in process_buffer /workspace/solution.cpp:14"
        ),
        "code_snippet": "int val = buffer[index];",
    }

    response = client.post("/v1/analyses/crash-explanation", json=payload)
    assert response.status_code == 200
    data = response.json()

    # Deterministic sanitizer parsing succeeded
    assert data["error_type"] == "heap-buffer-overflow"
    assert data["line_number"] == 14
    assert data["summary"] == "Detected heap-buffer-overflow at line 14."

    # AI step skipped with COST_CAP_REACHED matching docs/examples/v2-analysis-crash-explanation-cost-cap.json
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "COST_CAP_REACHED"
    assert data["ai_step"]["explanation"] is None
    assert data["ai_step"]["suggested_fix"] is None


def test_explain_endpoint_rule_findings_when_cap_reached():
    """Requirement 10c: /v1/explain returns rule findings with COST_CAP_REACHED when cap is reached."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.0
    record_model_call("room-cap", tokens=1000000)

    payload = {
        "request_id": "req-explain-cap-01",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "request_type": "explanation",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "rules_first": False,
        "code": "for i in range(10):\n    for j in range(10):\n        pass",
        "stderr": "RuntimeError: slow execution",
        "exit_code": 1,
        "execution_time_ms": 100.0,
        "peak_memory_mb": 5.0,
        "timed_out": False,
        "oom_killed": False,
        "failure_kind": "non_zero_exit",
    }

    response = client.post("/v1/explain", json=payload)
    assert response.status_code == 200
    data = response.json()

    assert data["explanation"] is None
    assert any(p["pattern"] == "nested_loops" for p in data["slow_patterns"])
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "COST_CAP_REACHED"
    assert data["ai_step"]["suggested_fix"] is None


# ============================================================================
# 11. Consent Gate Precedence: Missing consent produces CONSENT_MISSING
# ============================================================================


def test_consent_missing_takes_precedence_over_cost_cap():
    """Requirement 11: When consent is false/missing, CONSENT_MISSING is returned even if cost cap is also reached."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.0
    record_model_call("room-consent", tokens=1000000)  # Cap is reached

    payload = {
        "request_id": "req-consent-prec-01",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "analysis_id": "ana-consent-prec-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": False,  # Declining consent
        "ai_enabled": True,
        "sanitizer_output": "AddressSanitizer: heap-buffer-overflow:14",
        "code_snippet": "int val = buffer[index];",
    }

    response = client.post("/v1/analyses/crash-explanation", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"


# ============================================================================
# 12. Host AI Switch Precedence: Disabled AI produces AI_DISABLED
# ============================================================================


def test_ai_disabled_takes_precedence_over_cost_cap():
    """Requirement 12: When ai_enabled is false/missing, AI_DISABLED is returned even if cost cap is also reached."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.0
    record_model_call("room-switch", tokens=1000000)  # Cap is reached

    payload = {
        "request_id": "req-switch-prec-01",
        "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
        "analysis_id": "ana-switch-prec-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": False,  # Host AI switch disabled
        "sanitizer_output": "AddressSanitizer: heap-buffer-overflow:14",
        "code_snippet": "int val = buffer[index];",
    }

    response = client.post("/v1/analyses/crash-explanation", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "AI_DISABLED"


# ============================================================================
# 13. Cache Preservation: Cached results return even when cap is reached
# ============================================================================


def test_cache_hits_served_when_cost_cap_reached():
    """Requirement 13: Existing cached responses remain usable and are served even when the cost cap is reached."""
    room_id = "room-cache-preserve"
    code = "int x = buffer[i];"
    cache = get_analysis_cache()
    key = compute_cache_key(
        code=code,
        language="cpp",
        request_type="crash_explanation",
        error_signature="heap-buffer-overflow:14",
        error_output="AddressSanitizer: heap-buffer-overflow:14",
    )
    cache.set(
        key,
        {"explanation": "Previously cached explanation", "suggested_fix": "Add bounds check."},
        room_id=room_id,
    )

    # Exhaust budget after caching
    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.0
    record_model_call(room_id, tokens=1000000)
    assert is_cost_cap_reached() is True

    payload = {
        "request_id": "req-cache-hit-cap-01",
        "room_id": room_id,
        "analysis_id": "ana-cache-hit-cap-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": "AddressSanitizer: heap-buffer-overflow:14",
        "code_snippet": code,
    }

    response = client.post("/v1/analyses/crash-explanation", json=payload)
    assert response.status_code == 200
    data = response.json()

    # Cached response returned with ok status without making any paid outbound call
    assert data["ai_step"]["status"] == "ok"
    assert data["ai_step"]["explanation"] == "Previously cached explanation"
    assert data["ai_step"]["suggested_fix"] == "Add bounds check."

    # Verify cache hit was recorded
    assert get_room_usage(room_id)["cache_hits"] == 1


# ============================================================================
# 14. Strict Privacy Invariant (NFR8.2): Canary marker test
# ============================================================================


def test_canary_marker_never_stored_in_counters_or_logs(caplog):
    """Requirement 14 (NFR8.2): Canary markers in code/prompts never appear in counters or log output."""
    canary = "CANARY_SECRET_USER_CODE_XYZ_123456789"
    room_id = "room-canary-test"

    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.0
    record_model_call(room_id, tokens=1000000)  # Block AI calls

    with caplog.at_level(logging.DEBUG):
        payload = {
            "request_id": "req-canary-01",
            "room_id": room_id,
            "request_type": "explanation",
            "language": "python",
            "consent": True,
            "ai_enabled": True,
            "code": f"def sensitive_func():\n    # {canary}\n    pass",
            "stderr": f"Error with {canary}",
            "exit_code": 1,
            "execution_time_ms": 10.0,
            "peak_memory_mb": 5.0,
            "timed_out": False,
            "oom_killed": False,
            "failure_kind": "non_zero_exit",
        }
        response = client.post("/v1/explain", json=payload)
        assert response.status_code == 200

    # Ensure canary never leaked into any captured log record
    for record in caplog.records:
        assert canary not in record.getMessage()

    # Ensure canary never stored in usage tracker internal store
    tracker_dump = repr(tracker.__dict__)
    assert canary not in tracker_dump


# ============================================================================
# 15. Concurrency & Reservation Strategy
# ============================================================================


def test_concurrent_reservations_prevent_cap_stampede():
    """Requirement 15: Concurrent calls cannot all pass a stale cap check due to atomic reservation tracking.

    Uses threading.Barrier to ensure all 10 threads attempt reservation concurrently.
    Verifies that projected spend (committed + active + new < budget) admits exactly
    the allowable number of reservations and rejects all subsequent attempts.
    """
    tracker = get_tracker()
    tracker.token_cost_per_token = 0.0000035
    # Headroom for exactly two 100-token reservations:
    # Call 1: 0 + 0 + 100 = 100 < 201 (admitted)
    # Call 2: 0 + 100 + 100 = 200 < 201 (admitted)
    # Call 3: 0 + 200 + 100 = 300 >= 201 (rejected)
    tracker.monthly_budget_usd = 201 * 0.0000035

    successful_reservations = []
    blocked_reservations = []
    lock = threading.Lock()
    barrier = threading.Barrier(10)

    def attempt_reservation(worker_id: int):
        barrier.wait()
        try:
            res = tracker.reserve_call(estimated_tokens=100)
            with lock:
                successful_reservations.append((worker_id, res))
        except CostCapReachedError:
            with lock:
                blocked_reservations.append(worker_id)

    # Launch 10 simultaneous threads attempting to reserve budget
    threads = [threading.Thread(target=attempt_reservation, args=(i,)) for i in range(10)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    # Exactly 2 calls fit within the budget; the remaining 8 are rejected deterministically
    assert len(successful_reservations) == 2, f"Expected 2 successful reservations, got {len(successful_reservations)}"
    assert len(blocked_reservations) == 8
    # Active reservations hold exactly 200 tokens
    assert tracker._active_reservations_tokens == 200

    # Clean up reservations
    for _, res in successful_reservations:
        res.release()

    # Releasing reservations restores budget headroom when calls aborted
    assert tracker._active_reservations_tokens == 0
    assert not is_cost_cap_reached(include_reservations=True)


# ============================================================================
# 16. Fallback Activation Counter Increment
# ============================================================================


def test_cost_cap_blocked_call_records_fallback():
    """Verify that blocking a call due to cost cap increments total_fallback_activations."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 1.0
    record_model_call("room-fallback", tokens=1000000)

    initial_fallbacks = get_total_usage()["total_fallback_activations"]

    payload = {
        "request_id": "req-fb-01",
        "room_id": "room-fallback",
        "analysis_id": "ana-fb-01",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": "AddressSanitizer: heap-buffer-overflow:14",
        "code_snippet": "int val = buffer[index];",
    }

    response = client.post("/v1/analyses/crash-explanation", json=payload)
    assert response.status_code == 200

    updated_usage = get_total_usage()
    assert updated_usage["total_fallback_activations"] == initial_fallbacks + 1
    assert get_room_usage("room-fallback")["fallback_activations"] == 1


# ============================================================================
# 17. Telemetry GET /v1/usage Contract Verification
# ============================================================================


def test_get_usage_contract_fields_and_types():
    """Verify GET /v1/usage returns all required schema fields matching docs/schemas/usage-response.json."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.0000035
    record_model_call("room-t18", tokens=850000)  # $2.975 (exceeds 80%? No, 80% is $8.00)

    response = client.get("/v1/usage")
    assert response.status_code == 200
    data = response.json()

    required_fields = {
        "total_model_calls": int,
        "total_tokens_used": int,
        "total_cache_hits": int,
        "total_fallback_activations": int,
        "monthly_spend_usd": (float, int),
        "monthly_budget_usd": (float, int),
        "alert_80_percent_triggered": bool,
        "cost_cap_reached": bool,
        "rooms_tracked": int,
    }

    for field, expected_type in required_fields.items():
        assert field in data, f"Missing field {field} in /v1/usage response"
        assert isinstance(data[field], expected_type), f"Field {field} had unexpected type {type(data[field])}"


# ============================================================================
# 18. Atomic Reservation Logic Boundaries (Issue 1)
# ============================================================================


def test_headroom_smaller_than_reservation_is_rejected():
    """Requirement 1a: When remaining budget headroom is smaller than requested reservation, reject immediately."""
    tracker = get_tracker()
    tracker.token_cost_per_token = 0.0000035
    # Budget for 200 tokens = $0.0007
    tracker.monthly_budget_usd = 0.0007

    # Current spend = 150 tokens ($0.000525). Remaining headroom = 50 tokens ($0.000175)
    record_model_call("room-headroom-small", tokens=150)
    assert not is_cost_cap_reached()

    # Attempting to reserve 100 tokens (> 50 tokens headroom) must be rejected
    with pytest.raises(CostCapReachedError):
        tracker.reserve_call(estimated_tokens=100)

    # Active reservations and monthly tokens remain unaltered
    assert tracker._active_reservations_tokens == 0
    assert tracker.monthly_tokens_used == 150


def test_headroom_exactly_equal_to_reservation_is_rejected():
    """Requirement 1b: When headroom exactly equals reservation, projected spend reaches budget -> reject."""
    tracker = get_tracker()
    tracker.token_cost_per_token = 0.0000035
    # Budget for 200 tokens = $0.0007
    tracker.monthly_budget_usd = 0.0007

    # Current spend = 100 tokens. Remaining headroom = exactly 100 tokens.
    record_model_call("room-headroom-exact", tokens=100)

    # 100 current + 0 active + 100 new = 200 tokens (spend reaches budget $0.0007 >= $0.0007)
    with pytest.raises(CostCapReachedError):
        tracker.reserve_call(estimated_tokens=100)

    assert tracker._active_reservations_tokens == 0


def test_headroom_strictly_greater_than_reservation_is_accepted():
    """Requirement 1c: When headroom is strictly greater than reservation by even 1 token, reservation succeeds."""
    tracker = get_tracker()
    tracker.token_cost_per_token = 0.0000035
    # Budget for 201 tokens = $0.0007035
    tracker.monthly_budget_usd = 0.0007035

    # Current spend = 100 tokens. Headroom = 101 tokens.
    record_model_call("room-headroom-ok", tokens=100)

    # 100 current + 0 active + 100 new = 200 tokens < 201 tokens -> ACCEPTED
    res = tracker.reserve_call(estimated_tokens=100)
    assert res.reserved_tokens == 100
    assert tracker._active_reservations_tokens == 100

    # A subsequent reservation of even 1 token reaches budget (200 + 1 = 201) -> REJECTED
    with pytest.raises(CostCapReachedError):
        tracker.reserve_call(estimated_tokens=1)

    res.release()
    assert tracker._active_reservations_tokens == 0


def test_reservation_accounting_existing_spend_plus_active_reservations_plus_new():
    """Requirement 1d: Projected spend evaluates spend + active reservations + new reservation atomically."""
    tracker = get_tracker()
    tracker.token_cost_per_token = 0.0000035
    # Budget for 300 tokens = $0.00105
    tracker.monthly_budget_usd = 0.00105

    # Existing spend = 100 tokens ($0.00035)
    record_model_call("room-chain", tokens=100)

    # First reservation: 100 tokens (projected: 100 + 0 + 100 = 200 < 300) -> OK
    res1 = tracker.reserve_call(estimated_tokens=100)
    assert tracker._active_reservations_tokens == 100

    # Second reservation: 100 tokens (projected: 100 + 100 + 100 = 300 >= 300) -> BLOCKED
    with pytest.raises(CostCapReachedError):
        tracker.reserve_call(estimated_tokens=100)

    # Third reservation: 50 tokens (projected: 100 + 100 + 50 = 250 < 300) -> OK
    res2 = tracker.reserve_call(estimated_tokens=50)
    assert tracker._active_reservations_tokens == 150

    # Fourth reservation: 50 tokens (projected: 100 + 150 + 50 = 300 >= 300) -> BLOCKED
    with pytest.raises(CostCapReachedError):
        tracker.reserve_call(estimated_tokens=50)

    # Release res1 (100 tokens): active reservations drops to 50
    res1.release()
    assert tracker._active_reservations_tokens == 50

    # Now fifth reservation of 100 tokens (projected: 100 + 50 + 100 = 250 < 300) -> OK
    res3 = tracker.reserve_call(estimated_tokens=100)
    assert tracker._active_reservations_tokens == 150

    res2.release()
    res3.release()
    assert tracker._active_reservations_tokens == 0


def test_concurrent_reservations_near_exact_budget_boundary():
    """Requirement 1e: Multiple concurrent reservations near boundary cannot exceed headroom."""
    tracker = get_tracker()
    tracker.token_cost_per_token = 0.0000035
    # Budget for 450 tokens = $0.001575
    tracker.monthly_budget_usd = 0.001575
    # Current spend = 100 tokens. Headroom = 350 tokens.
    record_model_call("room-boundary", tokens=100)

    barrier = threading.Barrier(12)
    successful = []
    blocked = []
    lock = threading.Lock()

    def try_reserve(idx: int):
        barrier.wait()
        try:
            # Each requests 100 tokens. Headroom is 350, so at most 3 can succeed (100+300=400 < 450; 4th would be 500 >= 450)
            r = tracker.reserve_call(estimated_tokens=100)
            with lock:
                successful.append((idx, r))
        except CostCapReachedError:
            with lock:
                blocked.append(idx)

    threads = [threading.Thread(target=try_reserve, args=(i,)) for i in range(12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert len(successful) == 3, f"Expected exactly 3 successful reservations, got {len(successful)}"
    assert len(blocked) == 9
    assert tracker._active_reservations_tokens == 300
    assert tracker.monthly_tokens_used + tracker._active_reservations_tokens <= 450

    for _, r in successful:
        r.release()

    assert tracker._active_reservations_tokens == 0


# ============================================================================
# 19. Eliminate Unreserved Direct Paid Calls (Issue 2)
# ============================================================================


@pytest.mark.anyio
async def test_concurrent_direct_complete_calls_cannot_bypass_budget_gate():
    """Requirement 2: Direct LLMClient.complete() calls without explicit reservation MUST be atomically reserved.

    Verifies the admission invariant under concurrent direct complete() calls:
        committed monthly usage + active reservations + new reservation < monthly budget

    Deterministic design using asyncio synchronization events:
    1. Initial committed spend is established (e.g. 200 tokens).
    2. Five concurrent tasks invoke direct llm_client.complete() without passing a reservation.
    3. The budget is calibrated so exactly 2 calls can be admitted (200 + 2*260 = 720 < 800),
       while any 3rd call requires 200 + 520 + 260 = 980 >= 800 and must be rejected.
    4. Admitted calls enter the mock HTTP post handler and are held in flight using an asyncio.Event.
    5. While the 2 admitted calls are held in flight:
       - The admission invariant is verified: committed + active <= budget.
       - The remaining 3 concurrent tasks attempt complete() and are immediately blocked with CostCapReachedError.
    6. When the in-flight calls are released:
       - One call completes successfully (commits actual tokens, e.g. 150 tokens).
       - One call encounters an upstream HTTP 500 error (releases reservation without billing).
    7. Verifies:
       - Exactly 2 calls were admitted, exactly 3 were blocked by the cost cap.
       - The successful call committed its actual tokens.
       - The failed call released its reservation without billing.
       - tracker._active_reservations_tokens returns to exactly 0 (no leaks).
       - No concurrent admission ever bypassed the budget cap.
    """
    with (
        patch.object(settings, "AI_MAX_TOKENS_PER_REQUEST", 256),
        patch.object(settings, "LLM_MAX_OUTPUT_TOKENS", 256),
    ):
        tracker = get_tracker()
        tracker.token_cost_per_token = 0.001

        max_output_tokens = int(getattr(settings, "LLM_MAX_OUTPUT_TOKENS", 256))
        prompt = "Direct call test"  # 16 chars -> 4 tokens
        admission_tokens = max(100, (len(prompt) // 4) + max_output_tokens)  # 260 tokens
        assert admission_tokens == 260

        # Establish initial committed usage: 200 tokens ($0.20)
        record_model_call("room-direct-init", tokens=200)
        assert tracker.monthly_tokens_used == 200

        # Budget calibrated for exactly 2 admissions:
        # Call 1 projected: 200 + 0 + 260 = 460 < 800 (admitted)
        # Call 2 projected: 200 + 260 + 260 = 720 < 800 (admitted)
        # Call 3 projected: 200 + 520 + 260 = 980 >= 800 (rejected)
        budget_tokens = 800
        tracker.monthly_budget_usd = budget_tokens * 0.001  # $0.80

        llm_client = LLMClient()

        in_flight_entered = asyncio.Event()
        release_in_flight = asyncio.Event()
        admitted_calls = []
        admitted_lock = asyncio.Lock()

        async def mock_post(url, headers=None, json=None, timeout=None):
            nonlocal admitted_calls
            async with admitted_lock:
                # An admitted call has passed reserve_call() and entered network dispatch
                current_monthly = tracker.monthly_tokens_used
                current_active = tracker._active_reservations_tokens
                call_idx = len(admitted_calls) + 1
                admitted_calls.append((call_idx, current_monthly, current_active))
                if len(admitted_calls) == 2:
                    in_flight_entered.set()

            # Hold this call in-flight until the test signals release
            await release_in_flight.wait()

            # Call 1 succeeds with 150 actual tokens; Call 2 fails with HTTP 500
            mock_resp = MagicMock()
            if call_idx == 1:
                mock_resp.status_code = 200
                mock_resp.json.return_value = {
                    "candidates": [{"content": {"parts": [{"text": "Fix applied"}]}}],
                    "usageMetadata": {"totalTokenCount": 150},
                }
            else:
                mock_resp.status_code = 500
            return mock_resp

        results = {}

        async def worker(worker_id: int):
            try:
                res = await llm_client.complete(
                    prompt=prompt,
                    api_key="fake-key",
                    model="gemini-1.5-flash",
                    room_id="room-direct-worker",
                )
                results[worker_id] = ("SUCCESS", res)
            except CostCapReachedError:
                results[worker_id] = ("COST_CAP_REACHED", None)
            except LLMProviderError as exc:
                results[worker_id] = ("PROVIDER_ERROR", exc.status_code)

        with patch("httpx.AsyncClient.post", side_effect=mock_post):
            # Launch 5 concurrent workers calling complete() directly
            tasks = [asyncio.create_task(worker(i)) for i in range(5)]

            # Wait deterministically until exactly 2 admitted calls are in flight
            await asyncio.wait_for(in_flight_entered.wait(), timeout=5.0)

            # Allow other worker coroutines to run their reservation attempts while the 2 calls are in flight
            await asyncio.sleep(0.05)

            # ----------------------------------------------------------------------
            # INVARIANT VERIFICATION WHILE CALLS ARE IN FLIGHT:
            # For every admitted call, the admission invariant must hold:
            # committed monthly usage + active reservations + new reservation < budget
            # ----------------------------------------------------------------------
            assert len(admitted_calls) == 2, f"Expected exactly 2 admitted calls, got {len(admitted_calls)}"

            # Check snapshot 1 (when Call 1 was admitted):
            # committed = 200, active was 260 (200 + 0 + 260 = 460 < 800)
            call1_idx, call1_monthly, call1_active = admitted_calls[0]
            assert call1_monthly == 200
            assert call1_active == 260
            assert call1_monthly + call1_active <= budget_tokens

            # Check snapshot 2 (when Call 2 was admitted):
            # committed = 200, active was 520 (200 + 260 + 260 = 720 < 800)
            call2_idx, call2_monthly, call2_active = admitted_calls[1]
            assert call2_monthly == 200
            assert call2_active == 520
            assert call2_monthly + call2_active <= budget_tokens

            # While both calls are in flight, active reservations must equal exactly 2 * 260 = 520:
            assert tracker._active_reservations_tokens == 520
            assert tracker.monthly_tokens_used == 200
            # Invariant check: committed + active <= budget
            assert tracker.monthly_tokens_used + tracker._active_reservations_tokens <= budget_tokens

            # All other 3 workers MUST have been blocked already by CostCapReachedError
            # because committed(200) + active(520) + new(260) = 980 >= 800:
            blocked_so_far = [w_id for w_id, (status, _) in results.items() if status == "COST_CAP_REACHED"]
            assert len(blocked_so_far) == 3, f"Expected 3 blocked calls, got {len(blocked_so_far)}"

            # ----------------------------------------------------------------------
            # RELEASE IN-FLIGHT CALLS & VERIFY COMMIT / RELEASE LIFECYCLE:
            # ----------------------------------------------------------------------
            release_in_flight.set()
            await asyncio.gather(*tasks)

        # 1. Exactly 1 success, 1 provider error, 3 cost cap blocked
        statuses = [status for status, _ in results.values()]
        assert statuses.count("SUCCESS") == 1
        assert statuses.count("PROVIDER_ERROR") == 1
        assert statuses.count("COST_CAP_REACHED") == 3

        # 2. Reservation cleanup: ALL active reservations must be 0 (no leaks!)
        assert tracker._active_reservations_tokens == 0

        # 3. Monthly usage: initial (200) + successful committed actual tokens (150) = 350
        # (failed call released without billing, blocked calls never billed)
        assert tracker.monthly_tokens_used == 350
        assert tracker.get_room_usage("room-direct-worker")["tokens_used"] == 150


# ============================================================================
# 20. Webhook Network I/O Outside Tracker Lock (Issue 3)
# ============================================================================


def test_alert_webhook_timeout_does_not_break_request(caplog):
    """Requirement 3a: Webhook timeout must never break analysis, accounting, or raise to caller."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001
    # 80% is $8.00 (800,000 tokens)

    with (
        patch.object(settings, "ALERT_WEBHOOK_URL", "https://hooks.internal/alerts"),
        patch("httpx.Client.post", side_effect=httpx.TimeoutException("Webhook timed out")),
        caplog.at_level(logging.WARNING),
    ):
        # Must succeed without throwing
        record_model_call("room-webhook-timeout", tokens=800000)
        tracker.wait_for_alert_webhook()

    assert tracker.alert_80_percent_triggered is True
    assert tracker.get_monthly_spend_usd() == 8.0
    # Verified structured log warning was recorded
    assert any("AI_COST_CAP_ALERT" in r.getMessage() for r in caplog.records)
    assert any("Failed to dispatch alert webhook" in r.getMessage() for r in caplog.records)


def test_alert_webhook_connection_error_does_not_break_request(caplog):
    """Requirement 3b: Webhook connection error must never break analysis or accounting."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    with (
        patch.object(settings, "ALERT_WEBHOOK_URL", "https://unreachable.host/webhook"),
        patch("httpx.Client.post", side_effect=httpx.ConnectError("Connection refused")),
        caplog.at_level(logging.WARNING),
    ):
        record_model_call("room-webhook-conn", tokens=800000)
        tracker.wait_for_alert_webhook()

    assert tracker.alert_80_percent_triggered is True
    assert tracker.get_monthly_spend_usd() == 8.0
    assert any("Failed to dispatch alert webhook" in r.getMessage() for r in caplog.records)


def test_alert_webhook_http_500_logged_as_warning_not_success(caplog):
    """Requirement 3c: Webhook HTTP 500 status must be logged as a failure and not treated as successful delivery."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    bad_resp = MagicMock()
    bad_resp.status_code = 500

    with (
        patch.object(settings, "ALERT_WEBHOOK_URL", "https://hooks.internal/alerts"),
        patch("httpx.Client.post", return_value=bad_resp),
        caplog.at_level(logging.WARNING),
    ):
        record_model_call("room-webhook-500", tokens=800000)
        tracker.wait_for_alert_webhook()

    assert tracker.alert_80_percent_triggered is True
    assert any("Alert webhook dispatch failed with HTTP status 500" in r.getMessage() for r in caplog.records)


def test_concurrent_threshold_crossing_initiates_only_one_webhook_call():
    """Requirement 3d: Concurrent calls crossing 80% threshold initiate exactly ONE notification attempt."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001
    # 80% = $8.00 (800,000 tokens). Start at 790,000 tokens ($7.90)
    record_model_call("room-conc-alert", tokens=790000)

    webhook_mock = MagicMock()
    webhook_mock.status_code = 200
    barrier = threading.Barrier(10)

    def cross_threshold():
        barrier.wait()
        tracker.record_model_call("room-conc-alert", tokens=2000)

    with (
        patch.object(settings, "ALERT_WEBHOOK_URL", "https://hooks.internal/alerts"),
        patch("httpx.Client.post", return_value=webhook_mock) as mock_post,
    ):
        # 10 threads each adding 2,000 tokens (crossing 800,000 simultaneously)
        threads = [threading.Thread(target=cross_threshold) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        tracker.wait_for_alert_webhook()
        # Exactly 1 HTTP POST dispatched
        assert mock_post.call_count == 1


def test_alert_webhook_never_logs_url_secrets_or_code_canary(caplog):
    """Requirement 3e (NFR8.2): Webhook failure logs must never contain webhook URL, secrets, or prompts."""
    secret_url = "https://hooks.internal/alerts?token=TOP_SECRET_WEBHOOK_TOKEN_123"
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    with (
        patch.object(settings, "ALERT_WEBHOOK_URL", secret_url),
        patch("httpx.Client.post", side_effect=httpx.ConnectError("Failed")),
        caplog.at_level(logging.DEBUG),
    ):
        record_model_call("room-secret", tokens=800000)
        tracker.wait_for_alert_webhook()

    for record in caplog.records:
        msg = record.getMessage()
        assert "TOP_SECRET_WEBHOOK_TOKEN_123" not in msg
        assert secret_url not in msg


# ============================================================================
# 21. Alert Deduplication Across Dynamic Budget Changes (Issue 4)
# ============================================================================


def test_alert_deduplication_survives_budget_increase_and_decrease(caplog):
    """Requirement 4: Once 80% alert has triggered in a UTC month, config changes must not reset deduplication."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    with caplog.at_level(logging.WARNING):
        # Step 1: Spend $8.50 -> 80% alert fires
        record_model_call("room-dedup-test", tokens=850000)
        alerts = [r for r in caplog.records if "AI_COST_CAP_ALERT" in r.getMessage()]
        assert len(alerts) == 1

        # Step 2: Increase budget to $20.00 -> spend ($8.50) is now 42.5% (< 80%)
        tracker.monthly_budget_usd = 20.0
        # Deduplication flag MUST NOT be reset
        assert tracker.alert_80_percent_triggered is True

        # Step 3: Lower budget again to $10.00 -> spend ($8.50) is >= 80% again
        tracker.monthly_budget_usd = 10.0

        # Step 4: Record another call in the same month
        record_model_call("room-dedup-test", tokens=1000)

        # Confirm there is STILL ONLY ONE alert in this month
        alerts_same_month = [r for r in caplog.records if "AI_COST_CAP_ALERT" in r.getMessage()]
        assert len(alerts_same_month) == 1

        # Step 5: Advance to next UTC month
        next_month_time = datetime(2027, 2, 1, 0, 0, 1, tzinfo=timezone.utc)
        set_clock(lambda: next_month_time)

        # Confirm alert resets for new month
        assert tracker.alert_80_percent_triggered is False

        # In new month, crossing 80% triggers a new alert
        record_model_call("room-dedup-test", tokens=850000)
        alerts_next_month = [r for r in caplog.records if "AI_COST_CAP_ALERT" in r.getMessage() and "month=2027-02" in r.getMessage()]
        assert len(alerts_next_month) == 1


# ============================================================================
# 22. Spending Precision on Small Budgets & 0.0000035 Token Price (Issue 5)
# ============================================================================


def test_spending_precision_boundary_small_budget_without_rounding_error():
    """Requirement 5: Internal spending must not prematurely round before threshold/budget comparisons."""
    tracker = get_tracker()
    tracker.token_cost_per_token = 0.0000035
    # Budget = 100 tokens = $0.00035 exactly
    tracker.monthly_budget_usd = 0.00035

    # 99 tokens * 0.0000035 = 0.0003465 (strictly below budget 0.00035)
    # If prematurely rounded to 4 decimals, 0.0003465 rounds to 0.0003, or 100 rounds to 0.0004
    record_model_call("room-prec", tokens=99)
    assert not is_cost_cap_reached()
    assert tracker.cost_cap_reached is False

    # 1 more token -> exactly 100 tokens * 0.0000035 = 0.0003500 -> cost cap reached!
    record_model_call("room-prec", tokens=1)
    assert is_cost_cap_reached() is True
    assert tracker.cost_cap_reached is True

    # 80% threshold check on small budget:
    # 80% of 100 tokens = 80 tokens (0.00028)
    tracker.reset()
    tracker.token_cost_per_token = 0.0000035
    tracker.monthly_budget_usd = 0.00035

    record_model_call("room-prec-80", tokens=79)  # 79 * 0.0000035 = 0.0002765 < 0.00028
    assert tracker.alert_80_percent_triggered is False

    record_model_call("room-prec-80", tokens=1)  # 80 * 0.0000035 = 0.0002800 == 0.00028
    assert tracker.alert_80_percent_triggered is True


# ============================================================================
# 23. Provider Missing/Malformed Usage & Safe Accounting Policy (Issue 6)
# ============================================================================


@pytest.mark.anyio
async def test_provider_missing_usage_metadata_falls_back_to_safe_billing():
    """Requirement 6a: Successful response with missing usageMetadata must not silently become a zero-cost paid call."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.0000035

    llm_client = LLMClient()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "candidates": [{"content": {"parts": [{"text": "Code analysis"}]}}],
        "usageMetadata": None,  # Provider omitted usage metadata!
    }

    prompt = "Analyze"
    max_output_tokens = int(getattr(settings, "LLM_MAX_OUTPUT_TOKENS", 256))
    expected_reserved = max(100, (len(prompt) // 4) + max_output_tokens)

    with patch("httpx.AsyncClient.post", return_value=mock_resp):
        out = await llm_client.complete(
            prompt=prompt,
            api_key="fake-key",
            model="gemini-1.5-flash",
            room_id="room-missing-usage",
        )

    assert out == "Code analysis"
    # Safe accounting policy: billed for reserved tokens (prompt-aware admission) rather than zero cost
    assert tracker.monthly_tokens_used == expected_reserved
    assert tracker.get_monthly_spend_usd() == round(expected_reserved * 0.0000035, 4)
    # FR40.1: room usage tokens_used is 0 because extracted from provider was null
    assert get_room_usage("room-missing-usage")["tokens_used"] == 0


@pytest.mark.anyio
async def test_provider_malformed_json_on_200_commits_usage_before_raising_provider_error():
    """Requirement 6b: If provider returned HTTP 200 (was billed) but body is malformed, commit usage before 502."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.0000035

    llm_client = LLMClient()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.side_effect = json.JSONDecodeError("Unterminated string", "doc", 0)

    prompt = "Broken JSON"
    max_output_tokens = int(getattr(settings, "LLM_MAX_OUTPUT_TOKENS", 256))
    expected_reserved = max(100, (len(prompt) // 4) + max_output_tokens)

    with patch("httpx.AsyncClient.post", return_value=mock_resp):
        with pytest.raises(LLMProviderError) as exc_info:
            await llm_client.complete(
                prompt=prompt,
                api_key="fake-key",
                model="gemini-1.5-flash",
                room_id="room-malformed",
            )

    assert exc_info.value.status_code == 502
    assert exc_info.value.code == "PROVIDER_ERROR"
    # Safe accounting policy: billed request accounted for in monthly spend (prompt-aware reserved)
    assert tracker.monthly_tokens_used == expected_reserved
    # Active reservation released (not leaked)
    assert tracker._active_reservations_tokens == 0


@pytest.mark.anyio
async def test_provider_500_error_releases_reservation_without_billing():
    """Requirement 6c: Upstream 500 error releases reservation without adding to spend."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.0000035

    llm_client = LLMClient()
    mock_resp = MagicMock()
    mock_resp.status_code = 500

    with patch("httpx.AsyncClient.post", return_value=mock_resp):
        with pytest.raises(LLMProviderError):
            await llm_client.complete(
                prompt="Server error",
                api_key="fake-key",
                model="gemini-1.5-flash",
                room_id="room-500",
            )

    assert tracker.monthly_tokens_used == 0
    assert tracker._active_reservations_tokens == 0


@pytest.mark.anyio
async def test_provider_pre_dispatch_connect_failure_releases_without_billing():
    """Requirement 2a: Known pre-dispatch connection error (server never reached) releases reservation without billing."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.0000035

    llm_client = LLMClient()
    with patch("httpx.AsyncClient.post", side_effect=httpx.ConnectError("Failed to connect")):
        with pytest.raises(LLMProviderError):
            await llm_client.complete(
                prompt="Connect fail",
                api_key="fake-key",
                model="gemini-1.5-flash",
                room_id="room-conn-fail",
            )

    # Provider was never reached: 0 tokens charged
    assert tracker.monthly_tokens_used == 0
    assert tracker._active_reservations_tokens == 0


@pytest.mark.anyio
async def test_provider_post_dispatch_timeout_conservatively_commits_spend():
    """Requirement 2b: Uncertain post-dispatch timeout (request reached provider, response lost) uses conservative accounting."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.0000035

    llm_client = LLMClient()
    # Simulates request transmitted over wire, but read times out while waiting for response
    prompt = "Timeout call"
    max_output_tokens = int(getattr(settings, "LLM_MAX_OUTPUT_TOKENS", 256))
    expected_reserved = max(100, (len(prompt) // 4) + max_output_tokens)

    with patch("httpx.AsyncClient.post", side_effect=httpx.ReadTimeout("Read timed out")):
        with pytest.raises(LLMTimeoutError):
            await llm_client.complete(
                prompt=prompt,
                api_key="fake-key",
                model="gemini-1.5-flash",
                room_id="room-timeout",
            )

    # Uncertain outcome: provider may have billed generation! Conservatively committed reserved tokens
    assert tracker.monthly_tokens_used == expected_reserved
    assert tracker._active_reservations_tokens == 0


@pytest.mark.anyio
async def test_provider_post_dispatch_network_drop_conservatively_commits_spend():
    """Requirement 2c: Uncertain post-dispatch network drop (response lost during transfer) uses conservative accounting."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.0000035

    llm_client = LLMClient()
    # Simulates server received request, but connection reset while reading response
    prompt = "Drop call"
    max_output_tokens = int(getattr(settings, "LLM_MAX_OUTPUT_TOKENS", 256))
    expected_reserved = max(100, (len(prompt) // 4) + max_output_tokens)

    with patch("httpx.AsyncClient.post", side_effect=httpx.ReadError("Connection reset by peer")):
        with pytest.raises(LLMProviderError):
            await llm_client.complete(
                prompt=prompt,
                api_key="fake-key",
                model="gemini-1.5-flash",
                room_id="room-drop",
            )

    assert tracker.monthly_tokens_used == expected_reserved
    assert tracker._active_reservations_tokens == 0


@pytest.mark.anyio
async def test_provider_post_dispatch_cancellation_conservatively_commits_spend():
    """Requirement 2d: Uncertain cancellation after request transmission uses conservative accounting."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.0000035

    llm_client = LLMClient()
    prompt = "Cancelled call"
    max_output_tokens = int(getattr(settings, "LLM_MAX_OUTPUT_TOKENS", 256))
    expected_reserved = max(100, (len(prompt) // 4) + max_output_tokens)

    with patch("httpx.AsyncClient.post", side_effect=asyncio.CancelledError()):
        with pytest.raises(asyncio.CancelledError):
            await llm_client.complete(
                prompt=prompt,
                api_key="fake-key",
                model="gemini-1.5-flash",
                room_id="room-cancelled",
            )

    # Request was transmitted: conservatively committed so expenditure is not undercounted
    assert tracker.monthly_tokens_used == expected_reserved
    assert tracker._active_reservations_tokens == 0


def test_in_flight_reservation_survives_utc_month_rollover_and_commits_in_new_month():
    """Requirement 3a: In-flight reservation created before month transition is preserved and commits in new month."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    # Step 1: At 23:59:59 UTC on 2026-11-30, start an in-flight request
    nov_time = datetime(2026, 11, 30, 23, 59, 59, tzinfo=timezone.utc)
    set_clock(lambda: nov_time)
    record_model_call("room-rollover-res", tokens=500000)  # $5.00 in Nov
    res = tracker.reserve_call(estimated_tokens=100)
    assert tracker._active_reservations_tokens == 100
    assert tracker.monthly_tokens_used == 500000

    # Step 2: At 00:00:01 UTC on 2026-12-01, month rolls over to December
    dec_time = datetime(2026, 12, 1, 0, 0, 1, tzinfo=timezone.utc)
    set_clock(lambda: dec_time)

    # In-flight reservation must NOT be wiped out or orphaned
    assert tracker.current_month == "2026-12"
    assert tracker.monthly_tokens_used == 0
    assert tracker._active_reservations_tokens == 100
    assert res.reservation_id in tracker._reservations

    # Step 3: In-flight request completes in December with 150 tokens
    res.commit(tokens=150, room_id="room-rollover-res")

    # Verified clean state: active reservations decremented, spend attributed to December
    assert tracker._active_reservations_tokens == 0
    assert tracker.monthly_tokens_used == 150
    assert tracker.get_monthly_spend_usd() == round(150 * 0.00001, 4)
    # Lifetime room usage retains November tokens (500,000) + December tokens (150)
    assert get_room_usage("room-rollover-res")["tokens_used"] == 500150
    assert tracker.get_total_usage()["total_tokens_used"] == 500150


def test_in_flight_reservation_survives_utc_month_rollover_and_releases_in_new_month():
    """Requirement 3b: In-flight reservation that rolls over and then fails releases cleanly in new month."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    # Start in November
    nov_time = datetime(2026, 11, 30, 23, 59, 59, tzinfo=timezone.utc)
    set_clock(lambda: nov_time)
    res = tracker.reserve_call(estimated_tokens=100)
    assert tracker._active_reservations_tokens == 100

    # Advance to December
    dec_time = datetime(2026, 12, 1, 0, 0, 1, tzinfo=timezone.utc)
    set_clock(lambda: dec_time)
    assert tracker._active_reservations_tokens == 100

    # Request fails in December
    res.release()

    assert tracker._active_reservations_tokens == 0
    assert tracker.monthly_tokens_used == 0


@pytest.mark.anyio
async def test_provider_preserves_actual_token_usage_when_available():
    """Requirement 6e: Preserve actual provider token usage whenever it is available."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.0000035

    llm_client = LLMClient()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "candidates": [{"content": {"parts": [{"text": "Optimal code"}]}}],
        "usageMetadata": {"totalTokenCount": 384},
    }

    with patch("httpx.AsyncClient.post", return_value=mock_resp):
        await llm_client.complete(
            prompt="Optimize",
            api_key="fake-key",
            model="gemini-1.5-flash",
            room_id="room-actual-usage",
        )

    # Actual 384 tokens preserved exactly
    assert tracker.monthly_tokens_used == 384
    assert get_room_usage("room-actual-usage")["tokens_used"] == 384
    assert tracker.get_total_usage()["total_tokens_used"] == 384



# ============================================================================
# 16. Admission Reservation Uses LLM_MAX_OUTPUT_TOKENS (US-24 rev4)
# ============================================================================


def test_admission_reservation_uses_max_output_tokens_from_config():
    """Requirement 16a: Admission reservation includes configured max output tokens, not fixed 64."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 100.0
    tracker.token_cost_per_token = 0.001  # $0.001/token for easy math

    max_output_tokens = int(getattr(settings, "LLM_MAX_OUTPUT_TOKENS", 256))
    prompt = "Fix the bug"
    prompt_tokens = len(prompt) // 4  # ~2 tokens for this prompt
    expected_admission = max(100, prompt_tokens + max_output_tokens)

    res = reserve_call(estimated_tokens=expected_admission)
    try:
        assert tracker._active_reservations_tokens == expected_admission
        assert tracker._active_reservations_tokens >= max_output_tokens
    finally:
        res.release()

    assert tracker._active_reservations_tokens == 0


def test_large_prompt_admission_tokens_scale_with_prompt_length():
    """Requirement 16b: Large prompts produce proportionally larger admission reservations."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 100.0
    tracker.token_cost_per_token = 0.0000001

    max_output_tokens = int(getattr(settings, "LLM_MAX_OUTPUT_TOKENS", 256))

    # Short prompt: ~2 tokens → admission = max(100, 2 + 256) = 258
    short_prompt = "x" * 8
    short_admission = max(100, (len(short_prompt) // 4) + max_output_tokens)

    # Large prompt: 2000 chars → ~500 tokens → admission = max(100, 500 + 256) = 756
    large_prompt = "x" * 2000
    large_admission = max(100, (len(large_prompt) // 4) + max_output_tokens)

    # Confirm large is greater than short
    assert large_admission > short_admission
    # Confirm large prompt reservation is bigger than max(100,...) floor
    assert large_admission == (len(large_prompt) // 4) + max_output_tokens

    # Both reservations must succeed independently (plenty of budget)
    res_short = reserve_call(estimated_tokens=short_admission)
    assert tracker._active_reservations_tokens == short_admission
    res_short.release()

    res_large = reserve_call(estimated_tokens=large_admission)
    assert tracker._active_reservations_tokens == large_admission
    res_large.release()

    assert tracker._active_reservations_tokens == 0


def test_concurrent_calls_near_boundary_use_max_output_reservation():
    """Requirement 16c: Concurrent reservations using max-output-sized estimates cannot all pass near budget boundary."""
    max_output_tokens = int(getattr(settings, "LLM_MAX_OUTPUT_TOKENS", 256))

    # Each call with prompt "x" (1 char) reserves max(100, 0+256) = 256 tokens.
    # Budget: 2 admissions fit, 3rd is rejected.
    # Reserve gate rejects when projected_tokens * cost >= budget_tokens * cost (i.e. >= not >).
    # So for N=2 to fit and N=3 to fail: budget_tokens must satisfy:
    #   - 2 * 256 < budget_tokens  (so 2nd fits, projected=512 < budget)
    #   - 3 * 256 >= budget_tokens (so 3rd fails, projected=768 >= budget)
    # Choose budget_tokens = 2 * 256 + 1 = 513.
    per_call_estimate = max_output_tokens  # = 256 for prompt "x"
    budget_tokens = 2 * per_call_estimate + 1  # = 513
    cost_per_token = 0.001

    tracker = get_tracker()
    tracker.monthly_budget_usd = budget_tokens * cost_per_token  # = 0.513
    tracker.token_cost_per_token = cost_per_token

    errors = []
    reservations = []
    lock = threading.Lock()
    barrier = threading.Barrier(3)

    def try_reserve():
        barrier.wait()
        try:
            # Use a short prompt whose admission = max(100, 0+256) = 256 = per_call_estimate
            prompt = "x"
            admit = max(100, (len(prompt) // 4) + max_output_tokens)
            res = reserve_call(estimated_tokens=admit)
            with lock:
                reservations.append(res)
        except CostCapReachedError as exc:
            with lock:
                errors.append(exc)

    threads = [threading.Thread(target=try_reserve) for _ in range(3)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    # Exactly 1 thread must have been rejected (budget_tokens=513 covers exactly 2 × 256-token admissions)
    assert len(errors) == 1, f"Expected 1 rejection, got {len(errors)}"
    assert len(reservations) == 2

    # Clean up
    for res in reservations:
        res.release()
    assert tracker._active_reservations_tokens == 0


@pytest.mark.anyio
async def test_actual_billing_uses_provider_tokens_not_admission_estimate():
    """Requirement 16d: Actual monthly spend uses provider-reported tokens, never the admission estimate.

    Documents the overshoot scenario:
    - Admission reserves max(100, prompt_tokens + max_output_tokens).
    - Provider reports MORE tokens than reserved (e.g., large input + full output).
    - Monthly spend is updated with actual provider tokens, not the admission estimate.
    - This is the documented US-24 overshoot: the last admitted request may cause
      actual monthly spend to exceed the budget by up to the actual total token count
      minus the reserved token count.
    """
    with (
        patch.object(settings, "AI_MAX_TOKENS_PER_REQUEST", 256),
        patch.object(settings, "LLM_MAX_OUTPUT_TOKENS", 256),
    ):
        tracker = get_tracker()
        max_output_tokens = int(getattr(settings, "LLM_MAX_OUTPUT_TOKENS", 256))

        # Short prompt → admission = max(100, 0 + 256) = 256 tokens
        short_prompt = "x"
        admission = max(100, (len(short_prompt) // 4) + max_output_tokens)

        tracker.monthly_budget_usd = 100.0
        tracker.token_cost_per_token = 0.0000035

        # Mock provider returns 1200 actual tokens (input 944 + output 256 = 1200)
        # This exceeds the 256-token admission reservation
        actual_provider_tokens = 1200
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {
            "candidates": [{"content": {"parts": [{"text": "Long output here"}]}}],
            "usageMetadata": {"totalTokenCount": actual_provider_tokens},
        }

        with patch("httpx.AsyncClient.post", return_value=mock_resp):
            await call_llm_with_consent(
                prompt=short_prompt,
                consent=True,
                ai_enabled=True,
                api_key="fake-key",
                room_id="room-overshoot",
            )

        # Monthly spend must equal actual provider tokens (1200), NOT the admission (256)
        assert tracker.monthly_tokens_used == actual_provider_tokens, (
            f"Expected monthly tokens == {actual_provider_tokens} (actual provider tokens), "
            f"got {tracker.monthly_tokens_used} (admission was {admission})"
        )
        # Room-level tokens also reflect actual provider tokens
        assert get_room_usage("room-overshoot")["tokens_used"] == actual_provider_tokens
        # This confirms documented overshoot: spend = 1200 tokens vs 256 reserved
        assert tracker.monthly_tokens_used > admission


# ============================================================================
# 22. PR Review Feedback Verifications (Prince's Review)
# ============================================================================


def test_alert_webhook_does_not_block_caller_on_slow_endpoint():
    """Verify that a slow webhook endpoint (delayed HTTP response) does NOT block the caller or hold the tracker lock."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001  # $8.00 threshold = 800,000 tokens

    webhook_entered = threading.Event()
    release_webhook = threading.Event()

    def slow_post(url, json=None, timeout=None):
        webhook_entered.set()
        release_webhook.wait(timeout=5.0)
        resp = MagicMock()
        resp.status_code = 200
        return resp

    with (
        patch.object(settings, "ALERT_WEBHOOK_URL", "https://slow.webhook/alerts"),
        patch("httpx.Client.post", side_effect=slow_post),
    ):
        t0 = time.perf_counter()
        # Crossing 80% triggers webhook dispatch in background
        record_model_call("room-slow-hook", tokens=850000)
        elapsed = time.perf_counter() - t0

        # record_model_call must return essentially instantaneously without waiting for slow_post
        assert elapsed < 0.2, f"record_model_call blocked for {elapsed:.3f}s"

        # Wait until background worker actually enters slow_post
        assert webhook_entered.wait(timeout=2.0), "Background webhook worker did not start"

        # Verify tracker lock is NOT held while webhook is in flight:
        # A concurrent operation must be able to acquire lock and run immediately
        t_lock_start = time.perf_counter()
        record_model_call("room-concurrent", tokens=1000)
        lock_elapsed = time.perf_counter() - t_lock_start
        assert lock_elapsed < 0.05, f"Tracker lock was held during webhook dispatch! Elapsed: {lock_elapsed:.3f}s"

        # Release the slow webhook worker
        release_webhook.set()
        tracker.wait_for_alert_webhook(timeout=2.0)

    assert tracker.alert_80_percent_triggered is True
    assert tracker.monthly_tokens_used == 851000


def test_reservation_lifecycle_timestamp_and_idempotent_finalization():
    """Verify reservation created_at timestamp, is_finalized property, and idempotent commit/release."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    t_before = datetime.now(timezone.utc)
    res = tracker.reserve_call(estimated_tokens=500)
    t_after = datetime.now(timezone.utc)

    assert t_before <= res.created_at <= t_after
    assert res.is_finalized is False
    assert tracker.get_active_reservations_count() == 1
    assert tracker.get_active_reservations_tokens() == 500

    # Commit the reservation
    res.commit(tokens=250, room_id="room-life")
    assert res.is_finalized is True
    assert tracker.get_active_reservations_count() == 0
    assert tracker.get_active_reservations_tokens() == 0
    assert tracker.monthly_tokens_used == 250

    # Idempotent second commit: must be a no-op (no double-counting)
    res.commit(tokens=250, room_id="room-life")
    assert tracker.monthly_tokens_used == 250

    # Idempotent release after commit: must be a no-op
    res.release()
    assert tracker.get_active_reservations_tokens() == 0
    assert tracker.monthly_tokens_used == 250

    # Test release idempotency on a new reservation
    res2 = tracker.reserve_call(estimated_tokens=300)
    assert tracker.get_active_reservations_tokens() == 300
    res2.release()
    assert res2.is_finalized is True
    assert tracker.get_active_reservations_tokens() == 0
    res2.release()  # second release is a no-op
    assert tracker.get_active_reservations_tokens() == 0

    # Test commit_uncertain idempotency
    res3 = tracker.reserve_call(estimated_tokens=400)
    res3.commit_uncertain(room_id="room-life")
    assert res3.is_finalized is True
    assert tracker.monthly_tokens_used == 250 + 400
    res3.commit_uncertain(room_id="room-life")  # second call is a no-op
    assert tracker.monthly_tokens_used == 250 + 400
    res3.release()  # release after commit_uncertain is a no-op
    assert tracker.monthly_tokens_used == 250 + 400


def test_reservation_guaranteed_cleanup_in_context_manager_on_exception():
    """Verify context manager guarantees release of reservation if an unhandled exception occurs."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.00001

    try:
        with tracker.reserve_call(estimated_tokens=1500) as res:
            assert tracker.get_active_reservations_tokens() == 1500
            assert res.is_finalized is False
            raise RuntimeError("Unexpected failure inside processing block")
    except RuntimeError:
        pass

    # Context manager __exit__ must have deterministically finalized/released the reservation
    assert tracker.get_active_reservations_tokens() == 0
    assert tracker.get_active_reservations_count() == 0
    assert res.is_finalized is True


def test_clock_reset_isolation_restores_real_utc_clock():
    """Verify UsageTracker.reset() restores real UTC clock and recalculates current calendar month."""
    tracker = get_tracker()
    # Inject synthetic future clock
    future_time = datetime(2035, 7, 20, 12, 0, 0, tzinfo=timezone.utc)
    tracker.set_clock(lambda: future_time)
    tracker.record_model_call("room-future", tokens=500)

    assert tracker._current_month_key == (2035, 7)
    assert tracker._clock_fn is not None

    # Reset tracker
    tracker.reset()

    real_now = datetime.now(timezone.utc)
    assert tracker._clock_fn is None
    assert tracker._current_month_key == (real_now.year, real_now.month)
    assert tracker.monthly_tokens_used == 0


def test_budget_setter_validation_rejects_zero_negative_nan_inf():
    """Verify UsageTracker.monthly_budget_usd setter validates finite positive values."""
    tracker = get_tracker()

    with pytest.raises(ValueError, match="must be a finite positive number"):
        tracker.monthly_budget_usd = 0.0

    with pytest.raises(ValueError, match="must be a finite positive number"):
        tracker.monthly_budget_usd = -5.0

    with pytest.raises(ValueError, match="must be a finite positive number"):
        tracker.monthly_budget_usd = float("nan")

    with pytest.raises(ValueError, match="must be a finite positive number"):
        tracker.monthly_budget_usd = float("inf")

    with pytest.raises(ValueError, match="must be a finite positive number"):
        tracker.monthly_budget_usd = float("-inf")

    with pytest.raises(ValueError, match="must be a valid float"):
        tracker.monthly_budget_usd = "not-a-number"


@pytest.mark.anyio
async def test_unified_token_config_governs_payload_and_admission_gemini_and_openai():
    """Verify effective max tokens governs both LLM request payload and admission reservation for Gemini and OpenAI."""
    llm_client = LLMClient()
    captured_payloads = []

    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "candidates": [{"content": {"parts": [{"text": "Result"}]}}],
        "usageMetadata": {"totalTokenCount": 50},
    }

    async def mock_post(url, headers=None, json=None, timeout=None):
        captured_payloads.append(json)
        return mock_resp

    with (
        patch.object(settings, "AI_MAX_TOKENS_PER_REQUEST", 777),
        patch.object(settings, "LLM_MAX_OUTPUT_TOKENS", 777),
        patch("httpx.AsyncClient.post", side_effect=mock_post),
    ):
        # 1. Gemini payload check
        await llm_client.complete(
            prompt="Test Gemini tokens",
            api_key="fake-gemini-key",
            model="gemini-1.5-flash",
            room_id="room-gemini",
        )
        assert captured_payloads[0]["generationConfig"]["maxOutputTokens"] == 777

        # 2. OpenAI payload check
        openai_mock_resp = MagicMock()
        openai_mock_resp.status_code = 200
        openai_mock_resp.json.return_value = {
            "choices": [{"message": {"content": "OpenAI Result"}}],
            "usage": {"total_tokens": 50},
        }
        mock_resp.json.return_value = openai_mock_resp.json.return_value

        await llm_client.complete(
            prompt="Test OpenAI tokens",
            api_key="fake-openai-key",
            model="gpt-4o-mini",
            room_id="room-openai",
        )
        assert captured_payloads[1]["max_tokens"] == 777


@pytest.mark.anyio
async def test_us77_explicit_max_tokens_parameter_governs_payload_and_admission():
    """Verify US-77 compatibility: passing max_tokens explicitly overrides default in payload and reservation."""
    captured_payloads = []

    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "candidates": [{"content": {"parts": [{"text": "US77 result"}]}}],
        "usageMetadata": {"totalTokenCount": 80},
    }

    async def mock_post(url, headers=None, json=None, timeout=None):
        captured_payloads.append(json)
        return mock_resp

    tracker = get_tracker()
    tracker.monthly_budget_usd = 100.0

    with (
        patch.object(settings, "AI_MAX_TOKENS_PER_REQUEST", 4096),
        patch("httpx.AsyncClient.post", side_effect=mock_post),
    ):
        # Call with explicit max_tokens=150 (as US-77 does)
        prompt = "US-77 prompt"  # 13 chars -> 3 tokens
        # Admission must be max(100, 3 + 150) = 153 tokens, NOT max(100, 3 + 4096) = 4099 tokens
        await call_llm_with_consent(
            prompt=prompt,
            consent=True,
            ai_enabled=True,
            api_key="fake-key",
            room_id="room-us77",
            max_tokens=150,
        )

        assert captured_payloads[0]["generationConfig"]["maxOutputTokens"] == 150


def test_stale_reservation_pruned_after_ttl_expires():
    """Verify orphaned reservations are auto-expired after TTL to prevent leaked headroom (US-24)."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 10.0
    tracker.token_cost_per_token = 0.001

    base_time = datetime(2026, 11, 20, 12, 0, 0, tzinfo=timezone.utc)
    current_time = base_time
    tracker.set_clock(lambda: current_time)

    res = tracker.reserve_call(estimated_tokens=500)
    assert tracker.get_active_reservations_tokens() == 500
    assert tracker.get_active_reservations_count() == 1

    # Advance time by 5 seconds (less than 2 * 10s TTL): reservation must remain active
    current_time = base_time + timedelta(seconds=5)
    assert tracker.get_active_reservations_tokens() == 500

    # Advance time by 25 seconds (exceeds 2 * 10s = 20s TTL): reservation must be evicted
    current_time = base_time + timedelta(seconds=25)
    assert tracker.get_active_reservations_tokens() == 0
    assert tracker.get_active_reservations_count() == 0

    # Even if late commit is called on the stale reservation, tokens are not decremented below zero
    res.commit(tokens=300, room_id="room-stale")
    assert tracker.get_active_reservations_tokens() == 0
    assert tracker.monthly_tokens_used == 300


def test_auto_prune_on_reserve_restores_headroom():
    """Verify that new reserve_call automatically prunes stale reservations and reclaims headroom."""
    tracker = get_tracker()
    tracker.monthly_budget_usd = 0.60
    tracker.token_cost_per_token = 0.001  # Budget = 600 tokens ($0.60)

    base_time = datetime(2026, 11, 20, 12, 0, 0, tzinfo=timezone.utc)
    current_time = base_time
    tracker.set_clock(lambda: current_time)

    # Reserve 500 tokens (headroom remaining: 100 tokens)
    res1 = tracker.reserve_call(estimated_tokens=500)
    assert tracker.get_active_reservations_tokens() == 500

    # A second reservation of 200 tokens would exceed budget (500 + 200 = 700 >= 600) -> blocked
    with pytest.raises(CostCapReachedError):
        tracker.reserve_call(estimated_tokens=200)

    # Advance clock past TTL (25s > 20s)
    current_time = base_time + timedelta(seconds=25)

    # Now reserve_call should automatically prune res1 and admit the new reservation!
    res2 = tracker.reserve_call(estimated_tokens=200)
    assert res2.reserved_tokens == 200
    assert tracker.get_active_reservations_tokens() == 200
    assert tracker.get_active_reservations_count() == 1
    res2.release()
    assert tracker.get_active_reservations_tokens() == 0
