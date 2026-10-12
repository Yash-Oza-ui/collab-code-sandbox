"""FastAPI entrypoint for ai-ml-service.

Scaffold implementation for issue T-04 (Category C).
Provides GET /health probe and routes conforming to docs/ai-ml-contract.md.
"""

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError, HTTPException

from app.config import settings
from app.routers import anomaly, explain, review

app = FastAPI(
    title="Collab Code Sandbox — AI/ML Service",
    description="Microservice for code review, failure explanation, and execution anomaly scoring.",
    version="0.1.0",
)
import logging

# Configure basic logging to explicitly exclude request bodies
logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger("ai_ml_service")

@app.middleware("http")
async def log_requests(request: Request, call_next):
    """Log request metadata only, strictly omitting request bodies/raw code (NFR8.2)."""
    response = await call_next(request)
    logger.info(
        f"Request: {request.method} {request.url.path} "
        f"| Status: {response.status_code} "
        f"| Room: {getattr(request.state, 'room_id', 'unknown')}"
    )
    return response


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    """Format HTTP errors according to the contract error envelope (contract §5)."""
    detail = exc.detail
    if isinstance(detail, dict) and "code" in detail:
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "request_id": getattr(request.state, "request_id", None),
                "error": detail,
            },
        )
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "request_id": getattr(request.state, "request_id", None),
            "error": {
                "code": "HTTP_ERROR",
                "message": str(detail),
                "retry_after_seconds": None,
            },
        },
    )


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    """Format validation errors into the contract error envelope (contract §5)."""
    return JSONResponse(
        status_code=422,
        content={
            "request_id": getattr(request.state, "request_id", None),
            "error": {
                "code": "INVALID_REQUEST",
                "message": f"Schema validation error: {exc.errors()}",
                "retry_after_seconds": None,
            },
        },
    )


@app.get("/health")
def health_check():
    """Liveness probe endpoint returning HTTP 200.

    Conforms to docs/ai-ml-contract.md §4.1 and tests/integration/smoke.sh.
    """
    return {"status": "ok", "version": "v0"}


# Register API routers
app.include_router(anomaly.router)
app.include_router(explain.router)
app.include_router(review.router)
