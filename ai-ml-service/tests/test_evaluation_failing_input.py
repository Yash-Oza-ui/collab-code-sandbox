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
                 minimization_batches=0,minimization_rounds=0,
                 ai_step={"error_code":"CONSENT_MISSING"}),
        {"x-failing-input-skipped-cases":"maximum_bounds (not supplied)"}))
    row=evaluate("http://unused",SCENARIO_BY_KEY["correct_unreferenced"],"","sha",8)
    assert row["result"]=="PASS"
    assert row["commit"]=="sha"
    assert "--case correct_unreferenced" in row["reproduce"]
