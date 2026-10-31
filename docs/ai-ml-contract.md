# AI/ML Service — Execution-to-AI/ML Contract

**Version:** v2  
**Issue:** T-18 · Backlog v2 §6.2  
**Built against:** Requirements Specification v2 (`docs/IT314_Group32_Requirements_v2_Final.md`), Product Backlog v2 (`docs/IT314_Group32_Backlog_v2_Final.md`), and `docs/ai-ml-service.md`  
**Authors:** Prince Gadara (Tasks 1 & 2), Chandan (Task 3), Digvijay Parmar (Tasks 4 & 5)  
**Status:** Decided / Aligned with v2 Architecture (CHG-01)  

---

## 1. Overview

This document specifies the HTTP contract between **execution-service** (caller, Java/Spring Boot) and **ai-ml-service** (service, Python/FastAPI), operating on port 8000.

Per **Requirements v2** and **CHG-01 §4.6**, the AI/ML service delivers 5 core tasks:
1. **Task 1: Time complexity measurement and verified optimizations** (runs input sizes $n, 2n, 4n$; AST/regex rules first; verifies patches in sandbox).
2. **Task 2: Slowdown detection on run history** (median & MAD baseline for same `input_hash`; flags runs $\ge 8\times$ slower; diff highlights).
3. **Task 3: Failing-input search and minimization** (edge cases first; random generator; delta reduction to small failing input; Save as Test Case).
4. **Task 4: C/C++ crash explanation** (AddressSanitizer and UndefinedBehaviorSanitizer report parsing; LLM explanation on error summary + relevant snippet only).
5. **Task 5: AI usage and cost control** (normalized-code caching, telemetry counters, hourly per-room limit, monthly budget cap with alert at 80%, `COST_CAP_REACHED`).

### Core Architectural Rules
- **Non-blocking execution (NFR9, CR08):** Execution results are returned immediately to the user. Analyses run asynchronously and their results arrive as separate messages to the run's audience.
- **Dual Consent & Gating (FR12, FR35, CR12):** Any step contacting an external LLM requires `consent == true` (user) and `ai_enabled == true` (Host switch).
- **Graceful Fallback / AI-free continuity (NFR7.5, FR35.3, FR40.5):** If AI is disabled, consent is missing, rate limits are hit, or the cost cap is reached, AI-free analysis (measurements, rules, statistics, sanitizer parsing) proceeds and returns HTTP 200 with `ai_step: { "status": "skipped", "error_code": "..." }`.
- **Data Minimization & Privacy (NFR8.2, US-25):** Telemetry logs and counters contain metadata only (`request_id`, `room_id`, `request_type`, `language`, `status`, `error_code`, `latency_ms`). Logs never contain source code, stdin, stderr, or prompts.
- **Relevant Snippet Only (FR40.2, US-76):** External AI prompts contain only the error summary and relevant function/snippet, never the full file.

---

## 2. Base URL and Health Check

- Inside Docker network: `http://ai-ml-service:8000`
- Local development: `http://localhost:8000`
- Health check: `GET /health` -> `{"status": "ok", "version": "v2"}`

---

## 3. Common Request Envelope

Every analysis endpoint requires the following envelope fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `request_id` | string (UUID v4) | Yes | Correlation ID matching the original run request. |
| `room_id` | string | Yes | Room identifier. Used for rate-limiting, history, and telemetry. |
| `analysis_id` | string (UUID v4) | Yes | Identifier for this specific analysis operation. |
| `request_type` | `"complexity"` \| `"slowdown"` \| `"failing_input"` \| `"crash_explanation"` | Yes | Specific analysis task type. |
| `language` | `"cpp"` \| `"python"` \| `"c"` \| `"java"` \| `"javascript"` | Yes | Programming language identifier (DR1). |
| `consent` | boolean | Yes | User AI consent status (FR12). |
| `ai_enabled` | boolean | Yes | Room Host AI switch status (FR35). |

---

## 4. Endpoints

### 4.1 Health Check
`GET /health`  
Response (200 OK):
```json
{
  "status": "ok",
  "version": "v2"
}
```

---

### 4.2 Task 1: Time Complexity & Verified Optimizations
`POST /v1/analyses/complexity`

Per FR36.1–FR36.4, US-65–US-67. Analyzes growth rate across sizes $n, 2n, 4n$, runs AST/regex rules for common slow patterns, and optionally verifies fixes in the sandbox.

#### Request Body
```json
{
  "request_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "room_id": "room-pair-101",
  "analysis_id": "c1f7a0b3-9e4a-4d2b-a71c-1c2e3f4a5b6c",
  "request_type": "complexity",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "code": "def solve(n):\n    total = 0\n    for i in range(n):\n        for j in range(n):\n            total += 1\n    return total",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [1.1, 1.2, 1.1]},
    {"input_size": 200, "runtimes_ms": [4.3, 4.5, 4.4]},
    {"input_size": 400, "runtimes_ms": [17.8, 18.0, 17.9]}
  ]
}
```

#### Response Body (200 OK)
```json
{
  "request_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "analysis_id": "c1f7a0b3-9e4a-4d2b-a71c-1c2e3f4a5b6c",
  "task": "complexity",
  "growth_rate": 2.01,
  "estimated_complexity": "O(n^2)",
  "r2_score": 0.999,
  "projected_timeout_input_size": 6500,
  "slow_patterns": [
    {
      "pattern": "nested_loops",
      "line": 3,
      "message": "Nested loop detected: potential quadratic time complexity O(n^2)."
    }
  ],
  "ai_step": {
    "status": "ok",
    "suggested_fix": null,
    "verified": false
  }
}
```

---

### 4.3 Task 2: Detect Slowdowns in Run History
`POST /v1/analyses/slowdown`

Per FR37.1–FR37.4, US-68–US-69. Compares runs with identical `input_hash` against historical median and MAD baseline. Flags runs $\ge 8\times$ slower.

#### Request Body
```json
{
  "request_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "room_id": "room-pair-101",
  "analysis_id": "d2e6f1a4-8c3b-4b1a-9f2e-3d4c5b6a7b8c",
  "request_type": "slowdown",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "input_hash": "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e",
  "current_run": {
    "execution_time_ms": 1250.0,
    "peak_memory_mb": 14.2,
    "code": "def process():\n    time.sleep(1.2)\n    return 42"
  }
}
```

#### Response Body (200 OK)
```json
{
  "request_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "analysis_id": "d2e6f1a4-8c3b-4b1a-9f2e-3d4c5b6a7b8c",
  "task": "slowdown",
  "input_hash": "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e",
  "is_slowdown": true,
  "slowdown_factor": 12.5,
  "baseline": {
    "median_ms": 100.0,
    "mad_ms": 5.0,
    "sample_count": 8
  },
  "changed_lines": [
    {"line": 2, "content": "    time.sleep(1.2)"}
  ],
  "ai_step": {
    "status": "ok",
    "explanation": "Runtime increased by 12.5x due to added sleep/delay."
  }
}
```

---

### 4.4 Task 3: Failing Inputs & Input Minimization
`POST /v1/analyses/failing-input`

Per FR38.1–FR38.5, US-70–US-72. Searches edge cases and delta-reduces failing inputs.

#### Request Body
```json
{
  "request_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "room_id": "room-pair-101",
  "analysis_id": "f3b2c1d0-1a2b-3c4d-5e6f-7a8b9c0d1e2f",
  "request_type": "failing_input",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "failing_input": "99, 102, -5, 0, 14, 88, 33, 12, 1000",
  "failure_type": "wrong_output",
  "expected_output": "1000",
  "actual_output": "0"
}
```

#### Response Body (200 OK)
```json
{
  "request_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "analysis_id": "f3b2c1d0-1a2b-3c4d-5e6f-7a8b9c0d1e2f",
  "task": "failing_input",
  "minimized_input": "0",
  "original_input_length": 37,
  "minimized_input_length": 1,
  "failure_type": "wrong_output",
  "can_save_as_test_case": true
}
```

---

### 4.5 Task 4: C/C++ Crash Explanation
`POST /v1/analyses/crash-explanation`

Per FR39.1–FR39.3, DR5, US-73–US-74. Deterministically parses AddressSanitizer and UndefinedBehaviorSanitizer output and explains crashes using small code snippets only.

#### Request Body
```json
{
  "request_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "room_id": "room-pair-101",
  "analysis_id": "e4d3c2b1-0f1e-2d3c-4b5a-6789abcdef01",
  "request_type": "crash_explanation",
  "language": "cpp",
  "consent": true,
  "ai_enabled": true,
  "sanitizer_output": "==12345==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000014 at pc 0x000000401234 bp 0x7ffd7a435880 sp 0x7ffd7a435878\nREAD of size 4 at 0x602000000014 thread T0\n    #0 0x401233 in main /workspace/main.cpp:12",
  "code_snippet": "int arr[5];\nreturn arr[10];"
}
```

#### Response Body (200 OK)
```json
{
  "request_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "analysis_id": "e4d3c2b1-0f1e-2d3c-4b5a-6789abcdef01",
  "task": "crash_explanation",
  "error_type": "heap-buffer-overflow",
  "line_number": 12,
  "ai_step": {
    "status": "ok",
    "explanation": "Memory was accessed outside array bounds on line 12. Ensure loop or array indexes do not exceed allocated size.",
    "suggested_fix": "Change index 10 to a valid index between 0 and 4."
  }
}
```

---

### 4.6 Task 5: AI Usage & Telemetry Counters
`GET /v1/usage`

Per FR40.1, NFR7, US-75. Returns aggregate and per-room usage counters. Never stores or returns user code or text.

#### Response Body (200 OK)
```json
{
  "total_model_calls": 42,
  "total_tokens_used": 15200,
  "total_cache_hits": 68,
  "total_fallback_activations": 3,
  "monthly_spend_usd": 0.038,
  "monthly_budget_usd": 10.0,
  "alert_80_percent_triggered": false,
  "cost_cap_reached": false,
  "rooms_tracked": 5
}
```

---

### 4.7 Room State Lifecycle
`DELETE /v1/rooms/{room_id}/state`

Per FR32, FR37.4, US-56. Called by execution-service when a room is closed or deleted. Cleans up all in-memory measurements and room-scoped counters.

#### Response Body (200 OK)
```json
{
  "room_id": "room-pair-101",
  "status": "cleared"
}
```

---

## 5. Retired Endpoints (Backlog v2 §6.2)

1. `POST /v1/review`: Retired. Code review is out-of-scope for the sandbox (FR7 removed).
2. `POST /v1/explain`: Retired as a general failure explainer (FR13 removed). Replaced by `POST /v1/analyses/crash-explanation` (Task 4, C/C++ sanitizer explanation).
3. `POST /v1/anomaly`: Retired. Replaced by Task 2 (`POST /v1/analyses/slowdown`). The offline ML scorer (`app/ml/scorer.py`) remains unrouted in the repo for mentor evaluation.

---

## 6. Error Envelope and Error Codes

For unprocessable requests, the standard error envelope is used:

```json
{
  "request_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "error": {
    "code": "COST_CAP_REACHED",
    "message": "Monthly AI budget limit has been reached. Paid AI calls are paused.",
    "retry_after_seconds": null
  }
}
```

### Standard Error Codes
| Code | HTTP Status | Description |
|---|---|---|
| `CONSENT_MISSING` | 200 (in `ai_step`) / 403 | User consent is false (FR12). |
| `AI_DISABLED` | 200 (in `ai_step`) / 403 | Room Host switch is off (FR35). |
| `COST_CAP_REACHED` | 200 (in `ai_step`) / 429 | Monthly AI spending cap reached (NFR7.2). |
| `ROOM_RATE_LIMITED` | 200 (in `ai_step`) / 429 | Hourly per-room AI call limit exceeded (NFR5.3). |
| `INVALID_REQUEST` | 422 | Schema validation failure or missing required fields. |
| `UNSUPPORTED_LANGUAGE`| 422 | Feature requested on incompatible language (e.g., ASan on Python). |
| `PROVIDER_ERROR` | 502 | Upstream LLM provider error. |
| `PROVIDER_TIMEOUT` | 504 | Upstream LLM timeout. |

---

## 7. Normalized Code Caching (NFR5.2)

Cache keys are constructed as:
$$\text{key} = \text{SHA-256}(\text{normalized\_code} + \text{language} + \text{request\_type} + \text{error\_signature})$$

Normalization rules:
1. Strip single-line comments (`#`, `//`).
2. Strip multi-line comments (`/* ... */`, `""" ... """`).
3. Strip trailing whitespace from all lines.
4. Collapse contiguous empty lines to a single blank line.
5. In Python, preserve meaningful leading indentation whitespace. In brace-based languages (C, C++, Java, JS), normalize leading whitespace.
6. Only the 64-character SHA-256 hex digest is stored.

---

## 8. Privacy & Telemetry Logging (NFR8.1, NFR8.2, US-25)

Every request emits a structured log line with metadata only:
```
request_id=9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d room_id=room-pair-101 request_type=complexity language=python status=200 error_code=none latency_ms=42.5
```
**Strict Privacy Guarantee:** Telemetry, log lines, exception handlers, and SQLite usage counters **never** record raw source code, stdin, stderr, or prompts.
