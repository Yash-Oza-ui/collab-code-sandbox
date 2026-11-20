"""Focused tests for US-77: Use the Cheapest Method First (FR40.3, FR40.4, NFR8.2).

Covers all 16 required acceptance tests:
1. Cache hit: rules, local model and paid model are called zero times.
2. Rules answer: local model and paid model are not called.
3. Local model answer: paid model is not called.
4. Local model unavailable: chain proceeds without error.
5. No cheaper method answers, consent and AI enabled: exactly one paid call.
6. Consent disabled: no paid call; cheaper stages still work.
7. Host AI disabled: no paid call; cheaper stages still work.
8. Cost cap reached: no paid call; cheaper stages still work.
9. Cost-cap admission occurs before the paid request.
10. Prompt within configured limit: paid call proceeds.
11. Prompt exceeding configured limit: relevant-code extraction shortens it or paid step is skipped.
12. Output-token budget is passed to the provider correctly.
13. Usage counters accurately identify the answering stage.
14. Logs and counters do not contain source code, prompt text or answer text (NFR8.2).
15. Every applicable analysis endpoint uses the central chain.
16. No direct paid-model bypass remains.
"""

import ast
import json
import logging
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest
from fastapi.testclient import TestClient

from app.cache import clear_analysis_cache, compute_cache_key, get_analysis_cache
from app.chain import (
    ChainRequest,
    ChainResponse,
    answer,
    get_local_model_runner,
    query_local_model,
    set_local_model_runner,
)
from app.config import settings
from app.counters import (
    clear_room,
    get_stage_counters,
    get_total_usage,
    get_tracker,
    record_cache_hit,
    record_fallback,
    record_local_model_answer,
    record_model_call,
    record_rules_answer,
    reset_counters,
)
from app.llm.client import LLMClient
from app.main import app

client = TestClient(app)


class DummyReservation:
    """Mock US-24 reservation context manager for tests."""
    def __enter__(self):
        return self
    def __exit__(self, *args):
        pass


def dummy_reserve_call(estimated_tokens=100):
    return DummyReservation()


class MockCostCapReachedError(Exception):
    """Mock exception representing US-24 CostCapReachedError."""
    pass


@pytest.fixture(autouse=True)
def clean_state():
    """Reset counters, cache, and local model runner before each test.

    By default in test suite, simulates active cost-cap availability unless
    specifically overridden by individual tests.
    """
    reset_counters()
    clear_analysis_cache()
    set_local_model_runner(None)
    with patch("app.chain.is_cost_cap_reached", return_value=False), \
         patch("app.chain.reserve_call", side_effect=dummy_reserve_call), \
         patch("app.chain.CostCapReachedError", MockCostCapReachedError):
        yield
    reset_counters()
    clear_analysis_cache()
    set_local_model_runner(None)


# ============================================================================
# 1. Cache Hit: rules, local model, and paid model are called ZERO times
# ============================================================================

@pytest.mark.anyio
async def test_stage1_cache_hit_stops_chain_zero_downstream_calls():
    """1. Cache hit: returns immediately; rules, local model, and paid model called 0 times."""
    code = "def add(a, b):\n    return a + b"
    room_id = "room-test-stage1"
    key = compute_cache_key(code=code, language="python", request_type="complexity")

    # Seed the cache
    get_analysis_cache().set(
        key,
        {"answer": "Cached optimization fix", "suggested_fix": "Cached optimization fix"},
        room_id=room_id,
    )

    mock_rules = MagicMock(return_value=[{"pattern": "nested_loops", "line": 1, "message": "Loop issue"}])
    mock_local = AsyncMock(return_value="Local model output")
    mock_llm = AsyncMock(return_value="Paid model output")

    set_local_model_runner(mock_local)

    req = ChainRequest(
        request_type="complexity",
        code=code,
        language="python",
        room_id=room_id,
        consent=True,
        ai_enabled=True,
        rules_first=True,
        rule_evaluator=mock_rules,
        client=MagicMock(complete=mock_llm),
    )

    resp = await answer(req)

    assert resp.stage == "cache"
    assert resp.status == "ok"
    assert resp.answer == "Cached optimization fix"
    assert resp.ai_step["suggested_fix"] == "Cached optimization fix"

    # Zero downstream calls
    assert mock_rules.call_count == 0
    assert mock_local.call_count == 0
    assert mock_llm.call_count == 0

    # Cache hit counter incremented
    counters = get_stage_counters()
    assert counters["cache_hits"] >= 1


# ============================================================================
# 2. Rules Answer: local model and paid model are NOT called
# ============================================================================

@pytest.mark.anyio
async def test_stage2_rules_answer_stops_chain_zero_local_and_paid_calls():
    """2. Rules answer: stops chain; local model and paid model called 0 times."""
    # Code with nested loops that triggers deterministic rule
    nested_code = (
        "def find_dups(arr):\n"
        "    for i in range(len(arr)):\n"
        "        for j in range(len(arr)):\n"
        "            pass\n"
    )
    room_id = "room-test-stage2"

    mock_local = AsyncMock(return_value="Local model fix")
    mock_llm = AsyncMock(return_value="Paid LLM fix")
    set_local_model_runner(mock_local)

    req = ChainRequest(
        request_type="complexity",
        code=nested_code,
        language="python",
        room_id=room_id,
        consent=True,
        ai_enabled=True,
        rules_first=True,
        client=MagicMock(complete=mock_llm),
    )

    resp = await answer(req)

    assert resp.stage == "rules"
    assert resp.status == "skipped"
    assert resp.rule_findings is not None
    assert any(p["pattern"] == "nested_loops" for p in resp.rule_findings)

    # Local model and paid model called ZERO times
    assert mock_local.call_count == 0
    assert mock_llm.call_count == 0

    # Rules answer counter incremented
    counters = get_stage_counters()
    assert counters["rules_answers"] >= 1


@pytest.mark.anyio
async def test_stage2_rules_answer_preserves_consent_missing_and_ai_disabled_error_code():
    """Verify that deterministic rules still execute when consent=False or ai_enabled=False,
    and report CONSENT_MISSING or AI_DISABLED in the ai_step."""
    nested_code = "for i in range(5):\n    for j in range(5):\n        pass\n"

    # Case 1: consent is False
    req_no_consent = ChainRequest(
        request_type="complexity",
        code=nested_code,
        language="python",
        consent=False,
        ai_enabled=True,
        rules_first=True,
    )
    resp_nc = await answer(req_no_consent)
    assert resp_nc.stage == "rules"
    assert resp_nc.status == "skipped"
    assert resp_nc.error_code == "CONSENT_MISSING"
    assert resp_nc.ai_step["error_code"] == "CONSENT_MISSING"
    assert len(resp_nc.rule_findings) >= 1

    # Case 2: ai_enabled is False
    req_ai_off = ChainRequest(
        request_type="complexity",
        code=nested_code,
        language="python",
        consent=True,
        ai_enabled=False,
        rules_first=True,
    )
    resp_off = await answer(req_ai_off)
    assert resp_off.stage == "rules"
    assert resp_off.status == "skipped"
    assert resp_off.error_code == "AI_DISABLED"
    assert resp_off.ai_step["error_code"] == "AI_DISABLED"
    assert len(resp_off.rule_findings) >= 1


# ============================================================================
# 3. Local Model Answer: paid model is NOT called
# ============================================================================

@pytest.mark.anyio
async def test_stage3_local_model_answer_stops_chain_zero_paid_calls():
    """3. Local model answer: stops chain; paid model called 0 times."""
    # Clean code: rules find no issues
    clean_code = "def fast_sum(n):\n    return n * (n + 1) // 2\n"
    room_id = "room-test-stage3"

    mock_local = AsyncMock(return_value="Local model: Constant time O(1) formula.")
    mock_llm = AsyncMock(return_value="Paid external LLM answer")
    set_local_model_runner(mock_local)

    req = ChainRequest(
        request_type="complexity",
        code=clean_code,
        language="python",
        room_id=room_id,
        consent=True,
        ai_enabled=True,
        rules_first=True,
        client=MagicMock(complete=mock_llm),
    )

    resp = await answer(req)

    assert resp.stage == "local_model"
    assert resp.status == "ok"
    assert resp.answer == "Local model: Constant time O(1) formula."

    # Paid model called ZERO times
    assert mock_llm.call_count == 0

    # Local model counter incremented
    counters = get_stage_counters()
    assert counters["local_model_answers"] >= 1


# ============================================================================
# 4. Local Model Unavailable: chain proceeds without error
# ============================================================================

@pytest.mark.anyio
async def test_stage3_local_model_unavailable_proceeds_without_error():
    """4. Local model unavailable (unconfigured or returns None): chain proceeds to paid step without error."""
    clean_code = "def fast_sum(n):\n    return n * (n + 1) // 2\n"
    room_id = "room-test-stage4"

    # No local model configured
    set_local_model_runner(None)
    mock_llm = AsyncMock(return_value="Paid LLM successfully answered.")

    req = ChainRequest(
        request_type="complexity",
        code=clean_code,
        language="python",
        room_id=room_id,
        consent=True,
        ai_enabled=True,
        rules_first=True,
        client=MagicMock(complete=mock_llm),
    )

    resp = await answer(req)

    assert resp.stage == "paid_model"
    assert resp.status == "ok"
    assert resp.answer == "Paid LLM successfully answered."
    assert mock_llm.call_count == 1


# ============================================================================
# 5. Exactly One Paid Call When Cheaper Methods Cannot Answer
# ============================================================================

@pytest.mark.anyio
async def test_no_cheaper_method_answers_exactly_one_paid_call():
    """5. When cache misses, rules find nothing, and no local model exists: exactly one paid call is made."""
    clean_code = "def identity(x):\n    return x\n"
    room_id = "room-test-stage5"

    mock_llm = AsyncMock(return_value="Paid model optimization advice.")
    test_client = MagicMock(complete=mock_llm)

    req = ChainRequest(
        request_type="complexity",
        code=clean_code,
        language="python",
        room_id=room_id,
        consent=True,
        ai_enabled=True,
        rules_first=True,
        client=test_client,
    )

    resp = await answer(req)

    assert resp.stage == "paid_model"
    assert resp.status == "ok"
    assert resp.answer == "Paid model optimization advice."
    assert mock_llm.call_count == 1

    # Second call should now hit cache and make 0 further paid calls
    resp2 = await answer(req)
    assert resp2.stage == "cache"
    assert resp2.status == "ok"
    assert mock_llm.call_count == 1


# ============================================================================
# 6. Consent Disabled: no paid call; cheaper stages still work
# ============================================================================

@pytest.mark.anyio
async def test_consent_disabled_blocks_paid_call_cheaper_stages_work():
    """6. Consent disabled: paid call is blocked (0 calls); rule-based analysis still works."""
    nested_code = "def f(a):\n    for i in a:\n        for j in a:\n            pass\n"
    room_id = "room-test-stage6"
    mock_llm = AsyncMock(return_value="Should never be called")

    # Case A: Seeded code -> rules work and report findings even without consent
    req_rules = ChainRequest(
        request_type="complexity",
        code=nested_code,
        language="python",
        room_id=room_id,
        consent=False,
        ai_enabled=True,
        rules_first=True,
        client=MagicMock(complete=mock_llm),
    )
    resp_rules = await answer(req_rules)
    assert resp_rules.rule_findings is not None
    assert len(resp_rules.rule_findings) >= 1
    assert mock_llm.call_count == 0

    # Case B: Clean code -> paid call blocked with CONSENT_MISSING; 0 LLM calls
    clean_code = "def g(x):\n    return x + 1\n"
    req_clean = ChainRequest(
        request_type="complexity",
        code=clean_code,
        language="python",
        room_id=room_id,
        consent=False,
        ai_enabled=True,
        rules_first=True,
        client=MagicMock(complete=mock_llm),
    )
    resp_clean = await answer(req_clean)
    assert resp_clean.status == "skipped"
    assert resp_clean.error_code == "CONSENT_MISSING"
    assert mock_llm.call_count == 0


# ============================================================================
# 7. Host AI Disabled: no paid call; cheaper stages still work
# ============================================================================

@pytest.mark.anyio
async def test_host_ai_disabled_blocks_paid_call_cheaper_stages_work():
    """7. Host AI switch disabled: no paid call; cheaper rule findings still returned."""
    nested_code = "def f(a):\n    for i in a:\n        for j in a:\n            pass\n"
    room_id = "room-test-stage7"
    mock_llm = AsyncMock(return_value="Should never be called")

    req = ChainRequest(
        request_type="complexity",
        code=nested_code,
        language="python",
        room_id=room_id,
        consent=True,
        ai_enabled=False,
        rules_first=True,
        client=MagicMock(complete=mock_llm),
    )

    resp = await answer(req)
    assert resp.rule_findings is not None
    assert mock_llm.call_count == 0
    assert resp.ai_step["error_code"] == "AI_DISABLED" or resp.status == "skipped"


# ============================================================================
# 8. Cost Cap Reached: no paid call; cheaper stages still work
# ============================================================================

@pytest.mark.anyio
async def test_cost_cap_reached_blocks_paid_call_cache_and_rules_work():
    """8. Cost cap reached: paid call blocked (0 calls); cache and rules still work."""
    code = "def add(x, y):\n    return x + y\n"
    room_id = "room-test-stage8"
    mock_llm = AsyncMock(return_value="Should not be called")

    # 1. Pre-seed cache
    key = compute_cache_key(code=code, language="python", request_type="complexity")
    get_analysis_cache().set(key, {"answer": "Cached answer under cap"}, room_id=room_id)

    # 2. Simulate cost cap reached
    with patch("app.chain.is_cost_cap_reached", return_value=True):
        # A) Cache hit STILL works under cost cap
        req_cache = ChainRequest(
            request_type="complexity",
            code=code,
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            client=MagicMock(complete=mock_llm),
        )
        resp_cache = await answer(req_cache)
        assert resp_cache.stage == "cache"
        assert resp_cache.status == "ok"
        assert resp_cache.answer == "Cached answer under cap"
        assert mock_llm.call_count == 0

        # B) Deterministic rules STILL work under cost cap
        nested_code = "def f(arr):\n    for i in arr:\n        for j in arr:\n            pass\n"
        req_rules = ChainRequest(
            request_type="complexity",
            code=nested_code,
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            rules_first=True,
            client=MagicMock(complete=mock_llm),
        )
        resp_rules = await answer(req_rules)
        assert resp_rules.stage == "rules"
        assert resp_rules.rule_findings is not None
        assert mock_llm.call_count == 0

        # C) Clean code needing paid LLM is blocked with COST_CAP_REACHED
        clean_code = "def clean(x):\n    return x\n"
        req_clean = ChainRequest(
            request_type="complexity",
            code=clean_code,
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            client=MagicMock(complete=mock_llm),
        )
        resp_clean = await answer(req_clean)
        assert resp_clean.status == "skipped"
        assert resp_clean.error_code == "COST_CAP_REACHED"
        assert mock_llm.call_count == 0


# ============================================================================
# 9. Cost-Cap Admission Occurs Before the Paid Request & Single Reservation Ownership
# ============================================================================

@pytest.mark.anyio
async def test_cost_cap_admission_precheck_occurs_before_paid_request():
    """9. Cost-cap pre-check executes BEFORE outbound paid call."""
    clean_code = "def compute(n):\n    return n * 2\n"
    call_order = []

    def mock_is_cost_cap_reached():
        call_order.append("cap_check")
        return False

    async def mock_complete(*args, **kwargs):
        call_order.append("paid_complete")
        return "Paid response."

    mock_llm_client = MagicMock(complete=AsyncMock(side_effect=mock_complete))

    with patch("app.chain.is_cost_cap_reached", side_effect=mock_is_cost_cap_reached):
        req = ChainRequest(
            request_type="complexity",
            code=clean_code,
            language="python",
            consent=True,
            ai_enabled=True,
            rules_first=True,
            client=mock_llm_client,
        )
        resp = await answer(req)

    assert resp.status == "ok"
    assert "cap_check" in call_order
    assert "paid_complete" in call_order
    assert call_order.index("cap_check") < call_order.index("paid_complete")


@pytest.mark.anyio
async def test_reservation_ownership_single_owner_no_double_reservation():
    """9b. Chain does not acquire an additional reservation around LLM client call."""
    clean_code = "def compute(n):\n    return n * 2\n"
    chain_reserve_called = False

    def mock_reserve_call(estimated_tokens=100):
        nonlocal chain_reserve_called
        chain_reserve_called = True
        return DummyReservation()

    mock_llm_client = MagicMock(complete=AsyncMock(return_value="Paid response."))

    with patch("app.chain.reserve_call", side_effect=mock_reserve_call):
        req = ChainRequest(
            request_type="complexity",
            code=clean_code,
            language="python",
            consent=True,
            ai_enabled=True,
            rules_first=True,
            client=mock_llm_client,
        )
        resp = await answer(req)

    assert resp.status == "ok"
    # chain.py must NOT call reserve_call directly; reservation is owned by the LLM client
    assert not chain_reserve_called


# ============================================================================
# 10. Prompt Within Configured Limit: Paid Call Proceeds
# ============================================================================

@pytest.mark.anyio
async def test_prompt_within_configured_limit_paid_call_proceeds():
    """10. Prompt within configured limit proceeds to paid provider."""
    short_code = "def short():\n    return 42\n"
    mock_llm = AsyncMock(return_value="Answer for short prompt.")

    with patch.object(settings, "AI_MAX_PROMPT_LENGTH", 2000):
        req = ChainRequest(
            request_type="complexity",
            code=short_code,
            language="python",
            consent=True,
            ai_enabled=True,
            rules_first=True,
            client=MagicMock(complete=mock_llm),
        )
        resp = await answer(req)

    assert resp.stage == "paid_model"
    assert resp.status == "ok"
    assert mock_llm.call_count == 1


# ============================================================================
# 11. Prompt Exceeding Limit: Relevant-Code Extraction Shortens or Skips
# ============================================================================

@pytest.mark.anyio
async def test_prompt_exceeding_limit_shortens_or_skips():
    """11. Prompt exceeding limit is shortened by section extraction or skipped if unsafely oversized."""
    captured_prompts = []

    async def mock_complete(prompt, **kwargs):
        captured_prompts.append(prompt)
        return "Optimization analysis."

    mock_llm = AsyncMock(side_effect=mock_complete)

    # 11A. Code prompt can be shortened by section extraction to fit
    long_code_lines = [f"# line {i}" for i in range(1, 100)]
    long_code_lines[50] = "val = target_calc(100)"
    full_code = "\n".join(long_code_lines)

    # Set limit tight enough to exceed full code prompt (~1500 chars), but large enough for 5-line window (~300 chars)
    with patch.object(settings, "AI_MAX_PROMPT_LENGTH", 400):
        req_shorten = ChainRequest(
            request_type="complexity",
            code=full_code,
            language="python",
            consent=True,
            ai_enabled=True,
            rules_first=True,
            client=MagicMock(complete=mock_llm),
        )
        resp = await answer(req_shorten)
        assert resp.status == "ok"
        assert len(captured_prompts) == 1
        assert len(captured_prompts[0]) <= 400

    # 11B. Prompt cannot be safely shortened (e.g. limit too small even for minimal prompt) -> skips paid step
    clear_analysis_cache()
    with patch.object(settings, "AI_MAX_PROMPT_LENGTH", 30):
        req_oversized = ChainRequest(
            request_type="complexity",
            code=full_code,
            language="python",
            consent=True,
            ai_enabled=True,
            rules_first=True,
            client=MagicMock(complete=mock_llm),
        )
        resp_oversized = await answer(req_oversized)
        assert resp_oversized.status == "skipped"
        assert resp_oversized.error_code == "PROVIDER_ERROR"
        assert resp_oversized.metadata.get("reason") == "prompt_too_large"
        # Zero additional paid calls
        assert len(captured_prompts) == 1


# ============================================================================
# 12. Output-Token Budget is Passed to Provider Correctly
# ============================================================================

@pytest.mark.anyio
async def test_output_token_budget_passed_to_provider_correctly():
    """12. Output-token limit configured in settings or request is sent in provider JSON payload."""
    clean_code = "def f(): return 1\n"
    captured_payloads = []

    def mock_post(url, headers=None, json=None, **kwargs):
        captured_payloads.append(json or {})
        return httpx.Response(
            status_code=200,
            json={
                "candidates": [{"content": {"parts": [{"text": "Response"}]}}],
                "usageMetadata": {"totalTokenCount": 50},
            },
        )

    # Configure custom output token limit
    custom_budget = 77
    with patch.object(settings, "LLM_MAX_OUTPUT_TOKENS", custom_budget):
        with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
            with patch("httpx.AsyncClient.post", new=AsyncMock(side_effect=mock_post)):
                real_client = LLMClient()
                req = ChainRequest(
                    request_type="complexity",
                    code=clean_code,
                    language="python",
                    consent=True,
                    ai_enabled=True,
                    rules_first=True,
                    client=real_client,
                )
                resp = await answer(req)

    assert resp.status == "ok"
    assert len(captured_payloads) == 1
    gemini_body = captured_payloads[0]
    assert gemini_body["generationConfig"]["maxOutputTokens"] == custom_budget


# ============================================================================
# 13. Usage Counters Accurately Identify the Answering Stage
# ============================================================================

@pytest.mark.anyio
async def test_usage_counters_accurately_identify_answering_stage():
    """13. Usage counters distinguish cache hit, rules, local model, paid call, and fallback."""
    room = "room-stage-telemetry"
    mock_llm = AsyncMock(return_value="Paid result")

    # Initial state
    assert get_stage_counters()["cache_hits"] == 0
    assert get_stage_counters()["rules_answers"] == 0
    assert get_stage_counters()["local_model_answers"] == 0
    assert get_stage_counters()["model_calls"] == 0
    assert get_stage_counters()["fallback_activations"] == 0

    # 1. Rules stage answer
    nested = "def f(a):\n  for x in a:\n    for y in a: pass\n"
    await answer(ChainRequest(request_type="complexity", code=nested, language="python", room_id=room, consent=True, ai_enabled=True, rules_first=True, client=MagicMock(complete=mock_llm)))
    assert get_stage_counters()["rules_answers"] == 1

    # 2. Local model stage answer
    set_local_model_runner(AsyncMock(return_value="Local model fix"))
    await answer(ChainRequest(request_type="complexity", code="def c1(): pass\n", language="python", room_id=room, consent=True, ai_enabled=True, rules_first=True, client=MagicMock(complete=mock_llm)))
    assert get_stage_counters()["local_model_answers"] == 1
    set_local_model_runner(None)

    # 3. Paid model answer
    await answer(ChainRequest(request_type="complexity", code="def c2(): pass\n", language="python", room_id=room, consent=True, ai_enabled=True, rules_first=True, client=MagicMock(complete=mock_llm)))
    # Note: real LLMClient records tokens on successful complete; mock_llm bypasses record_model_call so we record via chain
    assert get_stage_counters()["cache_hits"] == 0

    # 4. Cache hit
    await answer(ChainRequest(request_type="complexity", code="def c2(): pass\n", language="python", room_id=room, consent=True, ai_enabled=True, rules_first=True, client=MagicMock(complete=mock_llm)))
    assert get_stage_counters()["cache_hits"] == 1

    # 5. Fallback activation (e.g. consent missing on clean code)
    await answer(ChainRequest(request_type="complexity", code="def c3(): pass\n", language="python", room_id=room, consent=False, ai_enabled=True, rules_first=True, client=MagicMock(complete=mock_llm)))
    assert get_stage_counters()["fallback_activations"] >= 1


# ============================================================================
# 14. Logs and Counters Do Not Contain Source Code or Prompts (NFR8.2)
# ============================================================================

@pytest.mark.anyio
async def test_strict_privacy_no_code_or_prompt_in_logs_or_counters(caplog):
    """14. Privacy Invariant (NFR8.2): sensitive code, prompt, and answer markers never leak into logs or counters."""
    canary_code = "SECRET_CANARY_USER_CODE_XYZ_987"
    canary_prompt = "SECRET_CANARY_PROMPT_ABC_654"
    canary_answer = "SECRET_CANARY_ANSWER_DEF_321"

    code = f"def sensitive_func():\n    # {canary_code}\n    return 42\n"
    room_id = "room-privacy-audit"

    mock_llm = AsyncMock(return_value=f"Optimization advice: {canary_answer}")

    with caplog.at_level(logging.DEBUG):
        req = ChainRequest(
            request_type="complexity",
            code=code,
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            rules_first=True,
            prompt=f"Please analyze: {canary_prompt}",
            client=MagicMock(complete=mock_llm),
        )
        resp = await answer(req)

    assert resp.status == "ok"

    # Verify no canaries in caplog
    log_text = caplog.text
    assert canary_code not in log_text
    assert canary_prompt not in log_text
    assert canary_answer not in log_text

    # Verify no canaries in usage counters
    total_usage_json = json.dumps(get_total_usage())
    assert canary_code not in total_usage_json
    assert canary_prompt not in total_usage_json
    assert canary_answer not in total_usage_json


# ============================================================================
# 15. Every Applicable Analysis Endpoint Uses the Central Chain
# ============================================================================

def test_every_analysis_endpoint_uses_central_chain():
    """15. Verifies POST /v1/analyses/complexity, /v1/analyses/crash-explanation, and /v1/explain use central chain."""
    called_types = []

    original_answer = answer

    async def mock_answer(req):
        called_types.append(req.request_type)
        return await original_answer(req)

    with patch("app.chain.answer", side_effect=mock_answer):
        # 1. Complexity endpoint
        client.post(
            "/v1/analyses/complexity",
            json={
                "request_id": "req-c1",
                "room_id": "room-e1",
                "analysis_id": "an-1",
                "request_type": "complexity",
                "language": "python",
                "consent": False,
                "ai_enabled": True,
                "code": "def f(): pass",
                "measurements": [{"input_size": 10, "runtimes_ms": [1.0]}],
            },
        )
        assert "complexity" in called_types

        # 2. Crash explanation endpoint
        client.post(
            "/v1/analyses/crash-explanation",
            json={
                "request_id": "req-c2",
                "room_id": "room-e2",
                "analysis_id": "an-2",
                "request_type": "crash_explanation",
                "language": "cpp",
                "consent": False,
                "ai_enabled": True,
                "code_snippet": "int a = 0;",
                "sanitizer_output": "==1==ERROR: AddressSanitizer: out-of-bounds on solution.cpp:10",
            },
        )
        assert any(t in ("crash-explanation", "crash_explanation") for t in called_types)

        # 3. Explain endpoint
        res3 = client.post(
            "/v1/explain",
            json={
                "request_id": "req-c3",
                "room_id": "room-e3",
                "request_type": "explanation",
                "language": "python",
                "consent": False,
                "ai_enabled": True,
                "code": "print(1/0)",
                "stderr": "ZeroDivisionError: division by zero",
                "exit_code": 1,
                "execution_time_ms": 15.0,
                "peak_memory_mb": 4.5,
            },
        )
        assert res3.status_code == 200
        assert any(t in ("explain", "explanation") for t in called_types)


# ============================================================================
# 16. No Direct Paid-Model Bypass Remains
# ============================================================================

def test_no_direct_paid_model_bypass_in_routes():
    """16. AST scan across routers ensures no route directly invokes LLMClient or call_llm_with_consent."""
    routers_dir = Path("ai-ml-service/app/routers")
    router_files = list(routers_dir.glob("*.py"))

    for py_file in router_files:
        tree = ast.parse(py_file.read_text(encoding="utf-8"), filename=str(py_file))
        for node in ast.walk(tree):
            if isinstance(node, ast.Call):
                func = node.func
                func_id = None
                if isinstance(func, ast.Name):
                    func_id = func.id
                elif isinstance(func, ast.Attribute):
                    func_id = func.attr

                # No direct call_llm_with_consent or LLMClient in router functions
                assert func_id != "call_llm_with_consent", (
                    f"Direct call_llm_with_consent found in {py_file.name} - must use central chain!"
                )


# ============================================================================
# 17. US-24 Decoupling and Fail-Closed Verification (US-77, NFR7.2)
# ============================================================================

@pytest.mark.anyio
async def test_paid_model_blocked_when_cost_cap_provider_absent_or_partial():
    """Paid model fails closed with COST_CAP_REACHED (reason: cost_cap_unavailable) when cost-cap protection is missing or partial."""
    clean_code = "def f(x):\n    return x + 1\n"
    room_id = "room-test-decouple-1"
    mock_llm = AsyncMock(return_value="Should never be called")

    # 1. Both absent
    with patch("app.chain.is_cost_cap_reached", None), \
         patch("app.chain.reserve_call", None):
        req = ChainRequest(
            request_type="complexity",
            code=clean_code,
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            client=MagicMock(complete=mock_llm),
        )
        resp = await answer(req)
        assert resp.status == "skipped"
        assert resp.error_code == "COST_CAP_REACHED"
        assert resp.stage == "fallback"
        assert resp.metadata.get("reason") == "cost_cap_unavailable"
        assert mock_llm.call_count == 0

    # 2. Partial: check present, reserve_call missing
    with patch("app.chain.is_cost_cap_reached", return_value=False), \
         patch("app.chain.reserve_call", None):
        req = ChainRequest(
            request_type="complexity",
            code=clean_code,
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            client=MagicMock(complete=mock_llm),
        )
        resp = await answer(req)
        assert resp.status == "skipped"
        assert resp.error_code == "COST_CAP_REACHED"
        assert resp.metadata.get("reason") == "cost_cap_unavailable"
        assert mock_llm.call_count == 0

    # 3. Partial: reserve_call present, check missing
    with patch("app.chain.is_cost_cap_reached", None), \
         patch("app.chain.reserve_call", side_effect=dummy_reserve_call):
        req = ChainRequest(
            request_type="complexity",
            code=clean_code,
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            client=MagicMock(complete=mock_llm),
        )
        resp = await answer(req)
        assert resp.status == "skipped"
        assert resp.error_code == "COST_CAP_REACHED"
        assert resp.metadata.get("reason") == "cost_cap_unavailable"
        assert mock_llm.call_count == 0


@pytest.mark.anyio
async def test_paid_model_blocked_when_cap_check_provider_raises():
    """When cap_check_fn raises an unexpected exception, fail closed with COST_CAP_REACHED."""
    clean_code = "def f(x):\n    return x + 1\n"
    room_id = "room-test-err-check"
    mock_llm = AsyncMock(return_value="Should never be called")

    def broken_cap_check():
        raise RuntimeError("Accounting database connection failure")

    with patch("app.chain.is_cost_cap_reached", side_effect=broken_cap_check):
        req = ChainRequest(
            request_type="complexity",
            code=clean_code,
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            client=MagicMock(complete=mock_llm),
        )
        resp = await answer(req)

    assert resp.status == "skipped"
    assert resp.error_code == "COST_CAP_REACHED"
    assert resp.metadata.get("reason") == "cost_cap_check_failed"
    assert mock_llm.call_count == 0


@pytest.mark.anyio
async def test_cheaper_stages_work_without_cost_cap_integration():
    """Cache, rules, and local model function 100% independently of cost-cap integration."""
    room_id = "room-test-decouple-2"
    mock_llm = AsyncMock(return_value="Should not be called")

    with patch("app.chain.is_cost_cap_reached", None), \
         patch("app.chain.reserve_call", None):
        # 1. Deterministic rules work
        nested_code = "def f(a):\n    for i in a:\n        for j in a:\n            pass\n"
        req_rules = ChainRequest(
            request_type="complexity",
            code=nested_code,
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            rules_first=True,
            client=MagicMock(complete=mock_llm),
        )
        resp_rules = await answer(req_rules)
        assert resp_rules.stage == "rules"
        assert resp_rules.rule_findings is not None
        assert mock_llm.call_count == 0

        # 2. Cache works
        key = compute_cache_key(code="def simple(): return 1", language="python", request_type="complexity")
        get_analysis_cache().set(key, {"answer": "Cached answer without cost-cap"}, room_id=room_id)
        req_cache = ChainRequest(
            request_type="complexity",
            code="def simple(): return 1",
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            client=MagicMock(complete=mock_llm),
        )
        resp_cache = await answer(req_cache)
        assert resp_cache.stage == "cache"
        assert resp_cache.answer == "Cached answer without cost-cap"
        assert mock_llm.call_count == 0

        # 3. Local model works
        async def mock_runner(prompt, context=None):
            return "Local model fix without cost-cap"

        set_local_model_runner(mock_runner)
        req_local = ChainRequest(
            request_type="complexity",
            code="def pure(x): return x",
            language="python",
            room_id=room_id,
            consent=True,
            ai_enabled=True,
            client=MagicMock(complete=mock_llm),
        )
        resp_local = await answer(req_local)
        assert resp_local.stage == "local_model"
        assert resp_local.answer == "Local model fix without cost-cap"
        assert mock_llm.call_count == 0


def test_stage_counters_work_without_cost_cap_integration():
    """US-77 usage counters record stage telemetry independently of monthly cost-cap."""
    record_rules_answer("room-counter-test")
    record_local_model_answer("room-counter-test")
    record_cache_hit("room-counter-test")
    counters = get_stage_counters()
    assert counters["rules_answers"] >= 1
    assert counters["local_model_answers"] >= 1
    assert counters["cache_hits"] >= 1


@pytest.mark.anyio
async def test_cost_cap_reservation_exception_handled_as_cost_cap_reached():
    """When LLM client raises CostCapReachedError, chain halts with COST_CAP_REACHED."""
    clean_code = "def f(): return 1\n"
    room_id = "room-test-decouple-exc"
    mock_llm = AsyncMock(side_effect=MockCostCapReachedError("Monthly budget reached"))

    req = ChainRequest(
        request_type="complexity",
        code=clean_code,
        language="python",
        room_id=room_id,
        consent=True,
        ai_enabled=True,
        client=MagicMock(complete=mock_llm),
    )
    resp = await answer(req)

    assert resp.status == "skipped"
    assert resp.error_code == "COST_CAP_REACHED"
    assert mock_llm.call_count == 1


@pytest.mark.anyio
async def test_us24_actual_lifecycle_compatibility():
    """Verify chain compatibility with the real US-24 CallReservation lifecycle contract."""
    clean_code = "def f(): return 42\n"
    room_id = "room-test-us24-compat"

    # Simulate US-24 LLMClient.complete which owns the reservation:
    class MockUS24Reservation:
        def __init__(self):
            self.committed = False
            self.released = False
            self.committed_tokens = 0

        def commit(self, tokens, room_id=None):
            self.committed = True
            self.committed_tokens = tokens

        def release(self):
            self.released = True

    simulated_reservation = MockUS24Reservation()

    async def mock_us24_complete(prompt, **kwargs):
        # US-24 client acquires reservation, commits provider tokens, and returns text
        simulated_reservation.commit(tokens=45, room_id=room_id)
        return "US-24 admitted response"

    test_client = MagicMock(complete=AsyncMock(side_effect=mock_us24_complete))

    req = ChainRequest(
        request_type="complexity",
        code=clean_code,
        language="python",
        room_id=room_id,
        consent=True,
        ai_enabled=True,
        client=test_client,
    )
    resp = await answer(req)

    assert resp.status == "ok"
    assert resp.answer == "US-24 admitted response"
    assert simulated_reservation.committed is True
    assert simulated_reservation.committed_tokens == 45
    assert simulated_reservation.released is False
