"""T-24b evaluation: synthetic source is sent only to the Docker-backed test_batch service.

Never execute fixture programs locally. Logs contain metadata, not submitted source.
"""
from __future__ import annotations
import argparse
import json
import os
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import NAMESPACE_URL, uuid5

ROOM = "Zq8-Lw2Ef6Th0Yx4Rn7JbA"
SUM = "import sys\nnums=list(map(int,sys.stdin.read().split()))\nprint(sum(nums))\n"
WRONG_DUP = "import sys\nnums=list(map(int,sys.stdin.read().split()))\nprint(999 if len(nums)>=2 and nums[0]==nums[1] else sum(nums))\n"
CRASH_EMPTY = "import sys\ns=sys.stdin.read().strip()\nif not s: raise ValueError('synthetic-empty')\nprint('ok')\n"
CRASH_EMPTY_REFERENCE = "import sys\nsys.stdin.read()\nprint('ok')\n"
CRASH_NEGATIVE = "import sys\ntokens=sys.stdin.read().split()\nif '-5' in tokens: raise RuntimeError('synthetic-negative')\nprint('ok')\n"
CRASH_NEGATIVE_REFERENCE = "import sys\nsys.stdin.read()\nprint('ok')\n"
TIMEOUT_SINGLE = "import sys,time\ntokens=sys.stdin.read().split()\nif tokens==['1']: time.sleep(10)\nprint('ok')\n"
TIMEOUT_REFERENCE = "import sys\nsys.stdin.read()\nprint('ok')\n"

@dataclass(frozen=True)
class Scenario:
    key: str
    source: str
    reference: str | None
    consent: bool
    ai_enabled: bool
    expected_kind: str | None
    expected_seed: str | None
    reference_expected: bool
    gate: str

SCENARIOS = (
    Scenario("wrong_output_reference", WRONG_DUP, SUM, False, True,
             "wrong_output", "1 1", True, "CONSENT_MISSING"),
    Scenario("wrong_output_unreferenced", WRONG_DUP, None, False, False,
             None, None, False, "CONSENT_MISSING"),
    Scenario("crash_empty_unref", CRASH_EMPTY, None, False, False,
             "crash", "", False, "CONSENT_MISSING"),
    Scenario("crash_empty_ref", CRASH_EMPTY, CRASH_EMPTY_REFERENCE, False, True,
             "crash", "", True, "CONSENT_MISSING"),
    Scenario("crash_negative", CRASH_NEGATIVE, None, False, False,
             "crash", "10 -5 20", False, "CONSENT_MISSING"),
    Scenario("crash_negative_repeat", CRASH_NEGATIVE, None, False, False,
             "crash", "10 -5 20", False, "CONSENT_MISSING"),
    Scenario("timeout_single", TIMEOUT_SINGLE, None, False, False,
             "timeout", "1", False, "CONSENT_MISSING"),
    Scenario("correct_unreferenced", SUM, None, False, False,
             None, None, False, "CONSENT_MISSING"),
    Scenario("correct_reference", SUM, SUM, False, True,
             None, None, True, "CONSENT_MISSING"),
    Scenario("host_ai_disabled_correct", SUM, None, True, False,
             None, None, False, "AI_DISABLED"),
    Scenario("consent_disabled_correct", SUM, None, False, True,
             None, None, False, "CONSENT_MISSING"),
)
SCENARIO_BY_KEY = {s.key:s for s in SCENARIOS}

def request_body(scenario: Scenario) -> dict:
    data = {
        "request_id":str(uuid5(NAMESPACE_URL,"task3-eval-request:"+scenario.key)),
        "analysis_id":str(uuid5(NAMESPACE_URL,"task3-eval-analysis:"+scenario.key)),
        "room_id":ROOM, "request_type":"failing_input", "language":"python",
        "code":scenario.source, "consent":scenario.consent,
        "ai_enabled":scenario.ai_enabled,
    }
    if scenario.reference is not None: data["reference_solution"]=scenario.reference
    return data

def send(base_url: str, scenario: Scenario, token: str) -> tuple[int,dict,dict]:
    if scenario.reference and not token:
        raise RuntimeError("Authoritative reference requires INTERNAL_API_TOKEN")
    headers={"Content-Type":"application/json"}
    if scenario.reference: headers["X-Internal-Token"]=token
    req=Request(base_url.rstrip("/")+"/v1/analyses/failing-input",
                data=json.dumps(request_body(scenario)).encode("utf-8"),
                headers=headers,method="POST")
    try:
        with urlopen(req,timeout=90) as response:
            return response.status,json.load(response),{k.lower():v for k,v in response.headers.items()}
    except HTTPError as exc:
        try: body=json.loads(exc.read().decode("utf-8"))
        except (ValueError,UnicodeDecodeError): body={"error":"invalid error response"}
        return exc.code,body,{k.lower():v for k,v in exc.headers.items()}
    except URLError as exc:
        raise RuntimeError("Evaluation endpoint unavailable") from exc

def evaluate(base_url: str, scenario: Scenario, token: str, sha: str, max_runs: int) -> dict:
    status,result,headers=send(base_url,scenario,token)
    kind=result.get("failure_type")
    seed=result.get("original_input")
    minimized=result.get("minimized_input")
    tests=result.get("tests_executed")
    rounds=result.get("minimization_rounds")
    batches=result.get("minimization_batches")
    failures=[]
    if status != 200: failures.append("HTTP status "+str(status))
    else:
        if kind != scenario.expected_kind: failures.append("wrong failure type")
        if scenario.expected_kind is not None and seed != scenario.expected_seed:
            failures.append("seed mismatch")
        if result.get("reference_used") is not scenario.reference_expected:
            failures.append("reference reporting mismatch")
        if result.get("ai_step",{}).get("error_code") != scenario.gate:
            failures.append("AI gating mismatch")
        if not isinstance(tests,int) or not 0<=tests<=max_runs:
            failures.append("test count exceeds configured cap")
        if scenario.expected_kind:
            if not isinstance(minimized,str): failures.append("missing minimizer result")
            elif not isinstance(seed,str) or len(minimized)>len(seed):
                failures.append("minimizer increased input size")
            if result.get("minimization_status") not in ("completed","budget_exhausted"):
                failures.append("invalid minimizer status")
        elif result.get("failure_found") is not False:
            failures.append("unexpected failure reported")
        if scenario.key=="crash_negative" and (not isinstance(batches,int) or batches<1):
            failures.append("minimizer did not run")
        if "x-failing-input-skipped-cases" not in headers:
            failures.append("missing skipped-case disclosure")
    reproduce=("python3 ai-ml-service/tests/task3_evaluation.py --case "+
               scenario.key+" --sha "+sha+
               " --base-url <AI_ML_SERVICE_URL> --out <RESULT_JSON>")
    return {
        "id":scenario.key,
        "source_fixture":scenario.key.removesuffix("_repeat"),
        "expected":{"failure":scenario.expected_kind,"seed":scenario.expected_seed,
                    "reference_used":scenario.reference_expected,"ai_error":scenario.gate},
        "observed":{"http":status,"failure":kind,"seed":seed,"minimized":minimized,
                    "minimization_status":result.get("minimization_status"),
                    "original_size":result.get("original_size"),
                    "minimized_size":result.get("minimized_size"),
                    "tests_executed":tests,"minimization_rounds":rounds,
                    "minimization_batches":batches,"reference_used":result.get("reference_used"),
                    "ai_error":result.get("ai_step",{}).get("error_code"),
                    "skipped_cases":headers.get("x-failing-input-skipped-cases",""),
                    "scope":headers.get("x-failing-input-scope",""),
                    "budget":headers.get("x-failing-input-budget","")},
        "result":"PASS" if not failures else "FAIL","failures":failures,
        "commit":sha,"reproduce":reproduce,"cap_runs":max_runs,
        "evidence":"HTTP to ai-ml-service to execution-service Docker test_batch",
    }

def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url",default=os.getenv("AI_ML_SERVICE_URL","http://localhost:8000"))
    parser.add_argument("--sha",default=os.getenv("GITHUB_SHA","unverified"))
    parser.add_argument("--out",required=True,type=Path)
    parser.add_argument("--case",choices=[*SCENARIO_BY_KEY,"all"],default="all")
    parser.add_argument("--report-out",type=Path,default=None)
    parser.add_argument("--max-runs",type=int,default=int(os.getenv("FAILING_INPUT_MAX_RUNS","8")))
    args=parser.parse_args()
    rows=[]
    for scenario in (SCENARIOS if args.case=="all" else (SCENARIO_BY_KEY[args.case],)):
        try: row=evaluate(args.base_url,scenario,os.getenv("INTERNAL_API_TOKEN",""),args.sha,args.max_runs)
        except Exception as exc:
            row={"id":scenario.key,"expected":{"failure":scenario.expected_kind},
                 "observed":{"error_type":type(exc).__name__},
                 "result":"FAIL","failures":[str(exc)[:250]],"commit":args.sha,
                 "reproduce":"python3 ai-ml-service/tests/task3_evaluation.py --case "+
                             scenario.key+" --out <RESULT_JSON>",
                 "evidence":"request failed","cap_runs":args.max_runs}
        rows.append(row)
        info=row.get("observed",{})
        print("TASK3_EVAL_ROW "+json.dumps({
            "id":row["id"],"result":row["result"],
            "failure":info.get("failure"),"tests":info.get("tests_executed"),
            "rounds":info.get("minimization_rounds"),
            "batches":info.get("minimization_batches"),
            "original_size":info.get("original_size"),
            "minimized_size":info.get("minimized_size"),
            "errors":row.get("failures",[])},separators=(",",":")))
    by_id={row["id"]:row for row in rows}
    if "crash_negative" in by_id and "crash_negative_repeat" in by_id:
        a,b=by_id["crash_negative"],by_id["crash_negative_repeat"]
        keys=("failure","minimized","minimization_status","original_size","minimized_size")
        if any(a.get("observed",{}).get(k)!=b.get("observed",{}).get(k) for k in keys):
            b["result"]="FAIL"
            b["failures"].append("non-deterministic minimization across independent runs")
    artifact={"report_version":1,"commit":args.sha,
              "execution":"Docker test_batch via running services",
              "configured_max_runs":args.max_runs,"rows":rows,
              "passes":sum(r["result"]=="PASS" for r in rows),
              "fails":sum(r["result"]=="FAIL" for r in rows)}
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(artifact,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    if args.report_out:
        lines=["# T-24b measured Task 3 results","",
               "Real Docker-backed test_batch; commit "+args.sha,"",
               "| Scenario | Expected | Observed | Result | Tests | Batches | Sizes | SHA | Reproduce |",
               "|---|---|---|---|---:|---:|---|---|---|"]
        for row in rows:
            x=row.get("observed",{})
            lines.append("| "+row["id"]+" | "+str(row.get("expected",{}).get("failure"))+
                " | "+str(x.get("failure"))+" / "+str(x.get("minimization_status"))+
                " | "+row["result"]+" | "+str(x.get("tests_executed"))+
                " | "+str(x.get("minimization_batches"))+
                " | "+str(x.get("original_size"))+" to "+str(x.get("minimized_size"))+
                " | "+args.sha[:10]+" | "+row["reproduce"]+" |")
        args.report_out.parent.mkdir(parents=True,exist_ok=True)
        args.report_out.write_text("\n".join(lines)+"\n",encoding="utf-8")
    print("TASK3_EVAL_SUMMARY passes="+str(artifact["passes"])+" fails="+str(artifact["fails"]))
    return 0 if artifact["fails"]==0 else 1

if __name__=="__main__":
    raise SystemExit(main())
