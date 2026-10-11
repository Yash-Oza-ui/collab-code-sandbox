# AI/ML Service

The AI/ML microservice for **Collab Code Sandbox** (IT-314, Group 32). This service provides on-demand AI code reviews, failure explanations, and ML-based execution anomaly detection.

## Prerequisites

- Python 3.10+ (Python 3.11 recommended)
- `pip` and `venv`

## Installation

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

## Environment Variables

Copy the example configuration:
```bash
cp .env.example .env
```

| Variable | Description | Default | Required |
|---|---|---|---|
| `LLM_API_KEY` | API key for the external LLM provider | `None` | **No** (service starts without key) |
| `LLM_MODEL` | Target LLM model name | `gemini-1.5-flash` | No |

> **Security & Privacy Note (NFR8.2):**
> Never commit `.env` or real API keys to version control. The service starts normally even when `LLM_API_KEY` is not set. The service never logs request bodies, source code, stderr text, or API keys.

## Running the Service

Start the FastAPI application using Uvicorn:

```bash
uvicorn app.main:app --reload --port 8000
```

The service will be accessible at `http://localhost:8000`.

## Testing the Health Endpoint

### 1. Using curl
With the service running:
```bash
curl -i http://localhost:8000/health
```
Expected output:
```http
HTTP/1.1 200 OK
content-type: application/json

{"status":"ok"}
```

### 2. Using pytest
Run the automated test suite:
```bash
pytest -v
```
