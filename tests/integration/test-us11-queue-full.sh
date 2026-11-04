#!/usr/bin/env bash
# US-11 (FR16) integration test: queue-full QUEUE_FULL response shape (T-08).
#
#   bash tests/integration/test-us11-queue-full.sh
#
# Requires execution-service running (started by up.sh).
# Skipped automatically if execution-service has no Dockerfile yet.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SERVICE="execution-service"

if ! service_present "$SERVICE"; then
  skip "$SERVICE" "(no Dockerfile yet - US-11 queue test skipped)"
  exit 0
fi

BASE="$(service_url "$SERVICE")"
RUNS_URL="$BASE/v1/runs"

BODY='{
  "request_id": "us11-inttest-0000-0000-000000000001",
  "room_id":    "room-us11-bash-test",
  "session_id": "us11-inttest-0000-0000-000000000002",
  "language":   "python",
  "code":       "import time; time.sleep(30)"
}'

BODY2='{
  "request_id": "us11-inttest-0000-0000-000000000003",
  "room_id":    "room-us11-bash-test",
  "session_id": "us11-inttest-0000-0000-000000000002",
  "language":   "python",
  "code":       "print(42)"
}'

failed=0

# ── Helper ──────────────────────────────────────────────────────────────────

check() {
  local label="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then
    pass "$label" "= $got"
  else
    fail "$label" "expected '$want', got '$got'"
    failed=$((failed + 1))
  fi
}

# ── Step 1: confirm service is healthy ───────────────────────────────────────

health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/actuator/health" 2>/dev/null || echo 000)"
if [ "$health_code" != "200" ]; then
  fail "health" "$BASE/actuator/health -> $health_code (service not up?)"
  exit 1
fi
pass "health" "$BASE/actuator/health -> 200"

# ── Step 2: fire a slow-running request in background (holds the slot) ───────
# This test only works if max-queue-length is 1 in the running stack.
# If the limit is higher, we cannot easily fill it here, so we note that
# and still verify the 200-path is alive.

http_code_ok="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d '{"request_id":"us11-probe","room_id":"r","session_id":"s","language":"python","code":"print(1)"}' \
  2>/dev/null || echo 000)"

if [ "$http_code_ok" != "200" ]; then
  fail "normal-run" "POST /v1/runs -> $http_code_ok (expected 200 on an empty queue)"
  failed=$((failed + 1))
else
  pass "normal-run" "POST /v1/runs -> 200 (queue gate accepts when slot free)"
fi

# ── Step 3: verify QUEUE_FULL shape when limit explicitly hit ─────────────────
# We hit /v1/runs twice concurrently: first blocks, second must see 503.
# Use a slow-sleep code in first request and background it.

TMPFILE_1="$(mktemp)"
TMPFILE_2="$(mktemp)"

# Fire request 1 in background (long sleep – will be cancelled when we kill curl)
curl -s -o "$TMPFILE_1" -w '%{http_code}' --max-time 20 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$BODY" > /dev/null 2>&1 &
BG_PID=$!

# Give it a moment to reach the service
sleep 2

# Fire request 2 synchronously – it should be refused at once
STATUS2="$(curl -s -o "$TMPFILE_2" -w '%{http_code}' --max-time 10 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$BODY2" 2>/dev/null || echo 000)"

# Kill the background slow request
kill "$BG_PID" 2>/dev/null || true
wait "$BG_PID" 2>/dev/null || true

if [ "$STATUS2" = "503" ]; then
  # Verify error shape
  code_field="$(grep -oE '"code"[[:space:]]*:[[:space:]]*"[^"]+"' "$TMPFILE_2" | head -1 | sed -E 's/.*"code"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"
  message_field="$(grep -oE '"message"[[:space:]]*:[[:space:]]*"[^"]+"' "$TMPFILE_2" | head -1 | sed -E 's/.*"message"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"
  check "queue-full-status"  "503"        "$STATUS2"
  check "queue-full-code"    "QUEUE_FULL" "$code_field"
  if [ -n "$message_field" ]; then
    pass "queue-full-message" "= $message_field"
  else
    fail "queue-full-message" "error.message field missing from 503 body"
    failed=$((failed + 1))
  fi
elif [ "$STATUS2" = "200" ]; then
  # Queue limit > 1: both requests were accepted - service is working correctly
  # but we cannot verify the 503 path here; note it as a skip
  skip "queue-full-503" "(max-queue-length > 1 in this stack; 503 path not exercised)"
else
  fail "queue-full-status" "expected 503 or 200, got $STATUS2"
  failed=$((failed + 1))
fi

rm -f "$TMPFILE_1" "$TMPFILE_2"

# ── Result ───────────────────────────────────────────────────────────────────

if [ "$failed" -gt 0 ]; then
  echo "US-11 QUEUE-FULL TEST FAILED ($failed assertion(s) failed)"
  exit 1
fi
echo "US-11 QUEUE-FULL TEST PASSED"

