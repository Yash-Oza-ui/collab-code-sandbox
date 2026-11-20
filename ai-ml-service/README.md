# AI/ML Service

The AI/ML microservice for **Collab Code Sandbox** (IT-314, Group 32). This service provides on-demand AI code reviews, failure explanations, and ML-based execution anomaly detection.

## Prerequisites

- Python 3.10+ (Python 3.11 recommended)
- `pip` and `venv`
- Docker (for containerized deployment)

## Architecture & Responsibilities

- **Scaffold (T-04):** FastAPI web service running on port 8000, health check at `/health`.
- **Contracts (T-07 / T-18 v2):** Endpoints and payloads conform to `../docs/ai-ml-contract.md`.
- **Configurable Limits (DR3 / US-16):**
  - `ai-ml-service` owns AI rate and cost limits: `AI_SLOWDOWN_FACTOR` (FR37.2, default `1.0`), `AI_MAX_TOKENS_PER_REQUEST` (FR40.4, default `4096`), `LLM_TIMEOUT_SECONDS` (FR40.4, default `10.0`), `ROOM_HOURLY_LIMIT` (NFR5.3, default `20`), `MONTHLY_BUDGET_USD` (NFR7.2, default `10.0`), `TOKEN_COST_PER_TOKEN` (default `0.0000035`).
  - Slowdown detection configurable limits (DR3 / US-68): `SLOWDOWN_FACTOR_THRESHOLD` (FR37.2, default `8.0`), `SLOWDOWN_OUTLIER_THRESHOLD` (default `3.0`, alias `SLOWDOWN_MODIFIED_Z_THRESHOLD`), `SLOWDOWN_MIN_SAMPLES` (default `3`), `SLOWDOWN_MAX_HISTORY` (default `100`).
  - See [docs/host-config.md](../docs/host-config.md) for details on host configuration and overrides. Final per-host values are determined only after host benchmarking.
- **Endpoints:**
  - `GET /health`: Liveness probe for container orchestration and smoke tests.
  - `POST /v1/analyses/complexity`: Rules-first time complexity analysis with verified AI fallback (FR36, US-17, US-22).
  - `POST /v1/analyses/slowdown`: Statistical slowdown detection against empirical Median and MAD baseline (FR37, US-68).
  - `POST /v1/analyses/crash-explanation`: Deterministic C/C++ sanitizer parser with crash explanation fallback (FR39, DR5).
  - `POST /v1/explain`: Execution failure explainer behind consent, normalized caching, and rate limits (T-16, US-22).
  - `GET /v1/usage`: Aggregate AI telemetry counters (T-18 v2).
  - `DELETE /v1/rooms/{room_id}/state`: Purges room-scoped cache entries, measurement history, and rate-limit state (US-22, US-56, FR32).

## Local Development Setup

1. Navigate to the service directory:

   ```bash
   cd ai-ml-service
   ```

2. Create a Python virtual environment:

   ```bash
   python -m venv venv
   ```

   Activate on Linux/macOS:

   ```bash
   source venv/bin/activate
   ```

   Activate on Windows PowerShell:

   ```powershell
   .\venv\Scripts\Activate.ps1
   ```

3. Install pinned dependencies:

   ```bash
   pip install -r requirements.txt
   ```

4. Set up environment configuration:

   Linux/macOS:

   ```bash
   cp .env.example .env
   ```

   Windows PowerShell:

   ```powershell
   Copy-Item .env.example .env
   ```

| Variable | Description | Default | Required |
|---|---|---|---|
| `PORT` | Service listening port | `8000` | No |
| `LLM_API_KEY` | API key for the external LLM provider | `None` | No (service starts without key) |
| `LLM_MODEL` | Target LLM model name | `gemini-1.5-flash` | No |
| `ROOM_HOURLY_LIMIT` | Max outbound AI calls per room per rolling hour (NFR5.3) | `20` | No |

> **Security & Privacy Note (NFR8.2, NFR10):**
> Never commit `.env` or real API keys to version control. The service starts normally even when `LLM_API_KEY` is not set. The service never logs request bodies, source code, stderr text, or API keys.

## User Consent & Consent Withdrawal (US-17, US-21, FR12.4)

In accordance with `FR12` (Privacy Consent Gate) and `FR12.4` / `US-21` (Withdraw AI Consent):

1. **Per-Session Consent Lifecycle:**
   - Consent is granted per user session (not per room).
   - Under US-17, consent is given once upon initial user agreement in a session.
   - Under US-21, a user can withdraw consent at any time during an active session via the client-side withdrawal control.
   - Withdrawal is communicated to the backend by the client sending `consent: false` on subsequent analysis requests.
   - If the user decides to opt back in later during that active session, consent can be granted again by sending `consent: true`. Withdrawing consent does not permanently disable AI for a user or session.

2. **Dual-Gate Enforcement:**
   - External AI calls strictly require **BOTH** `consent: true` (individual session consent, FR12) and `ai_enabled: true` (room Host AI switch, FR35).
   - If `consent` is `false` or missing (`null`), the central consent gate (`app.llm.client.call_llm_with_consent`) immediately blocks external LLM calls with 0 outbound calls, 0 token consumption, and no raw code or prompts logged (NFR8.2).
   - If `consent: true` but `ai_enabled: false`, the call is blocked under `error_code: "AI_DISABLED"`. User consent and Host AI switch are independent controls.

3. **Preservation of AI-Free Functionality:**
   - Withdrawing consent skips **only** the external AI step.
   - All AI-free capabilities—code editing, sandbox code execution, deterministic AST/regex rule detection (`slow_patterns`), empirical Big-O regression fitting, and AddressSanitizer crash diagnostics—continue to execute unconditionally and return HTTP 200 OK.
   - When an AI step is skipped due to withdrawn or missing consent, the service returns HTTP 200 with:
     - `ai_step.status = "skipped"`
     - `ai_step.error_code = "CONSENT_MISSING"`
   - The user receives the standard FR40.5 user-facing fallback message: `"AI analysis skipped: user consent required."`

4. **Session Isolation (No Shared Room State):**
   - Consent state belongs strictly to the user's session, never to the collaborative room.
   - In a shared room, one participant withdrawing consent (`consent: false`) has zero effect on another participant's consent (`consent: true`).
   - The backend never maintains a shared room-level boolean for consent and does not own a second persistence store or consent database.

5. **Client Coordination Contract:**
   - The frontend team is responsible for the visible withdrawal control (e.g., in room settings or an AI consent toggle).
   - The client remains the single source of truth for the session's current consent state (as specified in T-18 Decision 8).
   - The frontend withdrawal control must update the current session's consent state and send the updated boolean value on every subsequent analysis request (`/v1/analyses/complexity`, `/v1/analyses/crash-explanation`, `/v1/explain`).
   - The backend does not own or maintain a secondary consent store.

## AI Request Control, Normalization & Rate Limiting (US-22)

AI steps across all analysis endpoints enforce the controlled execution sequence defined in `NFR5.1`, `NFR5.2`, `NFR5.3`, and `FR40.3`:

1. **Consent & Host AI Switch Gates (`US-17`, `FR35`):** External AI calls never execute unless both `consent=true` and `ai_enabled=true` in the request. Otherwise, rule-based / deterministic results still return HTTP 200 with a skipped `ai_step` (`CONSENT_MISSING` or `AI_DISABLED`).
2. **Normalized SHA-256 Code Cache (`app/cache.py`, `NFR5.2`, `NFR8.2`):**
   - Supported DR1 languages (`python`, `cpp`, `c`, `java`, `javascript`) are normalized before hashing:
     - **Python:** Tokenized via standard-library `tokenize`; removes `#` comments and excess whitespace/blank lines while strictly preserving semantic docstrings, string literals, and `INDENT`/`DEDENT` block structure.
     - **C / C++ / Java / JavaScript:** Character-scanner strips `//` and `/* */` comments while strictly preserving string/character/template literals, C++ raw strings (`R"(...)"`), C++ string prefixes (`u8`, `u`, `U`, `L`), Java text blocks (`"""..."""`), and JavaScript regular-expression literals (`/[//]/`). Strictly preserves C/C++ preprocessor directive boundaries (`#define X 1\nX`) and operator token boundaries (`a + ++b` vs `a++ + b`).
   - Cache key formula: `SHA-256(normalized_code + ":" + language + ":" + request_type + ":" + error_signature + ":" + error_output)`.
   - Only the 64-character hexadecimal digest is stored in `AnalysisCache`; no source code or stderr is persisted or logged.
   - Cache hits return immediately without invoking the LLM or consuming room rate-limit quota.
3. **Per-Room Hourly Rate Limiting (`app/limiter.py`, `NFR5.3`, `DR3`):**
   - `RoomRateLimiter` tracks outbound LLM call timestamps per `room_id` over a rolling 1-hour window against `ROOM_HOURLY_LIMIT` (default: `20`).
   - Uses atomic `check_and_reserve(room_id)` under thread lock to prevent race conditions during concurrent requests.
   - Over-limit requests return HTTP 200 with AI-free findings intact and `ai_step: { "status": "skipped", "error_code": "ROOM_RATE_LIMITED" }`.
4. **Room State Cleanup (`US-56`, `FR32`):**
   - `DELETE /v1/rooms/{room_id}/state` evicts room-scoped cache keys and resets that room's rate-limit counters.

## Rules-First Static Analysis (US-67, FR36.4)

Before the system makes a paid AI call regarding slow code, simple deterministic rules look for obvious causes so that AI cost stays low (`FR36.4`, `US-67`).

### Covered Patterns & Static Analysis Rules

1. **Nested Loops (`nested_loops`):**
   - Detects loop-within-a-loop constructs causing quadratic or polynomial time complexity ($O(n^2)$ or worse).
   - Identifies the exact line number of the inner loop construct.
2. **Repeated String Concatenation (`repeated_string_concat`):**
   - Detects iterative string accumulation inside loop bodies (e.g. `s += str(x)`, `res = res + item`, `strcat`, `.concat()`).
   - Advises using string buffers, pre-allocated lists, or join operations.
3. **Linear Search in Loops (`linear_search_in_loop`):**
   - Detects linear membership testing (`item in list`, `.indexOf()`, `.includes()`, `std::find`) inside an outer loop.
   - Advises replacing linear collections with hash sets or hash maps for $O(1)$ lookups.

### Supported Languages (DR1)

The static rules cover all five supported languages:

- **Python (`python`):** Full AST-based parsing via Python's standard-library `ast.parse` for exact syntactic node walking; gracefully falls back to lexical line scanning if the user code contains syntax errors.
- **C++ (`cpp`):** Comment- and string-aware block depth scanner tracking brace nesting levels and regex patterns for loop headers (`for`, `while`, `do {`) and string modifications.
- **C (`c`):** Comment- and string-aware brace scanner detecting nested loops and library string mutations (`strcat`, `strncat`).
- **Java (`java`):** Comment- and string-aware brace scanner detecting standard/enhanced for-loops, while-loops, and string concatenation (`+=`, `.concat()`).
- **JavaScript (`javascript`):** Comment- and string-aware brace scanner supporting standard for-loops, `for..of`, `for..in`, while-loops, and string accumulation.

### False-Positive Protection

- **Sequential Loop Tracking:** Sequential (consecutive, non-nested) loops in both braced (`{ ... }`) and single-statement unbraced formats (`for (...) stmt;`) are tracked using statement boundaries and block depth, ensuring they are strictly **not** reported as nested loops.
- **Scope-Boundary AST Traversal:** In Python AST analysis, loop body traversal strictly terminates at nested function (`def`, `async def`), class (`class`), and lambda definition boundaries, ensuring loops and string accumulations inside nested definitions are not falsely attributed to the outer loop.
- **Numeric Accumulation vs. String Concatenation:** Numeric loop accumulation in Python (e.g. `total += i`, `count += 1`, `total = total + i`) is distinguished from string accumulation and is strictly **not** classified as `repeated_string_concat`.
- **String-Aware Lexical Scanning:** In C-family languages, comments (`//`, `/* */`) are stripped in a string-aware manner; comment markers and braces inside string and character literals (`"//..."`, `"/*...*/"`, `"{...}"`) are preserved as string content and do not corrupt loop or block depth tracking.

### Analysis Flow (Rules versus AI)
1. **Rule Evaluation (Default & AI-Free):** Evaluates deterministic static rules by default for complexity analysis requests; works without network or AI models regardless of consent.
2. **Rule Match Avoids AI:** When any rule fires, findings are reported in `slow_patterns` and `ai_step` is marked `status: "skipped"` with `error_code: null`, making **zero outbound AI calls** and incurring $0 cost.
3. **Controlled AI Fallback:** An AI call is made only when rules find no likely issues (clean code), and strictly subject to user consent (`FR12`, `US-17`) and the room AI switch (`FR35`, `US-59`). Clients may optionally pass `rules_first: false` to request direct AI analysis.

## Running the Service

Start the FastAPI application using Uvicorn:

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

The service will be accessible at `http://localhost:8000`.

## Testing

Run tests with `pytest`:

```bash
pytest -v
```

## Docker Container

Build and run using Docker:

```bash
docker build -t collab-ai-ml-service .
docker run -p 8000:8000 collab-ai-ml-service
```

## Synthetic Dataset (T-12)

The offline generator creates labelled synthetic execution metrics for T-15 training. Dataset generation is separate from the running API service.

See [dataset documentation](docs/synthetic-dataset.md) for column definitions, generation rules, class ratio, and regeneration commands.

Run these commands from `ai-ml-service/`:

```bash
python generate_dataset.py --seed 42 --rows 5000 --out runs.csv
python -m unittest discover -s tests -v
```

The default dataset contains 5,000 rows: 80% normal and 20% anomalous. It covers all five languages: C++, Python, C, Java, and JavaScript.

The generator uses the eight T-13 features plus a label column. It contains only synthetic metrics, with no real user code or user data.

The dataset generator and its unittest checks require only the Python standard library. The API service requires the dependencies listed above.

Keep generated CSV files out of commits. The production anomaly score threshold will be chosen during Sprint 3 validation.

## Slowdown Detection in Run History (US-68, FR37, T-18 v2 §16.2)

### User Story & Purpose (US-68)
As a student, I want a warning when a run is far slower than my earlier runs so that I notice a performance regression.

Slowdown detection evaluates execution runtime against an empirical historical baseline built from earlier runs of the exact same code input (`input_hash`).

### Statistical Baseline & Detection Formulas (T-18 v2 §16.2)
For all earlier runs in the room sharing the same `input_hash`, `ai-ml-service` computes:
1. **Historical Median Runtime:**
   $$\tilde{x} = \text{median}(x_1, x_2, \dots, x_k)$$
2. **Median Absolute Deviation (MAD):**
   $$\text{MAD} = \text{median}(|x_i - \tilde{x}|)$$
3. **Statistical Outlier Threshold:**
   $$T_{\text{threshold}} = \tilde{x} + k \cdot \text{MAD}$$
   where $k$ defaults to `3.0` per contract §16.2 (configurable via `SLOWDOWN_OUTLIER_THRESHOLD` with `SLOWDOWN_MODIFIED_Z_THRESHOLD` retained as a backward-compatible alias).

### Dual Flagging Criteria (Regression Decision)
A run is flagged as a performance slowdown (`is_slowdown: true`) if and only if **BOTH** of the following independent criteria hold (§16.2):
1. **Statistical Outlier:** The execution runtime exceeds three MAD deviations above the historical median:
   $$T_{\text{curr}} > \tilde{x} + 3 \cdot \text{MAD}$$
   (Configurable outlier multiplier, default `3.0` via `SLOWDOWN_OUTLIER_THRESHOLD` / `SLOWDOWN_MODIFIED_Z_THRESHOLD`).
2. **Severe Fold Slowdown:** The current runtime is at least 8 times slower than the baseline median:
   $$\text{slowdown\_factor} = \frac{T_{\text{curr}}}{\tilde{x}} \ge 8.0$$
   (Default factor: `8.0`, configurable via `SLOWDOWN_FACTOR_THRESHOLD` per FR37.2 and DR3).

### AI Explanation Generation & Gating (FR13.1, FR40.2, §16.2)
- When a regression is detected (`is_slowdown: true`) and both user consent (`consent: true`) and the Host AI switch (`ai_enabled: true`) are active, `ai-ml-service` generates a concise AI explanation of the regression.
- **3-Sentence Limit (FR13.1):** The explanation is constrained to plain prose and strictly capped at **3 sentences** via programmatic truncation (`enforce_sentence_limit`).
- **Data Minimization (FR40.2, NFR8.2):** Code is sliced to a small relevant window (`extract_relevant_section`) rather than sending full source files.
- **Budget & Rate Protection (NFR5.2, NFR5.3, NFR7.2):** Outbound calls are cached by SHA-256 digest of normalized code to prevent duplicate spend, and respect room hourly limits (`ROOM_HOURLY_LIMIT`) and monthly budget caps (`MONTHLY_BUDGET_USD`).
- **Fallback on Error / Missing Consent:** If consent is missing (`CONSENT_MISSING`), AI is disabled (`AI_DISABLED`), or provider limits/errors occur (`COST_CAP_REACHED`, `ROOM_RATE_LIMITED`, `PROVIDER_TIMEOUT`, `PROVIDER_ERROR`), the statistical detection results (`is_slowdown`, `slowdown_factor`, `baseline`, and `message`) **are returned in full** with `ai_step: { "status": "skipped", "error_code": "..." }`.
- **Normal Runs:** When runtime is within normal parameters (`is_slowdown: false`), no AI call is made and `ai_step` returns `{ "status": "ok", "error_code": null, "explanation": null }` per §16.2.

### Clarification on Deferred Scope: `changed_lines` (FR37.3, US-69)
In US-68 responses, `changed_lines` is returned as an empty list (`[]`).
- **Reason for Deferral:** Under US-68 / NFR8.2 data minimization, `ai-ml-service` maintains short-lived, metadata-only storage in `SlowdownStore` (storing only numeric runtime and memory metrics, never raw source code).
- **Scheduled Delivery:** Line-by-line diffing of source code against the last normal run is assigned to **US-69 (FR37.3)** in Sprint 5. Returning `[]` strictly conforms to the JSON schema (`docs/schemas/slowdown-response.json`).

### Configurable Limits & Request-Level Override Validation (DR3 / US-16 / US-68)
Each threshold is read from environment configuration without hard-coding:
- `SLOWDOWN_FACTOR_THRESHOLD`: Fold multiplier threshold (default `8.0`).
- `SLOWDOWN_OUTLIER_THRESHOLD`: Outlier threshold multiplier (default `3.0`, per §16.2 $T > \tilde{x} + 3 \cdot \text{MAD}$).
- `SLOWDOWN_MODIFIED_Z_THRESHOLD`: Backward-compatible alias for `SLOWDOWN_OUTLIER_THRESHOLD` (default `3.0`).
- `SLOWDOWN_MIN_SAMPLES`: Minimum number of earlier runs required to establish a valid baseline (default `3`).
- `SLOWDOWN_MAX_HISTORY`: Maximum number of measurements retained per `(room_id, input_hash)` bucket (default `100`).

Thresholds may be dynamically overridden on individual analysis requests via HTTP query parameters (`factor_threshold`, `outlier_threshold` / `z_threshold`, `min_samples`) or headers (`x-slowdown-factor`, `x-outlier-threshold`, `x-modified-z-threshold`, `x-min-samples`).

**Override Validation Rules:**
Overrides are strictly validated before processing. If an invalid value is supplied, the service immediately rejects the request with HTTP 422 `INVALID_REQUEST`:
- `min_samples`: Must be an integer $\ge 1$. Values $\le 0$, non-integers, floats (e.g., `1.5`), and non-numeric strings are rejected.
- `factor_threshold`: Must be a finite number $\ge 1.0$. Values $< 1.0$, $\le 0$, negative, NaN, Inf, and non-numeric strings are rejected.
- `outlier_threshold`: Must be a positive finite number $> 0.0$. Values $\le 0$, negative, NaN, Inf, and non-numeric strings are rejected.

### Safe Handling of MAD = 0 Policy
When more than 50% of historical runs have identical execution times, $\text{MAD} = 0$. To prevent division by zero and eliminate false alarms:
1. If $T_{\text{curr}} \le \tilde{x}$, `condition_1_outlier = False` (not an outlier).
2. If $T_{\text{curr}} > \tilde{x}$, the service evaluates the mean absolute deviation: $\text{mean\_ad} = \text{mean}(|x_i - \tilde{x}|)$.
   - If $\text{mean\_ad} > 0$, condition 1 checks $T_{\text{curr}} > \tilde{x} + k \cdot \text{mean\_ad}$.
   - If all historical samples were strictly identical ($\text{mean\_ad} = 0$), historical variance is zero. Any runtime increase represents a statistical anomaly; condition 1 evaluates to `True` without division by zero.
3. Crucially, a regression is flagged **only** if Condition 2 ($T_{\text{curr}} \ge 8.0 \times \tilde{x}$) is ALSO satisfied. Normal run fluctuations (e.g., 101 ms vs. 100 ms baseline) have $\text{slowdown\_factor} = 1.01 < 8.0$ and are strictly **not flagged**, preventing false alarms.

### Insufficient History Policy
A minimum of 3 historical runs (`sample_count >= 3`) with matching `input_hash` is required to establish a baseline. Before reaching this threshold:
- The service records the current measurement into short-lived storage.
- It returns `is_slowdown: false`, `slowdown_factor: 1.0`, and an explicit contract-defined message:
  `"Recorded run measurements. Baseline establishing (k of 3 minimum samples collected)."`
- The `ai_step` is marked `status: "skipped"` with `error_code: null`.

### Room-Scoped In-Memory Storage & Privacy (NFR8.2, FR37.4, US-52, CR11)
- Historical measurements are maintained exclusively in `SlowdownStore` (thread-safe in-memory dictionaries protected by `threading.Lock`).
- Measurements are strictly isolated by `(room_id, input_hash)`. Different rooms and different input hashes never share baselines.
- **Privacy & Data Minimization (NFR8.2):** Stores **ONLY** numeric primitives: `execution_time_ms`, `peak_memory_mb`, and `timestamp`.
- **Zero Raw Data Stored:** Source code, stdin, stdout, stderr, prompts, or complete run records are **never stored** in `ai-ml-service`. Canonical run records remain owned exclusively by `sync-service` (US-52).
- When a run is flagged as a slowdown, it is **not added** to normal baseline measurements, preventing regression contamination of subsequent baselines.
- Normal runs are added to the baseline up to `SLOWDOWN_MAX_HISTORY` (FIFO eviction).

### Room Deletion Behavior (US-56, FR32)
When a room is closed or deleted, `execution-service` calls `DELETE /v1/rooms/{room_id}/state` on `ai-ml-service`. This immediately and permanently frees all room-scoped measurements (`SlowdownStore.clear_room(room_id)`), code cache entries, and rate-limit counters. Deleting one room has zero effect on any other room.

### Execution Ordering & AI Independence (NFR9, CR08)
- **Statistics-Only Feature:** Slowdown detection is a purely mathematical algorithm (T-18 Decision 3) and requires **no external LLM or AI model calls**.
- **Never Delays Execution (NFR9):** Code execution results are returned immediately by `execution-service`. Slowdown analysis is delivered asynchronously afterwards as a separate `analysis-result` message with the same audience as the corresponding run result (CR08).
- **Consent and Host Switch Independence:** The statistical detection executes unconditionally regardless of whether user consent is granted or AI is enabled. If consent is missing or AI is disabled by the Host, `ai_step` is marked `status: "skipped"` with the corresponding error code (`CONSENT_MISSING` or `AI_DISABLED`), while the regression flag, slowdown factor, baseline, and messages are returned in full.

---

## Anomaly Detection Baseline (T-15) & Scorer Retirement

**Contract & Scope Status (T-18 Decision 3 / Product Backlog v2 §6.2 Decision 3):**
1. **Endpoint `POST /v1/anomaly` is Retired:** The v0 anomaly endpoint was a placeholder that always returned "not anomalous" and has been permanently retired.
2. **Slowdown Detection Replaces Anomaly Route:** Regression detection is implemented via Task 2 (`POST /v1/analyses/slowdown`, FR37, US-68) using empirical Median and MAD statistics; it needs no machine learning model.
3. **T-15 Scorer Status:** The offline `AnomalyScorer` prototype (`app/ml/scorer.py`) was removed during v2 cleanup and is unwired to any route. It remains outside the v2 production scope.
4. **Mentor-Check Status:** In accordance with Product Backlog v2 Section 6.2 Decision 3, the mentor check regarding whether a trained ML model is desired remains an open item for the team. No mentor approval has been claimed without evidence.

### Background: Initial Synthetic Baseline Evaluation (T-15 Prototype) 

**Algorithm Choice:** 
We utilize an **Isolation Forest** via `scikit-learn`. This was chosen because:
1. It is explicitly designed for anomaly/outlier detection without requiring strictly balanced classes.
2. It handles multi-dimensional tabular limits (time, memory, output bytes) effectively.
3. Inference time is extremely fast, ensuring we do not delay the room broadcast sync (NFR9).

### Training the Model
To generate and train the model locally (requires the synthetic dataset from T-12):
```bash
python generate_dataset.py --out data.csv
python scripts/train_baseline.py --data data.csv --seed 42
```
This saves the trained Isolation Forest pipeline to `models/baseline_anomaly_model.joblib` and the held-out test split (20%) to `models/test_set.csv`.

### Evaluating the Model (Prince's part)
Run the evaluation script on the held-out test set:
```bash
python scripts/evaluate_baseline.py --data data.csv --seed 42
# or directly on the saved held-out test set:
python scripts/evaluate_baseline.py --test-data models/test_set.csv
```

### Baseline Evaluation Results (Held-Out Synthetic Data)

| Metric | Value | Notes |
|---|---|---|
| **ROC-AUC** | **0.9997** | Threshold-independent ranking separation |
| **PR-AUC** | **0.9990** | Precision-Recall area under curve |
| **Precision** (@ 0.50) | 0.9949 | At neutral normalized cutoff |
| **Recall** (@ 0.50) | 0.9238 | At neutral normalized cutoff |
| **F1-Score** (@ 0.50) | 0.9580 | Harmonic mean of precision and recall |
| **Normal Mean Score** | 0.4506 | Distribution center for normal samples |
| **Anomalous Mean Score** | 0.5495 | Distribution center for anomalous samples |
| **Separation Margin** | +0.0989 | Difference between anomalous and normal mean |

> **Important Disclaimer (Synthetic Data Only):**
> These results are evaluated **exclusively on synthetic data** generated under T-13 rule boundaries. They demonstrate that the Isolation Forest pipeline effectively separates synthetic execution stress profiles from normal runs. **They say nothing yet about real user code or real-world execution telemetry.** Real validation, tuning, and threshold selection will take place in Sprint 3 using real execution metrics (FR8.1).

### Sanity Check & Reproducibility
- **Directional Sanity Check:** Evaluated via `tests/test_evaluation.py`. A clearly anomalous row (e.g., wall-clock timeout kill, memory spike > 115 MB, or output flooding > 900 KB) scores strictly higher than a typical quick execution.
- **Reproducibility:** Two runs with the same random seed (`--seed 42`) yield identical model parameters, test splits, and evaluation metrics.


## Relevant Code Section Extraction (US-76, FR40.2, NFR8.2)

To satisfy student privacy and data minimization requirements, calls to external AI services send only the code section relevant to the request—such as the target function or a small bounded section around a reported error line—and **never the whole file**.

### Supported Languages & Extraction Mechanics (DR1)

Function body extraction is implemented in `app.section.extract_relevant_section` across all five supported languages:

1. **Python (`python`):**
   - AST-based parsing using `ast.parse` to locate `FunctionDef` and `AsyncFunctionDef` line boundaries (`lineno` through `end_lineno`).
   - Fallback to indentation-based line scanning if user code contains syntax errors.
2. **C++ (`cpp`):**
   - Regex-based function signature recognition (handling `inline`, `static`, `virtual`, `constexpr`, qualifiers, and template returns).
   - String- and comment-aware brace matching depth counters to detect the exact closing brace.
3. **C (`c`):**
   - Signature detection for standard C function definitions.
   - String- and comment-aware brace matching.
4. **Java (`java`):**
   - Method signature detection supporting annotations (`@Override`), visibility and concurrency modifiers (`public`, `private`, `static`, `synchronized`, `final`), generics (`<T>`), and `throws` clauses.
   - String- and comment-aware brace matching.
5. **JavaScript (`javascript`):**
   - Matches `function` declarations, `async function`, arrow functions assigned to `const`/`let`/`var`, and class/object method definitions.
   - String- and comment-aware brace matching.

### Error Line Windows & Diagnostic Parsing

When an error line is provided or detected:
- The section extractor extracts a bounded window of lines centered on the error line (default: `10` lines before and `10` lines after).
- Edge cases (e.g. line 1, EOF, or negative/out-of-bounds indices) are clamped safely without crashing.
- Automatic stderr diagnostic parsing (`app.section.extract_error_line_from_stderr`) detects error line numbers from:
  - AddressSanitizer and UBSan reports (`/workspace/solution.cpp:14`).
  - Python tracebacks (`File "main.py", line 14`).
  - GCC and Clang compiler errors (`solution.c:14:5: error:`).
  - Java runtime stack traces (`(Solution.java:14)`).
  - Node.js stack traces (`at Solution.js:14:5`).

### Configurable Bounds & Fallback Strategy

The extraction limits are named configuration parameters in `app.config.Settings`:

| Setting | Environment Variable | Default | Purpose |
|---|---|---|---|
| `SECTION_WINDOW_LINES` | `SECTION_WINDOW_LINES` | `10` | Number of lines included before and after the reported error line |
| `SECTION_MAX_LINES` | `SECTION_MAX_LINES` | `30` | Upper limit on total lines; sections exceeding this are truncated |
| `SECTION_FALLBACK_LINES` | `SECTION_FALLBACK_LINES` | `20` | Bounded line count used when no function name or error line is found |

**Fallback Guarantee:** If no target function is found or no error line can be resolved, the extractor sends a small bounded snippet (`SECTION_FALLBACK_LINES`, default 20 lines) and **never falls back to the full file**.

## AI Usage Counters and Telemetry (US-75, FR40.1, T-18 v2)

### Storage Decision: In-Memory Storage
We selected **thread-safe in-memory storage** (`UsageTracker` protected by `threading.Lock`) for usage counters over SQLite for the following architectural reasons:
1. **Low Latency & High Concurrency:** Eliminates disk I/O latency and SQLite locking contention on asynchronous analysis pipelines.
2. **Room Lifecycle Management (US-56, FR32):** In-memory dictionaries enable instantaneous per-room eviction via `clear_room(room_id)` when a room is deleted or closed, leaving overall platform telemetry intact.
3. **Data Minimization & Privacy (NFR8.2):** Guarantees zero persistence of raw execution strings, prompts, or code on disk; counters store primitive integers, room identifiers, and dollar spend values only.

### Counter Interface (`app.counters`)
- `record_model_call(room_id: str, tokens: int = 0)`: Records an outbound model call and token usage extracted directly from upstream LLM provider responses (Gemini `usageMetadata.totalTokenCount` or OpenAI `usage.total_tokens`). Tokens are never estimated from user-written code.
- `record_cache_hit(room_id: str)`: Records a cache hit when a request matches a normalized SHA-256 code key.
- `record_fallback(room_id: str)`: Records an AI fallback or skipped step activation (`CONSENT_MISSING`, `AI_DISABLED`, `PROVIDER_TIMEOUT`, `RATE_LIMITED_PROVIDER`, `PROVIDER_ERROR`).
- `clear_room(room_id: str) -> bool`: Removes room-scoped counter state.
- `get_room_usage(room_id: str) -> dict`: Returns usage metrics for an individual room.
- `get_total_usage() -> dict`: Returns overall aggregate metrics matching the T-18 v2 contract.

### Telemetry Endpoints
- `GET /v1/usage`: Returns aggregate platform counters:
  ```json
  {
    "total_model_calls": 24,
    "total_tokens_used": 8450,
    "total_cache_hits": 41,
    "total_fallback_activations": 2,
    "monthly_spend_usd": 0.0296,
    "monthly_budget_usd": 10.0,
    "alert_80_percent_triggered": false,
    "cost_cap_reached": false,
    "rooms_tracked": 3
  }
  ```
- `GET /v1/rooms/{room_id}/usage`: Returns metrics scoped to a specific room.
- `DELETE /v1/rooms/{room_id}/state`: Clears room-scoped counters and cache entries when closed or deleted (US-56).

## Deterministic Sanitizer Crash Report Parsing (US-73, FR39.1, FR39.2, DR5)

The deterministic sanitizer parser (`app.sanitizer.parse_sanitizer_report`) parses compiler diagnostics from AddressSanitizer (ASan) and UndefinedBehaviorSanitizer (UBSan) for C and C++ programs without invoking external AI models.

### Recognized Error Types
- **AddressSanitizer:** `heap-buffer-overflow`, `stack-buffer-overflow`, `global-buffer-overflow`, `heap-use-after-free`, `double-free`.
- **UndefinedBehaviorSanitizer:** `signed-integer-overflow`, `shift-out-of-bounds`, `index-out-of-bounds`, `null-pointer-dereference`, `division-by-zero`.

### Line Extraction & Edge Cases
- **Line Extraction:** Extracts the 1-based source line from the innermost frame (e.g. `#0 0x... in func /workspace/solution.cpp:14` or `/sandbox/code/solution.c:14:5: runtime error:`).
- **Line Not Found:** When no source line can be determined, returns `line_number=None` and formats summary as `Detected <error_type>.`.
- **Clean Runs:** When no sanitizer error is reported and the program executes successfully, returns `error_type=None`, `line_number=None`, and `summary=""` (empty summary, not an error).
- **Non-Sanitizer Crashes:** Standard crashes (e.g. segmentation faults without sanitizer instrumentation) preserve exit information (e.g. `Process exited with exit code 139 (no sanitizer finding).`) without inventing a sanitizer error type.
- **Compiler Compatibility:** Tested against diagnostic outputs from GCC 12+ and Clang/LLVM 14+.
