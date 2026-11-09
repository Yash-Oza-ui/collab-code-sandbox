#!/usr/bin/env bash
# test-ai-usage-counters.sh: verify telemetry usage counters and room teardown (FR40.1, US-75, US-56, T-18 v2).
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

room_id="room-int-usage-$(date +%s)"

# 1. Test: GET /v1/usage returns HTTP 200 and valid schema
resp_usage="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET "$base/v1/usage")"
status_usage="$(echo "$resp_usage" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_usage="$(echo "$resp_usage" | grep -v 'HTTP_STATUS:')"

if [ "$status_usage" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on GET /v1/usage, got $status_usage. Body: $body_usage"
  exit 1
fi

for field in "total_model_calls" "total_tokens_used" "total_cache_hits" "total_fallback_activations" "monthly_spend_usd" "monthly_budget_usd" "alert_80_percent_triggered" "cost_cap_reached" "rooms_tracked"; do
  if ! echo "$body_usage" | grep -q "\"$field\""; then
    fail ai-ml-service "GET /v1/usage missing required field $field. Body: $body_usage"
    exit 1
  fi
done
pass ai-ml-service "GET /v1/usage returns valid telemetry metrics (FR40.1, T-18 v2 §16.5)"

# 2. Test: Trigger a fallback (consent=false) and verify fallback counter increments
payload_fallback='{
  "request_id": "test-int-usage-fallback-001",
  "room_id": "'"$room_id"'",
  "request_type": "explanation",
  "language": "python",
  "consent": false,
  "code": "print(42)",
  "stderr": "Traceback",
  "exit_code": 1,
  "execution_time_ms": 10.0,
  "peak_memory_mb": 4.0,
  "timed_out": false,
  "oom_killed": false,
  "failure_kind": "non_zero_exit"
}'

resp_fb="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/explain" \
  -H "Content-Type: application/json" \
  -d "$payload_fallback")"
status_fb="$(echo "$resp_fb" | grep 'HTTP_STATUS:' | cut -d':' -f2)"

if [ "$status_fb" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on explain request, got $status_fb"
  exit 1
fi

# Check room usage endpoint
resp_room="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET "$base/v1/rooms/$room_id/usage")"
status_room="$(echo "$resp_room" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_room="$(echo "$resp_room" | grep -v 'HTTP_STATUS:')"

if [ "$status_room" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on GET /v1/rooms/$room_id/usage, got $status_room"
  exit 1
fi

if ! echo "$body_room" | grep -q "\"fallback_activations\": 1"; then
  fail ai-ml-service "expected room fallback_activations == 1, got: $body_room"
  exit 1
fi
pass ai-ml-service "fallback activation incremented room usage counter (FR40.1, US-75)"

# 3. Test: DELETE /v1/rooms/{room_id}/state clears room state (US-56, FR32)
resp_del="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X DELETE "$base/v1/rooms/$room_id/state")"
status_del="$(echo "$resp_del" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_del="$(echo "$resp_del" | grep -v 'HTTP_STATUS:')"

if [ "$status_del" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on DELETE /v1/rooms/$room_id/state, got $status_del"
  exit 1
fi

if ! echo "$body_del" | grep -q "\"status\": \"cleared\""; then
  fail ai-ml-service "expected status: cleared in delete response, got: $body_del"
  exit 1
fi

# Verify room counters are reset
resp_room_post_del="$(curl -s -X GET "$base/v1/rooms/$room_id/usage")"
if ! echo "$resp_room_post_del" | grep -q "\"fallback_activations\": 0"; then
  fail ai-ml-service "expected fallback_activations == 0 after room clearance, got: $resp_room_post_del"
  exit 1
fi
pass ai-ml-service "DELETE /v1/rooms/{room_id}/state removes room counters (US-56, FR32)"
