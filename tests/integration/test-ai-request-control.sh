#!/usr/bin/env bash
# test-ai-request-control.sh: verify per-room hourly rate limit, gate order, and room state cleanup (US-22, NFR5.3, US-56).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

base="$(service_url ai-ml-service)"   # -> http://localhost:8000

# Skip cleanly if the service folder has no Dockerfile yet.
service_present ai-ml-service || { skip ai-ml-service "not built yet"; exit 0; }

# Check service liveness; skip if container is not running locally.
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  skip ai-ml-service "service not running on $base (start with tests/integration/up.sh)"
  exit 0
fi

limit="${ROOM_HOURLY_LIMIT:-20}"
ts="$(date +%s)"
room_id="room-int-ctrl-$ts"
other_room="room-int-ctrl-other-$ts"

# Ensure clean room state at end
cleanup() {
  curl -s -o /dev/null -X DELETE "$base/v1/rooms/$room_id/state" || true
  curl -s -o /dev/null -X DELETE "$base/v1/rooms/$other_room/state" || true
}
trap cleanup EXIT

# 1. Send `limit` distinct requests with consent=true and ai_enabled=true -> none should be ROOM_RATE_LIMITED
for i in $(seq 1 "$limit"); do
  payload="{
    \"request_id\": \"test-ctrl-req-$i\",
    \"room_id\": \"$room_id\",
    \"analysis_id\": \"an-ctrl-$i\",
    \"request_type\": \"complexity\",
    \"language\": \"python\",
    \"consent\": true,
    \"ai_enabled\": true,
    \"code\": \"def fn_$i():\\n    return $i\\n\"
  }"
  resp="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
    -H "Content-Type: application/json" \
    -d "$payload")"
  status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
  body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"

  if [ "$status" != "200" ]; then
    fail ai-ml-service "expected HTTP 200 on request $i/$limit, got $status. Body: $body"
    exit 1
  fi
  if echo "$body" | grep -q "ROOM_RATE_LIMITED"; then
    fail ai-ml-service "unexpected ROOM_RATE_LIMITED on request $i/$limit: $body"
    exit 1
  fi
done
pass ai-ml-service "allowed up to ROOM_HOURLY_LIMIT=$limit requests without throttling (NFR5.3)"

# 2. Next distinct request (limit + 1) must be rate-limited -> HTTP 200 with error_code ROOM_RATE_LIMITED
over_idx=$((limit + 1))
payload_over="{
  \"request_id\": \"test-ctrl-req-$over_idx\",
  \"room_id\": \"$room_id\",
  \"analysis_id\": \"an-ctrl-$over_idx\",
  \"request_type\": \"complexity\",
  \"language\": \"python\",
  \"consent\": true,
  \"ai_enabled\": true,
  \"code\": \"def fn_$over_idx():\\n    return $over_idx\\n\"
}"
resp_over="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_over")"
status_over="$(echo "$resp_over" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_over="$(echo "$resp_over" | grep -v 'HTTP_STATUS:')"

if [ "$status_over" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on rate-limited request, got $status_over. Body: $body_over"
  exit 1
fi
if ! echo "$body_over" | grep -q "ROOM_RATE_LIMITED"; then
  fail ai-ml-service "expected ROOM_RATE_LIMITED on request $((limit + 1)), got: $body_over"
  exit 1
fi
pass ai-ml-service "request $((limit + 1)) returned skipped AI step ROOM_RATE_LIMITED (US-22)"

# 3. Verify gate precedence on throttled room: consent=false still yields CONSENT_MISSING
payload_consent="{
  \"request_id\": \"test-ctrl-consent\",
  \"room_id\": \"$room_id\",
  \"analysis_id\": \"an-ctrl-consent\",
  \"request_type\": \"complexity\",
  \"language\": \"python\",
  \"consent\": false,
  \"ai_enabled\": true,
  \"code\": \"def fn_consent():\\n    return 999\\n\"
}"
resp_consent="$(curl -s -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_consent")"
if ! echo "$resp_consent" | grep -q "CONSENT_MISSING"; then
  fail ai-ml-service "expected CONSENT_MISSING before rate limit on throttled room, got: $resp_consent"
  exit 1
fi
pass ai-ml-service "consent check takes precedence over rate limit (NFR5.1)"

# 4. Separate room is unaffected by first room's quota
payload_other="{
  \"request_id\": \"test-ctrl-other\",
  \"room_id\": \"$other_room\",
  \"analysis_id\": \"an-ctrl-other\",
  \"request_type\": \"complexity\",
  \"language\": \"python\",
  \"consent\": true,
  \"ai_enabled\": true,
  \"code\": \"def fn_other():\\n    return 123\\n\"
}"
resp_other="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_other")"
status_other="$(echo "$resp_other" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_other="$(echo "$resp_other" | grep -v 'HTTP_STATUS:')"

if [ "$status_other" != "200" ] || echo "$body_other" | grep -q "ROOM_RATE_LIMITED"; then
  fail ai-ml-service "separate room should not be rate-limited ($status_other): $body_other"
  exit 1
fi
pass ai-ml-service "separate room remains unaffected by throttled room quota"

# 5. DELETE /v1/rooms/{room_id}/state drops room cache and rate-limit state (US-56, FR32)
del_resp="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X DELETE "$base/v1/rooms/$room_id/state")"
del_status="$(echo "$del_resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
del_body="$(echo "$del_resp" | grep -v 'HTTP_STATUS:')"

if [ "$del_status" != "200" ] || ! echo "$del_body" | grep -q '"cleared"'; then
  fail ai-ml-service "DELETE /v1/rooms/$room_id/state failed ($del_status): $del_body"
  exit 1
fi

# After clearing room state, next request is no longer rate-limited
after_idx=$((limit + 2))
payload_after="{
  \"request_id\": \"test-ctrl-after-$after_idx\",
  \"room_id\": \"$room_id\",
  \"analysis_id\": \"an-ctrl-after-$after_idx\",
  \"request_type\": \"complexity\",
  \"language\": \"python\",
  \"consent\": true,
  \"ai_enabled\": true,
  \"code\": \"def fn_after():\\n    return $after_idx\\n\"
}"
resp_after="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_after")"
status_after="$(echo "$resp_after" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_after="$(echo "$resp_after" | grep -v 'HTTP_STATUS:')"

if [ "$status_after" != "200" ] || echo "$body_after" | grep -q "ROOM_RATE_LIMITED"; then
  fail ai-ml-service "expected room quota reset after DELETE /state ($status_after): $body_after"
  exit 1
fi
pass ai-ml-service "DELETE /v1/rooms/{room_id}/state resets room quota and state (US-56)"
