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
HEALTH_URL="$BASE/actuator/health"

failed=0

# ── Helpers ──────────────────────────────────────────────────────────────────

check() {
  local label="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then
    pass "$label" "= $got"
  else
    fail "$label" "expected '$want', got '$got'"
    failed=$((failed + 1))
  fi
}

gen_uuid() {
  if command -v python >/dev/null 2>&1; then
    python -c 'import uuid; print(uuid.uuid4())' 2>/dev/null && return 0
  fi
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen 2>/dev/null | tr '[:upper:]' '[:lower:]' && return 0
  fi
  printf "%04x%04x-%04x-4000-8000-%04x%04x%04x\n" \
    $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM
}

# ── Step 1: Confirm service is healthy and query queue configuration ─────────

health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  fail "health" "$HEALTH_URL -> $health_code (service not up?)"
  exit 1
fi
pass "health" "$HEALTH_URL -> 200"

health_body="$(curl -s --max-time 5 "$HEALTH_URL" 2>/dev/null || true)"

# Details are hidden by default (MANAGEMENT_HEALTH_SHOW_DETAILS=never); this test
# needs them to observe queue saturation. up.sh enables them for the test stack.
if ! echo "$health_body" | grep -q '"in_flight"'; then
  fail "health-details" "$HEALTH_URL has no queue details; start the stack with MANAGEMENT_HEALTH_SHOW_DETAILS=always (bash tests/integration/up.sh does this)"
  exit 1
fi

# Inspect configured max-queue-length and retry-after from actuator health
configured_max_q=$(echo "$health_body" | grep -o '"max_queue_length":[0-9]*' | head -1 | cut -d':' -f2)
configured_retry=$(echo "$health_body" | grep -o '"retry_after_seconds":[0-9]*' | head -1 | cut -d':' -f2)

MAX_QUEUE="${configured_max_q:-${EXECUTION_MAX_QUEUE_LENGTH:-10}}"
EXPECTED_RETRY="${configured_retry:-5}"

echo "Configured queue capacity: $MAX_QUEUE slot(s), retry_after: ${EXPECTED_RETRY}s"

# ── Step 2: Verify normal run on an empty queue (HTTP 200) ───────────────────

REQ_ID_NORMAL="$(gen_uuid)"
SESS_ID_NORMAL="$(gen_uuid)"
ROOM_ID="room-us11-bash-test"

BODY_NORMAL=$(cat <<EOF
{
  "request_id": "$REQ_ID_NORMAL",
  "room_id":    "$ROOM_ID",
  "session_id": "$SESS_ID_NORMAL",
  "language":   "python",
  "source": { "kind": "inline", "files": [ { "name": "main.py", "content": "print('ready')" } ], "entry": "main.py" }
}
EOF
)

normal_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$BODY_NORMAL" 2>/dev/null || echo "000")"

if [ "$normal_code" != "200" ]; then
  fail "normal-run" "POST /v1/runs -> $normal_code (expected 200 on an empty queue)"
  failed=$((failed + 1))
else
  pass "normal-run" "POST /v1/runs -> 200 (queue gate accepts when slot free)"
fi

# ── Step 3: Occupy all configured queue slots and establish readiness ─────────

HOLD_PIDS=()
TMP_FILES=()

cleanup() {
  for pid in "${HOLD_PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  for pid in "${HOLD_PIDS[@]:-}"; do
    wait "$pid" 2>/dev/null || true
  done
  for tmp in "${TMP_FILES[@]:-}"; do
    rm -f "$tmp"
  done
}
trap cleanup EXIT INT TERM

# Launch MAX_QUEUE holding requests with bounded mock runner execution (3 seconds sleep)
echo "Launching $MAX_QUEUE holding request(s) to occupy all queue slots..."
for i in $(seq 1 "$MAX_QUEUE"); do
  hold_req_id="$(gen_uuid)"
  hold_sess_id="$(gen_uuid)"
  hold_tmp="$(mktemp)"
  TMP_FILES+=("$hold_tmp")

  hold_body=$(cat <<EOF
{
  "request_id": "$hold_req_id",
  "room_id":    "$ROOM_ID",
  "session_id": "$hold_sess_id",
  "language":   "python",
  "source": { "kind": "inline", "files": [ { "name": "main.py", "content": "import time; time.sleep(3)" } ], "entry": "main.py" }
}
EOF
)

  curl -s -o "$hold_tmp" -w '%{http_code}' --max-time 15 \
    -X POST "$RUNS_URL" \
    -H "Content-Type: application/json" \
    -d "$hold_body" > /dev/null 2>&1 &
  HOLD_PIDS+=($!)
done

# Wait for all holding requests to genuinely acquire queue slots
echo "Verifying queue saturation via observable in-flight state..."
saturated=false
in_flight=0
for attempt in $(seq 1 100); do
  h_body="$(curl -s --max-time 2 "$HEALTH_URL" 2>/dev/null || true)"
  in_flight=$(echo "$h_body" | grep -o '"in_flight":[0-9]*' | head -1 | cut -d':' -f2)
  if [ -n "$in_flight" ] && [ "$in_flight" -ge "$MAX_QUEUE" ]; then
    saturated=true
    pass "queue-saturation" "observable in_flight ($in_flight) >= max_queue ($MAX_QUEUE)"
    break
  fi
  sleep 0.1
done

if [ "$saturated" != "true" ]; then
  fail "queue-saturation" "could not establish queue saturation (in_flight: ${in_flight:-unknown}, required: $MAX_QUEUE)"
  echo "NOT EXECUTED: Queue saturation preconditions not met"
  exit 1
fi

# ── Step 4: Submit overflow request while queue is full ───────────────────────

OVERFLOW_REQ_ID="$(gen_uuid)"
OVERFLOW_SESS_ID="$(gen_uuid)"

OVERFLOW_BODY=$(cat <<EOF
{
  "request_id": "$OVERFLOW_REQ_ID",
  "room_id":    "$ROOM_ID",
  "session_id": "$OVERFLOW_SESS_ID",
  "language":   "python",
  "source": { "kind": "inline", "files": [ { "name": "main.py", "content": "print('overflow')" } ], "entry": "main.py" }
}
EOF
)

OVERFLOW_OUT="$(mktemp)"
OVERFLOW_HDR="$(mktemp)"
TMP_FILES+=("$OVERFLOW_OUT" "$OVERFLOW_HDR")

overflow_code="$(curl -s -o "$OVERFLOW_OUT" -D "$OVERFLOW_HDR" -w '%{http_code}' --max-time 10 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$OVERFLOW_BODY" 2>/dev/null || echo "000")"

overflow_body="$(cat "$OVERFLOW_OUT")"
retry_after_hdr="$(grep -i '^Retry-After:' "$OVERFLOW_HDR" | head -1 | tr -d '\r' | awk '{print $2}')"

# ── Step 5: Verify the complete QUEUE_FULL response shape (Comment 4B) ────────

check "queue-full-status" "503" "${overflow_code}"
check "retry-after-header" "$EXPECTED_RETRY" "${retry_after_hdr:-missing}"

resp_req_id=$(echo "$overflow_body" | grep -o '"request_id":"[^"]*"' | head -1 | cut -d':' -f2 | tr -d '"')
error_code=$(echo "$overflow_body" | grep -o '"code":"[^"]*"' | head -1 | cut -d':' -f2 | tr -d '"')
error_msg=$(echo "$overflow_body" | sed -E 's/.*"message"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')
retry_after_sec=$(echo "$overflow_body" | grep -o '"retry_after_seconds":[0-9]*' | head -1 | cut -d':' -f2)

check "error-request-id"          "$OVERFLOW_REQ_ID"                                         "${resp_req_id:-missing}"
check "error-code"                "QUEUE_FULL"                                               "${error_code:-missing}"
check "error-message"             "Execution service is busy, try again in a few seconds."   "${error_msg:-missing}"
check "error-retry-after-seconds" "$EXPECTED_RETRY"                                         "${retry_after_sec:-missing}"

# ── Step 6: Clean cleanup - wait for holding requests to finish bounded run ───

echo "Waiting for held requests to complete on their own (bounded execution)..."
for pid in "${HOLD_PIDS[@]}"; do
  wait "$pid" 2>/dev/null || true
done

# Confirm queue slots are released
recovered=false
for attempt in $(seq 1 40); do
  h_body="$(curl -s --max-time 2 "$HEALTH_URL" 2>/dev/null || true)"
  in_flight=$(echo "$h_body" | grep -o '"in_flight":[0-9]*' | head -1 | cut -d':' -f2)
  if [ -n "$in_flight" ] && [ "$in_flight" -eq 0 ]; then
    recovered=true
    pass "queue-recovery" "all slots released (in_flight = 0)"
    break
  fi
  sleep 0.1
done

# Verify a new request succeeds once slots are free
POST_REQ_ID="$(gen_uuid)"
POST_SESS_ID="$(gen_uuid)"
POST_BODY=$(cat <<EOF
{
  "request_id": "$POST_REQ_ID",
  "room_id":    "$ROOM_ID",
  "session_id": "$POST_SESS_ID",
  "language":   "python",
  "source": { "kind": "inline", "files": [ { "name": "main.py", "content": "print('after recovery')" } ], "entry": "main.py" }
}
EOF
)

post_status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$POST_BODY" 2>/dev/null || echo "000")"

check "post-recovery-status" "200" "$post_status"

# ── Final Result ─────────────────────────────────────────────────────────────

if [ "$failed" -gt 0 ]; then
  echo "US-11 QUEUE-FULL TEST FAILED ($failed assertion(s) failed)"
  exit 1
fi

echo "US-11 QUEUE-FULL TEST PASSED"
exit 0
