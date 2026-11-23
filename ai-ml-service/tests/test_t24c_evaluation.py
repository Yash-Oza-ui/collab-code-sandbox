"""Comprehensive Evaluation Test Suite for Sprint 5 T-24c (Tasks 4 & 5).

Covers:
- Task 4 (US-73, US-74): Sanitizer crash parsing matrix, plain-language explanation,
  relevant-code isolation, canary tests, and caching behavior.
- Task 5 (US-75, US-24, US-78): AI usage counters, multi-room aggregation, monthly cost cap,
  atomic reservation concurrency, fallbacks (consent missing, cost cap reached, service unavailable,
  AI disabled), and multi-room concurrent load evaluation.

All model clients are strictly mocked (zero paid LLM calls).
All code and traffic is synthetic and isolated.
"""

import asyncio
from concurrent.futures import ThreadPoolExecutor
import logging
import threading
import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.cache import clear_analysis_cache, get_analysis_cache
from app.config import settings
from app.counters import (
    CallReservation,
    CostCapReachedError,
    clear_room,
    get_monthly_spend,
    get_room_usage,
    get_total_usage,
    get_tracker,
    is_cost_cap_reached,
    record_cache_hit,
    record_fallback,
    record_model_call,
    reset_counters,
    set_clock,
    wait_for_alert_webhook,
)
from app.llm.client import LLMClient
from app.llm.explainer import split_sentences
from app.main import app
from app.sanitizer import (
    extract_sanitizer_error_type,
    extract_sanitizer_line_number,
    parse_sanitizer_report,
)

client = TestClient(app, raise_server_exceptions=False)


@pytest.fixture(autouse=True)
def isolate_tracker_and_cache():
    """Ensure clean counter, budget configuration, clock, and cache state before and after every test (test isolation)."""
    tracker = get_tracker()
    orig_budget_override = tracker._monthly_budget_override
    orig_token_cost_override = tracker._token_cost_override
    orig_clock = tracker._clock_fn
    reset_counters()
    set_clock(None)
    get_analysis_cache().clear()
    try:
        yield
    finally:
        wait_for_alert_webhook(timeout=1.0)
        reset_counters()
        with tracker._lock:
            tracker._monthly_budget_override = orig_budget_override
            tracker._token_cost_override = orig_token_cost_override
            tracker._clock_fn = orig_clock
        get_analysis_cache().clear()


# ============================================================================
# Task 4 — Sanitizer Crash Parsing Matrix (US-73)
# ============================================================================

SANITIZER_FIXTURES = [
    {
        "id": "SAN-01",
        "category": "AddressSanitizer",
        "type": "heap-buffer-overflow",
        "lang": "cpp",
        "expected_type": "heap-buffer-overflow",
        "expected_line": 5,
        "raw_report": (
            "==1001==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000014 at pc 0x000000401233 bp 0x7ffd0123 sp 0x7ffd0120\n"
            "READ of size 4 at 0x602000000014 thread T0\n"
            "    #0 0x401233 in main /workspace/solution.cpp:5\n"
        ),
    },
    {
        "id": "SAN-02",
        "category": "AddressSanitizer",
        "type": "stack-buffer-overflow",
        "lang": "c",
        "expected_type": "stack-buffer-overflow",
        "expected_line": 6,
        "raw_report": (
            "==1002==ERROR: AddressSanitizer: stack-buffer-overflow on address 0x7ffe12345678 at pc 0x0000004011aa bp 0x7ffe1234 sp 0x7ffe1230\n"
            "WRITE of size 4 at 0x7ffe12345678 thread T0\n"
            "    #0 0x4011aa in main /workspace/solution.c:6\n"
        ),
    },
    {
        "id": "SAN-03",
        "category": "AddressSanitizer",
        "type": "global-buffer-overflow",
        "lang": "cpp",
        "expected_type": "global-buffer-overflow",
        "expected_line": 4,
        "raw_report": (
            "==1003==ERROR: AddressSanitizer: global-buffer-overflow on address 0x0000004040a4 at pc 0x000000401150 bp 0x7fff1122 sp 0x7fff1120\n"
            "READ of size 4 at 0x0000004040a4 thread T0\n"
            "    #0 0x401150 in main /workspace/solution.cpp:4\n"
        ),
    },
    {
        "id": "SAN-04",
        "category": "AddressSanitizer",
        "type": "heap-use-after-free",
        "lang": "cpp",
        "expected_type": "heap-use-after-free",
        "expected_line": 6,
        "raw_report": (
            "==1004==ERROR: AddressSanitizer: heap-use-after-free on address 0x602000000010 at pc 0x000000401222 bp 0x7fff3344 sp 0x7fff3340\n"
            "READ of size 4 at 0x602000000010 thread T0\n"
            "    #0 0x401222 in main /workspace/solution.cpp:6\n"
        ),
    },
    {
        "id": "SAN-05",
        "category": "AddressSanitizer",
        "type": "double-free",
        "lang": "c",
        "expected_type": "double-free",
        "expected_line": 6,
        "raw_report": (
            "==1005==ERROR: AddressSanitizer: attempting double-free on 0x602000000010 in thread T0:\n"
            "    #0 0x7f8812 in free (/usr/lib/x86_64-linux-gnu/libasan.so.6+0xb2123)\n"
            "    #1 0x401350 in main /workspace/solution.c:6\n"
        ),
    },
    {
        "id": "SAN-06",
        "category": "AddressSanitizer",
        "type": "stack-use-after-return",
        "lang": "cpp",
        "expected_type": "stack-use-after-return",
        "expected_line": 7,
        "raw_report": (
            "==1006==ERROR: AddressSanitizer: stack-use-after-return on address 0x7ffe12345678 at pc 0x0000004011aa\n"
            "READ of size 4 at 0x7ffe12345678 thread T0\n"
            "    #0 0x4011aa in main /workspace/solution.cpp:7\n"
        ),
    },
    {
        "id": "SAN-07",
        "category": "AddressSanitizer",
        "type": "stack-use-after-scope",
        "lang": "cpp",
        "expected_type": "stack-use-after-scope",
        "expected_line": 7,
        "raw_report": (
            "==1007==ERROR: AddressSanitizer: stack-use-after-scope on address 0x7ffe12345678 at pc 0x0000004011aa\n"
            "READ of size 4 at 0x7ffe12345678 thread T0\n"
            "    #0 0x4011aa in main /workspace/solution.cpp:7\n"
        ),
    },
    {
        "id": "SAN-08",
        "category": "AddressSanitizer",
        "type": "initialization-order-fiasco",
        "lang": "cpp",
        "expected_type": "initialization-order-fiasco",
        "expected_line": 12,
        "raw_report": (
            "==1008==ERROR: AddressSanitizer: initialization-order-fiasco on address 0x0000004040a4\n"
            "READ of size 4 at 0x0000004040a4 thread T0\n"
            "    #0 0x401150 in init_global /workspace/solution.cpp:12\n"
        ),
    },
    {
        "id": "SAN-09",
        "category": "UndefinedBehaviorSanitizer",
        "type": "signed-integer-overflow",
        "lang": "c",
        "expected_type": "signed-integer-overflow",
        "expected_line": 5,
        "raw_report": "/sandbox/code/solution.c:5:15: runtime error: signed integer overflow: 2147483647 + 1 cannot be represented in type 'int'\n",
    },
    {
        "id": "SAN-10",
        "category": "UndefinedBehaviorSanitizer",
        "type": "shift-out-of-bounds",
        "lang": "c",
        "expected_type": "shift-out-of-bounds",
        "expected_line": 4,
        "raw_report": "/sandbox/code/solution.c:4:15: runtime error: shift exponent 32 is too large for 32-bit type 'int'\n",
    },
    {
        "id": "SAN-11",
        "category": "UndefinedBehaviorSanitizer",
        "type": "index-out-of-bounds",
        "lang": "cpp",
        "expected_type": "index-out-of-bounds",
        "expected_line": 3,
        "raw_report": "/sandbox/code/solution.cpp:3:12: runtime error: index 10 out of bounds for type 'int[5]'\n",
    },
    {
        "id": "SAN-12",
        "category": "UndefinedBehaviorSanitizer",
        "type": "null-pointer-dereference",
        "lang": "c",
        "expected_type": "null-pointer-dereference",
        "expected_line": 3,
        "raw_report": "/sandbox/code/solution.c:3:12: runtime error: null pointer dereference\n",
    },
    {
        "id": "SAN-13",
        "category": "UndefinedBehaviorSanitizer",
        "type": "division-by-zero",
        "lang": "c",
        "expected_type": "division-by-zero",
        "expected_line": 3,
        "raw_report": "/sandbox/code/solution.c:3:16: runtime error: division by zero\n",
    },
    {
        "id": "SAN-14",
        "category": "CleanExecution",
        "type": "none",
        "lang": "cpp",
        "expected_type": None,
        "expected_line": None,
        "raw_report": "Execution completed successfully.\nAll tests passed.\n",
    },
    {
        "id": "SAN-15",
        "category": "NonSanitizerCrash",
        "type": "sigsegv-ordinary",
        "lang": "c",
        "expected_type": None,
        "expected_line": None,
        "raw_report": "Segmentation fault (core dumped)\n",
    },
]


@pytest.mark.parametrize("f", SANITIZER_FIXTURES, ids=[f["id"] for f in SANITIZER_FIXTURES])
def test_sanitizer_matrix_parsing(f):
    """Verify deterministic parsing for every supported sanitizer crash category (US-73)."""
    exit_code = 139 if f["id"] == "SAN-15" else (0 if f["id"] == "SAN-14" else 1)
    report = parse_sanitizer_report(f["raw_report"], exit_code=exit_code)

    assert report.error_type == f["expected_type"], f"Expected {f['expected_type']}, got {report.error_type}"
    assert report.line_number == f["expected_line"], f"Expected line {f['expected_line']}, got {report.line_number}"

    if f["expected_type"]:
        assert f["expected_type"] in report.summary
        if f["expected_line"]:
            assert str(f["expected_line"]) in report.summary
    elif f["id"] == "SAN-15":
        assert "no sanitizer finding" in report.summary
    elif f["id"] == "SAN-14":
        assert report.summary == ""


def test_sanitizer_unsupported_language_rejection():
    """Verify non-C/C++ language is rejected with HTTP 422 UNSUPPORTED_LANGUAGE (DR5)."""
    payload = {
        "request_id": "req-san-unsupported-lang",
        "room_id": "room-san-unsupported",
        "analysis_id": "ana-san-unsupported",
        "request_type": "crash_explanation",
        "language": "python",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": SANITIZER_FIXTURES[0]["raw_report"],
        "code_snippet": "print('hello')",
    }
    resp = client.post("/v1/analyses/crash-explanation", json=payload)
    assert resp.status_code == 422
    data = resp.json()
    assert data["error"]["code"] == "UNSUPPORTED_LANGUAGE"


# ============================================================================
# Task 4 — Plain-Language Crash Explanation (US-74)
# ============================================================================

def test_crash_explanation_identification_and_limits():
    """Verify explanation pipeline identifies crash, programmatically truncates verbose output

    to <= 3 sentences (FR13.1), and provides fix.
    """
    room_id = "room-eval-exp-01"
    clear_room(room_id)

    # 5-sentence mock output: sentences 4 and 5 MUST be pruned away by enforce_sentence_limit
    mock_llm_response = (
        "Explanation: Sentence one describes a heap-buffer-overflow at line 5 due to an out-of-bounds read. "
        "Sentence two details vector allocation boundaries. "
        "Sentence three outlines the risk of memory corruption. "
        "Sentence four is verbose filler that must be trimmed. "
        "Sentence five must never appear in the output.\n"
        "Suggested Fix: Verify index bounds before accessing: if (index < v.size()) std::cout << v[index];"
    )

    with patch("app.llm.client.LLMClient.complete", new=AsyncMock(return_value=mock_llm_response)):
        payload = {
            "request_id": "req-exp-01",
            "room_id": room_id,
            "analysis_id": "ana-exp-01",
            "request_type": "crash_explanation",
            "language": "cpp",
            "consent": True,
            "ai_enabled": True,
            "sanitizer_output": SANITIZER_FIXTURES[0]["raw_report"],
            "code_snippet": "std::vector<int> v = {1, 2, 3};\nstd::cout << v[5] << std::endl;\n",
        }
        resp = client.post("/v1/analyses/crash-explanation", json=payload)
        assert resp.status_code == 200
        data = resp.json()

        assert data["task"] == "crash_explanation"
        assert data["error_type"] == "heap-buffer-overflow"
        assert data["line_number"] == 5
        assert data["summary"] == "Detected heap-buffer-overflow at line 5."

        ai_step = data["ai_step"]
        assert ai_step["status"] == "ok"
        assert ai_step["error_code"] is None

        explanation = ai_step["explanation"]
        assert "heap-buffer-overflow" in explanation
        assert "line 5" in explanation

        # 3-sentence limit check (FR13.1): Sentences 4 and 5 MUST have been truncated
        sentences = split_sentences(explanation)
        assert len(sentences) == 3, f"Expected exactly 3 sentences after truncation, got {len(sentences)}"
        assert "Sentence four" not in explanation
        assert "Sentence five" not in explanation

        # Suggested fix check
        fix = ai_step["suggested_fix"]
        assert fix is not None
        assert "v.size()" in fix or "bounds" in fix.lower() or "line 5" in fix


# ============================================================================
# Task 4 — Relevant-Code Isolation and Canary Marker Tests
# ============================================================================

def test_canary_isolation_and_relevant_code_slicing(caplog):
    """Verify canaries outside the crash window never reach the model prompt, logs, or telemetry (FR40.2, US-74, US-76, NFR8.2)."""
    room_id = "room-eval-canary-01"
    clear_room(room_id)

    canary_top = "CANARY_OUTSIDE_24C_A"
    canary_bottom = "CANARY_SECRET_24C_B"

    # Construct 50-line program with crash at line 25
    lines = [f"// line {i}: normal background code" for i in range(1, 51)]
    lines[0] = f"// {canary_top}"
    lines[24] = "int crash_val = buffer[idx]; // line 25: crashing read"
    lines[49] = f"// {canary_bottom}"
    full_code = "\n".join(lines)

    asan_report = (
        "==2001==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000010\n"
        "READ of size 4 at 0x602000000010 thread T0\n"
        "    #0 0x401233 in read_buf /workspace/solution.cpp:25\n"
    )

    captured_prompt = {}

    async def mock_complete(prompt, *args, **kwargs):
        captured_prompt["text"] = prompt
        return "Explanation: Out of bounds read at line 25.\nSuggested Fix: Add boundary validation."

    payload = {
        "request_id": "req-canary-eval",
        "room_id": room_id,
        "analysis_id": "ana-canary-eval",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": asan_report,
        "code_snippet": full_code,
    }

    with caplog.at_level(logging.DEBUG):
        with patch("app.llm.client.LLMClient.complete", side_effect=mock_complete):
            resp = client.post("/v1/analyses/crash-explanation", json=payload)
            assert resp.status_code == 200

    prompt = captured_prompt.get("text", "")

    # 1. Crashing line MUST be present in prompt
    assert "int crash_val = buffer[idx];" in prompt

    # 2. Canaries outside relevant window MUST NOT enter prompt
    assert canary_top not in prompt, f"Leak: {canary_top} appeared in prompt!"
    assert canary_bottom not in prompt, f"Leak: {canary_bottom} appeared in prompt!"

    # 3. Raw stack traces and container metadata MUST NOT enter prompt
    assert "==2001==ERROR: AddressSanitizer" not in prompt
    assert "#0 0x401233" not in prompt

    # 4. Canaries MUST NOT leak into counters or telemetry
    room_usage_str = str(get_room_usage(room_id))
    total_usage_str = str(get_total_usage())
    assert canary_top not in room_usage_str
    assert canary_bottom not in room_usage_str
    assert canary_top not in total_usage_str
    assert canary_bottom not in total_usage_str

    # 5. Canaries MUST NOT leak into logger messages
    log_text = " ".join(r.getMessage() for r in caplog.records)
    assert canary_top not in log_text, f"Leak: {canary_top} appeared in logs!"
    assert canary_bottom not in log_text, f"Leak: {canary_bottom} appeared in logs!"


# ============================================================================
# Task 4 — Cache Behavior Tests
# ============================================================================

def test_crash_explanation_cache_hit_and_miss():
    """Verify first request causes cache miss & model call, second causes cache hit (0 model calls)."""
    room_id = "room-eval-cache-01"
    clear_room(room_id)
    get_analysis_cache().clear()

    model_call_count = 0

    async def mock_model(prompt, *args, **kwargs):
        nonlocal model_call_count
        model_call_count += 1
        return "Explanation: Out of bounds memory access detected.\nSuggested Fix: Validate index."

    payload = {
        "request_id": "req-cache-1",
        "room_id": room_id,
        "analysis_id": "ana-cache-1",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": True,
        "sanitizer_output": SANITIZER_FIXTURES[0]["raw_report"],
        "code_snippet": "std::vector<int> v = {1, 2, 3};\nstd::cout << v[5];\n",
    }

    with patch("app.llm.client.LLMClient.complete", side_effect=mock_model):
        # 1. First request -> MISS -> 1 model call
        resp1 = client.post("/v1/analyses/crash-explanation", json=payload)
        assert resp1.status_code == 200
        assert model_call_count == 1
        data1 = resp1.json()

        # 2. Second request (identical) -> HIT -> 0 additional model calls
        payload["request_id"] = "req-cache-2"
        resp2 = client.post("/v1/analyses/crash-explanation", json=payload)
        assert resp2.status_code == 200
        assert model_call_count == 1, "Model called on cache hit!"
        data2 = resp2.json()

        assert data2["ai_step"]["explanation"] == data1["ai_step"]["explanation"]
        assert data2["ai_step"]["suggested_fix"] == data1["ai_step"]["suggested_fix"]

        # Cache hit observable via room telemetry
        room_usage = get_room_usage(room_id)
        assert room_usage["cache_hits"] == 1


# ============================================================================
# Task 5 — AI Usage Counters Evaluation (US-75)
# ============================================================================

def test_usage_counters_controlled_sequence_and_aggregation():
    """Verify model calls, cache hits, and fallbacks are aggregated accurately per room and globally."""
    reset_counters()

    room_a = "room-eval-cnt-a"
    room_b = "room-eval-cnt-b"

    # Sequence for Room A: 3 model calls (100 tokens each), 2 cache hits, 1 fallback
    for _ in range(3):
        record_model_call(room_a, tokens=100)
    for _ in range(2):
        record_cache_hit(room_a)
    record_fallback(room_a)

    # Sequence for Room B: 2 model calls (200 tokens each), 1 cache hit, 2 fallbacks
    for _ in range(2):
        record_model_call(room_b, tokens=200)
    record_cache_hit(room_b)
    for _ in range(2):
        record_fallback(room_b)

    # Verify Room A
    usage_a = get_room_usage(room_a)
    assert usage_a["model_calls"] == 3
    assert usage_a["tokens_used"] == 300
    assert usage_a["cache_hits"] == 2
    assert usage_a["fallback_activations"] == 1

    # Verify Room B
    usage_b = get_room_usage(room_b)
    assert usage_b["model_calls"] == 2
    assert usage_b["tokens_used"] == 400
    assert usage_b["cache_hits"] == 1
    assert usage_b["fallback_activations"] == 2

    # Verify Total Aggregation
    total = get_total_usage()
    assert total["total_model_calls"] == 5
    assert total["total_tokens_used"] == 700
    assert total["total_cache_hits"] == 3
    assert total["total_fallback_activations"] == 3
    assert total["rooms_tracked"] == 2

    # Room teardown check (US-56): clear Room A, Room B must remain intact
    clear_room(room_a)
    assert get_room_usage(room_a)["model_calls"] == 0
    assert get_room_usage(room_b)["model_calls"] == 2
    # Global totals must be preserved across room teardowns
    assert get_total_usage()["total_model_calls"] == 5


# ============================================================================
# Task 5 — Monthly Cost Cap Evaluation (US-24)
# ============================================================================

def test_monthly_cost_cap_admission_and_rejection(caplog):
    """Verify admission while budget remains, 80% alert threshold, and rejection with COST_CAP_REACHED."""
    tracker = get_tracker()
    orig_budget = tracker._monthly_budget_override
    orig_cost = tracker._token_cost_override
    reset_counters()
    try:
        budget = 1.00  # $1.00 budget
        tracker.monthly_budget_usd = budget
        tracker.token_cost_per_token = 0.001  # $0.001 per token (1000 tokens = $1.00)

        # 1. Admit request below 80% (700 tokens = $0.70)
        with caplog.at_level(logging.WARNING):
            record_model_call("room-cap-1", tokens=700)
            assert tracker.alert_80_percent_triggered is False
            assert not is_cost_cap_reached()
            assert tracker.get_monthly_spend_usd() == 0.70

            # 2. Cross 80% threshold ($0.80 out of $1.00): 150 more tokens -> 850 total = $0.85
            record_model_call("room-cap-1", tokens=150)
            assert tracker.alert_80_percent_triggered is True
            assert not is_cost_cap_reached()
            assert tracker.get_monthly_spend_usd() == 0.85

        alert_logs = [r for r in caplog.records if "AI_COST_CAP_ALERT" in r.getMessage()]
        assert len(alert_logs) == 1
        assert "spend_usd=0.8500" in alert_logs[0].getMessage()

        # 3. Reach budget cap (150 more tokens -> 1000 total = $1.00)
        record_model_call("room-cap-1", tokens=150)
        assert tracker.get_monthly_spend_usd() == 1.00
        assert is_cost_cap_reached() is True
        assert get_total_usage()["cost_cap_reached"] is True

        # 4. Attempt next call -> Must be blocked with CostCapReachedError in reservation
        with pytest.raises(CostCapReachedError):
            tracker.reserve_call(estimated_tokens=100)

        # Verify endpoint handles COST_CAP_REACHED cleanly
        payload = {
            "request_id": "req-cap-blocked",
            "room_id": "room-cap-1",
            "analysis_id": "ana-cap-blocked",
            "request_type": "crash_explanation",
            "language": "cpp",
            "consent": True,
            "ai_enabled": True,
            "sanitizer_output": SANITIZER_FIXTURES[0]["raw_report"],
            "code_snippet": "v[5] = 10;",
        }
        resp = client.post("/v1/analyses/crash-explanation", json=payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["ai_step"]["status"] == "skipped"
        assert data["ai_step"]["error_code"] == "COST_CAP_REACHED"
        # Deterministic parser summary must still be returned!
        assert data["error_type"] == "heap-buffer-overflow"
    finally:
        reset_counters()
        with tracker._lock:
            tracker._monthly_budget_override = orig_budget
            tracker._token_cost_override = orig_cost


def test_concurrent_cost_cap_reservations_prevent_overshoot():
    """Verify that simultaneous concurrent reservation attempts near the budget cap

    are atomic and reject excess requests without bypassing the cap (NFR7.2, US-24).
    """
    tracker = get_tracker()
    orig_budget = tracker._monthly_budget_override
    orig_cost = tracker._token_cost_override
    reset_counters()
    try:
        tracker.token_cost_per_token = 0.0000035

        # Headroom for exactly two 100-token reservations:
        # 2 * 100 * 0.0000035 < 201 * 0.0000035 (budget)
        # 3 * 100 * 0.0000035 >= 201 * 0.0000035 (rejection)
        tracker.monthly_budget_usd = 201 * 0.0000035

        successful = []
        blocked = []
        lock = threading.Lock()
        barrier = threading.Barrier(10)

        def attempt_reservation(worker_id: int):
            barrier.wait()
            try:
                res = tracker.reserve_call(estimated_tokens=100)
                with lock:
                    successful.append((worker_id, res))
            except CostCapReachedError:
                with lock:
                    blocked.append(worker_id)

        threads = [threading.Thread(target=attempt_reservation, args=(i,)) for i in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(successful) == 2, f"Expected exactly 2 admissions under concurrency, got {len(successful)}"
        assert len(blocked) == 8, f"Expected exactly 8 rejections under concurrency, got {len(blocked)}"
        assert tracker.get_active_reservations_tokens() == 200

        # Cleanup
        for _, res in successful:
            res.release()
        assert tracker.get_active_reservations_tokens() == 0
    finally:
        reset_counters()
        with tracker._lock:
            tracker._monthly_budget_override = orig_budget
            tracker._token_cost_override = orig_cost


# ============================================================================
# Task 5 — Fallback Behavior Evaluation (US-78)
# ============================================================================

def test_fallback_consent_missing():
    """Verify consent=False triggers skipped ai_step with error_code CONSENT_MISSING."""
    payload = {
        "request_id": "req-fb-consent",
        "room_id": "room-fb-consent",
        "analysis_id": "ana-fb-consent",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": False,
        "ai_enabled": True,
        "sanitizer_output": SANITIZER_FIXTURES[0]["raw_report"],
        "code_snippet": "v[5] = 10;",
    }
    resp = client.post("/v1/analyses/crash-explanation", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "CONSENT_MISSING"
    assert data["error_type"] == "heap-buffer-overflow"


def test_fallback_ai_disabled():
    """Verify ai_enabled=False triggers skipped ai_step with error_code AI_DISABLED."""
    payload = {
        "request_id": "req-fb-disabled",
        "room_id": "room-fb-disabled",
        "analysis_id": "ana-fb-disabled",
        "request_type": "crash_explanation",
        "language": "cpp",
        "consent": True,
        "ai_enabled": False,
        "sanitizer_output": SANITIZER_FIXTURES[0]["raw_report"],
        "code_snippet": "v[5] = 10;",
    }
    resp = client.post("/v1/analyses/crash-explanation", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["ai_step"]["status"] == "skipped"
    assert data["ai_step"]["error_code"] == "AI_DISABLED"
    assert data["error_type"] == "heap-buffer-overflow"


def test_fallback_provider_error():
    """Verify external model failure falls back gracefully to skipped PROVIDER_ERROR without crashing."""
    room_id = "room-fb-prov-err"
    clear_room(room_id)

    with patch("app.llm.client.LLMClient.complete", new=AsyncMock(side_effect=RuntimeError("Upstream 500 error"))):
        payload = {
            "request_id": "req-fb-error",
            "room_id": room_id,
            "analysis_id": "ana-fb-error",
            "request_type": "crash_explanation",
            "language": "cpp",
            "consent": True,
            "ai_enabled": True,
            "sanitizer_output": SANITIZER_FIXTURES[0]["raw_report"],
            "code_snippet": "v[5] = 10;",
        }
        resp = client.post("/v1/analyses/crash-explanation", json=payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["ai_step"]["status"] == "skipped"
        assert data["ai_step"]["error_code"] == "PROVIDER_ERROR"
        assert data["error_type"] == "heap-buffer-overflow"


# ============================================================================
# Task 5 — Multi-Room Concurrent Load Evaluation & Stampede Analysis
# ============================================================================

def test_multi_room_concurrent_load_staged():
    """Execute synthetic concurrent load across 4 rooms in staged batches

    to measure model-call latency, warm-cache latency, and fallback latency independently.
    """
    reset_counters()
    get_analysis_cache().clear()

    rooms = ["room-load-alpha", "room-load-beta", "room-load-gamma", "room-load-delta"]

    # Each room will run:
    # Stage 1: 4 distinct cold-cache requests (expected 4 model calls per room -> 16 total)
    # Stage 2: 4 duplicate warm-cache requests (expected 4 cache hits per room -> 16 total)
    # Stage 3: 2 consent=False requests (expected 2 fallbacks per room -> 8 total)
    # Total per room = 10 requests. Total across 4 rooms = 40 requests.

    async def mock_execute(
        self,
        prompt,
        api_key,
        model,
        base_url,
        timeout_seconds,
        room_id,
        active_res,
        max_tokens=None,
    ):
        await asyncio.sleep(0.005)  # 5ms synthetic provider latency
        active_res.commit(room_id=room_id, tokens=50)
        return "Explanation: Out of bounds read detected at line 5.\nSuggested Fix: Add boundary check."

    def make_request(req_info):
        room_id, req_id, code_id, consent = req_info
        payload = {
            "request_id": f"req-load-{req_id}",
            "room_id": room_id,
            "analysis_id": f"ana-load-{req_id}",
            "request_type": "crash_explanation",
            "language": "cpp",
            "consent": consent,
            "ai_enabled": True,
            "sanitizer_output": SANITIZER_FIXTURES[0]["raw_report"],
            "code_snippet": f"// code variant {code_id}\nint x = buffer[{code_id}];\n",
        }
        t0 = time.perf_counter()
        resp = client.post("/v1/analyses/crash-explanation", json=payload)
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        assert resp.status_code == 200
        return elapsed_ms

    stage1_requests = []
    stage2_requests = []
    stage3_requests = []
    req_counter = 0

    # Build Stage 1 (Distinct requests across 4 rooms)
    for r in rooms:
        for c in range(4):
            req_counter += 1
            stage1_requests.append((r, req_counter, c, True))

    # Build Stage 2 (Duplicate requests across 4 rooms)
    for r in rooms:
        for c in range(4):
            req_counter += 1
            stage2_requests.append((r, req_counter, c, True))

    # Build Stage 3 (Fallbacks across 4 rooms)
    for r in rooms:
        for c in range(2):
            req_counter += 1
            stage3_requests.append((r, req_counter, 100 + c, False))

    with patch.object(LLMClient, "_execute_complete", mock_execute):
        with ThreadPoolExecutor(max_workers=8) as executor:
            # Execute Stage 1: Cold requests concurrently
            latencies_stage1 = list(executor.map(make_request, stage1_requests))
            # Execute Stage 2: Warm cache requests concurrently
            latencies_stage2 = list(executor.map(make_request, stage2_requests))
            # Execute Stage 3: Fallbacks concurrently
            latencies_stage3 = list(executor.map(make_request, stage3_requests))

    total = get_total_usage()
    assert total["total_model_calls"] == 16, f"Expected 16 model calls, got {total['total_model_calls']}"
    assert total["total_cache_hits"] == 16, f"Expected 16 cache hits, got {total['total_cache_hits']}"
    assert total["total_fallback_activations"] == 8, f"Expected 8 fallbacks, got {total['total_fallback_activations']}"
    assert total["rooms_tracked"] == 4

    # Verify each room has exact counts
    for r in rooms:
        ru = get_room_usage(r)
        assert ru["model_calls"] == 4, f"Room {r} expected 4 model calls, got {ru['model_calls']}"
        assert ru["cache_hits"] == 4, f"Room {r} expected 4 cache hits, got {ru['cache_hits']}"
        assert ru["fallback_activations"] == 2, f"Room {r} expected 2 fallbacks, got {ru['fallback_activations']}"


def test_concurrent_cold_cache_stampede_behavior():
    """Demonstrate that an uncoordinated burst of identical requests on a cold cache

    proceeds to the model before any thread writes to cache (cold cache stampede),
    while UsageTracker counters remain 100% thread-safe with zero corruption.
    """
    reset_counters()
    get_analysis_cache().clear()
    room_id = "room-stampede"

    async def mock_execute(
        self,
        prompt,
        api_key,
        model,
        base_url,
        timeout_seconds,
        room_id,
        active_res,
        max_tokens=None,
    ):
        await asyncio.sleep(0.02)  # 20ms provider latency
        active_res.commit(room_id=room_id, tokens=50)
        return "Explanation: Out of bounds read at line 5.\nSuggested Fix: Add boundary check."

    barrier = threading.Barrier(4)

    def make_identical_request(req_id: int, wait_barrier: bool = True):
        payload = {
            "request_id": f"req-stampede-{req_id}",
            "room_id": room_id,
            "analysis_id": f"ana-stampede-{req_id}",
            "request_type": "crash_explanation",
            "language": "cpp",
            "consent": True,
            "ai_enabled": True,
            "sanitizer_output": SANITIZER_FIXTURES[0]["raw_report"],
            "code_snippet": "v[5] = 10;",
        }
        if wait_barrier:
            barrier.wait(timeout=5.0)
        resp = client.post("/v1/analyses/crash-explanation", json=payload)
        assert resp.status_code == 200
        return resp.json()

    with patch.object(LLMClient, "_execute_complete", mock_execute):
        with ThreadPoolExecutor(max_workers=4) as executor:
            results = list(executor.map(lambda i: make_identical_request(i, wait_barrier=True), range(4)))

    room_usage = get_room_usage(room_id)
    total = get_total_usage()

    # All 4 simultaneous calls checked the cache before any completed, resulting in 4 model calls
    assert room_usage["model_calls"] == 4
    assert room_usage["cache_hits"] == 0
    assert room_usage["tokens_used"] == 200
    assert total["total_model_calls"] == 4
    assert total["total_tokens_used"] == 200

    # The 5th sequential call arrives after cache is populated and MUST hit the cache
    make_identical_request(99, wait_barrier=False)
    updated_usage = get_room_usage(room_id)
    assert updated_usage["model_calls"] == 4  # No additional model calls
    assert updated_usage["cache_hits"] == 1   # Hit warm cache
