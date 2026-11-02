#!/usr/bin/env bash
# Integration test for T-19 run specification endpoint (Jay Limbasiya).
# Verifies that POST /v1/runs accepts the run spec object and rejects legacy code bodies.
set -uo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "execution-service"; then
  skip "execution-service" "(no Dockerfile)"
  exit 0
fi

base_url="$(service_url execution-service)"
endpoint="$base_url/v1/runs"

# Check if execution-service is accessible; skip if offline
if ! curl -s --max-time 2 "$base_url/actuator/health" >/dev/null 2>&1; then
  skip "execution-service" "(service not running at $base_url)"
  exit 0
fi

# 1. Valid inline source in Pair mode (T-19 AC1, AC2)
resp_inline="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
    "room_id": "integration-test-room",
    "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
    "language": "python",
    "stdin": "",
    "source": {
      "kind": "inline",
      "files": [
        {
          "name": "main.py",
          "content": "print(\"hello from test\")\n"
        }
      ],
      "entry": "main.py"
    }
  }')"

status_inline="$(echo "$resp_inline" | grep "HTTP_STATUS" | cut -d':' -f2)"
body_inline="$(echo "$resp_inline" | grep -v "HTTP_STATUS")"

if [ "$status_inline" = "200" ]; then
  pass "inline-run" "POST /v1/runs with inline source -> 200"
else
  fail "inline-run" "expected 200, got $status_inline: $body_inline"
  exit 1
fi

# 2. Legacy 'code' body must be rejected as 422 INVALID_REQUEST (T-19 AC4)
resp_code="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
    "room_id": "integration-test-room",
    "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
    "language": "python",
    "code": "print(1)"
  }')"

status_code="$(echo "$resp_code" | grep "HTTP_STATUS" | cut -d':' -f2)"
body_code="$(echo "$resp_code" | grep -v "HTTP_STATUS")"

if [ "$status_code" = "422" ] && echo "$body_code" | grep -q "INVALID_REQUEST"; then
  pass "legacy-code-422" "legacy 'code' rejected -> 422 INVALID_REQUEST"
else
  fail "legacy-code-422" "expected 422 with INVALID_REQUEST, got $status_code: $body_code"
  exit 1
fi

# 3. Invalid source kind must be rejected as 422 INVALID_REQUEST
resp_kind="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
    "room_id": "integration-test-room",
    "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
    "language": "python",
    "source": {
      "kind": "unknown_shape"
    }
  }')"

status_kind="$(echo "$resp_kind" | grep "HTTP_STATUS" | cut -d':' -f2)"
body_kind="$(echo "$resp_kind" | grep -v "HTTP_STATUS")"

if [ "$status_kind" = "422" ] && echo "$body_kind" | grep -q "INVALID_REQUEST"; then
  pass "invalid-kind-422" "invalid source kind rejected -> 422 INVALID_REQUEST"
else
  fail "invalid-kind-422" "expected 422 with INVALID_REQUEST, got $status_kind: $body_kind"
  exit 1
fi

exit 0

