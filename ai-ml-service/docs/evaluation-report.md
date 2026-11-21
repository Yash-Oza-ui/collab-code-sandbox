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

### Scope and coverage

Conservative deterministic edge inputs are empty stdin and `1`. When the source has a recognisable token reader, the search also tries duplicates (`1 1`), multiple tokens (`1 2 3`), and valid negatives (`10 -5 20`). Maximum bounds, where unspecified, are **skipped with reason**, not invented. When no trusted reference is supplied, a wrong-output result cannot be established; a negative search result must never be described as proof of correctness.

The configured run cap `FAILING_INPUT_MAX_RUNS` is not changed. `tests_executed` counts candidate executions; reference executions also consume shared budget slots. `minimization_rounds` and `minimization_batches` show how much work the deterministic minimizer completed. If the sandbox returns 429 `RUN_BUDGET_EXCEEDED`, a partial result must remain marked `budget_exhausted` and must not be called globally minimal. The 429 partial response is tested with a mocked batch, **not claimed as an observed real Docker refusal**.

The pair `crash_negative` and `crash_negative_repeat` uses independent requests to verify deterministic minimization results. The separate US-71 real integration test verifies the one-container-per-batch invariant; this HTTP evaluator does not directly count container starts.

### Consent, Host switch, and model calls

The evaluator's AI-off and consent-off requests still exercise deterministic edge cases. The companion `test_evaluation_failing_input_consent_host_switch_no_paid_model_calls` explicitly mocks the model chain and asserts zero calls. Real Host authorization and live switch broadcasts are covered separately in `tests/integration/test-us59-ai-switch.sh`. The AI-generation-script path is covered by the existing mocked `test_generated_script_must_be_sandboxed`; no paid model is called for this report.

### Unmet claims and next steps

- Real measured observations are not populated into this committed report until Docker CI produces evidence. Treat pending rows as **not met**, not passed.
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
