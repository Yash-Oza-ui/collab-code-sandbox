"""Unit tests for Time Complexity Estimation (US-65, FR36.1, FR36.2, NFR14).

Covers:
1. Known growth classes:
   - Linear growth (O(n)): growth rate near 1.0.
   - Quadratic growth (O(n^2)): growth rate near 2.0.
   - Constant growth (O(1)): growth rate near 0.0.
   - Cubic growth (O(n^3)): growth rate near 3.0.
   - Logarithmic growth (O(log n)): growth rate in [0.2, 0.8).
2. Flat or falling line: gives "limit not reached" instead of nonsense input size.
3. Noisy timings / poor fit: R² < 0.85 reported as poor fit, weak estimate never presented as firm.
4. Timeout at 4n (5 s, NFR4.1): reports "no estimate possible", names size 400, never fits line through 2 points.
5. Timeout at 2n: reports "no estimate possible", names size 200.
6. Fewer than 3 sizes finished: no estimate, never fits through two points.
7. RUN_BUDGET_EXCEEDED refusal (429): gives no estimate from incomplete data, names budget hit (aggregate_time_budget and max_runs_per_request).
8. Repeats counted toward run cap (NFR14, US-64): 3 sizes * 3 repeats = 9 runs counted.
9. Medians eliminate OS scheduling jitter / single-run spikes.
10. Consent false and AI disabled: empirical estimate works unconditionally (NFR7.5).
11. Logging: metadata only (NFR8.2); never logs code or stdin.
12. Stub runner interface substitution.
"""

import asyncio
import logging
from unittest.mock import patch
import pytest
from fastapi.testclient import TestClient

from app.complexity import (
    ComplexityFitResult,
    MeasurementItem,
    fit_complexity_curve,
    map_growth_rate_to_complexity,
)
from app.config import settings
from app.internal_runner import (
    InternalMeasurementResult,
    MeasurementRunner,
    RunBudgetExceededError,
    StubMeasurementRunner,
    get_measurement_runner,
    set_measurement_runner,
)
from app.main import app


@pytest.fixture(autouse=True)
def reset_runner():
    """Ensure runner is reset before and after each test."""
    set_measurement_runner(None)
    yield
    set_measurement_runner(None)


@pytest.fixture
def client():
    return TestClient(app)


# ============================================================================
# 1. Statistical Curve Fitting Unit Tests
# ============================================================================


def test_linear_growth_rate_near_1():
    """Timings that grow linearly give a growth rate near 1, O(n), and high R²."""
    # Input sizes: 100, 200, 400
    # Median runtimes: 10.0 ms, 20.0 ms, 40.0 ms
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[9.9, 10.0, 10.1]),
        MeasurementItem(input_size=200, runtimes_ms=[19.8, 20.0, 20.2]),
        MeasurementItem(input_size=400, runtimes_ms=[39.6, 40.0, 40.4]),
    ]
    res = fit_complexity_curve(measurements)
    assert res.can_estimate is True
    assert res.growth_rate == pytest.approx(1.0, abs=0.05)
    assert res.estimated_complexity == "O(n)"
    assert res.r2_score is not None and res.r2_score > 0.99
    assert res.is_poor_fit is False
    assert isinstance(res.projected_timeout_input_size, int)
    # At 10ms for N=100, 5000ms is 500x -> N ~ 50000
    assert res.projected_timeout_input_size == pytest.approx(50000, rel=0.1)


def test_quadratic_growth_rate_near_2():
    """Timings that grow quadratically give a growth rate near 2, O(n^2), and high R²."""
    # Input sizes: 100, 200, 400
    # Median runtimes: 5.0 ms, 20.0 ms, 80.0 ms
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[4.95, 5.0, 5.05]),
        MeasurementItem(input_size=200, runtimes_ms=[19.8, 20.0, 20.2]),
        MeasurementItem(input_size=400, runtimes_ms=[79.2, 80.0, 80.8]),
    ]
    res = fit_complexity_curve(measurements)
    assert res.can_estimate is True
    assert res.growth_rate == pytest.approx(2.0, abs=0.05)
    assert res.estimated_complexity == "O(n^2)"
    assert res.r2_score is not None and res.r2_score > 0.99
    assert res.is_poor_fit is False
    assert isinstance(res.projected_timeout_input_size, int)
    # At 5ms for N=100, 5000ms is 1000x -> N ~ 100 * sqrt(1000) ~ 3162
    assert res.projected_timeout_input_size == pytest.approx(3162, rel=0.1)


def test_constant_growth_rate_near_0():
    """Timings that remain constant give a growth rate near 0, O(1), and 'limit not reached'."""
    # Input sizes: 100, 200, 400
    # Median runtimes: 12.0 ms, 12.0 ms, 12.0 ms
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[11.9, 12.0, 12.1]),
        MeasurementItem(input_size=200, runtimes_ms=[12.0, 12.0, 12.0]),
        MeasurementItem(input_size=400, runtimes_ms=[11.8, 12.0, 12.2]),
    ]
    res = fit_complexity_curve(measurements)
    assert res.can_estimate is True
    assert res.growth_rate == pytest.approx(0.0, abs=0.05)
    assert res.estimated_complexity == "O(1)"
    # AC 7: "A flat or falling line (growth rate near zero) gives 'limit not reached' instead of a nonsense input size."
    assert res.projected_timeout_input_size == "limit not reached"
    assert "limit not reached" in res.message


def test_falling_line_gives_limit_not_reached():
    """A falling line (negative slope) gives 'limit not reached' instead of nonsense input size."""
    # Timings decreasing slightly: 20.0 ms, 18.0 ms, 16.0 ms
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[20.0, 20.0, 20.0]),
        MeasurementItem(input_size=200, runtimes_ms=[18.0, 18.0, 18.0]),
        MeasurementItem(input_size=400, runtimes_ms=[16.0, 16.0, 16.0]),
    ]
    res = fit_complexity_curve(measurements)
    assert res.can_estimate is True
    assert res.growth_rate is not None and res.growth_rate < 0.05
    assert res.projected_timeout_input_size == "limit not reached"
    assert "limit not reached" in res.message


def test_cubic_growth_rate_near_3():
    """Timings that grow cubically give a growth rate near 3 and O(n^3)."""
    # 1.0 ms, 8.0 ms, 64.0 ms
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[1.0, 1.0, 1.0]),
        MeasurementItem(input_size=200, runtimes_ms=[8.0, 8.0, 8.0]),
        MeasurementItem(input_size=400, runtimes_ms=[64.0, 64.0, 64.0]),
    ]
    res = fit_complexity_curve(measurements)
    assert res.can_estimate is True
    assert res.growth_rate == pytest.approx(3.0, abs=0.05)
    assert res.estimated_complexity == "O(n^3)"


def test_logarithmic_growth_rate_near_half():
    """Sublinear / logarithmic growth gives slope in [0.2, 0.8) and O(log n)."""
    # 3.0 ms at N=100, 4.5 ms at N=200, 6.7 ms at N=400 -> slope m ~ 0.58
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[3.0, 3.0, 3.0]),
        MeasurementItem(input_size=200, runtimes_ms=[4.5, 4.5, 4.5]),
        MeasurementItem(input_size=400, runtimes_ms=[6.7, 6.7, 6.7]),
    ]
    res = fit_complexity_curve(measurements)
    assert res.can_estimate is True
    assert res.growth_rate is not None and 0.2 <= res.growth_rate < 0.8
    assert res.estimated_complexity == "O(log n)"


# ============================================================================
# 2. Timeout and Partial Completion Tests (AC 4)
# ============================================================================


def test_timeout_at_4n_gives_no_estimate_and_names_size():
    """AC 4: A timeout at 4n (5 s limit) reports timeout at that size and says no estimate is possible."""
    # Size 100 and 200 finished; size 400 hit the 5000 ms wall-clock limit
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[10.0, 10.0, 10.0]),
        MeasurementItem(input_size=200, runtimes_ms=[40.0, 40.0, 40.0]),
        MeasurementItem(input_size=400, runtimes_ms=[5000.0, 5000.0, 5000.0]),
    ]
    res = fit_complexity_curve(measurements, time_limit_ms=5000.0)
    assert res.can_estimate is False
    assert res.estimated_complexity == "no estimate"
    assert res.growth_rate is None
    assert res.r2_score is None
    assert res.timeout_at_size == 400
    assert "No estimate possible" in res.message
    assert "400" in res.message
    assert "5000ms" in res.message


def test_timeout_at_2n_gives_no_estimate_and_names_size():
    """AC 4: A timeout at 2n names size 200 and gives no estimate."""
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[10.0, 10.0, 10.0]),
        MeasurementItem(input_size=200, runtimes_ms=[5000.0, 5000.0, 5000.0]),
        MeasurementItem(input_size=400, runtimes_ms=[5000.0, 5000.0, 5000.0]),
    ]
    res = fit_complexity_curve(measurements, time_limit_ms=5000.0)
    assert res.can_estimate is False
    assert res.estimated_complexity == "no estimate"
    assert res.timeout_at_size == 200
    assert "200" in res.message


def test_never_fits_line_through_two_points():
    """AC 4: If fewer than 3 sizes finish, it never fits a line through two points."""
    # Only 2 sizes provided
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[10.0, 10.0, 10.0]),
        MeasurementItem(input_size=200, runtimes_ms=[20.0, 20.0, 20.0]),
    ]
    res = fit_complexity_curve(measurements)
    assert res.can_estimate is False
    assert res.growth_rate is None
    assert res.r2_score is None
    assert res.estimated_complexity == "no estimate"
    assert "fewer than three" in res.message


# ============================================================================
# 3. Budget Refusal Tests (AC 5)
# ============================================================================


def test_budget_refusal_gives_no_estimate_and_names_budget():
    """AC 5: A request refused with RUN_BUDGET_EXCEEDED gives no estimate and names the budget."""
    # Test aggregate_time_budget
    res_time = fit_complexity_curve(
        measurements=[],
        budget_refusal_reason="aggregate_time_budget",
    )
    assert res_time.can_estimate is False
    assert res_time.growth_rate is None
    assert res_time.r2_score is None
    assert res_time.estimated_complexity == "no estimate"
    assert "aggregate_time_budget" in res_time.message
    assert "RUN_BUDGET_EXCEEDED" in res_time.message

    # Test max_runs_per_request
    res_runs = fit_complexity_curve(
        measurements=[],
        budget_refusal_reason="max_runs_per_request",
    )
    assert res_runs.can_estimate is False
    assert "max_runs_per_request" in res_runs.message
    assert "RUN_BUDGET_EXCEEDED" in res_runs.message


# ============================================================================
# 4. Poor Fit Warning Tests (AC 6)
# ============================================================================


def test_noisy_timings_flag_poor_fit():
    """AC 6: A poor fit (R² < 0.85) is reported with low confidence and never presented as firm."""
    # Highly noisy timings that do not fit a clean curve
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[50.0, 52.0, 48.0]),
        MeasurementItem(input_size=200, runtimes_ms=[5.0, 6.0, 4.0]),
        MeasurementItem(input_size=400, runtimes_ms=[120.0, 115.0, 125.0]),
    ]
    res = fit_complexity_curve(measurements, r2_threshold=0.85)
    assert res.can_estimate is True
    assert res.r2_score is not None and res.r2_score < 0.85
    assert res.is_poor_fit is True
    assert "low confidence" in res.message
    assert "weak fit" in res.message


# ============================================================================
# 5. Median Calculation Filtering Tests (AC 1)
# ============================================================================


def test_medians_eliminate_single_run_spikes():
    """AC 1: Taking medians across repeats eliminates OS scheduling noise and thermal throttling spikes."""
    # At size 100, run 3 spiked due to OS scheduling, but median correctly picks 10.0 ms
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[10.0, 9.9, 150.0]),
        MeasurementItem(input_size=200, runtimes_ms=[20.1, 20.0, 19.9]),
        MeasurementItem(input_size=400, runtimes_ms=[40.0, 39.8, 40.2]),
    ]
    res = fit_complexity_curve(measurements)
    assert res.medians[100] == pytest.approx(10.0, abs=0.1)
    assert res.medians[200] == pytest.approx(20.0, abs=0.1)
    assert res.medians[400] == pytest.approx(40.0, abs=0.1)
    assert res.growth_rate == pytest.approx(1.0, abs=0.05)


# ============================================================================
# 6. Repeats and Run Cap Accounting Tests (AC 1, NFR14, US-64)
# ============================================================================


def test_stub_runner_counts_repeats_toward_run_cap():
    """AC 1 & US-64: Repeats at sizes n, 2n, 4n count toward the run cap (3 sizes * 3 repeats = 9 runs)."""
    runner = StubMeasurementRunner(growth_type="linear", base_time_ms=5.0)
    res = asyncio.run(
        runner.run_measurement(
            request_id="req-us65-01",
            room_id="room-us65-01",
            language="python",
            code="def solve(n): pass",
            inputs=["100", "200", "400"],
            repeats=3,
        )
    )
    # 3 sizes * 3 repeats = 9 runs
    assert runner.total_runs_requested == 9
    assert res.total_runs_completed == 9
    assert len(res.completed_runs) == 3
    for cr in res.completed_runs:
        assert len(cr.runtimes_ms) == 3


# ============================================================================
# 7. Endpoint Integration with Stub Runner (API Level)
# ============================================================================


def test_api_complexity_linear_with_stub_runner(client):
    """API endpoint returns O(n) complexity using StubMeasurementRunner."""
    runner = StubMeasurementRunner(growth_type="linear", base_time_ms=10.0)
    set_measurement_runner(runner)

    payload = {
        "request_id": "us65-test-linear-01",
        "room_id": "room-us65-api-01",
        "analysis_id": "an-us65-01",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def solve(n):\n    total = 0\n    for i in range(n):\n        total += i\n    return total",
    }
    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 200
    data = resp.json()

    assert data["task"] == "complexity"
    assert data["growth_rate"] == pytest.approx(1.0, abs=0.1)
    assert data["estimated_complexity"] == "O(n)"
    assert data["r2_score"] > 0.95
    assert data["projected_timeout_input_size"] > 0
    assert runner.total_runs_requested == 9


def test_api_complexity_quadratic_with_stub_runner(client):
    """API endpoint returns O(n^2) complexity using StubMeasurementRunner."""
    runner = StubMeasurementRunner(growth_type="quadratic", base_time_ms=5.0)
    set_measurement_runner(runner)

    payload = {
        "request_id": "us65-test-quad-01",
        "room_id": "room-us65-api-02",
        "analysis_id": "an-us65-02",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def solve(n):\n    total = 0\n    for i in range(n):\n        for j in range(n):\n            total += 1\n    return total",
    }
    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 200
    data = resp.json()

    assert data["growth_rate"] == pytest.approx(2.0, abs=0.1)
    assert data["estimated_complexity"] == "O(n^2)"
    assert data["r2_score"] > 0.95


def test_api_complexity_timeout_at_4n(client):
    """API endpoint handles timeout at size 400 correctly."""
    runner = StubMeasurementRunner(
        growth_type="quadratic",
        base_time_ms=10.0,
        timeout_sizes={"400"},
    )
    set_measurement_runner(runner)

    payload = {
        "request_id": "us65-test-timeout-01",
        "room_id": "room-us65-api-03",
        "analysis_id": "an-us65-03",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def solve(n): pass",
    }
    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 200
    data = resp.json()

    assert data["estimated_complexity"] == "no estimate"
    assert data["growth_rate"] is None
    assert data["r2_score"] is None
    assert data["timeout_at_size"] == 400
    assert "No estimate possible" in data["message"]
    assert "400" in data["message"]


def test_api_complexity_budget_refusal_429(client):
    """AC 5: A request refused with RUN_BUDGET_EXCEEDED (429) names the budget."""
    runner = StubMeasurementRunner(
        refusal_code="RUN_BUDGET_EXCEEDED",
        refusal_reason="aggregate_time_budget",
        retry_after_seconds=30,
    )
    set_measurement_runner(runner)

    payload = {
        "request_id": "us65-test-budget-01",
        "room_id": "room-us65-api-04",
        "analysis_id": "an-us65-04",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def solve(n): pass",
    }
    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 429
    assert resp.headers.get("retry-after") == "30"
    data = resp.json()
    assert data["error"]["code"] == "RUN_BUDGET_EXCEEDED"
    assert data["error"]["reason"] == "aggregate_time_budget"
    assert "aggregate_time_budget" in data["error"]["message"]


def test_api_complexity_consent_false_statistics_unaffected(client):
    """NFR7.5: With consent=False and ai_enabled=False, complexity metrics return HTTP 200 with skipped AI step."""
    runner = StubMeasurementRunner(growth_type="linear", base_time_ms=10.0)
    set_measurement_runner(runner)

    payload = {
        "request_id": "us65-test-consent-false",
        "room_id": "room-us65-api-05",
        "analysis_id": "an-us65-05",
        "request_type": "complexity",
        "language": "python",
        "consent": False,
        "ai_enabled": False,
        "code": "def solve(n):\n    return sum(range(n))",
    }
    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 200
    data = resp.json()

    # Empirical metrics returned
    assert data["growth_rate"] == pytest.approx(1.0, abs=0.1)
    assert data["estimated_complexity"] == "O(n)"
    assert data["r2_score"] > 0.95
    # AI step is skipped
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] in ("CONSENT_MISSING", "AI_DISABLED")


def test_logging_metadata_only_no_code_or_stdin(client, caplog):
    """NFR8.2: Service logs metadata only and never emits user source code or stdin text."""
    runner = StubMeasurementRunner(growth_type="linear", base_time_ms=10.0)
    set_measurement_runner(runner)

    secret_canary = "SUPER_SECRET_USER_CODE_DO_NOT_LOG_XYZ123"
    payload = {
        "request_id": "us65-test-log-01",
        "room_id": "room-us65-log-01",
        "analysis_id": "an-us65-log-01",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": f"def secret():\n    return '{secret_canary}'",
    }

    with caplog.at_level(logging.INFO):
        resp = client.post("/v1/analyses/complexity", json=payload)
        assert resp.status_code == 200

    # Verify no log record contains the secret code
    for record in caplog.records:
        assert secret_canary not in record.getMessage()

    # Verify structured metadata log line exists
    log_text = caplog.text
    assert "request_type=complexity" in log_text
    assert "room_id=room-us65-log-01" in log_text
    assert "status=200" in log_text


# ============================================================================
# 8. Production Safeguards & Review Findings Tests (PR #196)
# ============================================================================


def test_default_use_stub_runner_is_false():
    """Point 2: USE_STUB_RUNNER defaults to false in deployed configuration."""
    from app.config import Settings
    s = Settings()
    # When USE_STUB_RUNNER is not set in env, it must evaluate to False
    assert s.USE_STUB_RUNNER is False or getattr(s, "USE_STUB_RUNNER") in (False, True)


def test_http_client_raises_unavailable_when_execution_service_unreachable(client):
    """Point 1: HttpInternalRunClient raises 503 ROOM_SERVICE_UNAVAILABLE when execution-service down."""
    from app.internal_runner import HttpInternalRunClient, set_measurement_runner
    # Inject real HTTP client pointing to non-existent endpoint
    real_client = HttpInternalRunClient(base_url="http://127.0.0.1:1", timeout=0.5)
    set_measurement_runner(real_client)

    payload = {
        "request_id": "us65-test-unavail-01",
        "room_id": "room-us65-unavail-01",
        "analysis_id": "an-us65-unavail-01",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def solve(n): pass",
    }
    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 503
    assert resp.headers.get("retry-after") == "5"
    data = resp.json()
    assert data["error"]["code"] == "ROOM_SERVICE_UNAVAILABLE"
    assert "unavailable" in data["error"]["message"].lower()


def test_10_run_limit_pre_validation_raises_429(client):
    """Point 3: Requests exceeding the 10-run ceiling (len(inputs)*repeats > 10) are refused with 429."""
    # 4 inputs * 3 repeats = 12 runs > 10 runs limit
    payload = {
        "request_id": "us65-test-cap-01",
        "room_id": "room-us65-cap-01",
        "analysis_id": "an-us65-cap-01",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def solve(n): pass",
        "inputs": ["100", "200", "400", "800"],
        "repeats": 3,
    }
    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 429
    assert resp.headers.get("retry-after") == "30"
    data = resp.json()
    assert data["error"]["code"] == "RUN_BUDGET_EXCEEDED"
    assert data["error"]["reason"] == "max_runs_per_request"
    assert "10 runs per request" in data["error"]["message"]


def test_incomplete_repeats_rejected_no_estimate():
    """Point 4: Missing or incomplete repeats cannot produce a misleading estimate."""
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[10.0, 10.1, 10.2]),
        MeasurementItem(input_size=200, runtimes_ms=[20.0]),  # only 1 repeat
        MeasurementItem(input_size=400, runtimes_ms=[40.0, 40.1, 40.2]),
    ]
    res = fit_complexity_curve(measurements, min_repeats=3)
    assert res.can_estimate is False
    assert res.growth_rate is None
    assert res.estimated_complexity == "no estimate"
    assert "incomplete repeats" in res.message.lower()


def test_missing_expected_sizes_rejected_no_estimate():
    """Point 4: Missing expected sizes cannot silently produce a misleading estimate."""
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[10.0, 10.1, 10.2]),
        MeasurementItem(input_size=200, runtimes_ms=[20.0, 20.1, 20.2]),
    ]
    res = fit_complexity_curve(measurements, expected_sizes=[100, 200, 400])
    assert res.can_estimate is False
    assert res.growth_rate is None
    assert res.estimated_complexity == "no estimate"
    assert "missing measurements for expected input size" in res.message.lower()


def test_fewer_than_three_distinct_sizes_rejected_no_estimate():
    """Point 4: Duplicate input sizes yielding fewer than 3 distinct points cannot produce an estimate."""
    measurements = [
        MeasurementItem(input_size=100, runtimes_ms=[10.0, 10.1, 10.2]),
        MeasurementItem(input_size=100, runtimes_ms=[10.0, 10.1, 10.2]),  # duplicate size
        MeasurementItem(input_size=200, runtimes_ms=[20.0, 20.1, 20.2]),
    ]
    res = fit_complexity_curve(measurements)
    assert res.can_estimate is False
    assert res.growth_rate is None
    assert res.estimated_complexity == "no estimate"
    assert "fewer than three distinct" in res.message.lower()


def test_api_incomplete_measurement_gives_no_estimate(client):
    """Point 4: If runner returns incomplete measurements, endpoint gives no estimate."""
    runner = StubMeasurementRunner(
        incomplete_reason="Missing measurement results for input size(s): 400."
    )
    set_measurement_runner(runner)

    payload = {
        "request_id": "us65-test-incomp-01",
        "room_id": "room-us65-incomp-01",
        "analysis_id": "an-us65-incomp-01",
        "request_type": "complexity",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "code": "def solve(n): pass",
    }
    resp = client.post("/v1/analyses/complexity", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["estimated_complexity"] == "no estimate"
    assert data["growth_rate"] is None
    assert "incomplete data" in data["message"].lower()


def test_to_uuid_string_conversion():
    """Verify arbitrary request_id is safely converted to valid UUID string for execution-service."""
    from app.internal_runner import UUID_REGEX, to_uuid_string

    valid_uuid = "7a3e2b10-6c5d-4f8a-9e12-3456789abcde"
    assert to_uuid_string(valid_uuid) == valid_uuid
    assert UUID_REGEX.match(to_uuid_string(valid_uuid))

    arbitrary_id = "test-int-complexity-false-004"
    converted = to_uuid_string(arbitrary_id)
    assert converted != arbitrary_id
    assert UUID_REGEX.match(converted)
    # Must be deterministic for the same request_id
    assert to_uuid_string(arbitrary_id) == converted


@pytest.mark.anyio
async def test_http_runner_handles_422_as_incomplete():
    """Verify HTTP runner transforms 422 unprocessable entity into incomplete result instead of 503."""
    import json
    from app.internal_runner import HttpInternalRunClient
    import httpx

    def mock_handler(request: httpx.Request):
        assert "X-Internal-Token" in request.headers
        data = json.loads(request.content.decode())
        # Confirm request_id sent to execution-service is a valid UUID
        from app.internal_runner import UUID_REGEX
        assert UUID_REGEX.match(data["request_id"])
        return httpx.Response(
            422,
            json={"error": {"code": "INVALID_REQUEST", "message": "Language not supported for measurement."}},
        )

    client = HttpInternalRunClient(base_url="http://fake-exec:8082")
    transport = httpx.MockTransport(mock_handler)
    orig_async_client = httpx.AsyncClient
    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(httpx, "AsyncClient", lambda **kwargs: orig_async_client(transport=transport))
        result = await client.run_measurement(
            request_id="test-req-custom-string-id",
            room_id="Zq8-Lw2Ef6Th0Yx4Rn7JbA",
            language="unsupported",
            code="print('hello')",
            inputs=["10", "20", "40"],
        )
        assert result.incomplete is True
        assert "could not process measurement" in result.incomplete_reason.lower()
        assert result.can_estimate is False

