# AI/ML Service

The AI/ML microservice for **Collab Code Sandbox** (IT-314, Group 32). This service provides on-demand AI code reviews, failure explanations, and ML-based execution anomaly detection.

## Prerequisites

- Python 3.10+ (Python 3.11 recommended)
- `pip` and `venv`
- Docker (for containerized deployment)

## Architecture & Responsibilities

- **Scaffold (T-04):** FastAPI web service running on port 8000, health check at `/health`.
- **Contracts (T-07):** Endpoints and payloads conform to `docs/ai-ml-contract.md`.
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

2. Create and activate a Python virtual environment:
   ```bash
   python -m venv venv
   source venv/bin/activate    # On Windows: venv\Scripts\activate
   ```

3. Install pinned dependencies:
   ```bash
   pip install -r requirements.txt
   ```

4. Set up environment configuration:
   ```bash
   cp .env.example .env
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
