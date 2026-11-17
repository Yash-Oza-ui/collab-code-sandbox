#!/usr/bin/env bash
# US-13 (NFR4.1 to NFR4.5) integration test: strict resource limits in execution-service.
#
#   bash tests/integration/test-us13-resource-limits.sh
#
# Requires execution-service running (started by up.sh).
# Skipped automatically if execution-service has no Dockerfile yet.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SERVICE="execution-service"

if ! service_present "$SERVICE"; then
  skip "$SERVICE" "(no Dockerfile yet - US-13 resource limits test skipped)"
  exit 0
fi

BASE="$(service_url "$SERVICE")"
RUNS_URL="$BASE/v1/runs"
HEALTH_URL="$BASE/actuator/health"

failed=0

check() {
  local label="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then
    pass "$label" "= $got"
  else
    fail "$label" "expected '$want', got '$got'"
    failed=$((failed + 1))
  fi
}

check_contains() {
  local label="$1" needle="$2" haystack="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    pass "$label" "contains '$needle'"
  else
    fail "$label" "expected to contain '$needle', got '$haystack'"
    failed=$((failed + 1))
  fi
}

new_uuid() {
  printf -v REPLY '%04x%04x-%04x-4%03x-%x%03x-%04x%04x%04x' \
    $RANDOM $RANDOM $RANDOM $((RANDOM % 4096)) $((8 + RANDOM % 4)) $((RANDOM % 4096)) \
    $RANDOM $RANDOM $RANDOM
}

# ── Step 1: Confirm service health ──────────────────────────────────────────

health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  fail "health" "$HEALTH_URL -> $health_code (service not up?)"
  exit 1
fi
pass "health" "$HEALTH_URL -> 200"

ROOM_ID="room-us13-test"

# ── Case 1: Infinite loop hits wall-clock time limit (NFR4.1) ─────────────────
# Contract: timed_out: true, failure_kind: "timeout", message: "Time limit exceeded"

new_uuid; REQ_ID_TIMEOUT="$REPLY"
new_uuid; SESS_ID_TIMEOUT="$REPLY"
TIMEOUT_BODY=$(cat <<EOF
{
  "request_id": "$REQ_ID_TIMEOUT",
  "room_id":    "$ROOM_ID",
  "session_id": "$SESS_ID_TIMEOUT",
  "language":   "python",
  "source": {
    "kind": "inline",
    "files": [ { "name": "main.py", "content": "while True:\n    pass\n" } ],
    "entry": "main.py"
  }
}
EOF
)

TIMEOUT_OUT="$(mktemp)"
timeout_code="$(curl -s -o "$TIMEOUT_OUT" -w '%{http_code}' --max-time 15 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$TIMEOUT_BODY" 2>/dev/null || echo "000")"

timeout_body="$(cat "$TIMEOUT_OUT")"
rm -f "$TIMEOUT_OUT"

check "timeout-http-status" "200" "$timeout_code"
check_contains "timeout-timed_out" '"timed_out":true' "$timeout_body"
check_contains "timeout-failure_kind" '"failure_kind":"timeout"' "$timeout_body"
check_contains "timeout-message" '"message":"Time limit exceeded"' "$timeout_body"

# ── Case 2: Memory hog hits memory limit (NFR4.2) ─────────────────────────────
# Contract: oom_killed: true, failure_kind: "non_zero_exit", message: "Memory limit exceeded"

new_uuid; REQ_ID_OOM="$REPLY"
new_uuid; SESS_ID_OOM="$REPLY"
OOM_BODY=$(cat <<EOF
{
  "request_id": "$REQ_ID_OOM",
  "room_id":    "$ROOM_ID",
  "session_id": "$SESS_ID_OOM",
  "language":   "python",
  "source": {
    "kind": "inline",
    "files": [ { "name": "main.py", "content": "chunks = []\nwhile True:\n    chunks.append(bytearray(10 * 1024 * 1024))\n" } ],
    "entry": "main.py"
  }
}
EOF
)

OOM_OUT="$(mktemp)"
oom_code="$(curl -s -o "$OOM_OUT" -w '%{http_code}' --max-time 15 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$OOM_BODY" 2>/dev/null || echo "000")"

oom_body="$(cat "$OOM_OUT")"
rm -f "$OOM_OUT"

check "oom-http-status" "200" "$oom_code"
check_contains "oom-oom_killed" '"oom_killed":true' "$oom_body"
check_contains "oom-failure_kind" '"failure_kind":"non_zero_exit"' "$oom_body"
check_contains "oom-message" '"message":"Memory limit exceeded"' "$oom_body"

# ── Case 3: Output flood hits max output limit (NFR4.4) ───────────────────────
# Contract: output_truncated: true

new_uuid; REQ_ID_FLOOD="$REPLY"
new_uuid; SESS_ID_FLOOD="$REPLY"
FLOOD_BODY=$(cat <<EOF
{
  "request_id": "$REQ_ID_FLOOD",
  "room_id":    "$ROOM_ID",
  "session_id": "$SESS_ID_FLOOD",
  "language":   "python",
  "source": {
    "kind": "inline",
    "files": [ { "name": "main.py", "content": "for _ in range(50000):\n    print('A' * 40)\n" } ],
    "entry": "main.py"
  }
}
EOF
)

FLOOD_OUT="$(mktemp)"
flood_code="$(curl -s -o "$FLOOD_OUT" -w '%{http_code}' --max-time 15 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$FLOOD_BODY" 2>/dev/null || echo "000")"

flood_body="$(cat "$FLOOD_OUT")"
rm -f "$FLOOD_OUT"

check "flood-http-status" "200" "$flood_code"
check_contains "flood-output_truncated" '"output_truncated":true' "$flood_body"

# ── Final result ─────────────────────────────────────────────────────────────

if [ "$failed" -gt 0 ]; then
  echo "US-13 RESOURCE-LIMITS TEST FAILED ($failed assertion(s) failed)"
  exit 1
fi

echo "US-13 RESOURCE-LIMITS TEST PASSED"
exit 0
