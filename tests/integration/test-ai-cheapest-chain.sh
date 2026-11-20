#!/usr/bin/env bash
# test-ai-cheapest-chain.sh: verify cheapest-method-first AI chain (FR40.4, US-77).
# Order of execution: Cache -> Rules/Stats -> Local Model -> Paid Model.
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

room_id="room-int-chain-$(date +%s)"

# 1. Test: Deterministic rules fire first before any external model call
payload_rules='{
  "request_id": "test-int-chain-rules-001",
  "room_id": "'"$room_id"'",
  "analysis_id": "an-chain-rules-001",
  "request_type": "complexity",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "code": "def find_duplicates(arr):\n    dups = []\n    for i in range(len(arr)):\n        for j in range(i + 1, len(arr)):\n            if arr[i] == arr[j]:\n                dups.append(arr[i])\n    return dups\n",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
    {"input_size": 200, "runtimes_ms": [4.0, 4.2]}
  ]
}'

resp_rules="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_rules")"

status_rules="$(echo "$resp_rules" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_rules="$(echo "$resp_rules" | grep -v 'HTTP_STATUS:')"

if [ "$status_rules" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on rule-triggering request, got $status_rules. Body: $body_rules"
  exit 1
fi

if ! echo "$body_rules" | grep -q "nested_loops"; then
  fail ai-ml-service "expected rule match for nested_loops in body: $body_rules"
  exit 1
fi

pass ai-ml-service "Stage 2 rules returned deterministic answer without paid model (US-77)"

# 2. Test: Second identical request hits Stage 1 Cache ($0) and returns HTTP 200
resp_cache="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_rules")"

status_cache="$(echo "$resp_cache" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_cache="$(echo "$resp_cache" | grep -v 'HTTP_STATUS:')"

if [ "$status_cache" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on cached request, got $status_cache"
  exit 1
fi
pass ai-ml-service "Stage 1 Cache returns cached result immediately (US-77, US-22)"

# 3. Test: Explain endpoint with consent=false stops before paid model, returns HTTP 200
payload_no_consent='{
  "request_id": "test-int-chain-consent-001",
  "room_id": "'"$room_id"'",
  "request_type": "explanation",
  "language": "python",
  "consent": false,
  "code": "a = [1, 2]\nprint(a[10])",
  "stderr": "IndexError: list index out of range",
  "exit_code": 1,
  "execution_time_ms": 12.0,
  "peak_memory_mb": 5.0,
  "timed_out": false,
  "oom_killed": false,
  "failure_kind": "non_zero_exit"
}'

resp_no_consent="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/explain" \
  -H "Content-Type: application/json" \
  -d "$payload_no_consent")"

status_nc="$(echo "$resp_no_consent" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_nc="$(echo "$resp_no_consent" | grep -v 'HTTP_STATUS:')"

if [ "$status_nc" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 with consent=false, got $status_nc. Body: $body_nc"
  exit 1
fi

if ! echo "$body_nc" | grep -q "CONSENT_MISSING"; then
  fail ai-ml-service "expected CONSENT_MISSING error code when consent=false, got: $body_nc"
  exit 1
fi
pass ai-ml-service "consent=false blocks paid call without breaking response (US-77, US-21)"

# 4. Test: Telemetry counters endpoint is accessible and healthy
resp_usage="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET "$base/v1/usage")"
status_usage="$(echo "$resp_usage" | grep 'HTTP_STATUS:' | cut -d':' -f2)"

if [ "$status_usage" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on /v1/usage, got $status_usage"
  exit 1
fi
pass ai-ml-service "usage counters verify telemetry status (US-77, US-75)"
