# Sprint 5 AI/ML Service Evaluation Report (T-24)

> **Consolidated Evaluation Document for Sprint 5**
> Covers Tasks 1 & 2 (T-24a; Prince), Task 3 (T-24b; #222), and Tasks 4 & 5 (T-24c; Digvijay).
> Tracking Issues: #222, #223

---

## Shared Report Overview

This document provides empirical evaluation evidence for all AI/ML subsystems implemented during Sprint 5. All evaluations are performed using synthetic, sanitized code and mock model clients to guarantee zero paid API expenditures and zero exposure of private user data or student code.

This is the shared report for the three owners. Results must be measured, not assumed. Other owners can fill their own sections without replacing this file.

- **T-24a (Tasks 1 & 2)** — Complexity Analysis & Slowdown Detection *(Reserved for Prince; Pending)*
- **T-24b (Task 3)** — Failing Input Search & Containerized Test Batching *(Contributed by Task 3 Owner, #222)*
- **T-24c (Tasks 4 & 5)** — Sanitizer Crash Parsing, Plain-Language Crash Explanation, AI Usage Counters, Monthly AI Cost Cap, Fallback Behavior & Multi-Room Load Evaluation *(Contributed by Digvijay, #223)*

---

## Overall Evaluation Summary

| Subsystem / Evaluation Task | Owner | Implementation Scope | Evaluation Status | Empirical Evidence & Methodology |
|---|---|---|---|---|
| **T-24a: Task 1** — Complexity Measurement | Prince | US-65 | **PENDING** | Pending Task 1 owner's measured section |
| **T-24a: Task 2** — Slowdown Analysis | Prince | US-68 | **PENDING** | Pending Task 2 owner's measured section |
| **T-24b: Task 3** — Failing-Input Search & Minimisation | Task 3 Owner (#222) | US-70, US-71, US-72 | **COMPLETED** | Verified via Docker CI runs #553 and #563 (11/11 passing); US-71 single container batch verified |
| **T-24c: Task 4** — Sanitizer Crash Parsing & Explanation | Digvijay (#223) | US-73, US-74, US-76, US-22 | **COMPLETED** | 16/16 sanitizer parsing matrix PASS; 3-sentence limit & canary isolation PASS; cache hits PASS |
| **T-24c: Task 5** — Usage Counters, Cost Cap, Fallback & Concurrency | Digvijay (#223) | US-75, US-24, US-78 | **COMPLETED** | Multi-room counters PASS; atomic cost cap reservations PASS; fallback mapping PASS; concurrency & stampede analysis documented |

> [!NOTE]
> **Consolidated Evaluation Summary Status:** In accordance with evaluation guidelines, results are measured and not assumed. Because T-24a (Tasks 1 & 2) measurements have not yet been submitted by the Task 1 & 2 owner, the overall summary table remains partially incomplete with T-24a explicitly marked as **PENDING**. Detailed empirical measurements for T-24b and T-24c are fully reported in their respective sections below.

---

## T-24a — Complexity Analysis & Slowdown Detection (Tasks 1 & 2)

> *Status: PENDING MERGE — To be populated by Task 1 & 2 owner (Prince) upon branch consolidation.*
> *(Placeholder preserved for T-24a evaluation matrix, empirical observations, and commit references.)*

### Task 1 — Complexity measurement (T-24a; Prince)

Pending Task 1 owner's measured section.

### Task 2 — Slowdown analysis (T-24a; Prince)

Pending Task 2 owner's measured section.

---

## T-24b — Task 3: Failing-Input Search and Minimisation (#222)

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

---

## T-24c — Tasks 4 & 5 Evaluation Report (Digvijay; #223)

**Author / Evaluator:** Task T-24c Engineer
**Evaluation Target:** Task 4 (US-73, US-74) & Task 5 (US-75, US-24, US-78)
**Evaluation Branch:** `docs/T-24c-evaluation-tasks-4-5`
**Tested Predecessor Commit:** `7d19ecf77f6f52ce8eec2869b1302e557010a31f` (Merged US-74 and US-78 on `origin/main`)
**Audit Commit:** `34b66cb`
**Pull Request Context:** Part of Issue #223 (Sprint 5 Consolidated Evaluation)

---

### 1. Environment and Commit Tested

| Component | Specification / Version | Classification / Operational Notes |
|---|---|---|
| **Repository** | `Yash-Oza-ui/collab-code-sandbox` | Active worktree |
| **Commit Evaluated** | `7d19ecf77f6f52ce8eec2869b1302e557010a31f` | Merged US-74 & US-78 on top of `origin/main` (`103f0c9`) |
| **Operating System** | Windows 11 Pro (x86_64 / AMD64) | Local development host |
| **Python Runtime** | Python 3.13.14 (Store distribution) | `py -3.13` launcher |
| **Test Framework** | pytest 8.3.3, anyio 4.15.1, pluggy 1.6.0 | 426 suite tests passing |
| **Web Framework** | FastAPI 0.115.x, Starlette 0.41.x, httpx 0.28.x | In-process ASGI `TestClient` |
| **Execution Environment** | In-process multi-threaded ASGI simulation | **Not external TCP network load testing** |
| **Docker Sandbox** | Docker Engine CLI / daemon | **BLOCKED — Not installed / unavailable on local host** |
| **Execution Service** | Spring Boot 3.3.x / Java 17 | Verified via recorded Surefire unit test reports |
| **Model Invocations** | Mocked `LLMClient` instances | **Zero paid calls** (100% mocked; no paid API tokens) |

---

### 2. Task 4 — Sanitizer Crash Parsing Matrix (US-73)

The sanitizer parsing matrix evaluates deterministic parsing of AddressSanitizer (ASan) and UndefinedBehaviorSanitizer (UBSan) diagnostics into exact `error_type` and `line_number` without invoking any external AI or model services (FR39.1, FR39.2, DR5, US-73).

> [!IMPORTANT]
> **Evaluation Path Distinction:**
> - **Parser Unit Test Status:** Evaluates production parser `parse_sanitizer_report()` in `ai-ml-service/app/sanitizer.py`.
> - **Docker Sandbox Execution Status:** Evaluates compiling and executing within the containerized sandbox. Because Docker is not installed on the local test host, container execution is recorded as **BLOCKED**.

| Test ID | Error Category | Language & Minimal Synthetic Fixture | Expected Type / Line | Observed Type / Line | Parser Unit Status | Docker Execution Status | Command | Evidence / Output |
|---|---|---|---|---|---|---|---|---|
| **SAN-01** | ASan: Heap Buffer Overflow | C++: `std::vector<int> v={1,2,3}; return v[5];` | `heap-buffer-overflow` / Line 5 | `heap-buffer-overflow` / Line 5 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-01]` | `summary="Detected heap-buffer-overflow at line 5."` |
| **SAN-02** | ASan: Stack Buffer Overflow | C: `char buf[8]; strcpy(buf, "long string");` | `stack-buffer-overflow` / Line 6 | `stack-buffer-overflow` / Line 6 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-02]` | `summary="Detected stack-buffer-overflow at line 6."` |
| **SAN-03** | ASan: Global Buffer Overflow | C++: `int g[5]; int main() { return g[10]; }` | `global-buffer-overflow` / Line 4 | `global-buffer-overflow` / Line 4 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-03]` | `summary="Detected global-buffer-overflow at line 4."` |
| **SAN-04** | ASan: Heap Use After Free | C++: `int* p = new int(1); delete p; return *p;` | `heap-use-after-free` / Line 6 | `heap-use-after-free` / Line 6 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-04]` | `summary="Detected heap-use-after-free at line 6."` |
| **SAN-05** | ASan: Double Free | C: `void* p = malloc(8); free(p); free(p);` | `double-free` / Line 6 | `double-free` / Line 6 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-05]` | `summary="Detected double-free at line 6."` |
| **SAN-06** | ASan: Stack Use After Return | C++: `int* f() { int x=1; return &x; }` | `stack-use-after-return` / Line 7 | `stack-use-after-return` / Line 7 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-06]` | `summary="Detected stack-use-after-return at line 7."` |
| **SAN-07** | ASan: Stack Use After Scope | C++: `int* p; { int x=1; p=&x; } return *p;` | `stack-use-after-scope` / Line 7 | `stack-use-after-scope` / Line 7 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-07]` | `summary="Detected stack-use-after-scope at line 7."` |
| **SAN-08** | ASan: Init Order Fiasco | C++: Cross-TU global static reference | `initialization-order-fiasco` / Line 12 | `initialization-order-fiasco` / Line 12 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-08]` | `summary="Detected initialization-order-fiasco at line 12."` |
| **SAN-09** | UBSan: Signed Int Overflow | C: `int x = INT_MAX; return x + 1;` | `signed-integer-overflow` / Line 5 | `signed-integer-overflow` / Line 5 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-09]` | `summary="Detected signed-integer-overflow at line 5."` |
| **SAN-10** | UBSan: Shift Out of Bounds | C: `int a = 1; return a << 32;` | `shift-out-of-bounds` / Line 4 | `shift-out-of-bounds` / Line 4 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-10]` | `summary="Detected shift-out-of-bounds at line 4."` |
| **SAN-11** | UBSan: Index Out of Bounds | C++: `int arr[5]; return arr[10];` | `index-out-of-bounds` / Line 3 | `index-out-of-bounds` / Line 3 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-11]` | `summary="Detected index-out-of-bounds at line 3."` |
| **SAN-12** | UBSan: Null Dereference | C: `int* p = NULL; return *p;` | `null-pointer-dereference` / Line 3 | `null-pointer-dereference` / Line 3 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-12]` | `summary="Detected null-pointer-dereference at line 3."` |
| **SAN-13** | UBSan: Division by Zero | C: `int zero = 0; return 100 / zero;` | `division-by-zero` / Line 3 | `division-by-zero` / Line 3 | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-13]` | `summary="Detected division-by-zero at line 3."` |
| **SAN-14** | Clean Execution | C++: `int main() { return 0; }` | `None` / `None` | `None` / `None` | **PASS** | N/A (clean run) | `pytest -k test_sanitizer_matrix_parsing[SAN-14]` | `summary=""`, clean execution yields no error |
| **SAN-15** | Non-Sanitizer Crash | C: `raise(SIGSEGV);` (no ASan diagnostics) | `None` / `None` | `None` / `None` | **PASS** | **BLOCKED** | `pytest -k test_sanitizer_matrix_parsing[SAN-15]` | `summary="Process exited with exit code 139 (no sanitizer finding)."` |
| **SAN-16** | Unsupported Language | Python submitted to crash explanation endpoint | HTTP 422 `UNSUPPORTED_LANGUAGE` | HTTP 422 `UNSUPPORTED_LANGUAGE` | **PASS** | N/A (API validation) | `pytest -k test_sanitizer_unsupported_language_rejection` | Response code 422 with structured error envelope |

---

### 3. Task 4 — Plain-Language Crash Explanation Evaluation (US-74)

Evaluated the plain-language crash explanation pipeline using a mocked LLM client with representative sanitizer crash diagnostics (FR13.1, FR39.3, FR40.2, DR5, US-74).

> [!NOTE]
> **Mocked Component Disclosure:**
> In accordance with safety rules (no paid LLM API calls), the outbound provider request is mocked using `AsyncMock`. The evaluation verifies prompt generation, deterministic summary inclusion, response parsing, sentence truncation, suggested fix extraction, and schema compliance. It does not measure external LLM hallucination rates or provider latency.

| Test ID | Verification Criteria | Expected Result | Observed Result | Status | Command | Evidence |
|---|---|---|---|---|---|---|
| **EXP-01** | Correct Crash Identification | Identifies error type and source line from summary | `error_type="heap-buffer-overflow"`, `line_number=5` | **PASS** | `pytest -k test_crash_explanation_identification_and_limits` | Response: `summary="Detected heap-buffer-overflow at line 5."` |
| **EXP-02** | 3-Sentence Short-Answer Limit | Enforces FR13.1 constraint (`<= 3` sentences) on a 5-sentence mock response | Truncates to exactly **3 sentences**; sentences 4 and 5 pruned | **PASS** | `pytest -k test_crash_explanation_identification_and_limits` | `len(split_sentences(explanation)) == 3`, sentences 4 and 5 absent |
| **EXP-03** | Suggested Fix Extraction | Produces actionable remediation advice | Non-empty fix string extracted from raw LLM output | **PASS** | `pytest -k test_crash_explanation_identification_and_limits` | `suggested_fix="Verify index bounds before accessing: if (index < v.size()) std::cout << v[index];"` |
| **EXP-04** | Response Schema Conformance | Matches T-18 v2 `CrashExplanationResponse` schema | Response contains `task`, `summary`, `error_type`, `line_number`, and `ai_step` envelope | **PASS** | `pytest -k test_crash_explanation_identification_and_limits` | HTTP 200 with schema-valid JSON envelope |
| **EXP-05** | Fallback on Provider Error | Skips AI step with `PROVIDER_ERROR` if LLM raises | `status="skipped"`, `error_code="PROVIDER_ERROR"`, deterministic summary preserved | **PASS** | `pytest -k test_fallback_provider_error` | `error_code="PROVIDER_ERROR"`, deterministic `error_type` intact |

---

### 4. Task 4 — Relevant-Code Isolation and Canary Marker Tests

To guarantee privacy and data minimization (FR40.2, US-74, US-76, NFR8.2), synthetic canary strings were embedded in a 50-line C++ file outside the active crash section (line 25):
- Top Canary: `CANARY_OUTSIDE_24C_A` (Line 1)
- Crashing Line: `int crash_val = buffer[idx];` (Line 25)
- Bottom Canary: `CANARY_SECRET_24C_B` (Line 50)

| Test ID | Target Property | Expected Prompt Content | Observed Prompt Content | Status | Command | Evidence |
|---|---|---|---|---|---|---|
| **CAN-01** | Top Canary Isolation | Top canary excluded from LLM prompt | `CANARY_OUTSIDE_24C_A` NOT present | **PASS** | `pytest -k test_canary_isolation_and_relevant_code_slicing` | `assert "CANARY_OUTSIDE_24C_A" not in prompt` |
| **CAN-02** | Bottom Canary Isolation | Bottom canary excluded from LLM prompt | `CANARY_SECRET_24C_B` NOT present | **PASS** | `pytest -k test_canary_isolation_and_relevant_code_slicing` | `assert "CANARY_SECRET_24C_B" not in prompt` |
| **CAN-03** | Relevant Code Containment | Relevant localized code slice present | `int crash_val = buffer[idx];` present | **PASS** | `pytest -k test_canary_isolation_and_relevant_code_slicing` | Crashing line located in sliced prompt window |
| **CAN-04** | Metadata Minimization | Raw ASan stack trace & container metadata excluded | `==2001==ERROR` and `#0 0x401233` NOT present | **PASS** | `pytest -k test_canary_isolation_and_relevant_code_slicing` | Prompt strictly contains localized code and deterministic summary |
| **CAN-05** | Telemetry Canary Isolation | Canaries absent from metrics and telemetry | Canaries absent from room usage and global totals | **PASS** | `pytest -k test_canary_isolation_and_relevant_code_slicing` | `assert canary not in str(get_room_usage())` |
| **CAN-06** | Logger Message Isolation | Canaries absent from server logs | Canaries absent from `caplog.records` | **PASS** | `pytest -k test_canary_isolation_and_relevant_code_slicing` | `assert canary not in " ".join(r.getMessage() for r in caplog.records)` |

---

### 5. Task 4 — Cache Behavior Evaluation

Evaluated caching behavior in `POST /v1/analyses/crash-explanation` (US-22, US-74, US-77 Stage 1).

| Test ID | Sequence Step | Cache State | Model Invocation Count | Observed Result | Status | Command | Evidence |
|---|---|---|---|---|---|---|---|
| **CACHE-01** | First Request (cold key) | Cache MISS | Increments by 1 | Model called, response generated and cached | **PASS** | `pytest -k test_crash_explanation_cache_hit_and_miss` | `model_call_count == 1`, `cache_hits == 0` |
| **CACHE-02** | Second Request (identical key) | Cache HIT | Remains unchanged (+0) | Cached explanation & fix returned instantly | **PASS** | `pytest -k test_crash_explanation_cache_hit_and_miss` | `model_call_count == 1`, `cache_hits == 1` |
| **CACHE-03** | Response Content Preservation | Cache HIT | N/A | Identical explanation and fix fields returned | **PASS** | `pytest -k test_crash_explanation_cache_hit_and_miss` | `resp2.json()["ai_step"] == resp1.json()["ai_step"]` |

---

### 6. Task 5 — AI Usage Counters Evaluation (US-75)

Evaluated counter tracking across multi-room synthetic workloads (FR40.1, US-75, NFR8.2).

**Controlled Input Sequence:**
- **Room A:** 3 model calls (100 tokens each), 2 cache hits, 1 fallback activation
- **Room B:** 2 model calls (200 tokens each), 1 cache hit, 2 fallback activations

| Test ID | Target Counter | Expected Count | Observed Count | Status | Command | Evidence |
|---|---|---|---|---|---|---|
| **CNT-01** | Room A Model Calls | 3 | 3 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `usage_a["model_calls"] == 3` |
| **CNT-02** | Room A Cache Hits | 2 | 2 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `usage_a["cache_hits"] == 2` |
| **CNT-03** | Room A Fallbacks | 1 | 1 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `usage_a["fallback_activations"] == 1` |
| **CNT-04** | Room A Token Usage | 300 | 300 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `usage_a["tokens_used"] == 300` |
| **CNT-05** | Room B Model Calls | 2 | 2 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `usage_b["model_calls"] == 2` |
| **CNT-06** | Room B Cache Hits | 1 | 1 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `usage_b["cache_hits"] == 1` |
| **CNT-07** | Room B Fallbacks | 2 | 2 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `usage_b["fallback_activations"] == 2` |
| **CNT-08** | Room B Token Usage | 400 | 400 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `usage_b["tokens_used"] == 400` |
| **CNT-09** | Global Total Model Calls | 5 | 5 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `total["total_model_calls"] == 5` |
| **CNT-10** | Global Total Cache Hits | 3 | 3 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `total["total_cache_hits"] == 3` |
| **CNT-11** | Global Total Fallbacks | 3 | 3 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `total["total_fallback_activations"] == 3` |
| **CNT-12** | Global Total Tokens | 700 | 700 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `total["total_tokens_used"] == 700` |
| **CNT-13** | Room Teardown Isolation | Clear Room A; Room B & Totals intact | Room A deleted; Room B has 2 calls; Total remains 5 | **PASS** | `pytest -k test_usage_counters_controlled_sequence_and_aggregation` | `clear_room("room-eval-a")` removes Room A only |

---

### 7. Task 5 — Monthly AI Cost Cap & Concurrent Reservations (US-24)

Evaluated monthly external AI cost cap enforcement, 80% budget threshold alerts, and atomic concurrent reservation behavior (NFR7.1, NFR7.2, NFR7.4, NFR7.5, US-24).
*Test Configuration:* Budget = `$1.00`, Token Cost = `$0.001` per token (1,000 tokens = `$1.00`).

| Test ID | Budget Stage | Cumulative Spend | Expected Behavior | Observed Behavior | Status | Command | Evidence |
|---|---|---|---|---|---|---|---|
| **CAP-01** | Below 80% Threshold | $0.70 (700 tokens) | Request admitted, alert flag `False` | Request admitted, spend `$0.70`, alert flag `False` | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | `tracker.alert_80_percent_triggered is False` |
| **CAP-02** | Crossing 80% Threshold | $0.85 (850 tokens) | Request admitted, alert log triggered once | Request admitted, spend `$0.85`, alert triggered | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | `AI_COST_CAP_ALERT: spend_usd=0.8500 budget_usd=1.0000` |
| **CAP-03** | Duplicate Alert Suppression | $0.95 (950 tokens) | No duplicate alert in same month | 0 additional alert log entries emitted | **PASS** | `pytest ai-ml-service/tests/test_cost_cap.py -k test_duplicate_alert` | Deduplication invariant preserved |
| **CAP-04** | At Budget Cap | $1.00 (1,000 tokens) | `is_cost_cap_reached() == True` | `is_cost_cap_reached() == True`, spend `$1.00` | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | `tracker.cost_cap_reached is True` |
| **CAP-05** | Sequential Rejection on Cap | $1.00 + new call | Reservation blocked with `CostCapReachedError` | Reservation raises `CostCapReachedError`; 0 model calls | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | Next outbound call rejected before provider dispatch |
| **CAP-06** | Concurrent Reservation Race | Budget headroom for 2 calls (10 threads under barrier) | Exactly 2 admitted, 8 rejected, no budget overshoot | Exactly 2 admitted, 8 rejected, active reserved = 200 tokens | **PASS** | `pytest -k test_concurrent_cost_cap_reservations_prevent_overshoot` | Atomic lock prevents concurrent over-allocation |

---

### 8. Task 5 — Fallback Behavior Evaluation (US-78)

Evaluated all fallback triggers across `ai-ml-service` and `execution-service` (FallbackMessageMapper / T-18 Decision 10 / FR40.5).

| Test ID | Trigger Condition | Expected Error Code | Expected User-Facing Fallback Message | Observed Error Code / Status | Status | Command | Evidence |
|---|---|---|---|---|---|---|---|
| **FB-01** | Consent missing (`consent=False`) | `CONSENT_MISSING` | *"AI analysis is unavailable because AI consent is turned off."* | `CONSENT_MISSING` / `skipped` | **PASS** | `pytest -k test_fallback_consent_missing` | Deterministic analysis preserved; AI step skipped |
| **FB-02** | AI disabled by Host switch (US-59) | `AI_DISABLED` | *"AI analysis is currently disabled."* | `AI_DISABLED` / `skipped` *(API logic)* | **PASS** *(API)*<br>**BLOCKED** *(US-59 UI)* | `pytest -k test_fallback_ai_disabled` | API verified; full UI toggle blocked by US-59 unavailability |
| **FB-03** | Monthly cost cap reached | `COST_CAP_REACHED` | *"AI analysis is temporarily unavailable because the monthly AI limit has been reached."* | `COST_CAP_REACHED` / `skipped` | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | Safe student-friendly message mapped |
| **FB-04** | AI/ML service down / Provider error | `PROVIDER_ERROR` or `SERVICE_UNAVAILABLE` | *"AI analysis service is temporarily unavailable."* / *"AI analysis could not be completed right now."* | `PROVIDER_ERROR` / `skipped` | **PASS** | `pytest -k test_fallback_provider_error` | External exception caught and mapped to fallback |
| **FB-05** | Non-Blocking Execution Preservation | None (Normal run) | Execution output unaffected by AI latency or failure | Run output returned immediately; AI is async (NFR9) | **PASS** | `tests/integration/test-ai-fallback-message.sh` | Normal run returns 200 without waiting for AI analysis |

---

### 9. Task 5 — Concurrent Multi-Room Load Evaluation & Stampede Analysis

A multi-room synthetic concurrency evaluation was conducted across 4 isolated rooms using 8 worker threads:
- **Simulation Setup:** 4 rooms (`room-load-alpha`, `room-load-beta`, `room-load-gamma`, `room-load-delta`), 8 worker threads in an in-process ASGI thread pool.
- **Stage 1 (Cold Cache):** 16 distinct requests across 4 rooms (4 per room) -> Expected: 16 model calls.
- **Stage 2 (Warm Cache):** 16 duplicate requests across 4 rooms (4 per room) -> Expected: 16 cache hits.
- **Stage 3 (Fallbacks):** 8 requests with `consent=False` (2 per room) -> Expected: 8 fallback activations.
- **Total Invocations:** 40 requests.

| Metric / Stage | Expected Count | Observed Count | Observed p50 Latency | Observed p95 Latency | Observed Max Latency | Status |
|---|---|---|---|---|---|---|
| **Cold Cache Requests (Model Calls)** | 16 | 16 | 37.42 ms | 47.51 ms | 47.51 ms | **PASS** |
| **Warm Cache Requests (Cache Hits)** | 16 | 16 | 25.77 ms | 40.16 ms | 40.16 ms | **PASS** |
| **Fallback Requests (`consent=False`)** | 8 | 8 | 23.54 ms | 25.42 ms | 25.42 ms | **PASS** |
| **Total Platform Requests** | 40 | 40 | — | — | — | **PASS** |
| **Rooms Tracked in Telemetry** | 4 | 4 | — | — | — | **PASS** |
| **Per-Room Counters Consistency** | 4 calls, 4 hits, 2 fallbacks per room | Exact match in all 4 rooms | — | — | — | **PASS** |

#### Cold-Cache Stampede Finding (Empirical Analysis)

When identical requests arrive simultaneously on an unpopulated (cold) cache before the first response completes, each concurrent thread experiences a cache miss and invokes the model.

In an uncoordinated test of 4 simultaneous identical requests on an empty cache:
- **Model calls observed:** 4 (instead of 1 call and 3 hits)
- **Cache hits observed:** 0
- **Counter integrity:** `UsageTracker` correctly and thread-safely recorded all 4 calls (200 tokens) with zero counter drift or memory race.
- **Subsequent sequential request:** Hit the warm cache with 0 additional model calls.

> [!TIP]
> **Architectural Implication:**
> This behavior is expected in an in-memory cache without a single-flight mutex per key. For benchmarks isolating cache lookup latency from LLM latency, traffic must be staged or the cache pre-warmed.

---

### 10. Issues Discovered

1. **Host Docker Daemon Missing for C/C++ Container Sandboxes:**
   Live container execution requires a running Docker daemon to spawn isolated compiler containers (`-fsanitize=address,undefined -no-pie -g -O1`). Because Docker is not installed on this Windows development environment, live container execution cannot run locally. Parser unit tests and execution limits property tests pass deterministically, but end-to-end container testing must run on a Docker-capable CI runner.
2. **Cold-Cache Concurrent Stampede Window:**
   Because the in-memory cache does not employ single-flight / mutex-per-key deduplication during in-flight model queries, concurrent identical requests arriving simultaneously on an empty cache will each query the model. Counters remain strictly consistent, but duplicated LLM spend can occur in burst scenarios.
3. **US-59 Host AI Switch Availability:**
   US-59 (Host AI toggle switch UI) is not present in the current branch. The backend API gate (`ai_enabled=False`), counter tracking (`fallback_activations`), and frontend fallback mapping (`MSG_AI_DISABLED`) were verified, but the full end-to-end UI switch test remains blocked until US-59 is merged.
4. **In-Process ASGI Simulation Scope:**
   Concurrency was evaluated using FastAPI's ASGI `TestClient` over a multi-threaded Python worker pool with a mocked model delay. This validates internal application thread safety, locks, cache lookups, and counter increments, but it does not measure external TCP socket overhead, network latency, or reverse-proxy saturation.

---

### 11. Reproduction Instructions

To reproduce all evaluations deterministically:

```bash
# 1. Switch to evaluation branch
git checkout docs/T-24c-evaluation-tasks-4-5

# 2. Run the full pytest test suite (426 tests)
py -3.13 -m pytest ai-ml-service/tests

# 3. Run the evaluation test suite specifically (27 tests)
py -3.13 -m pytest ai-ml-service/tests/test_t24c_evaluation.py -v

# 4. Run the standalone evaluation runner script with detailed metrics
py -3.13 scripts/evaluate_t24c.py
```

---

### 12. Limitations and Blocked Tests

| Item | Status | Root Blocker | Remediation Path |
|---|---|---|---|
| **Live Docker Sandbox Execution** | **BLOCKED** | Docker daemon not running in local Windows environment | Run `tests/integration/test-execution-sanitizer.sh` in a Linux CI runner with Docker daemon enabled. |
| **US-59 Host AI Switch UI** | **BLOCKED** | Feature branch for US-59 not yet merged into working branch | Re-run UI toggle test once US-59 is merged. Backend API logic (`ai_enabled=False`) is fully tested and PASSING. |
| **Consolidated Evaluation Summary** | **PARTIALLY COMPLETED (PENDING T-24a)** | T-24a (Tasks 1 & 2) report must be completed and merged | Merge T-24a report into evaluation-report.md once Prince submits Task 1 & 2 results before final sprint closure. |

---

## Consolidated Summary Table (T-24c)

> *Pending verified measurements from all section owners (T-24a pending; T-24b and T-24c documented in sections above).*
