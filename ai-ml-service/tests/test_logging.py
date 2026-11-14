"""Tests for AI/ML call logging (US-25, NFR8.1, NFR8.2).

Acceptance criteria verified here:
- Every AI/ML call writes one structured log line with the contract §9/§21 fields:
  request_id, room_id, request_type, language, status, error_code, latency_ms.
- room_id, request_id, request_type, and language come from validated request
  fields (never 'unknown' on valid requests).
- latency_ms is measured with a monotonic clock and logged on every path,
  including skipped AI calls, provider failures, and 422 validation errors.
- Logs and error responses never contain source code, stderr, sanitizer output,
  prompts, LLM output, or invalid user-supplied field values on any path.
- Explicit, schema-valid, endpoint-specific payload builders are used for every
  supported endpoint (/v1/explain, /v1/analyses/complexity,
  /v1/analyses/crash-explanation, plus T-18 contract-ready builders for
  /v1/analyses/slowdown and /v1/analyses/failing-input).
"""

import json
import logging
import sys
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fastapi.testclient import TestClient

from app.config import settings
from app.llm import LLMProviderError, LLMRateLimitError, LLMTimeoutError
from app.llm.client import ConsentMissingError
from app.main import app

client = TestClient(app, raise_server_exceptions=False)

# ---------------------------------------------------------------------------
# Constants & helpers
# ---------------------------------------------------------------------------

MARKER = "XMARKER_US25_NFR8_2_CONFIDENTIAL_XMARKER"
LLM_OUTPUT_MARKER = "XLLM_OUTPUT_SECRET_US25_NFR8_2"

_FASTAPI_INTERNAL_PATHS = frozenset({"/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"})

_MOCK_LLM_RESPONSE = httpx.Response(
    status_code=200,
    json={
        "candidates": [
            {
                "content": {
                    "parts": [
                        {
                            "text": (
                                f"Execution failed due to an error involving {LLM_OUTPUT_MARKER}. "
                                "Review the code. Fix the issue."
                            )
                        }
                    ],
                }
            }
        ]
    },
)


def _explain_payload(
    marker: str = MARKER,
    *,
    consent: bool | None = True,
    ai_enabled: bool | None = True,
    room_id: str = "room-test-explain-001",
    request_id: str | None = None,
    language: str = "python",
) -> dict:
    """Return a valid /v1/explain payload with marker in user-text fields."""
    payload: dict = {
        "request_id": request_id or str(uuid.uuid4()),
        "room_id": room_id,
        "request_type": "explanation",
        "language": language,
        "code": f"def run():\n    # {marker}\n    return 1 / 0",
        "stderr": f"ZeroDivisionError: {marker}",
        "exit_code": 1,
        "execution_time_ms": 12.5,
        "peak_memory_mb": 8.0,
        "timed_out": False,
        "oom_killed": False,
        "failure_kind": "non_zero_exit",
    }
    if consent is not None:
        payload["consent"] = consent
    if ai_enabled is not None:
        payload["ai_enabled"] = ai_enabled
    return payload


def _complexity_payload(
    marker: str = MARKER,
    *,
    consent: bool | None = True,
    ai_enabled: bool | None = True,
    room_id: str = "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
    request_id: str | None = None,
    language: str = "python",
) -> dict:
    """Return a valid /v1/analyses/complexity payload with marker in code & patch."""
    payload: dict = {
        "request_id": request_id or str(uuid.uuid4()),
        "room_id": room_id,
        "analysis_id": str(uuid.uuid4()),
        "request_type": "complexity",
        "language": language,
        "code": (
            f"# {marker}\n"
            "for i in range(10):\n"
            "    for j in range(10):\n"
            "        pass\n"
        ),
        "measurements": [
            {"input_size": 100, "runtimes_ms": [1.1, 1.2]},
            {"input_size": 200, "runtimes_ms": [4.4, 4.5]},
        ],
        "suggested_patch": f"# {marker}\ndef fast():\n    return 0\n",
        "suggested_patch_runtimes_ms": [0.5, 0.6],
    }
    if consent is not None:
        payload["consent"] = consent
    if ai_enabled is not None:
        payload["ai_enabled"] = ai_enabled
    return payload


def _crash_explanation_payload(
    marker: str = MARKER,
    *,
    consent: bool | None = True,
    ai_enabled: bool | None = True,
    room_id: str = "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
    request_id: str | None = None,
    language: str = "cpp",
) -> dict:
    """Return a valid /v1/analyses/crash-explanation payload with marker in crash fields."""
    payload: dict = {
        "request_id": request_id or str(uuid.uuid4()),
        "room_id": room_id,
        "analysis_id": str(uuid.uuid4()),
        "request_type": "crash_explanation",
        "language": language,
        "sanitizer_output": (
            f"==4210==ERROR: AddressSanitizer: heap-buffer-overflow {marker}\n"
            "    #0 0x401233 in process_buffer /workspace/solution.cpp:14"
        ),
        "code_snippet": f"// {marker}\nint val = buffer[index];\nreturn val * 2;",
    }
    if consent is not None:
        payload["consent"] = consent
    if ai_enabled is not None:
        payload["ai_enabled"] = ai_enabled
    return payload


def _slowdown_payload(
    marker: str = MARKER,
    *,
    consent: bool = True,
    ai_enabled: bool = True,
    room_id: str = "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
    request_id: str | None = None,
    language: str = "python",
) -> dict:
    """Return a contract-valid /v1/analyses/slowdown payload (T-18 Task 2 schema)."""
    return {
        "request_id": request_id or str(uuid.uuid4()),
        "room_id": room_id,
        "analysis_id": str(uuid.uuid4()),
        "request_type": "slowdown",
        "language": language,
        "consent": consent,
        "ai_enabled": ai_enabled,
        "input_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "current_run": {
            "execution_time_ms": 1420.0,
            "peak_memory_mb": 18.5,
            "code": f"# {marker}\ndef process_data(items):\n    return sorted(items)",
        },
    }


def _failing_input_payload(
    marker: str = MARKER,
    *,
    consent: bool = True,
    ai_enabled: bool = True,
    room_id: str = "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
    request_id: str | None = None,
    language: str = "python",
) -> dict:
    """Return a contract-valid /v1/analyses/failing-input payload (T-18 Task 3 schema)."""
    return {
        "request_id": request_id or str(uuid.uuid4()),
        "room_id": room_id,
        "analysis_id": str(uuid.uuid4()),
        "request_type": "failing_input",
        "language": language,
        "consent": consent,
        "ai_enabled": ai_enabled,
        "code": f"# {marker}\nprint(int(input()) + 1)",
        "reference_solution": f"# {marker}\nprint(int(input()))",
    }


def _invalid_explain_payload(marker: str = MARKER) -> dict:
    payload = _explain_payload(marker)
    payload.pop("exit_code")
    return payload


def _invalid_complexity_payload(marker: str = MARKER) -> dict:
    payload = _complexity_payload(marker)
    payload.pop("code")
    return payload


def _invalid_crash_explanation_payload(marker: str = MARKER) -> dict:
    payload = _crash_explanation_payload(marker)
    payload.pop("sanitizer_output")
    return payload


def _invalid_slowdown_payload(marker: str = MARKER) -> dict:
    payload = _slowdown_payload(marker)
    payload.pop("current_run")
    return payload


def _invalid_failing_input_payload(marker: str = MARKER) -> dict:
    payload = _failing_input_payload(marker)
    payload.pop("code")
    return payload


_ROUTE_BUILDERS = {
    "/v1/explain": (_explain_payload, _invalid_explain_payload),
    "/v1/analyses/complexity": (_complexity_payload, _invalid_complexity_payload),
    "/v1/analyses/crash-explanation": (_crash_explanation_payload, _invalid_crash_explanation_payload),
    "/v1/analyses/slowdown": (_slowdown_payload, _invalid_slowdown_payload),
    "/v1/analyses/failing-input": (_failing_input_payload, _invalid_failing_input_payload),
}


def _build_route_test_cases(marker: str) -> list[dict]:
    """Build explicit per-route test cases from app.routes (failing if an unmapped POST route exists)."""
    cases = []
    for route in app.routes:
        path = getattr(route, "path", None)
        methods = getattr(route, "methods", None) or set()
        if path is None or not methods or path in _FASTAPI_INTERNAL_PATHS or path == "/health":
            continue

        for method in sorted(methods):
            if method in {"HEAD", "OPTIONS"}:
                continue
            if method == "POST":
                assert path in _ROUTE_BUILDERS, (
                    f"POST route {path!r} has no explicit payload builder in _ROUTE_BUILDERS; "
                    "add a schema-valid builder instead of using a generic payload."
                )
                valid_builder, invalid_builder = _ROUTE_BUILDERS[path]
                payload = valid_builder(marker)
                invalid_payload = invalid_builder(marker)
            else:
                payload = None
                invalid_payload = None

            cases.append(
                {
                    "method": method,
                    "path": path,
                    "payload": payload,
                    "invalid_payload": invalid_payload,
                }
            )
    return cases


def _get_middleware_records(caplog) -> list[logging.LogRecord]:
    return [
        r for r in caplog.records
        if r.name == "ai_ml_service" and "request_id=" in r.getMessage()
    ]


def _parse_log_fields(log_line: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for token in log_line.split():
        if "=" in token:
            k, v = token.split("=", 1)
            fields[k] = v
    return fields


# ---------------------------------------------------------------------------
# Test 1 — Marker test: no user text or LLM response leaks on any path (NFR8.2)
# ---------------------------------------------------------------------------


def test_log_marker_never_appears_in_logs_on_any_path(caplog):
    """Exercise every registered endpoint across success, skipped-AI, provider-error, and 422 paths.

    Asserts neither MARKER (in any user-text field) nor LLM_OUTPUT_MARKER
    (in mocked LLM responses) ever appears in any captured log record.
    """
    route_cases = _build_route_test_cases(MARKER)
    registered_post_paths = {c["path"] for c in route_cases if c["method"] == "POST"}
    assert {
        "/v1/explain",
        "/v1/analyses/complexity",
        "/v1/analyses/crash-explanation",
    }.issubset(registered_post_paths)

    with caplog.at_level(logging.DEBUG):
        with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
            with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=_MOCK_LLM_RESPONSE)):
                client.get("/health")

                for case in route_cases:
                    if case["method"] == "GET":
                        resp = client.get(case["path"])
                        assert resp.status_code == 200
                    elif case["method"] == "POST":
                        valid_builder, _ = _ROUTE_BUILDERS[case["path"]]

                        # Path A: consent=True, ai_enabled=True (LLM success)
                        resp_ok = client.post(case["path"], json=case["payload"])
                        assert resp_ok.status_code == 200, f"{case['path']} failed: {resp_ok.text}"

                        # Path B: consent=False (HTTP 200 skipped)
                        resp_no_consent = client.post(
                            case["path"], json=valid_builder(MARKER, consent=False, ai_enabled=True)
                        )
                        assert resp_no_consent.status_code == 200

                        # Path C: ai_enabled=False (HTTP 200 skipped)
                        resp_ai_off = client.post(
                            case["path"], json=valid_builder(MARKER, consent=True, ai_enabled=False)
                        )
                        assert resp_ai_off.status_code == 200

                        # Path D: provider failure (no API key)
                        with patch.object(settings, "LLM_API_KEY", None):
                            resp_no_key = client.post(case["path"], json=case["payload"])
                            assert resp_no_key.status_code in (200, 502)

                        # Path E: schema validation failure (422)
                        resp_422 = client.post(case["path"], json=case["invalid_payload"])
                        assert resp_422.status_code == 422

                        # Path F: invalid enum fields containing marker (422)
                        bad_enum_payload = dict(case["payload"])
                        bad_enum_payload["language"] = f"INVALID_LANG_{MARKER}"
                        bad_enum_payload["request_type"] = f"INVALID_TYPE_{MARKER}"
                        resp_bad_enum = client.post(case["path"], json=bad_enum_payload)
                        assert resp_bad_enum.status_code == 422

                # Unsupported language DR5 path on crash-explanation
                resp_unsup = client.post(
                    "/v1/analyses/crash-explanation",
                    json=_crash_explanation_payload(MARKER, language="python"),
                )
                assert resp_unsup.status_code == 422

    for record in caplog.records:
        msg = record.getMessage()
        assert MARKER not in msg, (
            f"NFR8.2 violation: user marker found in logger '{record.name}' ({record.levelname}): {msg!r}"
        )
        assert LLM_OUTPUT_MARKER not in msg, (
            f"NFR8.2 violation: LLM output marker found in logger '{record.name}' ({record.levelname}): {msg!r}"
        )


# ---------------------------------------------------------------------------
# Test 2 — Structured log metadata & latency on all supported endpoints (NFR8.1)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "path, payload_builder, expected_request_type, expected_language",
    [
        ("/v1/explain", _explain_payload, "explanation", "python"),
        ("/v1/analyses/complexity", _complexity_payload, "complexity", "python"),
        ("/v1/analyses/crash-explanation", _crash_explanation_payload, "crash_explanation", "cpp"),
    ],
)
def test_log_metadata_and_latency_on_post_endpoints(
    caplog, path, payload_builder, expected_request_type, expected_language
):
    """Verify exactly one log record per POST call with all NFR8.1 fields populated."""
    expected_room_id = "Zq8-Lw2Ef6Th0Yx4Rn7JbA"
    expected_request_id = f"req-{expected_request_type}-001"
    payload = payload_builder(
        MARKER,
        room_id=expected_room_id,
        request_id=expected_request_id,
        language=expected_language,
    )

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
            with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=_MOCK_LLM_RESPONSE)):
                response = client.post(path, json=payload)

    assert response.status_code == 200

    records = _get_middleware_records(caplog)
    assert len(records) == 1, f"Expected exactly 1 middleware log record, got {len(records)}"

    fields = _parse_log_fields(records[0].getMessage())
    assert fields["request_id"] == expected_request_id
    assert fields["room_id"] == expected_room_id
    assert fields["request_type"] == expected_request_type
    assert fields["language"] == expected_language
    assert fields["status"] == "200"
    assert fields["error_code"] == "none"
    assert float(fields["latency_ms"]) >= 0


@pytest.mark.parametrize(
    "path, expected_request_type",
    [
        ("/v1/usage", "usage"),
        ("/v1/analyses/usage", "usage"),
        ("/health", "health"),
    ],
)
def test_log_metadata_for_get_endpoints(caplog, path, expected_request_type):
    """Verify GET endpoints emit one structured log record with safe non-unknown type."""
    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        response = client.get(path)

    assert response.status_code == 200

    records = _get_middleware_records(caplog)
    assert len(records) == 1

    fields = _parse_log_fields(records[0].getMessage())
    assert fields["request_id"] == "no-id"
    assert fields["room_id"] == "no-room"
    assert fields["request_type"] == expected_request_type
    assert fields["language"] == "none"
    assert fields["status"] == "200"
    assert fields["error_code"] == "none"
    assert float(fields["latency_ms"]) >= 0


# ---------------------------------------------------------------------------
# Test 3 — Blocked / skipped AI paths (US-17, FR35, T-18 v2 HTTP 200)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "path, builder, expected_request_type, expected_language",
    [
        ("/v1/explain", _explain_payload, "explanation", "python"),
        ("/v1/analyses/complexity", _complexity_payload, "complexity", "python"),
        ("/v1/analyses/crash-explanation", _crash_explanation_payload, "crash_explanation", "cpp"),
    ],
)
@pytest.mark.parametrize(
    "consent, ai_enabled, expected_error_code",
    [
        (False, True, "CONSENT_MISSING"),
        (None, True, "CONSENT_MISSING"),
        (True, False, "AI_DISABLED"),
        (True, None, "AI_DISABLED"),
    ],
)
def test_log_metadata_on_skipped_ai_paths(
    caplog,
    path,
    builder,
    expected_request_type,
    expected_language,
    consent,
    ai_enabled,
    expected_error_code,
):
    """Verify skipped AI paths return HTTP 200 and log metadata + skipped error_code with 0 LLM calls."""
    req_id = f"req-skip-{expected_request_type}-{expected_error_code}"
    room_id = "room-skip-audit-01"
    payload = builder(
        MARKER,
        consent=consent,
        ai_enabled=ai_enabled,
        room_id=room_id,
        request_id=req_id,
        language=expected_language,
    )

    mock_post = AsyncMock(return_value=_MOCK_LLM_RESPONSE)
    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch("httpx.AsyncClient.post", new=mock_post):
            response = client.post(path, json=payload)

    assert response.status_code == 200
    assert mock_post.call_count == 0
    assert response.json()["ai_step"]["status"] == "skipped"
    assert response.json()["ai_step"]["error_code"] == expected_error_code

    records = _get_middleware_records(caplog)
    assert len(records) == 1
    fields = _parse_log_fields(records[0].getMessage())
    assert fields["request_id"] == req_id
    assert fields["room_id"] == room_id
    assert fields["request_type"] == expected_request_type
    assert fields["language"] == expected_language
    assert fields["status"] == "200"
    assert fields["error_code"] == expected_error_code
    assert float(fields["latency_ms"]) >= 0


# ---------------------------------------------------------------------------
# Test 4 — Error paths, latency, and allowlisted 422 metadata (NFR8.1, NFR8.2)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "side_effect, expected_status, expected_error_code",
    [
        (None, 502, "PROVIDER_ERROR"),
        (httpx.TimeoutException("timeout"), 504, "PROVIDER_TIMEOUT"),
        (httpx.Response(status_code=429, headers={"Retry-After": "15"}, text="rate limit"), 429, "RATE_LIMITED_PROVIDER"),
    ],
)
def test_explain_provider_errors_log_status_error_code_and_latency(
    caplog, side_effect, expected_status, expected_error_code
):
    """Verify /v1/explain error responses log exact status, error_code, metadata, and latency_ms."""
    payload = _explain_payload(
        MARKER,
        room_id="room-explain-err-01",
        request_id="req-explain-err-01",
        consent=True,
        ai_enabled=True,
    )

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        if side_effect is None:
            with patch.object(settings, "LLM_API_KEY", None):
                response = client.post("/v1/explain", json=payload)
        elif isinstance(side_effect, httpx.Response):
            with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
                with patch("httpx.AsyncClient.post", new=AsyncMock(return_value=side_effect)):
                    response = client.post("/v1/explain", json=payload)
        else:
            with patch.object(settings, "LLM_API_KEY", "test-fake-key"):
                with patch("httpx.AsyncClient.post", side_effect=side_effect):
                    response = client.post("/v1/explain", json=payload)

    assert response.status_code == expected_status

    records = _get_middleware_records(caplog)
    assert len(records) == 1
    fields = _parse_log_fields(records[0].getMessage())
    assert fields["request_id"] == "req-explain-err-01"
    assert fields["room_id"] == "room-explain-err-01"
    assert fields["request_type"] == "explanation"
    assert fields["language"] == "python"
    assert fields["status"] == str(expected_status)
    assert fields["error_code"] == expected_error_code
    assert float(fields["latency_ms"]) >= 0


@pytest.mark.parametrize(
    "path, builder, expected_request_type, expected_language",
    [
        ("/v1/analyses/complexity", _complexity_payload, "complexity", "python"),
        ("/v1/analyses/crash-explanation", _crash_explanation_payload, "crash_explanation", "cpp"),
    ],
)
@pytest.mark.parametrize(
    "exc, expected_error_code",
    [
        (LLMTimeoutError("timed out"), "PROVIDER_TIMEOUT"),
        (LLMRateLimitError("rate limited"), "RATE_LIMITED_PROVIDER"),
        (LLMProviderError("upstream error"), "PROVIDER_ERROR"),
    ],
)
def test_analyses_upstream_errors_preserve_200_and_log_error_code(
    caplog, path, builder, expected_request_type, expected_language, exc, expected_error_code
):
    """Verify /v1/analyses/* return HTTP 200 with skipped ai_step and log error_code on provider error."""
    payload = builder(
        MARKER,
        room_id="room-analyses-err-01",
        request_id="req-analyses-err-01",
        language=expected_language,
        consent=True,
        ai_enabled=True,
    )

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch("app.llm.client.LLMClient.complete", side_effect=exc):
            response = client.post(path, json=payload)

    assert response.status_code == 200
    assert response.json()["ai_step"]["status"] == "skipped"
    assert response.json()["ai_step"]["error_code"] == expected_error_code

    records = _get_middleware_records(caplog)
    assert len(records) == 1
    fields = _parse_log_fields(records[0].getMessage())
    assert fields["request_type"] == expected_request_type
    assert fields["language"] == expected_language
    assert fields["status"] == "200"
    assert fields["error_code"] == expected_error_code
    assert float(fields["latency_ms"]) >= 0


def test_crash_explanation_unsupported_language_logs_metadata_and_error_code(caplog):
    """Verify DR5 422 UNSUPPORTED_LANGUAGE logs request_type, language, status=422, and latency_ms."""
    payload = _crash_explanation_payload(
        MARKER,
        language="python",
        room_id="room-crash-unsup-01",
        request_id="req-crash-unsup-01",
    )

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        response = client.post("/v1/analyses/crash-explanation", json=payload)

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "UNSUPPORTED_LANGUAGE"

    records = _get_middleware_records(caplog)
    assert len(records) == 1
    fields = _parse_log_fields(records[0].getMessage())
    assert fields["request_id"] == "req-crash-unsup-01"
    assert fields["room_id"] == "room-crash-unsup-01"
    assert fields["request_type"] == "crash_explanation"
    assert fields["language"] == "python"
    assert fields["status"] == "422"
    assert fields["error_code"] == "UNSUPPORTED_LANGUAGE"
    assert float(fields["latency_ms"]) >= 0


@pytest.mark.parametrize(
    "path, base_builder, expected_path_request_type",
    [
        ("/v1/explain", _explain_payload, "explanation"),
        ("/v1/analyses/complexity", _complexity_payload, "complexity"),
        ("/v1/analyses/crash-explanation", _crash_explanation_payload, "crash_explanation"),
    ],
)
def test_validation_error_suppresses_user_text_in_response_and_logs(
    caplog, path, base_builder, expected_path_request_type
):
    """Verify 422 responses and logs never echo invalid field values or user text (NFR8.2)."""
    invalid_payload = base_builder(MARKER)
    invalid_payload["language"] = f"INVALID_LANG_{MARKER}"
    invalid_payload["request_type"] = f"INVALID_TYPE_{MARKER}"
    invalid_payload["room_id"] = f"invalid room {MARKER}"
    invalid_payload["request_id"] = f"invalid req {MARKER}"

    with caplog.at_level(logging.DEBUG):
        response = client.post(path, json=invalid_payload)

    assert response.status_code == 422
    assert MARKER not in response.text
    data = response.json()
    assert data["error"]["code"] == "INVALID_REQUEST"
    assert data["request_id"] is None

    for record in caplog.records:
        assert MARKER not in record.getMessage()

    records = _get_middleware_records(caplog)
    assert len(records) == 1
    fields = _parse_log_fields(records[0].getMessage())
    assert fields["request_id"] == "no-id"
    assert fields["room_id"] == "no-room"
    assert fields["request_type"] == expected_path_request_type
    assert fields["language"] == "unknown-lang"
    assert fields["status"] == "422"
    assert fields["error_code"] == "INVALID_REQUEST"
    assert float(fields["latency_ms"]) >= 0


# ---------------------------------------------------------------------------
# Test 5 — Monotonic clock, exception handler preservation, & error-log safety
# ---------------------------------------------------------------------------


def test_latency_uses_monotonic_clock(caplog):
    """Verify middleware computes latency_ms using app.main.time.monotonic()."""
    payload = _complexity_payload(consent=False)
    calls = iter([100.0, 100.0425])
    fake_time = SimpleNamespace(monotonic=lambda: next(calls))

    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch("app.main.time", fake_time):
            response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 200
    records = _get_middleware_records(caplog)
    assert len(records) == 1
    fields = _parse_log_fields(records[0].getMessage())
    assert fields["latency_ms"] == "42.5"


def test_consent_missing_exception_handler_preserved(caplog):
    """Verify ConsentMissingError exception handler remains registered and logs CONSENT_MISSING."""
    assert ConsentMissingError in app.exception_handlers

    payload = _complexity_payload(
        consent=True,
        ai_enabled=True,
        room_id="room-handler-check",
        request_id="req-handler-01",
    )
    with caplog.at_level(logging.INFO, logger="ai_ml_service"):
        with patch(
            "app.routers.analyses.run_rule_based_analysis_with_ai_fallback",
            side_effect=ConsentMissingError(),
        ):
            response = client.post("/v1/analyses/complexity", json=payload)

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "CONSENT_MISSING"

    records = _get_middleware_records(caplog)
    assert len(records) == 1
    fields = _parse_log_fields(records[0].getMessage())
    assert fields["request_id"] == "req-handler-01"
    assert fields["room_id"] == "room-handler-check"
    assert fields["request_type"] == "complexity"
    assert fields["status"] == "403"
    assert fields["error_code"] == "CONSENT_MISSING"


def test_unexpected_exception_messages_do_not_leak_user_text(caplog):
    """Verify raw exception messages containing user text are not logged in analyses/explain."""
    crash_payload = _crash_explanation_payload(MARKER, consent=True, ai_enabled=True)
    complexity_payload = _complexity_payload(MARKER, consent=False)

    with caplog.at_level(logging.DEBUG):
        with patch(
            "app.routers.analyses.generate_explanation",
            side_effect=RuntimeError(f"leaked {MARKER}"),
        ):
            resp_crash = client.post("/v1/analyses/crash-explanation", json=crash_payload)
        with patch(
            "app.routers.analyses.np.polyfit",
            side_effect=ValueError(f"polyfit leaked {MARKER}"),
        ):
            resp_comp = client.post("/v1/analyses/complexity", json=complexity_payload)

    assert resp_crash.status_code == 200
    assert resp_crash.json()["ai_step"]["error_code"] == "PROVIDER_ERROR"
    assert resp_comp.status_code == 200

    for record in caplog.records:
        assert MARKER not in record.getMessage()


def test_contract_payload_builders_match_t18_schemas():
    """Verify T-18 Task 2/3 payload builders match docs/schemas/*.json required fields."""
    repo_root = Path(__file__).resolve().parent.parent.parent
    schemas = {
        "slowdown-request.json": _slowdown_payload(),
        "failing-input-request.json": _failing_input_payload(),
    }
    for filename, payload in schemas.items():
        schema_path = repo_root / "docs" / "schemas" / filename
        if not schema_path.exists():
            continue
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        for req_field in schema.get("required", []):
            assert req_field in payload, f"Missing required field {req_field!r} for {filename}"
        if schema.get("additionalProperties") is False:
            allowed = set(schema.get("properties", {}).keys())
            assert set(payload.keys()).issubset(allowed)
