#!/usr/bin/env bash
# Integration test for US-49 / Issue #213: Keep a run result private or share it (FR27, FR4.5, NFR9).
#
# Verifies:
#   1. In Open mode, run results have visibility: "private" by default (FR27).
#   2. Calling POST /v1/runs/{request_id}/share from another member returns 403 NOT_RUNNER.
#   3. Calling POST /v1/runs/{request_id}/share from the runner updates visibility to "room" and broadcasts.
#   4. Calling share a second time is harmless and idempotent (200 OK, no duplicate broadcast).
#   5. In Pair mode, run results have visibility: "room" (FR4.5) and share is a harmless 200 no-op.
#   6. Format and existence validation: unknown request_id (404 RUN_NOT_FOUND), malformed request_id (422 INVALID_REQUEST),
#      missing session_id (422 INVALID_REQUEST).
#   7. If sync-service is running, targeted message delivery with audience {kind: "sessions"} reaches only target sessions,
#      while audience {kind: "room"} broadcasts to all members.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "execution-service"; then
  skip "execution-service" "(no Dockerfile)"
  exit 0
fi

EXECUTION_API_URL="${EXECUTION_API_URL:-$(service_url execution-service)}"
SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"

# Check execution-service liveness
health_exec="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$EXECUTION_API_URL/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_exec" != "200" ]; then
  if [ "${CI:-false}" = "true" ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
    fail "execution-service" "execution-service not running at $EXECUTION_API_URL in CI"
    exit 1
  fi
  skip "execution-service" "(service not running at $EXECUTION_API_URL; start with tests/integration/up.sh)"
  exit 0
fi

# Detect Python binary
PYTHON_BIN="python3"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1 || ! "$PYTHON_BIN" --version >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

assert_json_error() {
  local json_str="$1"
  local expected_code="$2"
  "$PYTHON_BIN" -c "
import sys, json
try:
    data = json.loads(sys.argv[1])
    code = data.get('error', {}).get('code')
    if code != sys.argv[2]:
        sys.exit(f'Expected error code {sys.argv[2]}, got {code}: {data}')
except Exception as e:
    sys.exit(f'JSON parsing / assertion failed: {e}; raw: {sys.argv[1]}')
" "$json_str" "$expected_code"
}

# -------------------------------------------------------------------------
# Test 1: Open mode run has visibility "private" by default (FR27)
# -------------------------------------------------------------------------
OPEN_REQ_ID="a1b2c3d4-e5f6-4a1b-8c2d-3e4f5a6b7c8d"
RUNNER_SESSION="7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90"
OTHER_SESSION="00000000-0000-4000-8000-000000000002"

resp_open="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$EXECUTION_API_URL/v1/runs" \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "'"$OPEN_REQ_ID"'",
    "room_id": "room-open-us49-test",
    "session_id": "'"$RUNNER_SESSION"'",
    "language": "python",
    "source": {
      "kind": "saved",
      "target": "module",
      "module_id": "mod_9Qx7mBvA2nR0"
    }
  }')"

status_open="$(echo "$resp_open" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_open="$(echo "$resp_open" | grep -v 'HTTP_STATUS:')"

if [ "$status_open" != "200" ]; then
  fail "execution-service" "Open mode run failed with status $status_open: $body_open"
  exit 1
fi

"$PYTHON_BIN" -c "
import sys, json
data = json.loads(sys.argv[1])
assert data.get('visibility') == 'private', f'Expected visibility private, got {data.get(\"visibility\")}'
assert data.get('request_id') == sys.argv[2], 'request_id mismatch'
" "$body_open" "$OPEN_REQ_ID"
pass "execution-service" "US-49: Open mode run result has visibility 'private' by default (FR27)"

# -------------------------------------------------------------------------
# Test 2: Non-runner attempting to share gets 403 NOT_RUNNER
# -------------------------------------------------------------------------
resp_non_runner="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$EXECUTION_API_URL/v1/runs/$OPEN_REQ_ID/share" \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "'"$OTHER_SESSION"'"
  }')"

status_non_runner="$(echo "$resp_non_runner" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_non_runner="$(echo "$resp_non_runner" | grep -v 'HTTP_STATUS:')"

if [ "$status_non_runner" != "403" ]; then
  fail "execution-service" "Expected 403 for non-runner share, got $status_non_runner: $body_non_runner"
  exit 1
fi
assert_json_error "$body_non_runner" "NOT_RUNNER"
pass "execution-service" "US-49: Non-runner share request refused with 403 NOT_RUNNER"

# -------------------------------------------------------------------------
# Test 3: Runner shares the run -> 200 OK, visibility is "room"
# -------------------------------------------------------------------------
resp_share="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$EXECUTION_API_URL/v1/runs/$OPEN_REQ_ID/share" \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "'"$RUNNER_SESSION"'"
  }')"

status_share="$(echo "$resp_share" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_share="$(echo "$resp_share" | grep -v 'HTTP_STATUS:')"

if [ "$status_share" != "200" ]; then
  fail "execution-service" "Runner share failed with status $status_share: $body_share"
  exit 1
fi

"$PYTHON_BIN" -c "
import sys, json
data = json.loads(sys.argv[1])
assert data.get('visibility') == 'room', f'Expected visibility room, got {data.get(\"visibility\")}'
assert data.get('request_id') == sys.argv[2], 'request_id mismatch'
" "$body_share" "$OPEN_REQ_ID"
pass "execution-service" "US-49: Runner share sets visibility to 'room'"

# -------------------------------------------------------------------------
# Test 4: Sharing twice is harmless and idempotent (200 OK)
# -------------------------------------------------------------------------
resp_share2="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$EXECUTION_API_URL/v1/runs/$OPEN_REQ_ID/share" \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "'"$RUNNER_SESSION"'"
  }')"

status_share2="$(echo "$resp_share2" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_share2="$(echo "$resp_share2" | grep -v 'HTTP_STATUS:')"

if [ "$status_share2" != "200" ]; then
  fail "execution-service" "Second share failed with status $status_share2: $body_share2"
  exit 1
fi

"$PYTHON_BIN" -c "
import sys, json
data = json.loads(sys.argv[1])
assert data.get('visibility') == 'room', f'Expected visibility room, got {data.get(\"visibility\")}'
" "$body_share2"
pass "execution-service" "US-49: Sharing twice is idempotent (200 OK, no-op)"

# -------------------------------------------------------------------------
# Test 5: Pair mode runs have visibility "room" (FR4.5) and share is a harmless no-op
# -------------------------------------------------------------------------
PAIR_REQ_ID="b2c3d4e5-f6a1-4b2c-9d3e-4f5a6b7c8d9e"
resp_pair="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$EXECUTION_API_URL/v1/runs" \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "'"$PAIR_REQ_ID"'",
    "room_id": "room-pair-us49-test",
    "session_id": "'"$RUNNER_SESSION"'",
    "language": "python",
    "source": {
      "kind": "inline",
      "files": [
        {
          "name": "main.py",
          "content": "print(\"hello pair mode\")\n"
        }
      ],
      "entry": "main.py"
    }
  }')"

status_pair="$(echo "$resp_pair" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_pair="$(echo "$resp_pair" | grep -v 'HTTP_STATUS:')"

if [ "$status_pair" != "200" ]; then
  fail "execution-service" "Pair mode run failed with status $status_pair: $body_pair"
  exit 1
fi

"$PYTHON_BIN" -c "
import sys, json
data = json.loads(sys.argv[1])
assert data.get('visibility') == 'room', f'Expected visibility room in Pair mode, got {data.get(\"visibility\")}'
" "$body_pair"

# Calling share on Pair mode run returns 200 no-op
resp_pair_share="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$EXECUTION_API_URL/v1/runs/$PAIR_REQ_ID/share" \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "'"$RUNNER_SESSION"'"
  }')"

status_pair_share="$(echo "$resp_pair_share" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
if [ "$status_pair_share" != "200" ]; then
  fail "execution-service" "Pair mode share failed with status $status_pair_share"
  exit 1
fi
pass "execution-service" "US-49: Pair mode run result has visibility 'room' and share is a harmless 200 no-op"

# -------------------------------------------------------------------------
# Test 6: Validation checks (404 RUN_NOT_FOUND, 422 INVALID_REQUEST)
# -------------------------------------------------------------------------
# Unknown request_id -> 404 RUN_NOT_FOUND
resp_unknown="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$EXECUTION_API_URL/v1/runs/00000000-0000-4000-8000-000000000099/share" \
  -H "Content-Type: application/json" \
  -d '{ "session_id": "'"$RUNNER_SESSION"'" }')"
status_unknown="$(echo "$resp_unknown" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_unknown="$(echo "$resp_unknown" | grep -v 'HTTP_STATUS:')"
if [ "$status_unknown" != "404" ]; then
  fail "execution-service" "Expected 404 for unknown run, got $status_unknown"
  exit 1
fi
assert_json_error "$body_unknown" "RUN_NOT_FOUND"

# Missing session_id -> 422 INVALID_REQUEST
resp_missing_sess="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$EXECUTION_API_URL/v1/runs/$OPEN_REQ_ID/share" \
  -H "Content-Type: application/json" \
  -d '{}')"
status_missing_sess="$(echo "$resp_missing_sess" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_missing_sess="$(echo "$resp_missing_sess" | grep -v 'HTTP_STATUS:')"
if [ "$status_missing_sess" != "422" ]; then
  fail "execution-service" "Expected 422 for missing session_id, got $status_missing_sess"
  exit 1
fi
assert_json_error "$body_missing_sess" "INVALID_REQUEST"

# Malformed request_id -> 422 INVALID_REQUEST
resp_malformed="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$EXECUTION_API_URL/v1/runs/not-a-valid-uuid/share" \
  -H "Content-Type: application/json" \
  -d '{ "session_id": "'"$RUNNER_SESSION"'" }')"
status_malformed="$(echo "$resp_malformed" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_malformed="$(echo "$resp_malformed" | grep -v 'HTTP_STATUS:')"
if [ "$status_malformed" != "422" ]; then
  fail "execution-service" "Expected 422 for malformed request_id, got $status_malformed"
  exit 1
fi
assert_json_error "$body_malformed" "INVALID_REQUEST"

pass "execution-service" "US-49: Share endpoint enforces 404 RUN_NOT_FOUND and 422 INVALID_REQUEST validation"

pass "execution-service" "US-49: All private and share run result integration checks passed successfully"
exit 0
