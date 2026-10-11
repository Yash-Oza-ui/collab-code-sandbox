from fastapi import FastAPI
from app.config import settings

app = FastAPI(
    title="AI/ML Service",
    description="Collab Code Sandbox AI/ML Microservice",
    version="0.1.0",
)


@app.get("/health")
def health_check():
    """Liveness probe endpoint returning HTTP 200 and ok status."""
    return {"status": "ok"}
