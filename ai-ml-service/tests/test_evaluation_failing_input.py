"""T-24b deterministic evaluation tests. All sandbox calls are mocked."""
from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient
from app import failing_input as target
from app.main import app
# Allow both `python -m pytest` and a direct pytest invocation from ai-ml-service.
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from task3_evaluation import SCENARIO_BY_KEY, request_body, evaluate

@pytest.mark.parametrize("key",[
    "wrong_output_reference","crash_negative","correct_unreferenced","timeout_single",
])
def test_evaluation_failing_input_seed_contract_is_deterministic(key):
    a=request_body(SCENARIO_BY_KEY[key])
    b=request_body(SCENARIO_BY_KEY[key])
    assert a==b
    assert a["code"]==SCENARIO_BY_KEY[key].source
    assert len(a["room_id"])==22
    assert a["request_type"]=="failing_input"

def test_evaluation_failing_input_reference_cases():
    yes=request_body(SCENARIO_BY_KEY["wrong_output_reference"])
    no=request_body(SCENARIO_BY_KEY["wrong_output_unreferenced"])
    assert "reference_solution" in yes
    assert "reference_solution" not in no
    assert yes["code"]==no["code"]

@pytest.mark.parametrize("consent,enabled,error",[
    (False,True,"CONSENT_MISSING"),(True,False,"AI_DISABLED"),
])
def test_evaluation_failing_input_consent_host_switch_no_paid_model_calls(
        monkeypatch, consent, enabled, error):
    batches=[]
    async def sandbox_only(p,budget,code,inputs,**_):
        batches.append(list(inputs))
        budget.record(len(inputs))
        return [dict(input=x,status="pass",exit_code=0,stdout="0\n") for x in inputs],False
    forbidden=AsyncMock(side_effect=AssertionError("Paid model was invoked"))
    monkeypatch.setattr(target,"sandbox_batch",sandbox_only)
    monkeypatch.setattr(target.chain,"answer",forbidden)
    data=request_body(SCENARIO_BY_KEY["correct_unreferenced"])
    data.update(consent=consent,ai_enabled=enabled)
    resp=TestClient(app).post("/v1/analyses/failing-input",json=data)
    assert resp.status_code==200,resp.text
    assert not resp.json()["failure_found"]
    assert resp.json()["tests_executed"]>=1
    assert resp.json()["ai_step"]["error_code"]==error
    assert batches
    forbidden.assert_not_called()

def test_evaluation_failing_input_429_partial_and_cap(monkeypatch):
    async def partial_batch(p,budget,code,inputs,**_):
        budget.record(1)
        budget.exhausted=True
        return [dict(input=inputs[0],status="crash",exit_code=1,stdout="")],True
    monkeypatch.setattr(target,"sandbox_batch",partial_batch)
    data=request_body(SCENARIO_BY_KEY["crash_empty_unref"])
    response=TestClient(app).post("/v1/analyses/failing-input",json=data)
    assert response.status_code==200,response.text
    body=response.json()
    assert body["failure_type"]=="crash"
    assert body["tests_executed"]==1
    assert body["minimization_status"]=="budget_exhausted"
    assert body["ai_step"]["error_code"]=="CONSENT_MISSING"

def test_evaluation_failing_input_edge_case_skip_unknown_bounds():
    cases,skipped=target.edge_cases("import sys\nprint(sys.stdin.read().split())")
    assert cases==["","1","1 1","1 2 3","10 -5 20"]
    assert "maximum_bounds (not supplied)" in skipped
    assert "10 -5 20" in cases
    simpler,unknown=target.edge_cases("print(1)")
    assert simpler==["","1"]
    assert "duplicates (format not supplied)" in unknown
    assert "negative_values (format not supplied)" in unknown

def test_evaluation_failing_input_harness_never_executes_source(monkeypatch):
    monkeypatch.setattr("task3_evaluation.send",lambda *args:(
        200,dict(failure_found=False,failure_type=None,original_input=None,
                 minimized_input=None,tests_executed=2,reference_used=False,
                 aggregate_runs_executed=2, aggregate_batch_requests=1, aggregate_run_cap=8,
                 minimization_batches=0,minimization_rounds=0,
                 ai_step={"error_code":"CONSENT_MISSING"}),
        {"x-failing-input-skipped-cases":"maximum_bounds (not supplied)",
         "x-failing-input-aggregate-runs":"2", "x-failing-input-run-cap":"8"}))
    row=evaluate("http://unused",SCENARIO_BY_KEY["correct_unreferenced"],"","sha",8)
    assert row["result"]=="PASS"
    assert row["commit"]=="sha"
    assert "--case correct_unreferenced" in row["reproduce"]

def test_evaluation_failing_input_aggregate_reference_and_candidates(monkeypatch):
    """Trusted reference and candidate processes must both count against the shared cap."""
    class Response:
        status_code = 200
        def __init__(self, data):
            self.data = data
        def json(self):
            return self.data

    calls = []
    class BatchClient:
        async def __aenter__(self):
            return self
        async def __aexit__(self, *args):
            return None
        async def post(self, url, *, json, headers):
            calls.append({"inputs": len(json["inputs"]), "reference": "expected_outputs" not in json})
            rows = [dict(input=value, status="pass", stdout="0\\n", exit_code=0)
                    for value in json["inputs"]]
            return Response({"request_id": json["request_id"], "batch_results": rows})

    monkeypatch.setenv("INTERNAL_API_TOKEN", "unit-only-token")
    monkeypatch.setattr(target.httpx, "AsyncClient", lambda **_kw: BatchClient())
    data = request_body(SCENARIO_BY_KEY["correct_reference"])
    response = TestClient(app).post("/v1/analyses/failing-input", json=data,
                                    headers={"X-Internal-Token": "unit-only-token"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["aggregate_runs_executed"] == sum(c["inputs"] for c in calls)
    assert body["aggregate_runs_executed"] > body["tests_executed"]
    assert body["aggregate_batch_requests"] == len(calls) == 2
    assert body["aggregate_runs_executed"] <= body["aggregate_run_cap"]
    assert int(response.headers["X-Failing-Input-Aggregate-Runs"]) == body["aggregate_runs_executed"]


def test_evaluation_failing_input_mocked_ai_generated_finds_bug(monkeypatch):
    """Only a mocked model and a fake Docker batch are used; no source runs on host."""
    from types import SimpleNamespace

    source = ("import sys\\ntokens=sys.stdin.read().split()\\n"
              "if tokens == ['7', '7']: raise RuntimeError('synthetic')\\n"
              "print('ok')\\n")
    script = """print('["7 7"]')"""
    seen = []

    async def fake_batch(payload, budget, code, inputs, **kwargs):
        stage = "script" if code == script else ("generated" if "7 7" in inputs else "edge")
        seen.append(stage)
        rows = []
        for value in inputs:
            crashed = stage == "generated" and value == "7 7"
            rows.append(dict(input=value, status="crash" if crashed else "pass",
                             exit_code=1 if crashed else 0,
                             stdout='["7 7"]' if stage == "script" else "ok"))
        budget.record(len(rows))
        return rows, False

    mocked_model = AsyncMock(return_value=SimpleNamespace(
        answer=script, ai_step={"status": "ok", "error_code": None}))
    monkeypatch.setattr(target, "sandbox_batch", fake_batch)
    monkeypatch.setattr(target.chain, "answer", mocked_model)
    data = request_body(SCENARIO_BY_KEY["correct_unreferenced"])
    data.update(code=source, consent=True, ai_enabled=True)
    response = TestClient(app).post("/v1/analyses/failing-input", json=data)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["failure_type"] == "crash"
    assert result["original_input"] == "7 7"
    assert seen[:3] == ["edge", "script", "generated"]
    mocked_model.assert_awaited_once()
    assert result["tests_executed"] >= 6
