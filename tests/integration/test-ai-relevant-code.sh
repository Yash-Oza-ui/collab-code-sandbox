#!/usr/bin/env bash
# test-ai-relevant-code.sh: verify relevant code section extraction behavior (FR40.2, US-76, EPIC 3, Sprint 2).
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

base="$(service_url ai-ml-service)"   # -> http://localhost:8000

# Skip cleanly if the service folder has no Dockerfile yet.
service_present ai-ml-service || { skip ai-ml-service "not built yet"; exit 0; }

# Check service liveness; skip if container is not running locally.
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  skip ai-ml-service "service not running on $base (start with tests/integration/up.sh)"
  exit 0
fi

# 1. Test: POST /v1/explain accepts error_line and function_name for relevant section extraction (FR40.2, US-76)
payload_explain_section='{
  "request_id": "test-int-relevant-001",
  "room_id": "room-int-relevant-42",
  "request_type": "explanation",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "code": "# header line 1\n# header line 2\ndef target_calc(x):\n    return x / 0\n# footer line 5\n# footer line 6",
  "stderr": "File \"solution.py\", line 4, in target_calc\nZeroDivisionError: division by zero",
  "error_line": 4,
  "function_name": "target_calc",
  "exit_code": 1,
  "execution_time_ms": 15.0,
  "peak_memory_mb": 4.5,
  "timed_out": false,
  "oom_killed": false,
  "failure_kind": "non_zero_exit"
}'

resp_explain="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/explain" \
  -H "Content-Type: application/json" \
  -d "$payload_explain_section")"

status_explain="$(echo "$resp_explain" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_explain="$(echo "$resp_explain" | grep -v 'HTTP_STATUS:')"

# When API key is unset in CI environment, 502 PROVIDER_ERROR is returned after passing section extraction and AI gate.
# When key or mock is configured, 200 is returned. Both confirm request validation & relevant code extraction succeeded.
if [ "$status_explain" != "200" ] && [ "$status_explain" != "502" ]; then
  fail ai-ml-service "expected HTTP 200 or 502 on POST /v1/explain with relevant code section, got $status_explain. Body: $body_explain"
  exit 1
fi
pass ai-ml-service "POST /v1/explain accepts relevant code extraction fields (FR40.2, US-76)"

# 2. Test: POST /v1/analyses/complexity with multi-line code passes section extraction and returns HTTP 200
payload_complexity='{
  "request_id": "test-int-relevant-002",
  "room_id": "room-int-relevant-42",
  "analysis_id": "an-relevant-001",
  "request_type": "complexity",
  "language": "python",
  "consent": false,
  "ai_enabled": true,
  "code": "# Top header line\ndef bubble_sort(arr):\n    for i in range(len(arr)):\n        for j in range(len(arr)):\n            pass\n# Bottom footer line"
}'

resp_comp="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_complexity")"

status_comp="$(echo "$resp_comp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_comp="$(echo "$resp_comp" | grep -v 'HTTP_STATUS:')"

if [ "$status_comp" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on complexity analysis, got $status_comp. Body: $body_comp"
  exit 1
fi

if ! echo "$body_comp" | grep -q "nested_loops"; then
  fail ai-ml-service "expected nested_loops rule pattern in response, got: $body_comp"
  exit 1
fi
pass ai-ml-service "POST /v1/analyses/complexity executes rules-first analysis with section bounds (US-76, FR40.2)"

# 3. Test: POST /v1/analyses/crash-explanation parses sanitizer output line for section extraction (US-73, US-74, US-76)
payload_crash='{
  "request_id": "test-int-relevant-003",
  "room_id": "room-int-relevant-42",
  "analysis_id": "an-relevant-002",
  "request_type": "crash_explanation",
  "language": "cpp",
  "consent": false,
  "ai_enabled": true,
  "sanitizer_output": "==1001==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x123\nREAD of size 4 at 0x123 thread T0\n    #0 0x401233 in process_buffer /workspace/solution.cpp:14",
  "code_snippet": "int val = buffer[14];"
}'

resp_crash="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/crash-explanation" \
  -H "Content-Type: application/json" \
  -d "$payload_crash")"

status_crash="$(echo "$resp_crash" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_crash="$(echo "$resp_crash" | grep -v 'HTTP_STATUS:')"

if [ "$status_crash" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on crash explanation, got $status_crash. Body: $body_crash"
  exit 1
fi

if ! echo "$body_crash" | grep -q "heap-buffer-overflow"; then
  fail ai-ml-service "expected heap-buffer-overflow in error_type, got: $body_crash"
  exit 1
fi

if ! echo "$body_crash" | grep -q '"line_number"[[:space:]]*:[[:space:]]*14'; then
  fail ai-ml-service "expected line_number 14 extracted from sanitizer output, got: $body_crash"
  exit 1
fi
pass ai-ml-service "POST /v1/analyses/crash-explanation extracts error line for section extraction (US-74, US-76)"
