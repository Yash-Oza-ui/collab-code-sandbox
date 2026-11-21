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
  - `POST /v1/analyses/complexity`: Rules-first time complexity analysis with verified AI fallback and empirical log-log regression (FR36, US-65, US-17, US-22).
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

## Empirical Time Complexity Measurement & Regression (US-65, FR36.1, FR36.2)

### User Story & Purpose (US-65)
As a student, I want an estimate of how my program's run time grows so that I can see whether it will be too slow.

`ai-ml-service` measures the program at geometric input sizes $n$, $2n$, and $4n$, several times each, and calculates the median run time at each size. It fits a linear regression line to $\log(\text{input size})$ against $\log(\text{run time})$ to estimate the empirical growth rate ($m$), calculates the $R^2$ goodness-of-fit score, classifies the Big-O time complexity, and extrapolates the input size at which execution would reach the 5-second sandbox limit.

### Geometric Input Sizes & Repeat Count (FR36.1, US-64, NFR14)
- **Input Sizes:** Measurements are performed at 3 geometric points: $n$, $2n$, and $4n$ (configurable base size `COMPLEXITY_BASE_INPUT_SIZE`, default $n = 100$, yielding inputs $100, 200, 400$).
- **Repeats:** Each size is measured 3 times (`COMPLEXITY_DEFAULT_REPEATS = 3`) to filter transient container startup overhead, giving $3 \text{ sizes} \times 3 \text{ repeats} = 9 \text{ total runs}$.
- **Run Cap Compliance (US-64, NFR14):** In collaboration with Bhavya Vekariya, 3 repeats was selected so the 9 total runs strictly remain under the 10-run per-request ceiling (`max-runs-per-request: 10`) enforced by `execution-service`.
- **Median Runtime:** At each input size $N_i$, the representative execution time is the median runtime across the repeats:
  $$\tilde{t}_i = \text{median}(t_{i, 1}, t_{i, 2}, \dots, t_{i, k})$$
- **Stage Isolation (V2.9, AC 3):** Only the actual run stage time (`execution_time_ms`) is used for statistical regression. Compile time and container queue time are strictly excluded from the regression curve.
- **Aggregate Budget Accounting:** In compiled languages (C, C++, Java), compilation is executed on each measurement run. While compile time is excluded from `execution_time_ms`, it is counted toward the 20,000 ms aggregate execution budget in `execution-service` (`RunBudget`).

### Log-Log Linear Regression Formula (FR36.2)
For polynomial and power-law relationships $T(N) = a \cdot N^m$, taking the natural logarithm of both sides yields a linear equation:
$$\ln(T) = m \cdot \ln(N) + c$$
where:
- $N$ is the input size ($n, 2n, 4n$).
- $T$ is the median execution time in milliseconds.
- $m$ is the slope of the line, representing the empirical growth rate (power-law exponent).
- $c = \ln(a)$ is the intercept.

The slope $m$ and intercept $c$ are determined using ordinary least squares (OLS) linear regression:
$$m = \frac{\sum_{i=1}^k (\ln N_i - \overline{\ln N})(\ln T_i - \overline{\ln T})}{\sum_{i=1}^k (\ln N_i - \overline{\ln N})^2}$$
$$c = \overline{\ln T} - m \cdot \overline{\ln N}$$

### Big-O Complexity Classification Table
The empirical growth rate $m$ is mapped to standard Big-O notation using calibrated boundary intervals:

| Growth Rate Interval ($m$) | Estimated Complexity | Algorithmic Class |
|---|---|---|
| $m \le 0.20$ | $O(1)$ | Constant Time |
| $0.20 \le m < 0.80$ | $O(\log n)$ | Logarithmic Time |
| $0.80 \le m < 1.25$ | $O(n)$ | Linear Time |
| $1.25 \le m < 1.75$ | $O(n \log n)$ | Linearithmic Time |
| $1.75 \le m < 2.50$ | $O(n^2)$ | Quadratic Time |
| $2.50 \le m < 3.50$ | $O(n^3)$ | Cubic Time |
| $m \ge 3.50$ | $O(2^n)$ | Exponential Time |

### $R^2$ Score & Poor Fit Handling
The coefficient of determination ($R^2$) measures how well the regression line accounts for empirical variance:
$$R^2 = 1 - \frac{\sum_{i=1}^k (\ln T_i - \widehat{\ln T}_i)^2}{\sum_{i=1}^k (\ln T_i - \overline{\ln T})^2}$$
- **What Low $R^2$ Means:** Microbenchmark measurements at small input sizes are susceptible to noise, container scheduling jitter, garbage collection pauses, or algorithmic phase transitions (e.g. cache line thresholds). An $R^2$ score below `0.85` indicates that execution time did not scale cleanly with input size.
- **Reporting Policy:** When $R^2 < 0.85$ (`COMPLEXITY_R2_POOR_FIT_THRESHOLD`), the response sets `is_poor_fit: true` and includes an explicit warning message:
  `"Complexity estimate has low confidence (R² = 0.XX < 0.85). Variance or noise was high across measured sizes."`
  A weak estimate is never presented to the student as a firm answer.

### Projected Timeout Extrapolation (5 s Sandbox Limit)
Extrapolating the log-log fit predicts the input size $N_{\text{limit}}$ at which the program would hit the 5,000 ms (5 s) sandbox timeout limit (`COMPLEXITY_TIMEOUT_MS`, NFR4.1):
$$\ln(5000) = m \cdot \ln(N_{\text{limit}}) + c \implies N_{\text{limit}} = \operatorname{round}\left(\exp\left(\frac{\ln(5000) - c}{m}\right)\right)$$

- **Flat or Falling Line Protection:** When the slope is near zero or negative ($m \le 0.05$, e.g. for $O(1)$ constant time or caching effects), solving for $N_{\text{limit}}$ would yield astronomical or meaningless negative/fractional numbers. In such cases, the service reports `projected_timeout_input_size = "limit not reached"` rather than a nonsense number.

### Timeout Handling Policy (NFR4.1)
- **Individual Timeout Detection:** If any execution run at a given input size reaches or exceeds 5,000 ms (5 s), it is classified as a timeout at that size.
- **Minimum Points Requirement:** A valid regression curve strictly requires at least 3 distinct finished input sizes.
- **Refusal to Fit Through Two Points:** If fewer than 3 sizes finish (for instance, a timeout occurs at size $2n$ or $4n$), the service **refuses to fit a line through two points**. It sets `growth_rate = null`, `estimated_complexity = null`, `timeout_at_size = <size>`, and returns an explicit explanatory message:
  `"Execution timed out at input size 400 (5000 ms limit reached). No complexity estimate possible from fewer than 3 completed sizes."`

### Budget Exceeded & 10-Run Limit Policy (HTTP 429 RUN_BUDGET_EXCEEDED, US-64, NFR14)
- **Central Run Cap Enforcement:** `execution-service` centrally enforces the 10-run limit (`app.execution.limits.max-runs-per-request: 10`) via `RunBudget.java`. Each measurement run passes through `RunBudget.execute()`, which checks `runsCompleted >= maxRuns` before acquiring a queue slot or launching a container.
- **Client Pre-Validation:** `ai-ml-service` pre-validates `len(inputs) * repeats <= 10` before dispatching. If a request demands $> 10$ runs (e.g. 4 inputs $\times$ 3 repeats = 12 runs), it is immediately refused with HTTP 429 `RUN_BUDGET_EXCEEDED` (`reason: "max_runs_per_request"`, `Retry-After: 30`), preventing wasted container starts.
- **Aggregate Time Budget (20,000 ms):** When repeated measurements exceed the 20,000 ms aggregate runtime budget in `execution-service`, it returns HTTP 429 `RUN_BUDGET_EXCEEDED` (`reason: "aggregate_time_budget"`, `Retry-After: 30`).
- **No Incomplete Estimates:** `ai-ml-service` never attempts to construct a partial estimate from truncated runs. The specific budget hit is surfaced in `error.reason` and response message:
  `"Repeated-run execution budget exceeded (aggregate_time_budget). No estimate possible from incomplete data."`

### Incomplete Measurements Protection
- **Missing Sizes & Incomplete Repeats:** If `execution-service` returns fewer than the requested input sizes or if any size has fewer completed runs than the requested `repeats`, `HttpInternalRunClient` marks the result as incomplete (`incomplete: true`).
- `fit_complexity_curve` safely refuses estimation:
  `"No estimate possible from incomplete data: Incomplete repeats for input size '200' (received 1 of 3 required repeats)."`
- **Minimum 3 Distinct Finished Sizes:** If duplicate sizes are supplied or fewer than 3 distinct sizes finish, the service safely returns `can_estimate: false` and `estimated_complexity: "no estimate"`.

### Internal Run Architecture & Dual Runner Pattern (T-18 Decision 7)
`ai-ml-service` never spawns Docker containers directly. It interacts with `execution-service` via `POST /internal/runs` (`purpose: "measurement"`) authenticated by the shared header `X-Internal-Token`.
- **`MeasurementRunner` Interface:** Defines the contract for executing repeated runs at geometric input sizes.
- **Real Runner Default in Production (`USE_STUB_RUNNER=false`):** `HttpInternalRunClient` is the default measurement runner in deployed configurations.
- **Zero Synthetic Fallback in Production:** In production, `HttpInternalRunClient` **never** falls back to synthetic measurements on connection or execution failures. If `execution-service` cannot be reached or is saturated, the service raises an `ExecutionServiceUnavailableError` resulting in HTTP 503 `ROOM_SERVICE_UNAVAILABLE` with `Retry-After: 5`, ensuring students never receive artificial complexity ratings.
- **`StubMeasurementRunner` Restricted to Tests/Dev:** Stubs are strictly isolated to development/unit tests via explicit test fixture injection (`set_measurement_runner()` in `tests/conftest.py`) or setting `USE_STUB_RUNNER=true`.
- **`HttpInternalRunClient`:** Production client connecting to `execution-service` container runner over HTTP. Propagates internal credentials, parses `completed_runs`, detects timeouts, validates full batch completion, and extracts 429 budget refusal metadata.

### Asynchronous Delivery & Zero Execution Delay (NFR9, CR08)
- **Zero Run Delay:** Code execution results are returned immediately to the student without waiting for complexity measurements.
- **Separate WebSocket Message:** Complexity analysis is performed asynchronously and dispatched as a separate `analysis-result` message with `task: "complexity"`.
- **Identical Audience:** Dispatched to the same room or sessions that received the original run result.

### Privacy & Data Minimization (NFR7.5, NFR8.2)
- **No AI Call Required (NFR7.5):** Complexity curve fitting is pure mathematical statistics. It operates unconditionally regardless of user consent (`consent: false`) or the room AI switch (`ai_enabled: false`).
- **Metadata-Only Logging (NFR8.2):** Service logs record only request identifiers, input sizes, slope, $R^2$, and status codes. User source code and stdin input text are **never logged**.

## Optimization Sandbox Verification (US-66, FR36.3)

### User Story & Purpose (US-66)
As a student, I want a suggested fix tested before it is called an improvement so that I can trust it.

A suggested optimization fix is executed in the isolated sandbox under identical conditions to the original program. It is presented as a **verified improvement** (`verified: true`) only if its measured execution time is genuinely lower than the original code under comparable conditions. If the fix fails to compile, crashes, times out, exceeds the run budget, produces mismatched output, or fails to beat the noise margin, it is presented without that label (`verified: false`), accompanied by the measured numbers and an explicit explanation.

### Definition of "Lower" & "Comparable Conditions" (FR36.3)

#### 1. Statistic Compared: Medians across Repeats
- **Statistic:** The comparison uses the median execution time ($\tilde{T}$) rather than the arithmetic mean or minimum:
  $$\tilde{T} = \operatorname{median}(t_1, t_2, \dots, t_k)$$
  Medians are robust against transient operating system scheduling jitter, garbage collection interruptions, and container startup variance.
- **Evaluation Input Size:** To measure algorithmic improvement, the primary comparison evaluates medians at the largest completed geometric input size ($4n$, or across the matched input points) where asymptotic complexity differences dominate over constant startup overheads.
- **Speedup Percentage Formula:**
  $$\text{speedup\_pct} = \frac{\tilde{T}_{\text{orig}} - \tilde{T}_{\text{fix}}}{\tilde{T}_{\text{orig}}} \times 100$$

#### 2. Noise Margin Configuration (`VERIFICATION_NOISE_MARGIN_PCT`)
- **Noise Threshold:** A fix is declared a verified improvement **only if** the speedup strictly exceeds the configured noise margin $\delta$ (default `5.0`% via `VERIFICATION_NOISE_MARGIN_PCT`):
  $$\text{speedup\_pct} > \delta$$
  Equivalently:
  $$\tilde{T}_{\text{fix}} < \tilde{T}_{\text{orig}} \cdot \left(1 - \frac{\delta}{100}\right)$$
- **Boundary Handling:**
  - If $\text{speedup\_pct} > 5.0\%$: Marked `verified: true` with `verified_speedup_pct = round(speedup_pct, 1)`.
  - If $0 \le \text{speedup\_pct} \le 5.0\%$: Marked `verified: false`, `verified_speedup_pct = null`. Reason explicitly states that the gain is within the 5.0% noise margin.
  - If $\text{speedup\_pct} < 0$: Marked `verified: false`, `verified_speedup_pct = null`. Reason states that the fix is slower than the original.

#### 3. Run Ordering to Reduce Drift
- **Drift Mitigation Protocol:** Sequential execution of all original runs followed by all fix runs can suffer from systematic hardware drift (e.g. CPU thermal throttling, CPU frequency scaling changes, or changing host load).
- **Execution Strategy:**
  1. Identical Sandbox Constraints: Both programs execute under identical container cgroup limits (128 MB RAM, swap disabled, 5 s wall-clock time limit, 64 pids, `--network none`).
  2. Balanced Ordering: Runs for the original and suggested fix are executed under matched conditions using the same geometric sizes ($n, 2n, 4n$) and repeats ($k=3$), minimizing temporal drift between variants.

#### 4. Output Equivalence Check
- A faster program that produces different output is **not** an improvement.
- When outputs are captured across the measured inputs, the service checks that `output_fix(input) == output_orig(input)`.
- If an output mismatch is detected on any input size:
  - `verified: false`
  - `verified_speedup_pct: null`
  - Reason explicitly notes: `"Output mismatch on input '<input>': fix produced different output than original code."`

#### 5. Reporting Numbers & Failure Explanations
- **Measured Numbers Included:** No claim is made without numbers. Both `original_runtime_ms` and `patch_runtime_ms` are recorded in the response alongside `verification_reason`.
- **Compile Failure:** If the fix fails compilation (syntax error, type error, or missing symbol), `verified: false` is returned with the compiler message.
- **Runtime Crash:** If the fix terminates with a non-zero exit code or uncaught exception, `verified: false` is returned with the crash details.
- **Timeout (5 s limit, NFR4.1):** If the fix hits the 5,000 ms wall-time limit at any size, `verified: false` is returned naming the size that timed out.
- **Budget Refusal (`RUN_BUDGET_EXCEEDED`, 429, US-64):** If a measurement run is refused by `execution-service` (429), the fix remains unverified and surfaces the specific budget hit (`max_runs_per_request` or `aggregate_time_budget`).

### AI Gating & Consent Rules (FR12, FR35, NFR7.5)
- **Fix Generation:** Generating a fix via an external LLM requires `consent: true` and `ai_enabled: true`. If either is missing, the AI step is skipped (`status: "skipped"`, `error_code: "CONSENT_MISSING"` or `"AI_DISABLED"`), and the external AI service is called **0 times**.
- **Fix Verification:** Verifying an existing fix (either provided in `suggested_patch` or already generated) is pure sandbox execution and statistics. It requires **no AI call** and operates regardless of consent.

### Asynchronous Delivery & Zero Execution Delay (NFR9, CR08)
- Optimization verification never blocks or delays the initial execution result.
- Analysis outcomes are dispatched afterwards as a separate `analysis-result` message with `task: "complexity"` and `ai_step`.

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

## Monthly AI Cost Cap and Budget Alert (US-24, NFR7.1, NFR7.2, NFR7.4, NFR7.5)

To prevent runaway external AI provider costs and ensure predictable operational spend, the service enforces a hard monthly expenditure cap and early warning alerts.

### 1. Spending Calculation & Accounting Period (NFR7.1)
- **Calculation Formula:** `monthly_spend_usd = round(monthly_tokens_used * TOKEN_COST_PER_TOKEN, 4)`
- **Actual Tokens Only:** Tokens are extracted directly from upstream LLM provider responses (`usageMetadata.totalTokenCount` for Gemini or `usage.total_tokens` for OpenAI). Token counts are **never estimated** from user text or prompt length (FR40.1).
- **Accounting Month:** Accounting operates strictly on **UTC calendar months** (`datetime.now(timezone.utc)`). At 00:00:00 UTC on the first of each month (including year transitions such as December 31 to January 1), monthly spend counters and alert flags automatically reset to zero. Lifetime platform totals (`total_model_calls`, `total_tokens_used`, `total_cache_hits`, `total_fallback_activations`) remain preserved across resets.

### 2. Configuration Parameters
The cost cap is controlled via service environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `MONTHLY_BUDGET_USD` | `10.0` | Maximum monthly budget in USD for external AI provider calls (NFR7.2). Must be a finite positive number (`> 0.0`). Zero, negative values, and non-finite floats (`inf`, `nan`) are rejected at startup and configuration time (fail-closed). |
| `AI_MAX_TOKENS_PER_REQUEST` | `4096` | Canonical maximum tokens the AI may generate per request (FR40.4). Drives both provider request payload parameters (`maxOutputTokens` / `max_tokens`) and admission reservation token estimates. Must be positive. |
| `LLM_MAX_OUTPUT_TOKENS` | `4096` | Backward-compatible legacy alias for `AI_MAX_TOKENS_PER_REQUEST`. If both are provided and differ, `AI_MAX_TOKENS_PER_REQUEST` takes precedence with a startup warning. |
| `TOKEN_COST_PER_TOKEN` | `0.0000035` | Price in USD per consumed token (Gemini 1.5 Flash reference rate: $0.0035 / 1K tokens). |
| `ALERT_WEBHOOK_URL` | `None` | Optional HTTPS webhook URL for external alert notifications (NFR7.4). |

### 3. 80% Budget Warning Alert (NFR7.4)
- **Trigger:** When monthly spending reaches or exceeds 80% of `MONTHLY_BUDGET_USD` (e.g. $8.00 on a $10.00 budget), an alert is delivered to the team lead.
- **Deduplication:** The alert is sent **exactly once** during that UTC calendar month. Subsequent requests do not generate duplicate notifications. The alert state resets automatically when a new UTC month begins.
- **Delivery Mechanism:**
  - A structured warning-level log is always emitted:
    `AI_COST_CAP_ALERT: Monthly external AI spending has reached or exceeded 80% of budget. spend_usd=... budget_usd=... threshold_usd=... month=YYYY-MM`
  - If `ALERT_WEBHOOK_URL` is configured, an HTTP POST payload is dispatched with metadata asynchronously via a dedicated background thread pool (`ThreadPoolExecutor`). Webhook delivery is completely non-blocking: the usage tracker lock is never held during HTTP I/O, and webhook delays, network timeouts, or external failures cannot stall concurrent FastAPI request handlers or corrupt spending state.
- **Privacy Guarantee (NFR8.2):** Alerts contain numeric and temporal metadata only (`spend_usd`, `budget_usd`, `threshold_usd`, `month`). Alerts **never** contain source code, stderr, prompts, model responses, or user text.

### 4. Cost-Cap Enforcement & Skipped AI Steps (NFR7.2, T-18 Decision 10)
- **Pre-Call Enforcement:** Every outbound paid-model call passes through `app.counters.reserve_call` and `app.llm.client.call_llm_with_consent`. When monthly spend has reached or exceeded the configured budget:
  - The external LLM is **not called** (zero HTTP requests dispatched).
  - Zero tokens are consumed.
  - The request returns HTTP 200 with the AI step skipped:
    ```json
    "ai_step": {
      "status": "skipped",
      "error_code": "COST_CAP_REACHED",
      "explanation": null,
      "suggested_fix": null
    }
    ```
  - The system fallback counter (`total_fallback_activations`) is incremented.
- **Gate Precedence:** User consent (`CONSENT_MISSING`) and the Host AI switch (`AI_DISABLED`) are evaluated before the cost cap. If consent or host approval is missing, their specific skip codes take precedence.
- **Cache Preservation:** Existing cached results in `AnalysisCache` remain fully usable and are served with `status: "ok"` without making outbound paid calls even when the monthly cost cap has been reached.

### 5. Preservation of AI-Free Functionality (NFR7.5)
The AI/ML service is never in the critical path of code execution or editing (NFR9). When the AI cost cap is reached:
- Code editing and room synchronization continue normally.
- Code execution in Docker containers continues unaffected.
- **Rules-First Static Analysis:** Deterministic AST checks (e.g. nested loops, string concatenation in loops) execute and return findings normally.
- **Empirical Regression:** Mathematical log-log regression and Big-O complexity estimation continue unaffected.
- **Sanitizer Parsing:** AddressSanitizer/UBSan diagnostic parsing for C/C++ crashes extracts `error_type`, `line_number`, and `summary` deterministically.

### 6. Concurrency Strategy, Overshoot Bound & Operational Limitations
- **Atomic Reservation Logic:** To prevent multiple concurrent requests from reading a stale spending total and stampeding past the budget cap, `UsageTracker.reserve_call` atomically reserves estimated token headroom under a thread lock before dispatching an outbound request. If active reservations + committed spend reach or exceed the budget, subsequent concurrent requests are immediately blocked with `CostCapReachedError`.
- **Reservation Lifecycle & Stale Reservation Eviction (TTL):** Active reservations track in-flight provider calls with deterministic finalization guarantees provided by Python context managers (`with tracker.reserve_call(...)`) and `try...finally` blocks. To guard against orphaned reservations leaking budget headroom due to unexpected process crashes or unhandled task drops, each reservation records a `created_at` timestamp and is automatically evicted if it exceeds a TTL of `2 * settings.LLM_TIMEOUT_SECONDS` (default 20s). Every normal or error completion path immediately commits, conservatively commits (uncertain timeouts), or releases the reservation.
- **Admission Estimate & Overshoot Dynamics:** The reservation is an **admission estimate**, not a strict upper bound. External LLM providers (Google Gemini, OpenAI) bill on **total tokens** (input prompt tokens + output generated tokens) and do not provide a pre-flight token quoting API. Because total tokens include user prompt length, the final overshoot on the single admitted in-flight call is $(\text{actual\_total\_tokens} - \text{reserved\_tokens}) \times \text{TOKEN\_COST\_PER\_TOKEN}$, and is **not strictly bounded** by `maxOutputTokens`. Once this final call commits, all subsequent calls are strictly blocked.
- **Uncertain Post-Dispatch Outcomes:** Pre-dispatch network failures (e.g. `ConnectError`, `ConnectTimeout`) mean the request never reached the provider and release reservations with zero cost. For uncertain post-dispatch outcomes (e.g. `ReadTimeout`, connection drop while reading response, or client cancellation after transmission), the provider may have executed generation and billed the account; the service employs conservative accounting by committing the reserved tokens to monthly spend so expenditure is never undercounted.
- **Cross-Month Rollover for In-Flight Calls:** When a UTC month rollover occurs while calls are in flight, active reservations are preserved (not cleared) so they continue holding headroom in the new month. When generation finishes, their spend is attributed to the month in which completion occurs, preventing reservation leaks or orphaned tokens.
- **Single-Process Deployment Constraint:** The service is configured and deployed as a single container running a single Uvicorn process (`uvicorn app.main:app` in `Dockerfile`). The in-memory `UsageTracker` guarantees thread safety within this single process via `threading.Lock`. Multiple worker processes (`uvicorn --workers N`) or multiple container replicas do **not** provide a globally enforced budget cap with the current in-memory tracker; multi-process/multi-replica safety is not claimed without an external coordination layer (e.g. Redis atomic counters).

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

## Plain-Language Crash Explanation (US-74, FR13.1, FR39.3, FR40.2, DR5)

The crash explanation endpoint (`POST /v1/analyses/crash-explanation`) generates plain-language failure explanations and suggested fixes for compiler sanitizer crashes in C and C++ programs.

### 1. Language Restriction (DR5)
- **Supported Languages:** C (`c`) and C++ (`cpp`) only.
- **Unsupported Languages:** Any request specifying other coursework languages (e.g. `python`, `java`, `javascript`) is immediately rejected with HTTP `422 Unprocessable Entity` and error code `UNSUPPORTED_LANGUAGE` without invoking any AI models.

### 2. Privacy & Data Minimization (FR40.2, US-76)
- **Localized Code Only:** Uses `app.section.extract_relevant_section` to extract a small, bounded window of code lines centered on the crash `line_number`. Full files and unrelated modules are never sent.
- **Dedicated Prompt:** The outbound LLM prompt contains strictly the deterministic sanitizer summary and the localized code section. Raw ASan/UBSan stack traces, container exit codes, and execution context metadata are excluded.
- **Canary Isolation:** Code outside the localized window (including canary markers or secrets) is guaranteed never to leak into prompts.

### 3. Output Limitation & Response Schema (FR13.1, T-18 v2 §16.4)
- **Three-Sentence Cap:** The generated explanation (`ai_step.explanation`) is strictly constrained to at most three sentences in plain prose without markdown headers, bold asterisks, backticks, or filler greetings.
- **Suggested Fix:** A practical code or algorithmic remediation is extracted and returned in `ai_step.suggested_fix`.
- **AI Gating:** When consent is missing (`CONSENT_MISSING`), AI is disabled by Host (`AI_DISABLED`), or the monthly budget cap is reached (`COST_CAP_REACHED`), the endpoint returns HTTP 200 with deterministic `error_type`, `line_number`, and `summary` preserved, and `ai_step.status = "skipped"`.

### 4. Method Chain Integration (US-77)
- Evaluated through the central method chain (`app.chain.answer`) with `rules_first = False` to prevent code containing loops from being prematurely intercepted by complexity pattern rules.

## Cheapest-Method-First AI Chain (US-77, FR40.3, FR40.4)

To minimize unnecessary external API expenditure and latency, all AI-enabled analysis and explanation endpoints (`POST /v1/analyses/complexity`, `POST /v1/analyses/crash-explanation`, `POST /v1/explain`) route requests through a single central method chain: `app.chain.answer(request)`.

### 1. Strict Execution Order

The chain executes analysis requests in strictly ascending cost order:
1. **Stage 1 — Cache ($0, 0 latency):** In-memory normalized cache lookup (US-22).
2. **Stage 2 — Deterministic Rules & Statistics ($0):** Static AST/regex pattern detection (US-67).
3. **Stage 3 — Optional Local Model ($0 external cost):** Extensible local model runner interface (US-77).
4. **Stage 4 — Paid External Model ($$):** Central outbound LLM dispatch subject to consent, Host AI switch, room rate limits, prompt shortening, token budgets, and cost cap admission (US-17, US-21, US-24, US-76, US-77).

### 2. Early-Exit Stopping Criteria

The chain terminates at the earliest possible stage that can satisfy the request:

1. **Stage 1 — Cache (US-22):**
   - If the exact normalized request key matches an existing entry in `AnalysisCache`, the cached response is returned immediately with `stage="cache"`.
   - Stages 2, 3, and 4 are called **zero times**.
   - Increments the `cache_hits` telemetry counter.

2. **Stage 2 — Deterministic Rules (US-67, FR40.3):**
   - For complexity analysis requests with `rules_first=True`, the AST/regex analyzer evaluates the code.
   - If one or more slow code patterns (e.g., nested loops, regex in loops) are matched, the rule findings are returned immediately with `stage="rules"`.
   - The local model and paid model are called **zero times**.
   - Increments the `rules_answers` telemetry counter.

3. **Stage 3 — Local Model (US-77):**
   - Dispatches to an optional locally hosted or containerized model runner.
   - If the local model runner produces an answer string, it returns immediately with `stage="local_model"`.
   - The paid external model is called **zero times**.
   - Increments the `local_model_answers` telemetry counter.
   - If no local model is configured, or if the runner returns `None` or encounters an error, the chain safely falls through to Stage 4 without failing the request.

4. **Stage 4 — Paid External Model (US-17, US-21, US-24, US-77):**
   - Invoked **only** when Stages 1, 2, and 3 cannot answer.
   - Subject to strict consent gating, Host AI switch enforcement, hourly room quotas, and monthly budget cost-cap admission.
   - Increments `paid_model_calls` and updates token spend telemetry.

### 3. Optional Local-Model Interface

The local-model capability is exposed via a clean, one-function interface:
```python
from app.chain import query_local_model, set_local_model_runner

# Define a custom local runner (e.g. Ollama, vLLM, llama.cpp, LocalAI)
async def my_runner(prompt: str, context: dict | None = None) -> str | None:
    ...

set_local_model_runner(my_runner)
```

- **Zero-Dependency Guarantee:** The local model is entirely optional. No local model weights, PyTorch binaries, or runtime dependencies are required for standard installation or testing.
- **Environment Configuration:**
  - `LOCAL_MODEL_ENABLED`: Boolean toggle (default `False`).
  - `LOCAL_MODEL_URL`: Optional endpoint URL for an on-premise inference server.
  - `LOCAL_MODEL_NAME`: Optional model identifier.

### 4. Prompt-Size and Output-Token Budget Enforcement (FR40.4)

Both prompt size and completion output tokens are strictly configurable to prevent runaway costs:

| Setting | Environment Variable | Default | Purpose |
|---|---|---|---|
| `AI_MAX_PROMPT_LENGTH` | `AI_MAX_PROMPT_LENGTH` | `4000` | Upper bound on input prompt length in characters |
| `LLM_MAX_OUTPUT_TOKENS` | `LLM_MAX_OUTPUT_TOKENS` | `256` | Maximum token budget requested from the external LLM provider |

- **Relevant-Code Extraction Shortening (US-76, FR40.2):** When a constructed prompt exceeds `AI_MAX_PROMPT_LENGTH`, the central chain calls `extract_relevant_section()` on the source code to extract only the target function or bounded error line window.
- **Safety Omission Guarantee:** If the prompt cannot be shortened below `AI_MAX_PROMPT_LENGTH`, the paid model call is safely skipped with `status="skipped"` and `error_code="PROVIDER_ERROR"` (with `metadata.reason="prompt_too_large"` conforming strictly to contract schemas) rather than transmitting an oversized payload.
- **Provider Output Token Forwarding:** The configured `LLM_MAX_OUTPUT_TOKENS` is explicitly transmitted in outbound requests (`maxOutputTokens` for Google Gemini, `max_tokens` for OpenAI).

### 5. Consent, Host AI Switch, and Cost-Cap Integration

- **Dual-Gate Protection:** If `consent` is `False` or missing (`CONSENT_MISSING`), or if the room Host AI switch `ai_enabled` is `False` (`AI_DISABLED`), the paid external LLM step is skipped. However, **Stage 1 (Cache)** and **Stage 2 (Deterministic Rules)** remain 100% active and return HTTP 200 with complete AST findings.
- **Cost-Cap Admission:** Before every paid-model dispatch, the system verifies `is_cost_cap_reached()` and calls `reserve_call(estimated_tokens=...)`. If the monthly budget cap has been reached (or if cost-cap protection is unavailable/fails):
  - The paid model is immediately blocked with `error_code="COST_CAP_REACHED"`.
  - Zero outbound HTTP requests are dispatched to external providers.
  - Free cache hits and deterministic rule answers continue to function normally.

### 6. Usage Counters and Privacy Invariant (US-75, NFR8.2)

Usage counters accurately record which stage answered each request:
- `cache_hits`: Stage 1 cache hits
- `rules_answers`: Stage 2 deterministic rule answers
- `local_model_answers`: Stage 3 local model answers
- `paid_model_calls`: Stage 4 paid external API calls
- `fallbacks`: Skipped or fallback activations

Counters and application logs record **only** numeric counts, token consumption, room IDs, and error codes. Under no circumstances do logs or counter structures record source code, prompts, user input, or AI-generated answers (NFR8.2).

### 7. US-24 Monthly Cost-Cap Integration Status

- **Base Dependency:** US-77 is rebased directly on `feature/US-24-monthly-cost-cap` (PR #156), inheriting the persistent `UsageTracker`, atomic reservation lifecycle (`reserve_call()`), non-blocking webhook delivery (`ThreadPoolExecutor`), and `CostCapReachedError`.
- **Zero Duplication:** Stage telemetry counters (`record_rules_answer()`, `record_local_model_answer()`, `get_stage_counters()`) cleanly extend `UsageTracker` without duplicating cost-cap accounting code.


## US-70: Failing-input search

`POST /v1/analyses/failing-input` uses the v2 request and response schemas in
`docs/schemas/failing-input-*.json`. Requests begin with conservative deterministic
edge cases: empty stdin and `1`. For evident whitespace-separated numeric input,
duplicate and negative examples are also tried. Maximum bounds and any unknown
input grammar are **not guessed**. The response headers
`X-Failing-Input-Skipped-Cases`, `X-Failing-Input-Reference` and
`X-Failing-Input-Scope` communicate omissions and the search's limitations.

No reference: only crashes/timeouts can be established; a pass is **not proof of
correctness**. To compare output, an authoritative reference supplied by the
internal orchestrator is required with `X-Internal-Token`. The reference is
executed in execution-service Docker using `purpose=test_batch` to obtain
expected stdout; the student's batch is then executed against those outputs.
The current US-71 contract does not execute `reference_solution` itself.
The reference source must be server-controlled and must never be taken from
untrusted public clients. If the reference fails, comparison is disabled.

An AI-generated Python input script is optional, only with consent and AI on.
It is obtained through the US-77 cheapest-method chain and US-22 cache. Its
output is accepted only after the script has run as a Docker `test_batch`
inside execution-service. No generated or submitted code is executed by
ai-ml-service. A per-analysis process cap and time budget apply across both
reference and candidate batches; 429 budget refusals preserve partial results.
US-72 owns actual input minimization: US-70 preserves the original value in
`minimized_input` only as a placeholder, not as evidence of shrinking.

Set `EXECUTION_SERVICE_URL` to the internal execution-service origin and
`INTERNAL_API_TOKEN` to the shared internal secret. Additional configurable
limits: `FAILING_INPUT_MAX_RUNS` (default 8; max 10),
`FAILING_INPUT_BUDGET_SECONDS` (default/max 20),
`FAILING_INPUT_HTTP_TIMEOUT_SECONDS` (default 35), and
`FAILING_INPUT_SCRIPT_MAX_CHARS` (default 4000).

Run `pytest -k "failing_input or edge"` in `ai-ml-service/`. The unit tests
mock Docker execution, so user code never runs on the host.
