# AI/ML evaluation report — T-24

This is the shared report for the three owners. Results must be measured, not assumed. Other owners can fill their own sections without replacing this file.

## Task 1 — Complexity measurement (T-24a; Prince)

Pending Task 1 owner's measured section.

## Task 2 — Slowdown analysis (T-24a; Prince)

Pending Task 2 owner's measured section.

## Task 3 — Failing-input search and minimisation (T-24b; #222)

### Method

All fixtures are made-up Python programs in `ai-ml-service/tests/task3_evaluation.py`. The evaluator **never executes source on the host**. It makes HTTP requests to `POST /v1/analyses/failing-input`; ai-ml-service then submits source and inputs to execution-service `POST /internal/runs` with `purpose=test_batch`, which executes inside Docker. No real student submissions or paid model calls are used. The trusted reference requires `INTERNAL_API_TOKEN`, which is never logged.

Repeatable mock-only unit checks:

```bash
cd ai-ml-service
pytest -k "evaluation and (failing_input or minimi)"
```

Real Docker evaluation:

```bash
bash tests/integration/up.sh
bash tests/integration/test-t24b-task3-evaluation.sh
```

The existing `tests/integration/run-all.sh` automatically discovers this test. An on-demand workflow in `.github/workflows/task3-evaluation-evidence.yml` uploads `tests/integration/results/task3-evaluation.json` and `task3-evaluation.md`. **Only a completed Docker-backed run provides measured evidence**. Every generated result row gives the expected outcome, observed outcome, pass/fail, SHA, and exact reproduction command. Until actual CI results are recorded, the entries below are expectations, not observations.

### Synthetic scenario matrix

| ID and input | Expected result | Trusted reference | Measured observation |
|---|---|---|---|
| `wrong_output_reference`, duplicate `1 1` | `wrong_output` | Yes | Pending Docker evidence |
| `wrong_output_unreferenced`, same code | No provable wrong output; only crashes/timeouts | No | Pending |
| `crash_empty_unref`, empty input | Crash | No | Pending |
| `crash_empty_ref`, empty input | Crash | Yes | Pending |
| `crash_negative`, `10 -5 20` | Crash, potentially reduced | No | Pending |
| `crash_negative_repeat`, same seed | Same kind and minimized input | No | Pending |
| `timeout_single`, `1` | Timeout | No | Pending |
| `correct_unreferenced`, integer sum | Nothing found in tested inputs, not proof of correctness | No | Pending |
| `correct_reference`, integer sum | No mismatch found in tested inputs | Yes | Pending |
| `host_ai_disabled_correct`, integer sum | `AI_DISABLED`, edge search still runs | No | Pending |
| `consent_disabled_correct`, integer sum | `CONSENT_MISSING`, edge search still runs | No | Pending |

An individual case can be reproduced with:

```bash
python3 ai-ml-service/tests/task3_evaluation.py --case crash_negative \
  --base-url http://localhost:8000 --sha "$(git rev-parse HEAD)" \
  --out tests/integration/results/task3-evaluation.json
```

The JSON artifact includes each case's exact reproduction command and its observed sizes, rounds, candidate count, batch count, skip headers, reference usage, and budget status. The Markdown artifact prints measured rows for the tested commit. Do not transfer results from a different SHA without identifying it.

### Measured Docker evidence — CI #553

This table contains **actual results**, not expected placeholders. They were obtained from the
Docker-backed integration job in [CI run #553](https://github.com/Yash-Oza-ui/collab-code-sandbox/actions/runs/44931090377),
at commit `e161ba9dd54a3191894be1e708ce80ae439f5381`. The run logged
`TASK3_EVAL_SUMMARY passes=11 fails=0`. It did **not** expose total
reference-plus-candidate process counts or direct container-create events, so those are
not retroactively invented. The latest CI artifact provides those fields for its own SHA.

| Scenario | Observed result | Candidate tests | Rounds | Minimizer batches | Original → reduced |
|---|---|---:|---:|---:|---|
| wrong_output_reference | wrong_output | 4 | 1 | 0 | 3 → 3 |
| wrong_output_unreferenced | none found (unreferenced) | 5 | 0 | 0 | — |
| crash_empty_unref | crash | 2 | 0 | 0 | 0 → 0 |
| crash_empty_ref | crash | 2 | 0 | 0 | 0 → 0 |
| crash_negative | crash | 8 | 2 | 1 | 8 → 5 |
| crash_negative_repeat | crash | 8 | 2 | 1 | 8 → 5 |
| timeout_single | timeout | 5 | 0 | 0 | 1 → 1 |
| correct_unreferenced | none found (unreferenced) | 5 | 0 | 0 | — |
| correct_reference | none found with reference | 4 | 0 | 0 | — |
| host_ai_disabled_correct | none found; AI disabled | 5 | 0 | 0 | — |
| consent_disabled_correct | none found; no consent | 5 | 0 | 0 | — |

The generated-input-stage **mock** is separately verified by
`test_evaluation_failing_input_mocked_ai_generated_finds_bug`:
edge cases pass, an approved mocked AI response supplies a synthetic candidate,
and only the simulated `test_batch` response finds its crash. This is **not**
evidence of a real paid model call or a real Docker-generated-script run.

Aggregate instrumentation now records every actual completed sandbox process
(`aggregate_runs_executed`, including reference checks, candidate tests,
script validation and minimizer probes), `aggregate_batch_requests`, and
`aggregate_run_cap`. The evaluator fails if the total exceeds the configured cap.
The candidate-only `tests_executed` metric is retained separately. HTTP
`X-Failing-Input-Aggregate-Runs` and `X-Failing-Input-Run-Cap` are
cross-checked against the JSON body.

A batch-container evidence JSON is produced by
`tests/integration/test-us71-test-batch.sh` using timestamp-bounded
Docker container-create events during its ten-test batch. Its assertions
require exactly one `run-` container-create event and no leftover runtime
containers. Both this JSON and Task 3's measured JSON/Markdown are uploaded
from the CI integration job as run artifacts; see that run's *Artifacts* tab.

### Aggregate and runtime-container measurements — CI #563

[CI #563](https://github.com/Yash-Oza-ui/collab-code-sandbox/actions/runs/44941833481)
ran real Docker test batches at commit `ebe0306f8b2335ee2f2a517b991078a303d7ff6c`.
Its integration job **passed** all eleven Task 3 rows with aggregate accounting enabled,
and GitHub uploaded the `task3-and-batch-evidence` artifact (raw JSON,
measured Markdown, and a separate US-71 Docker event record).

| Scenario | Candidate tests | All sandbox runs / cap | Batch requests | Finding |
|---|---:|---:|---:|---|
| wrong_output_reference | 4 | 8 / 8 | 2 | wrong_output |
| wrong_output_unreferenced | 5 | 5 / 8 | 1 | none; crash/timeout scope |
| crash_empty_unref | 2 | 2 / 8 | 1 | crash |
| crash_empty_ref | 2 | 4 / 8 | 2 | crash |
| crash_negative | 8 | 8 / 8 | 2 | crash, shrunk 8 → 5 |
| crash_negative_repeat | 8 | 8 / 8 | 2 | same reduction |
| timeout_single | 5 | 5 / 8 | 1 | timeout |
| correct_unreferenced | 5 | 5 / 8 | 1 | none found; not proven correct |
| correct_reference | 4 | 8 / 8 | 2 | none found |
| host_ai_disabled_correct | 5 | 5 / 8 | 1 | AI_DISABLED |
| consent_disabled_correct | 5 | 5 / 8 | 1 | CONSENT_MISSING |

In the same Docker CI run, US-71's ten-input batch test recorded
`batch-10-one-runtime-container-created = 1` and passed all US-71
assertions. The separate `us71-container-count.json` artifact records the
container-create count and before/after container snapshots, with its original
commit SHA. These measurements are valid for the cited commit; a subsequent
code amendment requires its own exact-SHA CI evidence, which the CI workflow
automatically uploads. The Python-only and real-model boundaries remain as
stated below.

### Scope and coverage

Conservative deterministic edge inputs are empty stdin and `1`. When the source has a recognisable token reader, the search also tries duplicates (`1 1`), multiple tokens (`1 2 3`), and valid negatives (`10 -5 20`). Maximum bounds, where unspecified, are **skipped with reason**, not invented. When no trusted reference is supplied, a wrong-output result cannot be established; a negative search result must never be described as proof of correctness.

The configured run cap `FAILING_INPUT_MAX_RUNS` is not changed. `tests_executed` counts candidate executions; `aggregate_runs_executed` counts **all completed processes**, including references, generated-script validation, and minimisation. `aggregate_batch_requests` counts outgoing batch HTTP calls, not container starts; the US-71 event evidence measures actual container starts. `minimization_rounds` and `minimization_batches` show how much work the deterministic minimizer completed. If the sandbox returns 429 `RUN_BUDGET_EXCEEDED`, a partial result must remain marked `budget_exhausted` and must not be called globally minimal. The 429 partial response is tested with a mocked batch, **not claimed as an observed real Docker refusal**.

The pair `crash_negative` and `crash_negative_repeat` uses independent requests to verify deterministic minimization results. The US-71 integration test uses Docker event measurements to confirm exactly one runtime container starts per ten-test batch and no runtime container remains. The evidence JSON is stored alongside the Task 3 artifacts. The HTTP evaluator's batch-request count is a separate metric, not an observed container count.

### Consent, Host switch, and model calls

The evaluator's AI-off and consent-off requests still exercise deterministic edge cases. The companion `test_evaluation_failing_input_consent_host_switch_no_paid_model_calls` explicitly mocks the model chain and asserts zero calls. Real Host authorization and live switch broadcasts are covered separately in `tests/integration/test-us59-ai-switch.sh`. The AI-generation-script path is covered by the existing mocked `test_generated_script_must_be_sandboxed` and the new seeded stage-order test, which finds a synthetic crash only after the mocked generated script produces an input. This explicitly satisfies mocked-stage evidence, not paid-model or real-generated-script Docker evidence.

### Unmet claims and next steps

- The earlier Docker run #553 provides the measured baseline table above. This commit's exact-SHA JSON/Markdown, aggregate counts and container events are delivered through the CI artifact and must be checked before approval. The original scenario matrix remains the set of expectations.
- Fixtures currently cover **Python**. Other supported languages require their own measured rows before claiming per-language coverage.
- A real execution-service 429 during search, direct container-start counts, and a combined Host-toggle-to-analysis end-to-end trace are **not independently measured** by this evaluator.
- If any measured row fails, retain the failure and its reason in the raw artifact and file an issue linked to the fixture, commit SHA, and reproduction command. Never hide a failure by changing budgets or fixture conditions.
- Prince should review this section, and Bhavya should confirm batch figures against the US-71 contract.

## Task 4 — Sanitizer/crash explanation (T-24c; Digvijay)

Pending Task 4 owner's measured section.

## Task 5 — Fix verification (T-24c; Digvijay)

Pending Task 5 owner's measured section.

## Consolidated summary table (T-24c)

Pending verified measurements from all section owners.
