# Sprint 5 AI/ML Service Evaluation Report

> **Consolidated Evaluation Document for Sprint 5**
> Covers Tasks 1 & 2 (T-24a), Task 3 (T-24b), and Tasks 4 & 5 (T-24c).
> Tracking Issue: #223

---

## Shared Report Overview

This document provides empirical evaluation evidence for all AI/ML subsystems implemented during Sprint 5. All evaluations are performed using synthetic, sanitized code and mock model clients to guarantee zero paid API expenditures and zero exposure of private user data or student code.

- **Section 1: T-24a (Tasks 1 & 2)** — Complexity Analysis & Slowdown Detection *(Reserved for T-24a)*
- **Section 2: T-24b (Task 3)** — Failing Input Search & Containerized Test Batching *(Reserved for T-24b)*
- **Section 3: T-24c (Tasks 4 & 5)** — Sanitizer Crash Parsing, Plain-Language Crash Explanation, AI Usage Counters, Monthly AI Cost Cap, Fallback Behavior & Multi-Room Load Evaluation

---

## Section 1: T-24a — Complexity Analysis & Slowdown Detection (Tasks 1 & 2)

> *Status: PENDING MERGE — To be populated by T-24a author upon branch consolidation.*

*(Placeholder preserved for T-24a evaluation matrix, empirical observations, and commit references.)*

---

## Section 2: T-24b — Failing Input Search & Containerized Test Batching (Task 3)

> *Status: PENDING MERGE — To be populated by T-24b author upon branch consolidation.*

*(Placeholder preserved for T-24b evaluation matrix, empirical observations, and commit references.)*

---

## Section 3: T-24c — Tasks 4 & 5 Evaluation Report

**Author / Evaluator:** Task T-24c Engineer
**Evaluation Target:** Task 4 (US-73, US-74) & Task 5 (US-75, US-24, US-78)
**Evaluation Branch:** `docs/T-24c-evaluation-tasks-4-5`
**Evaluation Commit:** `7d19ecf77f6f52ce8eec2869b1302e557010a31f`
**Pull Request Context:** Part of Issue #223 (Sprint 5 Consolidated Evaluation)

---

### 1. Environment and Commit Tested

| Component | Specification / Version | Status / Notes |
|---|---|---|
| **Repository** | `Yash-Oza-ui/collab-code-sandbox` | Active worktree |
| **Commit Hash** | `7d19ecf77f6f52ce8eec2869b1302e557010a31f` | Merged US-74 & US-78 on top of main (`103f0c9`) |
| **Operating System** | Windows 11 Pro (x86_64 / AMD64) | Local development host |
| **Python Runtime** | Python 3.13.14 (Store distribution) | `py -3.13` launcher |
| **Test Framework** | pytest 8.3.3, anyio 4.15.1, pluggy 1.6.0 | 424 suite tests passing |
| **Web Framework** | FastAPI 0.115.x, Starlette 0.41.x, httpx 0.28.x | In-process ASGI `TestClient` |
| **Docker Sandbox** | Docker Engine CLI / daemon | **Not installed / unavailable on local host** |
| **Execution Service** | Spring Boot 3.3.x / Java 17 | Unit tests verified via recorded Surefire reports |
| **Paid API Calls** | Gemini API / OpenAI API | **Zero paid calls** (100% mocked model clients) |

---

### 2. Task 4 — Sanitizer Crash Parsing Matrix (US-73)

The sanitizer parsing matrix verifies deterministic parsing of AddressSanitizer (ASan) and UndefinedBehaviorSanitizer (UBSan) diagnostics into exact `error_type` and `line_number` without invoking any external AI or model services (FR39.1, FR39.2, DR5, US-73).

All 16 test cases were evaluated against `parse_sanitizer_report()` and API endpoint `POST /v1/analyses/crash-explanation`.

| Test ID | Error Category | Language & Minimal Synthetic Fixture | Expected Type / Line | Observed Type / Line | Status | Command | Evidence / Output |
|---|---|---|---|---|---|---|---|
| **SAN-01** | ASan: Heap Buffer Overflow | C++: `std::vector<int> v={1,2,3}; return v[5];` | `heap-buffer-overflow` / Line 5 | `heap-buffer-overflow` / Line 5 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-01]` | `summary="Detected heap-buffer-overflow at line 5."` |
| **SAN-02** | ASan: Stack Buffer Overflow | C: `char buf[8]; strcpy(buf, "long string");` | `stack-buffer-overflow` / Line 6 | `stack-buffer-overflow` / Line 6 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-02]` | `summary="Detected stack-buffer-overflow at line 6."` |
| **SAN-03** | ASan: Global Buffer Overflow | C++: `int g[5]; int main() { return g[10]; }` | `global-buffer-overflow` / Line 4 | `global-buffer-overflow` / Line 4 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-03]` | `summary="Detected global-buffer-overflow at line 4."` |
| **SAN-04** | ASan: Heap Use After Free | C++: `int* p = new int(1); delete p; return *p;` | `heap-use-after-free` / Line 6 | `heap-use-after-free` / Line 6 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-04]` | `summary="Detected heap-use-after-free at line 6."` |
| **SAN-05** | ASan: Double Free | C: `void* p = malloc(8); free(p); free(p);` | `double-free` / Line 6 | `double-free` / Line 6 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-05]` | `summary="Detected double-free at line 6."` |
| **SAN-06** | ASan: Stack Use After Return | C++: `int* f() { int x=1; return &x; }` | `stack-use-after-return` / Line 7 | `stack-use-after-return` / Line 7 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-06]` | `summary="Detected stack-use-after-return at line 7."` |
| **SAN-07** | ASan: Stack Use After Scope | C++: `int* p; { int x=1; p=&x; } return *p;` | `stack-use-after-scope` / Line 7 | `stack-use-after-scope` / Line 7 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-07]` | `summary="Detected stack-use-after-scope at line 7."` |
| **SAN-08** | ASan: Init Order Fiasco | C++: Cross-TU global static reference | `initialization-order-fiasco` / Line 12 | `initialization-order-fiasco` / Line 12 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-08]` | `summary="Detected initialization-order-fiasco at line 12."` |
| **SAN-09** | UBSan: Signed Int Overflow | C: `int x = INT_MAX; return x + 1;` | `signed-integer-overflow` / Line 5 | `signed-integer-overflow` / Line 5 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-09]` | `summary="Detected signed-integer-overflow at line 5."` |
| **SAN-10** | UBSan: Shift Out of Bounds | C: `int a = 1; return a << 32;` | `shift-out-of-bounds` / Line 4 | `shift-out-of-bounds` / Line 4 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-10]` | `summary="Detected shift-out-of-bounds at line 4."` |
| **SAN-11** | UBSan: Index Out of Bounds | C++: `int arr[5]; return arr[10];` | `index-out-of-bounds` / Line 3 | `index-out-of-bounds` / Line 3 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-11]` | `summary="Detected index-out-of-bounds at line 3."` |
| **SAN-12** | UBSan: Null Dereference | C: `int* p = NULL; return *p;` | `null-pointer-dereference` / Line 3 | `null-pointer-dereference` / Line 3 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-12]` | `summary="Detected null-pointer-dereference at line 3."` |
| **SAN-13** | UBSan: Division by Zero | C: `int zero = 0; return 100 / zero;` | `division-by-zero` / Line 3 | `division-by-zero` / Line 3 | **PASS** *(Parser)*<br>**BLOCKED** *(Docker)* | `pytest -k test_sanitizer_matrix_parsing[SAN-13]` | `summary="Detected division-by-zero at line 3."` |
| **SAN-14** | Clean Execution | C++: `int main() { return 0; }` | `None` / `None` | `None` / `None` | **PASS** | `pytest -k test_sanitizer_matrix_parsing[SAN-14]` | `summary=""`, clean execution yields no error |
| **SAN-15** | Non-Sanitizer Crash | C: `raise(SIGSEGV);` (no ASan diagnostics) | `None` / `None` | `None` / `None` | **PASS** | `pytest -k test_sanitizer_matrix_parsing[SAN-15]` | `summary="Process exited with exit code 139 (no sanitizer finding)."` |
| **SAN-16** | Unsupported Language | Python submitted to crash explanation endpoint | HTTP 422 `UNSUPPORTED_LANGUAGE` | HTTP 422 `UNSUPPORTED_LANGUAGE` | **PASS** | `pytest -k test_sanitizer_unsupported_language_rejection` | Response code 422 with structured error envelope |

> [!NOTE]
> **Execution Service Sandbox Verification:**
> The unit test `execution-service/src/test/java/com/collabcode/execution/runner/DockerRunnerSanitizerTest.java` was previously executed and confirmed passed via Surefire test reports (`Tests run: 3, Failures: 0, Errors: 0, Time elapsed: 0.012 s`). It verifies compiler flags (`-fsanitize=address,undefined -no-pie -g -O1`) and configurable container memory limits (`512m` sanitizer limit vs `128m` default). Live Docker sandbox execution could not be run on the current Windows host due to absence of the local Docker daemon.

---

### 3. Task 4 — Plain-Language Crash Explanation Evaluation (US-74)

Evaluated the plain-language crash explanation pipeline using a mocked LLM client with representative sanitizer crash diagnostics (FR13.1, FR39.3, FR40.2, DR5, US-74).

| Test ID | Verification Criteria | Expected Result | Observed Result | Status | Command | Evidence |
|---|---|---|---|---|---|---|
| **EXP-01** | Correct Crash Identification | Identifies error type and source line from summary | `error_type="heap-buffer-overflow"`, `line_number=5` | **PASS** | `pytest -k test_crash_explanation_identification_and_limits` | Response: `summary="Detected heap-buffer-overflow at line 5."` |
| **EXP-02** | 3-Sentence Short-Answer Limit | Enforces FR13.1 constraint (`<= 3` sentences) | Parsed sentence count: **3** | **PASS** | `pytest -k test_crash_explanation_identification_and_limits` | `len(split_sentences(explanation)) == 3` |
| **EXP-03** | Suggested Fix Generation | Produces actionable remediation advice | Non-empty string with boundary checking | **PASS** | `pytest -k test_crash_explanation_identification_and_limits` | `suggested_fix="Verify index bounds before accessing: if (index < v.size()) std::cout << v[index];"` |
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

### 7. Task 5 — Monthly AI Cost Cap Evaluation (US-24)

Evaluated monthly external AI cost cap enforcement and 80% budget threshold alerts (NFR7.1, NFR7.2, NFR7.4, NFR7.5, US-24).
*Test Configuration:* Budget = `$1.00`, Token Cost = `$0.001` per token (1,000 tokens = `$1.00`).

| Test ID | Budget Stage | Cumulative Spend | Expected Behavior | Observed Behavior | Status | Command | Evidence |
|---|---|---|---|---|---|---|---|
| **CAP-01** | Below 80% Threshold | $0.70 (700 tokens) | Request admitted, alert flag `False` | Request admitted, spend `$0.70`, alert flag `False` | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | `tracker.alert_80_percent_triggered is False` |
| **CAP-02** | Crossing 80% Threshold | $0.85 (850 tokens) | Request admitted, alert log triggered once | Request admitted, spend `$0.85`, alert triggered | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | `AI_COST_CAP_ALERT: spend_usd=0.8500 budget_usd=1.0000` |
| **CAP-03** | Duplicate Alert Suppression | $0.95 (950 tokens) | No duplicate alert in same month | 0 additional alert log entries emitted | **PASS** | `pytest ai-ml-service/tests/test_cost_cap.py -k test_duplicate_alert` | Deduplication invariant preserved |
| **CAP-04** | At Budget Cap | $1.00 (1,000 tokens) | `is_cost_cap_reached() == True` | `is_cost_cap_reached() == True`, spend `$1.00` | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | `tracker.cost_cap_reached is True` |
| **CAP-05** | Above Budget Cap Rejection | $1.00 + new call | Reservation blocked with `CostCapReachedError` | Reservation raises `CostCapReachedError`; 0 model calls | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | Next outbound call rejected before provider dispatch |
| **CAP-06** | Endpoint Fallback on Cap | $1.00 + API request | Returns `status="skipped"`, `COST_CAP_REACHED` | Endpoint returns HTTP 200 with skipped AI step | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | `data["ai_step"]["error_code"] == "COST_CAP_REACHED"` |

---

### 8. Task 5 — Fallback Behavior Evaluation (US-78)

Evaluated all fallback triggers across `ai-ml-service` and `execution-service` (FallbackMessageMapper / T-18 Decision 10 / FR40.5).

| Test ID | Trigger Condition | Expected Error Code | Expected User-Facing Fallback Message | Observed Error Code / Status | Status | Command | Evidence |
|---|---|---|---|---|---|---|---|
| **FB-01** | Consent missing (`consent=False`) | `CONSENT_MISSING` | *"AI analysis is unavailable because AI consent is turned off."* | `CONSENT_MISSING` / `skipped` | **PASS** | `pytest -k test_fallback_consent_missing` | Deterministic analysis preserved; AI step skipped |
| **FB-02** | AI disabled by Host switch (US-59) | `AI_DISABLED` | *"AI analysis is currently disabled."* | `AI_DISABLED` / `skipped` *(API)* | **PASS** *(API)*<br>**BLOCKED** *(US-59 UI)* | `pytest -k test_fallback_ai_disabled` | API verified; full UI toggle blocked by US-59 unavailability |
| **FB-03** | Monthly cost cap reached | `COST_CAP_REACHED` | *"AI analysis is temporarily unavailable because the monthly AI limit has been reached."* | `COST_CAP_REACHED` / `skipped` | **PASS** | `pytest -k test_monthly_cost_cap_admission_and_rejection` | Safe student-friendly message mapped |
| **FB-04** | AI/ML service down / Provider error | `PROVIDER_ERROR` or `SERVICE_UNAVAILABLE` | *"AI analysis service is temporarily unavailable."* / *"AI analysis could not be completed right now."* | `PROVIDER_ERROR` / `skipped` | **PASS** | `pytest -k test_fallback_provider_error` | External exception caught and mapped to fallback |
| **FB-05** | Non-Blocking Execution Preservation | None (Normal run) | Execution output unaffected by AI latency or failure | Run output returned immediately; AI is async (NFR9) | **PASS** | `tests/integration/test-ai-fallback-message.sh` | Normal run returns 200 without waiting for AI analysis |

---

### 9. Task 5 — Concurrent and Multi-Room Load Evaluation

A multi-room synthetic load evaluation was conducted across 4 isolated rooms using 8 concurrent worker threads:
- **Concurrency Setup:** 4 rooms (`room-load-alpha`, `room-load-beta`, `room-load-gamma`, `room-load-delta`), 8 worker threads.
- **Stage 1 (Cold Cache):** 16 distinct requests across 4 rooms (4 per room) -> Expected: 16 model calls.
- **Stage 2 (Warm Cache):** 16 duplicate requests across 4 rooms (4 per room) -> Expected: 16 cache hits.
- **Stage 3 (Fallbacks):** 8 requests with `consent=False` (2 per room) -> Expected: 8 fallback activations.
- **Total Invocations:** 40 requests.

| Metric / Stage | Expected Count | Observed Count | Observed p50 Latency | Observed p95 Latency | Observed Max Latency | Status |
|---|---|---|---|---|---|---|
| **Cold Cache Requests (Model Calls)** | 16 | 16 | 46.30 ms | 57.41 ms | 57.41 ms | **PASS** |
| **Warm Cache Requests (Cache Hits)** | 16 | 16 | 24.24 ms | 30.85 ms | 30.85 ms | **PASS** |
| **Fallback Requests (`consent=False`)** | 8 | 8 | 23.62 ms | 24.38 ms | 24.38 ms | **PASS** |
| **Total Platform Requests** | 40 | 40 | — | — | — | **PASS** |
| **Rooms Tracked in Telemetry** | 4 | 4 | — | — | — | **PASS** |
| **Per-Room Counters Consistency** | 4 calls, 4 hits, 2 fallbacks per room | Exact match in all 4 rooms | — | — | — | **PASS** |

> [!TIP]
> **Performance Observations:**
> 1. Cache hits resolve in ~24 ms (under local test server concurrency), achieving a 50% latency reduction compared to model calls (~46 ms).
> 2. Fallbacks resolve in ~23 ms with zero downstream model invocations.
> 3. Zero data races, zero lost reservations, and zero counter discrepancies observed across 40 concurrent operations.

---

### 10. Issues Discovered

1. **Docker Daemon Dependency for Real C/C++ Container Execution:**
   The execution service requires a live Docker daemon to spawn sandbox containers for compiling with `-fsanitize=address,undefined` and running user executables. In environments without Docker running (such as this Windows development host), live container execution is unavailable. Parser unit tests and execution limits property tests pass deterministically, but end-to-end container testing requires a container-capable CI runner.
2. **Cold-Cache Concurrent Stampede Behavior:**
   When identical requests arrive simultaneously on an empty cache before the first request finishes and writes to the cache, both requests proceed to the model stage. This is expected behavior with an in-memory cache without distributed single-flight locking, but it should be noted for high-frequency identical requests across concurrent sessions.
3. **US-59 Host AI Switch Availability:**
   US-59 (Host AI toggle switch UI) has not yet been merged into `main` or this branch. The backend API gate (`ai_enabled=False`), counter tracking (`fallback_activations`), and frontend fallback mapping (`MSG_AI_DISABLED`) were verified, but end-to-end UI toggle testing remains blocked until US-59 is integrated.

---

### 11. Reproduction Instructions

To reproduce all evaluations deterministically:

```bash
# 1. Switch to evaluation branch
git checkout docs/T-24c-evaluation-tasks-4-5

# 2. Run the full pytest test suite (424 tests)
py -3.13 -m pytest ai-ml-service/tests

# 3. Run the evaluation test suite specifically
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
| **Consolidated Evaluation Summary** | **PENDING** | T-24a and T-24b reports must be completed and merged | Merge T-24a and T-24b reports into `evaluation-report.md` before final sprint closure. |
