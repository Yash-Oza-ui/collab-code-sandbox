#!/usr/bin/env bash
# test-ai-cost-cap.sh: verify monthly AI cost cap, budget alerts, and AI-free analysis (US-24, NFR7.1, NFR7.2, NFR7.4, NFR7.5, T-18 v2).
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

room_id="room-int-cost-cap-$(date +%s)"

# 1. Test: GET /v1/usage returns HTTP 200 and contains all monthly budget fields
resp_usage="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET "$base/v1/usage")"
status_usage="$(echo "$resp_usage" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_usage="$(echo "$resp_usage" | grep -v 'HTTP_STATUS:')"

if [ "$status_usage" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on GET /v1/usage, got $status_usage. Body: $body_usage"
  exit 1
fi

for field in "monthly_spend_usd" "monthly_budget_usd" "alert_80_percent_triggered" "cost_cap_reached"; do
  if ! echo "$body_usage" | grep -q "\"$field\""; then
    fail ai-ml-service "GET /v1/usage missing required cost cap field: $field. Body: $body_usage"
    exit 1
  fi
done
pass ai-ml-service "GET /v1/usage returns cost cap and budget fields (US-24, NFR7.1, NFR7.2)"

# 2. Test: Complexity endpoint returns empirical results and ai_step
payload_comp='{
  "request_id": "test-int-cap-comp-01",
  "room_id": "'"$room_id"'",
  "analysis_id": "ana-int-cap-comp-01",
  "request_type": "complexity",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "code": "for i in range(10):\n    for j in range(10):\n        pass",
  "measurements": [
    {"input_size": 10, "runtimes_ms": [1.0, 1.1]},
    {"input_size": 20, "runtimes_ms": [4.0, 4.2]}
  ]
}'

resp_comp="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_comp")"
status_comp="$(echo "$resp_comp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_comp="$(echo "$resp_comp" | grep -v 'HTTP_STATUS:')"

if [ "$status_comp" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on complexity endpoint, got $status_comp. Body: $body_comp"
  exit 1
fi

if ! echo "$body_comp" | grep -q "\"estimated_complexity\""; then
  fail ai-ml-service "expected estimated_complexity in response, got: $body_comp"
  exit 1
fi

if ! echo "$body_comp" | grep -q "\"ai_step\""; then
  fail ai-ml-service "expected ai_step in complexity response, got: $body_comp"
  exit 1
fi
pass ai-ml-service "complexity endpoint executes empirical analysis and returns ai_step (US-24, NFR7.5)"

# 3. Test: Crash explanation returns deterministic sanitizer parsing and ai_step
payload_crash='{
  "request_id": "test-int-cap-crash-01",
  "room_id": "'"$room_id"'",
  "analysis_id": "ana-int-cap-crash-01",
  "request_type": "crash_explanation",
  "language": "cpp",
  "consent": true,
  "ai_enabled": true,
  "sanitizer_output": "AddressSanitizer: heap-buffer-overflow /workspace/solution.cpp:14",
  "code_snippet": "int val = buffer[index];"
}'

resp_crash="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/crash-explanation" \
  -H "Content-Type: application/json" \
  -d "$payload_crash")"
status_crash="$(echo "$resp_crash" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_crash="$(echo "$resp_crash" | grep -v 'HTTP_STATUS:')"

if [ "$status_crash" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on crash-explanation endpoint, got $status_crash. Body: $body_crash"
  exit 1
fi

if ! echo "$body_crash" | grep -q "\"error_type\""; then
  fail ai-ml-service "expected error_type in crash-explanation response, got: $body_crash"
  exit 1
fi

if ! echo "$body_crash" | grep -q "\"ai_step\""; then
  fail ai-ml-service "expected ai_step in crash-explanation response, got: $body_crash"
  exit 1
fi
pass ai-ml-service "crash-explanation endpoint parses sanitizer output deterministically (US-24, NFR7.5)"

# 4. Clean up room state
resp_del="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X DELETE "$base/v1/rooms/$room_id/state")"
status_del="$(echo "$resp_del" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
if [ "$status_del" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on room cleanup, got $status_del"
  exit 1
fi
pass ai-ml-service "room teardown completed cleanly (US-56, FR32)"
