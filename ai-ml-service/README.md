# AI/ML Service

The AI/ML microservice for **Collab Code Sandbox** (IT-314, Group 32). This service provides on-demand AI code reviews, failure explanations, and ML-based execution anomaly detection.

## Prerequisites

- Python 3.10+ (Python 3.11 recommended)
- `pip` and `venv`
- Docker (for containerized deployment)

## Architecture & Responsibilities

- **Scaffold (T-04):** FastAPI web service running on port 8000, health check at `/health`.
- **Contracts (T-07):** Endpoints and payloads conform to `../docs/ai-ml-contract.md`.
- **Endpoints:**
  - `GET /health`: Liveness probe for container orchestration and smoke tests.
  - `POST /v1/anomaly`: Metric-only anomaly scoring (T-13, T-15).
  - `POST /v1/explain`: Plain-language failed run explainer (FR13, T-16).
  - `POST /v1/review`: On-demand code review (FR7, FR12).

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

> **Security & Privacy Note (NFR8.2, NFR10):**
> Never commit `.env` or real API keys to version control. The service starts normally even when `LLM_API_KEY` is not set. The service never logs request bodies, source code, stderr text, or API keys.

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

## Anomaly Detection Baseline (T-15)

**Note:** The current anomaly model is a minimal baseline trained **exclusively on synthetic data** (T-12) to establish the pipeline. It does not yet reflect real user execution metrics. 

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
