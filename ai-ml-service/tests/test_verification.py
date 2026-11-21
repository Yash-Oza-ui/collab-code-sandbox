"""Unit tests for optimization fix sandbox verification (US-66, FR36.3, Task 1).

Covers all acceptance criteria:
1. Candidate fix evaluated under identical conditions (same inputs, same sizes, same repeats, same runner).
2. Fix verified only if measured runtime is lower and beats noise margin (default 5.0%).
3. Both measured runtimes are reported (no claim without numbers).
4. Failure handling:
   - Fix compile error or crash: unverified with reason.
   - Fix timeout (5 s, NFR4.1): unverified with reason naming the size.
   - Fix RUN_BUDGET_EXCEEDED (429, US-64): unverified naming the budget hit.
   - Output mismatch: unverified with reason naming the input.
5. Privacy and Consent (FR12, FR35, NFR7.5):
   - External LLM called 0 times when consent=False or ai_enabled=False.
   - Verifying an already provided fix requires no AI call and no consent.
"""

from unittest.mock import AsyncMock, patch

import pytest
from fastapi.testclient import TestClient

from app.complexity import MeasurementItem
from app.config import settings
from app.internal_runner import (
    MeasurementRunItem,
    StubMeasurementRunner,
    set_measurement_runner,
)
from app.main import app
from app.verification import compare_runtimes, verify_suggested_fix

client = TestClient(app)


def test_compare_runtimes_noise_margin():
    """Verify compare_runtimes logic against configured noise margin (5.0%)."""
    # 1. Clear improvement: 100ms -> 50ms (50% speedup > 5.0%)
    verified, speedup, reason = compare_runtimes(100.0, 50.0, noise_margin_pct=5.0)
    assert verified is True
    assert speedup == 50.0
    assert "Verified improvement: 50.0% speedup" in reason

    # 2. Inside noise margin: 100ms -> 97ms (3% speedup <= 5.0%)
    verified, speedup, reason = compare_runtimes(100.0, 97.0, noise_margin_pct=5.0)
    assert verified is False
    assert speedup is None
    assert "within the 5.0% noise margin" in reason

    # 3. Exactly at margin: 100ms -> 95ms (5% speedup <= 5.0%)
    verified, speedup, reason = compare_runtimes(100.0, 95.0, noise_margin_pct=5.0)
    assert verified is False
    assert speedup is None
    assert "within the 5.0% noise margin" in reason

    # 4. Slower fix: 100ms -> 120ms (-20% slowdown)
    verified, speedup, reason = compare_runtimes(100.0, 120.0, noise_margin_pct=5.0)
    assert verified is False
    assert speedup is None
    assert "fix is slower than original (20.0% slowdown)" in reason

    # 5. Zero original runtime edge case
    verified, speedup, reason = compare_runtimes(0.0, 10.0, noise_margin_pct=5.0)
    assert verified is False
    assert speedup is None
    assert "Original runtime was 0 ms" in reason


@pytest.mark.anyio
async def test_verify_suggested_fix_faster():
    """AC 1, AC 2, AC 4: Faster fix is verified and reports both measured numbers."""
    runner = StubMeasurementRunner(
        code_overrides={
            "def solve_fast": {
                "growth_type": "linear",
                "base_time_ms": 0.5,
            }
        }
    )

    orig_measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[2.0, 2.0, 2.0]),
        MeasurementItem(input_size=200, runtimes_ms=[8.0, 8.0, 8.0]),
        MeasurementItem(input_size=400, runtimes_ms=[32.0, 32.0, 32.0]),
    ]

    res = await verify_suggested_fix(
        runner=runner,
        request_id="test-req-1",
        room_id="room-1",
        language="python",
        original_code="def solve(n): pass",
        suggested_fix="def solve_fast(n): pass",
        inputs=["100", "200", "400"],
        repeats=3,
        original_measurements=orig_measurements,
    )

    assert res.verified is True
    assert res.original_runtime_ms == 32.0
    assert res.patch_runtime_ms == pytest.approx(2.0, abs=0.2)
    assert res.verified_speedup_pct is not None
    assert res.verified_speedup_pct > 90.0
    assert "Verified improvement" in (res.reason or "")


@pytest.mark.anyio
async def test_verify_suggested_fix_slower():
    """AC 2, AC 4: Slower fix is not verified but still reports both measured numbers."""
    runner = StubMeasurementRunner(
        code_overrides={
            "def solve_slow": {
                "growth_type": "quadratic",
                "base_time_ms": 5.0,
            }
        }
    )

    orig_measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[1.0, 1.0, 1.0]),
        MeasurementItem(input_size=200, runtimes_ms=[4.0, 4.0, 4.0]),
        MeasurementItem(input_size=400, runtimes_ms=[16.0, 16.0, 16.0]),
    ]

    res = await verify_suggested_fix(
        runner=runner,
        request_id="test-req-2",
        room_id="room-1",
        language="python",
        original_code="def solve(n): pass",
        suggested_fix="def solve_slow(n): pass",
        inputs=["100", "200", "400"],
        repeats=3,
        original_measurements=orig_measurements,
    )

    assert res.verified is False
    assert res.verified_speedup_pct is None
    assert res.original_runtime_ms == 16.0
    assert res.patch_runtime_ms == pytest.approx(80.0, abs=5.0)
    assert "fix is slower than original" in (res.reason or "")


@pytest.mark.anyio
async def test_verify_suggested_fix_noise_margin():
    """AC 3: Fix within noise margin is not verified."""
    runner = StubMeasurementRunner(
        code_overrides={
            "def solve_marginal": {
                "predefined_runs": [
                    {"input": "100", "runtimes_ms": [10.0, 10.0, 10.0]},
                    {"input": "200", "runtimes_ms": [20.0, 20.0, 20.0]},
                    {"input": "400", "runtimes_ms": [39.0, 39.0, 39.0]},
                ]
            }
        }
    )

    orig_measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[10.0, 10.0, 10.0]),
        MeasurementItem(input_size=200, runtimes_ms=[20.0, 20.0, 20.0]),
        MeasurementItem(input_size=400, runtimes_ms=[40.0, 40.0, 40.0]),
    ]

    res = await verify_suggested_fix(
        runner=runner,
        request_id="test-req-3",
        room_id="room-1",
        language="python",
        original_code="def solve(n): pass",
        suggested_fix="def solve_marginal(n): pass",
        inputs=["100", "200", "400"],
        repeats=3,
        original_measurements=orig_measurements,
        noise_margin_pct=5.0,
    )

    # 40.0 -> 39.0 is 2.5% speedup <= 5.0% margin
    assert res.verified is False
    assert res.verified_speedup_pct is None
    assert res.original_runtime_ms == 40.0
    assert res.patch_runtime_ms == 39.0
    assert "within the 5.0% noise margin" in (res.reason or "")


@pytest.mark.anyio
async def test_verify_suggested_fix_compile_error():
    """AC 5: Fix failing compilation/crash is not verified with explicit reason."""
    runner = StubMeasurementRunner(
        code_overrides={
            "def broken_fix": {
                "failed": True,
                "failure_reason": "SyntaxError: unexpected EOF while parsing",
            }
        }
    )

    orig_measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[1.0, 1.0, 1.0]),
        MeasurementItem(input_size=200, runtimes_ms=[2.0, 2.0, 2.0]),
        MeasurementItem(input_size=400, runtimes_ms=[4.0, 4.0, 4.0]),
    ]

    res = await verify_suggested_fix(
        runner=runner,
        request_id="test-req-4",
        room_id="room-1",
        language="python",
        original_code="def solve(n): pass",
        suggested_fix="def broken_fix(n): pass",
        inputs=["100", "200", "400"],
        repeats=3,
        original_measurements=orig_measurements,
    )

    assert res.verified is False
    assert res.verified_speedup_pct is None
    assert res.original_runtime_ms == 4.0
    assert res.patch_runtime_ms is None
    assert "Fix failed to compile or crashed" in (res.reason or "")
    assert "SyntaxError" in (res.reason or "")


@pytest.mark.anyio
async def test_verify_suggested_fix_timeout():
    """AC 5: Fix timing out (5 s) is not verified with explicit timeout size."""
    runner = StubMeasurementRunner(
        code_overrides={
            "def timeout_fix": {
                "timeout_sizes": {"400"},
            }
        }
    )

    orig_measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[1.0, 1.0, 1.0]),
        MeasurementItem(input_size=200, runtimes_ms=[2.0, 2.0, 2.0]),
        MeasurementItem(input_size=400, runtimes_ms=[4.0, 4.0, 4.0]),
    ]

    res = await verify_suggested_fix(
        runner=runner,
        request_id="test-req-5",
        room_id="room-1",
        language="python",
        original_code="def solve(n): pass",
        suggested_fix="def timeout_fix(n): pass",
        inputs=["100", "200", "400"],
        repeats=3,
        original_measurements=orig_measurements,
    )

    assert res.verified is False
    assert res.verified_speedup_pct is None
    assert res.original_runtime_ms == 4.0
    assert res.patch_runtime_ms is None
    assert "Fix execution timed out at input size 400" in (res.reason or "")


@pytest.mark.anyio
async def test_verify_suggested_fix_budget_refusal():
    """AC 6: 429 RUN_BUDGET_EXCEEDED leaves fix unverified and reports which budget was hit."""
    runner = StubMeasurementRunner(
        code_overrides={
            "def budget_fix": {
                "refusal_code": "RUN_BUDGET_EXCEEDED",
                "refusal_reason": "aggregate_time_budget",
            }
        }
    )

    orig_measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[1.0, 1.0, 1.0]),
        MeasurementItem(input_size=200, runtimes_ms=[2.0, 2.0, 2.0]),
        MeasurementItem(input_size=400, runtimes_ms=[4.0, 4.0, 4.0]),
    ]

    res = await verify_suggested_fix(
        runner=runner,
        request_id="test-req-6",
        room_id="room-1",
        language="python",
        original_code="def solve(n): pass",
        suggested_fix="def budget_fix(n): pass",
        inputs=["100", "200", "400"],
        repeats=3,
        original_measurements=orig_measurements,
    )

    assert res.verified is False
    assert res.verified_speedup_pct is None
    assert res.original_runtime_ms == 4.0
    assert res.patch_runtime_ms is None
    assert "repeated-run execution budget exceeded (aggregate_time_budget)" in (res.reason or "")


@pytest.mark.anyio
async def test_verify_suggested_fix_output_mismatch():
    """Technical Constraint: Faster program with different output is not verified."""
    runner = StubMeasurementRunner(
        code_overrides={
            "def mismatch_fix": {
                "predefined_runs": [
                    {"input": "100", "runtimes_ms": [0.5, 0.5, 0.5], "output": "wrong_ans_100"},
                    {"input": "200", "runtimes_ms": [1.0, 1.0, 1.0], "output": "wrong_ans_200"},
                    {"input": "400", "runtimes_ms": [2.0, 2.0, 2.0], "output": "wrong_ans_400"},
                ]
            }
        }
    )

    orig_measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[2.0, 2.0, 2.0]),
        MeasurementItem(input_size=200, runtimes_ms=[8.0, 8.0, 8.0]),
        MeasurementItem(input_size=400, runtimes_ms=[32.0, 32.0, 32.0]),
    ]
    orig_runs = [
        MeasurementRunItem(input="100", runtimes_ms=[2.0, 2.0, 2.0], output="correct_ans_100"),
        MeasurementRunItem(input="200", runtimes_ms=[8.0, 8.0, 8.0], output="correct_ans_200"),
        MeasurementRunItem(input="400", runtimes_ms=[32.0, 32.0, 32.0], output="correct_ans_400"),
    ]

    res = await verify_suggested_fix(
        runner=runner,
        request_id="test-req-7",
        room_id="room-1",
        language="python",
        original_code="def solve(n): pass",
        suggested_fix="def mismatch_fix(n): pass",
        inputs=["100", "200", "400"],
        repeats=3,
        original_measurements=orig_measurements,
        original_runs=orig_runs,
    )

    assert res.verified is False
    assert res.verified_speedup_pct is None
    assert res.original_runtime_ms == 32.0
    assert res.patch_runtime_ms == 2.0
    assert "Output mismatch on input '100'" in (res.reason or "")


def test_endpoint_complexity_verified_fix_via_runner():
    """Full HTTP test: POST /v1/analyses/complexity with suggested_fix verified in runner."""
    stub = StubMeasurementRunner(
        growth_type="quadratic",
        base_time_ms=1.0,
        code_overrides={
            "def optimized_solution": {
                "growth_type": "linear",
                "base_time_ms": 0.5,
            }
        },
    )
    set_measurement_runner(stub)

    payload = {
        "request_id": "8a4f3b21-6c5d-4e9a-0f12-3456789abcde",
        "room_id": "test-room-verify",
        "analysis_id": "9b5f4c32-7d6e-4f0b-1a23-456789abcdef",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def solve(n):\n    total = 0\n    for i in range(n):\n        for j in range(n):\n            total += 1\n    return total",
        "suggested_fix": "def optimized_solution(n):\n    return n * n",
    }

    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 200
    data = resp.json()

    assert data["estimated_complexity"] == "O(n^2)"
    ai_step = data["ai_step"]
    assert ai_step["verified"] is True
    assert ai_step["verified_speedup_pct"] is not None
    assert ai_step["verified_speedup_pct"] > 80.0
    assert ai_step["original_runtime_ms"] is not None
    assert ai_step["patch_runtime_ms"] is not None
    assert ai_step["original_runtime_ms"] > ai_step["patch_runtime_ms"]
    assert "Verified improvement" in ai_step["verification_reason"]


def test_endpoint_complexity_consent_off_skips_ai_step_and_returns_ai_free_parts():
    """AC 7: Consent off skips AI step (CONSENT_MISSING) and returns AI-free parts intact (0 LLM calls)."""
    stub = StubMeasurementRunner(
        growth_type="quadratic",
        base_time_ms=1.0,
    )
    set_measurement_runner(stub)

    payload = {
        "request_id": "9a5f4b32-7c6d-4e0a-1f23-456789abcdef",
        "room_id": "test-room-consent-off",
        "analysis_id": "0b6f5c43-8d7e-4a1c-2b34-456789abcdef",
        "request_type": "complexity",
        "language": "python",
        "consent": False,
        "ai_enabled": False,
        "code": "def solve(n): pass",
    }

    mock_llm_complete = AsyncMock(return_value="AI suggestion")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp = client.post("/v1/analyses/complexity", json=payload)
        assert resp.status_code == 200
        # Consent is false so external LLM client is called 0 times
        assert mock_llm_complete.call_count == 0

    data = resp.json()
    ai_step = data["ai_step"]
    # AI generation step was skipped with CONSENT_MISSING
    assert ai_step["status"] == "skipped"
    assert ai_step["error_code"] == "CONSENT_MISSING"
    assert ai_step["suggested_fix"] is None
    assert ai_step["verified"] is False
    assert ai_step["verified_speedup_pct"] is None
    # AI-free statistical complexity metrics return normally
    assert data["estimated_complexity"] == "O(n^2)"
    assert data["growth_rate"] is not None


def test_endpoint_complexity_slower_fix_shows_numbers_without_label():
    """AC 2, AC 4: Slower fix is shown without verified label, with measured numbers."""
    stub = StubMeasurementRunner(
        growth_type="linear",
        base_time_ms=1.0,
        code_overrides={
            "def slower_fix": {
                "growth_type": "quadratic",
                "base_time_ms": 2.0,
            }
        },
    )
    set_measurement_runner(stub)

    payload = {
        "request_id": "aa6f5b43-8c7d-4f1b-2a34-456789abcdef",
        "room_id": "test-room-slower",
        "analysis_id": "ba7f6c54-9d8e-4b2d-3c45-456789abcdef",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def solve(n): pass",
        "suggested_patch": "def slower_fix(n): pass",
    }

    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 200
    data = resp.json()

    ai_step = data["ai_step"]
    assert ai_step["verified"] is False
    assert ai_step["verified_speedup_pct"] is None
    assert ai_step["original_runtime_ms"] is not None
    assert ai_step["patch_runtime_ms"] is not None
    assert ai_step["patch_runtime_ms"] > ai_step["original_runtime_ms"]
    assert "fix is slower than original" in ai_step["verification_reason"]


def test_endpoint_complexity_fix_budget_refusal_surfaces_budget():
    """AC 6: Refusal with RUN_BUDGET_EXCEEDED leaves fix unverified and names the budget."""
    stub = StubMeasurementRunner(
        growth_type="linear",
        base_time_ms=1.0,
        code_overrides={
            "def budget_blowout": {
                "refusal_code": "RUN_BUDGET_EXCEEDED",
                "refusal_reason": "aggregate_time_budget",
            }
        },
    )
    set_measurement_runner(stub)

    payload = {
        "request_id": "ba7f6b54-9c8d-4a2c-3b45-456789abcdef",
        "room_id": "test-room-budget",
        "analysis_id": "cb8f7c65-0d9e-4c3e-4d56-456789abcdef",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def solve(n): pass",
        "suggested_patch": "def budget_blowout(n): pass",
    }

    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 200
    data = resp.json()

    # Original complexity analysis succeeds
    assert data["estimated_complexity"] == "O(n)"
    ai_step = data["ai_step"]
    assert ai_step["verified"] is False
    assert ai_step["verified_speedup_pct"] is None
    assert ai_step["patch_runtime_ms"] is None
    assert "repeated-run execution budget exceeded (aggregate_time_budget)" in ai_step["verification_reason"]


def test_endpoint_complexity_candidate_fix_verified_with_consent_false_and_zero_llm_calls():
    """AC 7 / Issue #185 Regression: Candidate fix is verified even when consent is false (0 LLM calls)."""
    stub = StubMeasurementRunner(
        growth_type="quadratic",
        base_time_ms=2.0,
        code_overrides={
            "def fast_patch": {
                "growth_type": "linear",
                "base_time_ms": 0.5,
            }
        },
    )
    set_measurement_runner(stub)

    payload = {
        "request_id": "ca8f7b65-0c9d-4e3f-4a56-456789abcdef",
        "room_id": "test-room-consent-verify",
        "analysis_id": "db9f8c76-1e0f-4d4a-5b67-456789abcdef",
        "request_type": "complexity",
        "language": "python",
        "consent": False,
        "ai_enabled": False,
        "code": "def solve(n): pass",
        "suggested_patch": "def fast_patch(n): pass",
    }

    mock_llm_complete = AsyncMock(return_value="Suggested AI fix.")
    with patch("app.llm.client.LLMClient.complete", mock_llm_complete):
        resp = client.post("/v1/analyses/complexity", json=payload)
        assert resp.status_code == 200
        # Consent is false so external LLM client is called 0 times
        assert mock_llm_complete.call_count == 0

    data = resp.json()
    ai_step = data["ai_step"]
    # AI generation step was skipped with CONSENT_MISSING (no LLM fix generated)
    assert ai_step["status"] == "skipped"
    assert ai_step["error_code"] == "CONSENT_MISSING"
    assert ai_step["suggested_fix"] is None

    # But sandbox verification of already-supplied candidate fix succeeds without consent!
    assert ai_step["verified"] is True
    assert ai_step["verified_speedup_pct"] is not None
    assert ai_step["verified_speedup_pct"] > 80.0
    assert ai_step["original_runtime_ms"] is not None
    assert ai_step["patch_runtime_ms"] is not None
    assert ai_step["original_runtime_ms"] > ai_step["patch_runtime_ms"]
    assert "Verified improvement" in ai_step["verification_reason"]


@pytest.mark.anyio
async def test_verify_suggested_fix_precomputed_timings_cannot_bypass_sandbox_runner():
    """Reviewer Issue 2 Regression: Untrusted caller timings cannot bypass sandbox execution or award verified: true."""
    # Runner simulates that the candidate fix is actually much SLOWER (quadratic 10.0ms vs original linear 1.0ms)
    runner = StubMeasurementRunner(
        growth_type="linear",
        base_time_ms=1.0,
        code_overrides={
            "def slow_cheat": {
                "growth_type": "quadratic",
                "base_time_ms": 10.0,
                "output": "ans_100",
            }
        },
    )

    orig_measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[1.0, 1.0, 1.0]),
        MeasurementItem(input_size=200, runtimes_ms=[2.0, 2.0, 2.0]),
        MeasurementItem(input_size=400, runtimes_ms=[4.0, 4.0, 4.0]),
    ]
    orig_runs = [
        MeasurementRunItem(input="100", runtimes_ms=[1.0, 1.0, 1.0], output="ans_100"),
        MeasurementRunItem(input="200", runtimes_ms=[2.0, 2.0, 2.0], output="ans_100"),
        MeasurementRunItem(input="400", runtimes_ms=[4.0, 4.0, 4.0], output="ans_100"),
    ]

    # Caller passes suggested_patch_runtimes_ms asserting a 90% speedup (0.4ms)
    res = await verify_suggested_fix(
        runner=runner,
        request_id="test-req-precompute-cheat",
        room_id="room-cheat",
        language="python",
        original_code="def solve(n): pass",
        suggested_fix="def slow_cheat(n): pass",
        inputs=["100", "200", "400"],
        repeats=3,
        original_measurements=orig_measurements,
        original_runs=orig_runs,
        suggested_patch_runtimes_ms=[0.4, 0.4, 0.4],
    )

    # Must NOT award verified: true based on caller's asserted timings!
    # Actual sandbox measurement (160.0ms) must be used.
    assert res.verified is False
    assert res.verified_speedup_pct is None
    assert res.original_runtime_ms == 4.0
    assert res.patch_runtime_ms == pytest.approx(160.0, abs=10.0)
    assert "fix is slower than original" in (res.reason or "")


@pytest.mark.anyio
async def test_verify_suggested_fix_missing_outputs_fail_closed_under_real_contract():
    """Reviewer Issue 3 Regression: Missing output on runner contract fails closed (verified: false)."""
    # Simulate legacy or incomplete contract where output was not captured (output=None)
    runner = StubMeasurementRunner(
        default_output=None,
        code_overrides={
            "def faster_fix": {
                "growth_type": "linear",
                "base_time_ms": 0.5,
                "output": None,
            }
        },
    )

    orig_measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[1.0, 1.0, 1.0]),
        MeasurementItem(input_size=200, runtimes_ms=[4.0, 4.0, 4.0]),
        MeasurementItem(input_size=400, runtimes_ms=[16.0, 16.0, 16.0]),
    ]
    orig_runs = [
        MeasurementRunItem(input="100", runtimes_ms=[1.0, 1.0, 1.0], output=None),
        MeasurementRunItem(input="200", runtimes_ms=[4.0, 4.0, 4.0], output=None),
        MeasurementRunItem(input="400", runtimes_ms=[16.0, 16.0, 16.0], output=None),
    ]

    res = await verify_suggested_fix(
        runner=runner,
        request_id="test-req-missing-output",
        room_id="room-output",
        language="python",
        original_code="def solve(n): pass",
        suggested_fix="def faster_fix(n): pass",
        inputs=["100", "200", "400"],
        repeats=3,
        original_measurements=orig_measurements,
        original_runs=orig_runs,
    )

    # Must fail closed: verified: false with error_code OUTPUT_NOT_CAPTURED
    assert res.verified is False
    assert res.verified_speedup_pct is None
    assert res.error_code == "OUTPUT_NOT_CAPTURED"
    assert "output was not captured" in (res.reason or "")


@pytest.mark.anyio
async def test_verify_suggested_fix_real_contract_matching_output_verifies():
    """Reviewer Issue 3 Regression: Contract with captured matching outputs verifies improvement."""
    runner = StubMeasurementRunner(
        default_output="expected_output_100\n",
        code_overrides={
            "def faster_correct_fix": {
                "growth_type": "linear",
                "base_time_ms": 0.5,
                "output": "expected_output_100\n",
            }
        },
    )

    orig_measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[1.0, 1.0, 1.0]),
        MeasurementItem(input_size=200, runtimes_ms=[4.0, 4.0, 4.0]),
        MeasurementItem(input_size=400, runtimes_ms=[16.0, 16.0, 16.0]),
    ]
    orig_runs = [
        MeasurementRunItem(input="100", runtimes_ms=[1.0, 1.0, 1.0], output="expected_output_100\n"),
        MeasurementRunItem(input="200", runtimes_ms=[4.0, 4.0, 4.0], output="expected_output_100\n"),
        MeasurementRunItem(input="400", runtimes_ms=[16.0, 16.0, 16.0], output="expected_output_100\n"),
    ]

    res = await verify_suggested_fix(
        runner=runner,
        request_id="test-req-matching-output",
        room_id="room-output",
        language="python",
        original_code="def solve(n): pass",
        suggested_fix="def faster_correct_fix(n): pass",
        inputs=["100", "200", "400"],
        repeats=3,
        original_measurements=orig_measurements,
        original_runs=orig_runs,
    )

    # Output matches and fix is faster -> verified: true
    assert res.verified is True
    assert res.verified_speedup_pct is not None
    assert res.verified_speedup_pct > 80.0
    assert "Verified improvement" in (res.reason or "")

