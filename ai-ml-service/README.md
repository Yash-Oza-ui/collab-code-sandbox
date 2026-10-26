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