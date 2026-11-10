"""FastAPI entrypoint for ai-ml-service.

Provides GET /health probe and routes conforming to docs/ai-ml-contract.md (T-18 v2).
"""

import json
import logging
import re
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

_PATH_REQUEST_TYPE_MAP = {
    "/health": "health",
    "/v1/explain": "explanation",
    "/v1/analyses/complexity": "complexity",
    "/v1/analyses/slowdown": "slowdown",
    "/v1/analyses/failing-input": "failing_input",
    "/v1/analyses/crash-explanation": "crash_explanation",
    "/v1/usage": "usage",
    "/v1/analyses/usage": "usage",
}
_ALLOWED_REQUEST_TYPES = frozenset(_PATH_REQUEST_TYPE_MAP.values())
_ALLOWED_LANGUAGES = frozenset({"cpp", "python", "c", "java", "javascript", "none"})
_NON_LANGUAGE_PATHS = frozenset({"/health", "/v1/usage", "/v1/analyses/usage"})
_SAFE_IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")

app = FastAPI(
    title="Collab Code Sandbox — AI/ML Service",
    description="Microservice for analysis tasks, complexity calculation, and failure explanation under T-18 v2 contract.",
    version="0.2.0",
)


@app.middleware("http")
async def log_requests(request: Request, call_next):
    """Log request metadata only, strictly omitting request bodies/raw code (NFR8.2).

    Logs one structured line per AI/ML call with the fields mandated by
    docs/ai-ml-contract.md §9 & §21 (NFR8.1, US-25):
        request_id, room_id, request_type, language, status, error_code, latency_ms

    Metadata is read from request.state after route validation (or allowlisted
    pre-route extraction for 422 errors), falling back to fixed safe defaults.
    """
    # Start monotonic clock *before* processing so latency covers every path.
    t_start = time.monotonic()

    # Initialise state fields that routes will populate after validation.
    request.state.room_id = None
    request.state.request_id = None
    request.state.request_type = None
    request.state.language = None
    request.state.error_code = None

    status_code = 500
    try:
        response = await call_next(request)
        status_code = response.status_code
        return response
    except Exception:
        if not getattr(request.state, "error_code", None):
            request.state.error_code = "INTERNAL_ERROR"
        raise
    finally:
        latency_ms = round((time.monotonic() - t_start) * 1000, 1)

        # Pull validated metadata written by the route handler (or fall back to
        # safe, non-user-text placeholders — never log anything the user wrote).
        room_id = getattr(request.state, "room_id", None) or "no-room"
        request_id = getattr(request.state, "request_id", None) or "no-id"
        request_type = (
            getattr(request.state, "request_type", None)
            or _PATH_REQUEST_TYPE_MAP.get(request.url.path)
            or "unknown-type"
        )
        default_lang = "none" if request.url.path in _NON_LANGUAGE_PATHS else "unknown-lang"
        language = getattr(request.state, "language", None) or default_lang
        error_code = getattr(request.state, "error_code", None) or "none"

        logger.info(
            "request_id=%s room_id=%s request_type=%s language=%s "
            "status=%s error_code=%s latency_ms=%s",
            request_id,
            room_id,
            request_type,
            language,
            status_code,
            error_code,
            latency_ms,
        )


@app.exception_handler(ConsentMissingError)
async def consent_missing_exception_handler(request: Request, exc: ConsentMissingError):
    """Format consent missing errors into contract envelope (FR12.2, contract §5)."""
    request.state.error_code = exc.code
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

    # Extract only strictly allowlisted / safe-format metadata from the raw body
    # so arbitrary or invalid user-supplied values never leak into logs (NFR8.2).
    try:
        body = await request.body()
        raw = json.loads(body)
        if isinstance(raw, dict):
            _room = raw.get("room_id")
            if isinstance(_room, str) and _SAFE_IDENTIFIER_RE.fullmatch(_room):
                request.state.room_id = _room
            _rid = raw.get("request_id")
            if isinstance(_rid, str) and _SAFE_IDENTIFIER_RE.fullmatch(_rid):
                request.state.request_id = _rid
            _rt = raw.get("request_type")
            if isinstance(_rt, str) and _rt in _ALLOWED_REQUEST_TYPES:
                request.state.request_type = _rt
            _lang = raw.get("language")
            if isinstance(_lang, str) and _lang in _ALLOWED_LANGUAGES:
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
