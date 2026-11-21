"""US-70 regression tests: no user program executes on the test host."""
import json
from unittest.mock import AsyncMock

from fastapi.testclient import TestClient
from app.main import app
from app import failing_input as target

ROOM = "Zq8-Lw2Ef6Th0Yx4Rn7JbA"
RID = "3e4f5a6b-7c8d-9e0f-1a2b-3c4d5e6f7a8b"
AID = "4f5a6b7c-8d9e-0f1a-2b3c-4d5e6f7a8b9c"


def payload(**overrides):
    data = {
        "request_id": RID, "room_id": ROOM, "analysis_id": AID,
        "request_type": "failing_input", "language": "python",
        "consent": False, "ai_enabled": False,
        "code": "print(input())",
    }
    data.update(overrides)
    return data


def record(value, status="pass", stdout="OK", expected=None):
    row = {"input": value, "status": status, "stdout": stdout,
           "exit_code": 1 if status == "crash" else 0,
           "execution_time_ms": 10}
    if expected is not None:
        row["expected_output"] = expected
    return row


def test_edge_cases_are_conservative():
    cases, skipped = target.edge_cases("print(input())")
    assert cases == ["", "1"]
    assert any("maximum_bounds" in s for s in skipped)
    cases, _ = target.edge_cases("print(input().split())")
    assert "1 1" in cases and "10 -5 20" in cases


def test_seeded_crash_found_consent_off(monkeypatch):
    async def fake_batch(p, b, code, inputs, **kwargs):
        assert kwargs.get("expected") is None
        result = [record(i, "crash" if i == "" else "pass") for i in inputs]
        b.record(len(result))
        return result, False
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    forbidden = AsyncMock(side_effect=AssertionError("AI invoked without consent"))
    monkeypatch.setattr(target.chain, "answer", forbidden)
    res = TestClient(app).post("/v1/analyses/failing-input", json=payload())
    assert res.status_code == 200
    body = res.json()
    assert body["failure_type"] == "crash" and body["original_input"] == ""
    assert body["tests_executed"] == 2
    assert body["ai_step"]["error_code"] == "CONSENT_MISSING"
    forbidden.assert_not_called()


def test_no_reference_ignores_wrong_output(monkeypatch):
    async def fake_batch(p, b, code, inputs, **kwargs):
        rows = [record(i, "wrong_output", stdout="not checked") for i in inputs]
        b.record(len(rows))
        return rows, False
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    res = TestClient(app).post("/v1/analyses/failing-input", json=payload())
    assert res.status_code == 200
    assert not res.json()["failure_found"]
    assert res.headers["X-Failing-Input-Scope"] == "crash-and-timeout-only"


def test_reference_wrong_output_requires_internal_token(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_TOKEN", "test-token")
    res = TestClient(app).post("/v1/analyses/failing-input", json=payload(
        reference_solution="print('expected')"))
    assert res.status_code == 401


def test_trusted_reference_wrong_output(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_TOKEN", "test-token")
    async def fake_batch(p, b, code, inputs, **kwargs):
        if code == "print('expected')":
            rows = [record(i, stdout="expected") for i in inputs]
        else:
            assert kwargs["expected"] == ["expected"] * len(inputs)
            rows = [record(i, "wrong_output", stdout="wrong", expected="expected") for i in inputs]
        b.record(len(rows))
        return rows, False
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    res = TestClient(app).post("/v1/analyses/failing-input",
        json=payload(reference_solution="print('expected')"),
        headers={"X-Internal-Token": "test-token"})
    assert res.status_code == 200
    result = res.json()
    assert result["failure_found"] and result["failure_type"] == "wrong_output"
    assert result["expected_output"] == "expected"
    assert result["actual_output"] == "wrong"
    assert res.headers["X-Failing-Input-Reference"] == "available"


def test_bad_reference_falls_back_to_crash_only(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_TOKEN", "test-token")
    async def fake_batch(p, b, code, inputs, **kwargs):
        rows = [record(i, "crash" if code == "bad ref" else "wrong_output") for i in inputs]
        b.record(len(rows))
        return rows, False
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    res = TestClient(app).post("/v1/analyses/failing-input",
        json=payload(reference_solution="bad ref"),
        headers={"X-Internal-Token": "test-token"})
    assert res.status_code == 200
    assert not res.json()["failure_found"]
    assert res.headers["X-Failing-Input-Reference"] == "unavailable"


def test_budget_exceeded_retains_partial_result(monkeypatch):
    async def fake_batch(p, b, code, inputs, **kwargs):
        b.record(1)
        b.exhausted = True
        return [record(inputs[0], "timeout")], True
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    res = TestClient(app).post("/v1/analyses/failing-input", json=payload())
    assert res.status_code == 200
    assert res.json()["failure_type"] == "timeout"
    assert res.json()["minimization_status"] == "budget_exhausted"
    assert res.json()["tests_executed"] == 1


def test_ai_off_skips_chain_but_runs_edges(monkeypatch):
    async def fake_batch(p, b, code, inputs, **kwargs):
        rows = [record(i) for i in inputs]
        b.record(len(rows))
        return rows, False
    chain_call = AsyncMock(side_effect=AssertionError("AI invoked when off"))
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    monkeypatch.setattr(target.chain, "answer", chain_call)
    res = TestClient(app).post("/v1/analyses/failing-input",
        json=payload(consent=True, ai_enabled=False))
    assert res.status_code == 200 and res.json()["tests_executed"] >= 1
    assert res.json()["ai_step"]["error_code"] == "AI_DISABLED"
    chain_call.assert_not_called()


def test_sandbox_batch_propagates_partial_429(monkeypatch):
    class StubResponse:
        status_code = 429
        def json(self):
            return {"request_id": str(target.uuid.UUID(RID)),
                    "error": {"code": "RUN_BUDGET_EXCEEDED"},
                    "batch_results": [record("1")]}
    class StubClient:
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def post(self, *args, **kwargs): return StubResponse()
    monkeypatch.setenv("INTERNAL_API_TOKEN", "test-token")
    monkeypatch.setattr(target.uuid, "uuid4", lambda: target.uuid.UUID(RID))
    monkeypatch.setattr(target.httpx, "AsyncClient", lambda **kwargs: StubClient())
    b = target.Budget()
    import asyncio
    rows, exceeded = asyncio.run(target.sandbox_batch(
        target.FailingInputRequest(**payload()), b, "print(1)", ["1", "2"]))
    assert exceeded and b.used == 1 and rows[0]["input"] == "1"


def test_generated_script_must_be_sandboxed(monkeypatch):
    async def fake_chain(req):
        assert req.request_type == "failing_input" and req.consent and req.ai_enabled
        return type("R", (), {"answer": "print(json.dumps(['7']))",
                              "ai_step": {"status": "ok", "error_code": None}})()
    calls = []
    async def fake_batch(p, b, code, inputs, **kwargs):
        calls.append((code, inputs, kwargs))
        if kwargs.get("language") == "python" and code.startswith("print(json"):
            rows = [record("", stdout=json.dumps(["7"]))]
        else:
            rows = [record(i) for i in inputs]
        b.record(len(rows))
        return rows, False
    monkeypatch.setattr(target.chain, "answer", fake_chain)
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    res = TestClient(app).post("/v1/analyses/failing-input",
        json=payload(consent=True, ai_enabled=True))
    assert res.status_code == 200
    assert any(kwargs.get("language") == "python" for _, _, kwargs in calls)
    assert any("7" in ins for _, ins, _ in calls)


def test_approved_ai_deterministic_crash_is_not_fallback(monkeypatch):
    async def fake_batch(p, b, code, inputs, **kwargs):
        rows = [record(i, "crash" if i == "" else "pass") for i in inputs]
        b.record(len(rows))
        return rows, False
    forbidden = AsyncMock(side_effect=AssertionError("deterministic failure should not call model"))
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    monkeypatch.setattr(target.chain, "answer", forbidden)
    response = TestClient(app).post("/v1/analyses/failing-input", json=payload(
        consent=True, ai_enabled=True))
    assert response.status_code == 200
    assert response.json()["failure_found"]
    assert response.json()["ai_step"] == {"status": "ok", "error_code": None}
    forbidden.assert_not_called()


def test_fallback_telemetry_when_consent_missing(monkeypatch):
    from app.counters import get_room_usage
    async def fake_batch(p, b, code, inputs, **kwargs):
        rows = [record(i) for i in inputs]
        b.record(len(rows))
        return rows, False
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    before = get_room_usage(ROOM).get("fallback_activations", 0)
    response = TestClient(app).post("/v1/analyses/failing-input", json=payload())
    after = get_room_usage(ROOM).get("fallbacks", 0)
    assert response.status_code == 200
    assert after == before + 1


def test_extract_script_from_conversational_markdown():
    source = "print('[]')"
    newline = chr(10)
    wrapped = "Here is a script:" + newline + "```python" + newline + source + newline + "```"
    assert target._extract_script(wrapped) == source

def test_multilanguage_token_readers():
    for source in (
        "cin >> x >> y;",
        'scanf("%d %d", &x, &y);',
        "Scanner sc = new Scanner(System.in);",
        "const raw = fs.readFileSync(0, 'utf8');",
    ):
        cases, skipped = target.edge_cases(source)
        assert "1 1" in cases and "10 -5 20" in cases
        assert "maximum_bounds (not supplied)" in skipped


def test_failing_input_prompt_recovery_preserves_task():
    from app.chain import ChainRequest, _build_default_prompt, _enforce_prompt_size
    req = ChainRequest(
        request_type="failing_input", language="python",
        code=chr(10).join(["x = 1"] * 1000), pre_sliced=True,
        prompt="X" * 10000,
    )
    shortened = _enforce_prompt_size(req.prompt, req, 550)
    assert shortened is not None
    assert "prints a JSON array" in shortened
    assert "performance issues" not in shortened
    assert "Review and analyze" not in shortened


def test_ai_chain_uses_relevant_code_and_token_budget(monkeypatch):
    from app.chain import ChainRequest
    calls = []
    async def fake_chain(req):
        calls.append(req)
        return type("R", (), {"answer": "print('[]')",
                              "ai_step": {"status": "ok", "error_code": None}})()
    async def fake_batch(p, b, code, inputs, **kwargs):
        rows = [record(i, stdout="[]") for i in inputs]
        b.record(len(rows))
        return rows, False
    monkeypatch.setattr(target.chain, "answer", fake_chain)
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    response = TestClient(app).post("/v1/analyses/failing-input", json=payload(
        consent=True, ai_enabled=True))
    assert response.status_code == 200
    assert len(calls) == 1
    assert isinstance(calls[0], ChainRequest)
    assert calls[0].max_tokens == 1024
    assert calls[0].pre_sliced is True
    assert calls[0].request_type == "failing_input"
