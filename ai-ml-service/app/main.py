"""FastAPI entrypoint for ai-ml-service.

Provides GET /health probe and routes conforming to docs/ai-ml-contract.md (T-18 v2).
"""

import logging
import time

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.config import settings
from app.llm.client import ConsentMissingError
from app.routers import analyses, explain

# Configure basic logging to explicitly exclude request bodies (NFR8.2)
logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger("ai_ml_service")

app = FastAPI(
    title="Collab Code Sandbox — AI/ML Service",
    description="Microservice for analysis tasks, complexity calculation, and failure explanation under T-18 v2 contract.",
    version="0.2.0",
)


@app.middleware("http")
async def log_requests(request: Request, call_next):
    """Log request metadata only, strictly omitting request bodies/raw code (NFR8.2).

    Logs one structured line per AI/ML call with the fields mandated by
    docs/ai-ml-contract.md §8 (NFR8.1, US-25):
        request_id, room_id, request_type, language, status, error_code, latency_ms

    room_id is read from request.state after the route handler has validated
    the request body and stored it there.  For requests that never reach a
    route handler (e.g. 422 validation errors) a fixed placeholder is used so
    the log line is never 'unknown' and never contains user-supplied text.
    """
    # Start monotonic clock *before* processing so latency covers every path.
    t_start = time.monotonic()

    # Initialise state fields that routes will populate after validation.
    request.state.room_id = None
    request.state.request_id = None
    request.state.request_type = None
    request.state.language = None
    request.state.error_code = None

    response = await call_next(request)

    latency_ms = round((time.monotonic() - t_start) * 1000, 1)

    # Pull validated metadata written by the route handler (or fall back to
    # safe, non-user-text placeholders — never log anything the user wrote).
    room_id = getattr(request.state, "room_id", None) or "no-room"
    request_id = getattr(request.state, "request_id", None) or "no-id"
    request_type = getattr(request.state, "request_type", None) or "unknown-type"
    language = getattr(request.state, "language", None) or "unknown-lang"
    error_code = getattr(request.state, "error_code", None) or "none"

    logger.info(
        "request_id=%s room_id=%s request_type=%s language=%s "
        "status=%s error_code=%s latency_ms=%s",
        request_id,
        room_id,
        request_type,
        language,
        response.status_code,
        error_code,
        latency_ms,
    )
    return response


@app.exception_handler(ConsentMissingError)
async def consent_missing_exception_handler(request: Request, exc: ConsentMissingError):
    """Format consent missing errors into contract envelope (FR12.2, contract §5)."""
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "request_id": getattr(request.state, "request_id", None),
            "error": {
                "code": exc.code,
                "message": exc.message,
                "retry_after_seconds": exc.retry_after_seconds,
            },
        },
    )


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    """Format HTTP errors according to the contract error envelope (contract §5).

    NFR8.2: The error message is a fixed string from the exception's detail
    dict — it is never user-supplied text echoed back.
    """
    detail = exc.detail
    if isinstance(detail, dict) and "code" in detail:
        # Record the structured error code so the middleware can log it.
        request.state.error_code = detail.get("code", "HTTP_ERROR")
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "request_id": getattr(request.state, "request_id", None),
                "error": detail,
            },
        )
    request.state.error_code = "HTTP_ERROR"
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
    """Format validation errors into the contract error envelope (contract §5).

    NFR8.2: The log line written by the middleware MUST NOT echo the invalid
    user input back.  We deliberately do NOT include exc.errors() (which can
    contain field values) in any log statement — only the fixed error code
    'INVALID_REQUEST' is recorded.
    """
    # Record error code for the middleware log line — never log exc.errors()
    # because Pydantic includes the bad field value in the error detail.
    request.state.error_code = "INVALID_REQUEST"

    # Try to extract room_id from the raw body without risking any log leak.
    # If room_id is present and is a plain string it is safe to record;
    # otherwise we fall back to the safe placeholder already set in state.
    try:
        body = await request.body()
        import json as _json

        raw = _json.loads(body)
        if isinstance(raw, dict):
            _room = raw.get("room_id")
            if isinstance(_room, str) and _room:
                request.state.room_id = _room
            _rid = raw.get("request_id")
            if isinstance(_rid, str) and _rid:
                request.state.request_id = _rid
            _rt = raw.get("request_type")
            if isinstance(_rt, str) and _rt:
                request.state.request_type = _rt
            _lang = raw.get("language")
            if isinstance(_lang, str) and _lang:
                request.state.language = _lang
    except Exception:  # noqa: BLE001
        pass  # body unreadable or not JSON — keep safe placeholders

    return JSONResponse(
        status_code=422,
        content={
            "request_id": getattr(request.state, "request_id", None),
            "error": {
                "code": "INVALID_REQUEST",
                "message": "Schema validation failed: one or more required fields are missing or invalid.",
                "retry_after_seconds": None,
            },
        },
    )


@app.get("/health")
def health_check():
    """Liveness probe endpoint returning HTTP 200.

    Conforms to docs/ai-ml-contract.md §4.1 and §14 (v2).
    """
    return {"status": "ok", "version": "v2"}


# Register API routers
app.include_router(analyses.router, tags=["analyses"])
app.include_router(explain.router)
