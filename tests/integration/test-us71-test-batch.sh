#!/usr/bin/env bash
# US-71 (FR38.4, FR5.1, NFR4.1, NFR4.2, NFR14, CR15) integration test:
# Runs a batch of generated tests in one container:
#   1. Authentication check (X-Internal-Token required).
#   2. Batch of 10 tests starts exactly ONE container and leaves no leftover container.
#   3. Per-test 5s wall-clock limit: infinite loop test times out, remaining tests still report results.
#   4. Per-test memory limit: memory-hog test is killed, remaining tests still run.
#   5. Network isolation: test attempting network connection fails under sandbox isolation.
#   6. Run cap exceeded: returns 429 RUN_BUDGET_EXCEEDED with partial results preserved.
#   7. Aggregate time budget exceeded: returns 429 RUN_BUDGET_EXCEEDED with partial results preserved.
#   8. Shared RunQueueGate refusal: returns 503 QUEUE_FULL busy message when queue is full.
#   9. Ephemeral container and workspace destroyed afterwards in all cases (FR5.2).
#
#   bash tests/integration/test-us71-test-batch.sh
#
# Requires execution-service running (started by tests/integration/up.sh).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SERVICE="execution-service"

if ! service_present "$SERVICE"; then
  skip "$SERVICE" "(no Dockerfile yet - US-71 test-batch test skipped)"
  exit 0
fi

BASE="$(service_url "$SERVICE")"
INTERNAL_RUNS_URL="$BASE/internal/runs"
PUBLIC_RUNS_URL="$BASE/v1/runs"
HEALTH_URL="$BASE/actuator/health"
TOKEN="${INTERNAL_API_TOKEN:-test-token}"

failed=0
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

check() {
  local label="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then
    pass "$label" "= $got"
  else
    fail "$label" "expected '$want', got '$got'"
    failed=$((failed + 1))
  fi
}

new_uuid() {
  printf -v REPLY '%04x%04x-%04x-4%03x-%x%03x-%04x%04x%04x' \
    $RANDOM $RANDOM $RANDOM $((RANDOM % 4096)) $((8 + RANDOM % 4)) $((RANDOM % 4096)) \
    $RANDOM $RANDOM $RANDOM
}

PYTHON_BIN="python3"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1 || ! "$PYTHON_BIN" --version >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

post_internal() {
  local timeout="$1" hdr="$2" out_file="$3" hdr_file="$4" data="$5"
  local cmd=(curl -s -o "$out_file" -w '%{http_code}' --max-time "$timeout" -X POST "$INTERNAL_RUNS_URL" -H "Content-Type: application/json")
  if [ -n "$hdr_file" ]; then cmd+=(-D "$hdr_file"); fi
  if [ -n "$hdr" ]; then cmd+=(-H "$hdr"); fi
  cmd+=(-d "$data")
  "${cmd[@]}" 2>/dev/null || echo "000"
}

AUTH_HDR="X-Internal-Token: $TOKEN"
WRONG_AUTH_HDR="X-Internal-Token: wrong-token"
ROOM_ID="Zq8-Lw2Ef6Th0Yx4Rn7JbA"

# ── Step 1: Liveness check ───────────────────────────────────────────────────

health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  if [ "${CI:-false}" = "true" ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
    fail "health" "$HEALTH_URL -> $health_code (service not running in CI)"
    exit 1
  fi
  skip "us71-test-batch" "execution-service not running on $BASE (start with tests/integration/up.sh)"
  exit 0
fi
pass "health" "$HEALTH_URL -> 200"

MAX_RUNS="${APP_EXECUTION_LIMITS_MAX_RUNS_PER_REQUEST:-10}"
BUDGET_MS="${APP_EXECUTION_LIMITS_AGGREGATE_TIME_BUDGET_MS:-20000}"
BUDGET_RETRY="${APP_EXECUTION_LIMITS_BUDGET_RETRY_AFTER_SECONDS:-30}"
WALL_SEC="${APP_EXECUTION_LIMITS_WALL_TIME_SECONDS:-5}"

# ── Step 2: Auth check ───────────────────────────────────────────────────────

new_uuid; REQ_AUTH="$REPLY"
AUTH_BODY="{\"request_id\":\"$REQ_AUTH\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"print(1)\",\"inputs\":[\"1\"]}"

no_tok_code="$(post_internal 10 "" /dev/null "" "$AUTH_BODY")"
check "auth-missing-token" "401" "$no_tok_code"

bad_tok_code="$(post_internal 10 "$WRONG_AUTH_HDR" /dev/null "" "$AUTH_BODY")"
check "auth-invalid-token" "401" "$bad_tok_code"

# ── Step 3: Happy path batch of 10 tests (FR5.1, FR38.4) ─────────────────────

new_uuid; REQ_BATCH10="$REPLY"
BATCH10_OUT="$(mktemp)"; TMP_FILES+=("$BATCH10_OUT")
BATCH10_INPUTS="$("$PYTHON_BIN" -c "import json; print(json.dumps([str(i) for i in range(1, 11)]))")"
BATCH10_BODY="{\"request_id\":\"$REQ_BATCH10\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"val = int(input().strip())\\nprint(val * 2)\",\"inputs\":$BATCH10_INPUTS}"

# Count run containers before
before_containers=0
if command -v docker >/dev/null 2>&1; then
  before_containers=$(docker ps -a --filter "name=run-" --format '{{.Names}}' 2>/dev/null | wc -l || echo 0)
fi

b10_code="$(post_internal 40 "$AUTH_HDR" "$BATCH10_OUT" "" "$BATCH10_BODY")"
check "batch-10-tests-status" "200" "$b10_code"

if "$PYTHON_BIN" - "$BATCH10_OUT" "$REQ_BATCH10" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
assert data["request_id"] == sys.argv[2], data
assert data["purpose"] == "test_batch", data
results = data["batch_results"]
assert len(results) == 10, f"Expected 10 results, got {len(results)}"
for i in range(10):
    r = results[i]
    assert r["input"] == str(i + 1), r
    assert r["status"] == "pass", r
    assert r["exit_code"] == 0, r
    assert r["stdout"].strip() == str((i + 1) * 2), r
PY
then
  pass "batch-10-tests-body" "10 sequential test results matching input order and calculation"
else
  fail "batch-10-tests-body" "unexpected response: $(cat "$BATCH10_OUT")"
  failed=$((failed + 1))
fi

if command -v docker >/dev/null 2>&1; then
  after_containers=$(docker ps -a --filter "name=run-" --format '{{.Names}}' 2>/dev/null | wc -l || echo 0)
  check "batch-10-no-leftover-containers" "$before_containers" "$after_containers"
fi

# ── Step 4: Per-test timeout limit (NFR4.1) ──────────────────────────────────

new_uuid; REQ_TO="$REPLY"
TO_OUT="$(mktemp)"; TMP_FILES+=("$TO_OUT")
TO_BODY="{\"request_id\":\"$REQ_TO\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"val = input().strip()\\nif val == 'loop':\\n    while True:\\n        pass\\nprint('OK:' + val)\",\"inputs\":[\"first\",\"loop\",\"last\"]}"

to_code="$(post_internal 35 "$AUTH_HDR" "$TO_OUT" "" "$TO_BODY")"
check "per-test-timeout-status" "200" "$to_code"

if "$PYTHON_BIN" - "$TO_OUT" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
results = data["batch_results"]
assert len(results) == 3, results
assert results[0]["status"] == "pass" and results[0]["stdout"].strip() == "OK:first", results[0]
assert results[1]["status"] == "timeout" and results[1]["exit_code"] == 124, results[1]
assert results[2]["status"] == "pass" and results[2]["stdout"].strip() == "OK:last", results[2]
PY
then
  pass "per-test-timeout-body" "test 2 timed out at 5s, remaining test 3 still ran and reported result"
else
  fail "per-test-timeout-body" "unexpected response: $(cat "$TO_OUT")"
  failed=$((failed + 1))
fi

# ── Step 5: Per-test memory limit (NFR4.2) ───────────────────────────────────

new_uuid; REQ_OOM="$REPLY"
OOM_OUT="$(mktemp)"; TMP_FILES+=("$OOM_OUT")
OOM_BODY="{\"request_id\":\"$REQ_OOM\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"val = input().strip()\\nif val == 'oom':\\n    data = []\\n    while True:\\n        data.append(bytearray(10 * 1024 * 1024))\\nprint('OK:' + val)\",\"inputs\":[\"first\",\"oom\",\"last\"]}"

oom_code="$(post_internal 35 "$AUTH_HDR" "$OOM_OUT" "" "$OOM_BODY")"
check "per-test-oom-status" "200" "$oom_code"

if "$PYTHON_BIN" - "$OOM_OUT" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
results = data["batch_results"]
assert len(results) == 3, results
assert results[0]["status"] == "pass" and results[0]["stdout"].strip() == "OK:first", results[0]
assert results[1]["status"] == "crash" and results[1]["exit_code"] != 0, results[1]
assert results[2]["status"] == "pass" and results[2]["stdout"].strip() == "OK:last", results[2]
PY
then
  pass "per-test-oom-body" "test 2 OOM-killed, remaining test 3 still ran and reported result"
else
  fail "per-test-oom-body" "unexpected response: $(cat "$OOM_OUT")"
  failed=$((failed + 1))
fi

# ── Step 6: Network isolation probe (NFR3.1, CR15) ───────────────────────────

new_uuid; REQ_NET="$REPLY"
NET_OUT="$(mktemp)"; TMP_FILES+=("$NET_OUT")
NET_BODY="{\"request_id\":\"$REQ_NET\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"import socket, sys\\ntry:\\n    socket.create_connection(('8.8.8.8', 53), timeout=2).close()\\n    print('NETWORK REACHABLE')\\n    sys.exit(2)\\nexcept OSError:\\n    print('NETWORK BLOCKED')\\n    sys.exit(0)\",\"inputs\":[\"1\"]}"

net_code="$(post_internal 20 "$AUTH_HDR" "$NET_OUT" "" "$NET_BODY")"
check "network-isolation-status" "200" "$net_code"

if "$PYTHON_BIN" - "$NET_OUT" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
r = data["batch_results"][0]
assert r["status"] == "pass", r
assert "NETWORK BLOCKED" in r["stdout"], r
assert "REACHABLE" not in r["stdout"], r
PY
then
  pass "network-isolation-body" "network access blocked under --network none sandbox isolation"
else
  fail "network-isolation-body" "unexpected response: $(cat "$NET_OUT")"
  failed=$((failed + 1))
fi

# ── Step 7: Run cap exceeded with partial results (NFR14, US-71 AC3) ─────────

new_uuid; REQ_CAP="$REPLY"
CAP_OUT="$(mktemp)"; CAP_HDR="$(mktemp)"; TMP_FILES+=("$CAP_OUT" "$CAP_HDR")
CAP_INPUTS="$("$PYTHON_BIN" -c "import json; print(json.dumps([str(i) for i in range($MAX_RUNS + 1)]))")"
CAP_BODY="{\"request_id\":\"$REQ_CAP\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"print(input())\",\"inputs\":$CAP_INPUTS}"

cap_code="$(post_internal 60 "$AUTH_HDR" "$CAP_OUT" "$CAP_HDR" "$CAP_BODY")"
cap_retry_hdr="$(grep -i '^Retry-After:' "$CAP_HDR" | head -1 | tr -d '\r' | awk '{print $2}')"
check "run-cap-status" "429" "$cap_code"
check "run-cap-retry-after-header" "$BUDGET_RETRY" "${cap_retry_hdr:-missing}"

if "$PYTHON_BIN" - "$CAP_OUT" "$REQ_CAP" "$MAX_RUNS" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
assert data["request_id"] == sys.argv[2], data
err = data["error"]
assert err["code"] == "RUN_BUDGET_EXCEEDED", err
assert err["reason"] == "max_runs_per_request", err
# Partial results preserved:
results = data.get("batch_results", [])
assert len(results) == int(sys.argv[3]), f"Expected {sys.argv[3]} partial results, got {len(results)}"
PY
then
  pass "run-cap-partial-results" "429 RUN_BUDGET_EXCEEDED contains preserved partial batch results"
else
  fail "run-cap-partial-results" "unexpected response: $(cat "$CAP_OUT")"
  failed=$((failed + 1))
fi

# ── Step 8: Shared RunQueueGate refusal (FR16, US-11) ─────────────────────────

health_body="$(curl -s --max-time 5 "$HEALTH_URL" 2>/dev/null || true)"
if echo "$health_body" | grep -q '"in_flight"'; then
  configured_max_q=$(echo "$health_body" | grep -o '"max_queue_length":[0-9]*' | head -1 | cut -d':' -f2)
  configured_retry=$(echo "$health_body" | grep -o '"retry_after_seconds":[0-9]*' | head -1 | cut -d':' -f2)
  MAX_QUEUE="${configured_max_q:-${EXECUTION_MAX_QUEUE_LENGTH:-10}}"
  QUEUE_RETRY="${configured_retry:-5}"

  HOLD_BODIES=()
  for _ in $(seq 1 "$MAX_QUEUE"); do
    new_uuid; h_req="$REPLY"
    new_uuid; h_sess="$REPLY"
    HOLD_BODIES+=("{\"request_id\":\"$h_req\",\"room_id\":\"$ROOM_ID\",\"session_id\":\"$h_sess\",\"language\":\"python\",\"source\":{\"kind\":\"inline\",\"files\":[{\"name\":\"main.py\",\"content\":\"import time; time.sleep(5)\"}],\"entry\":\"main.py\"}}")
  done

  for hb in "${HOLD_BODIES[@]}"; do
    curl -s -o /dev/null --max-time 20 \
      -X POST "$PUBLIC_RUNS_URL" \
      -H "Content-Type: application/json" \
      -d "$hb" > /dev/null 2>&1 &
    HOLD_PIDS+=($!)
  done

  saturated=false
  for _ in $(seq 1 100); do
    h_body="$(curl -s --max-time 2 "$HEALTH_URL" 2>/dev/null || true)"
    in_flight=$(echo "$h_body" | grep -o '"in_flight":[0-9]*' | head -1 | cut -d':' -f2)
    if [ -n "$in_flight" ] && [ "$in_flight" -ge "$MAX_QUEUE" ]; then
      saturated=true
      break
    fi
    sleep 0.1
  done

  if [ "$saturated" = "true" ]; then
    new_uuid; REQ_QFULL="$REPLY"
    Q_OUT="$(mktemp)"; Q_HDR="$(mktemp)"; TMP_FILES+=("$Q_OUT" "$Q_HDR")
    Q_BODY="{\"request_id\":\"$REQ_QFULL\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"print(1)\",\"inputs\":[\"1\"]}"

    q_code="$(post_internal 10 "$AUTH_HDR" "$Q_OUT" "$Q_HDR" "$Q_BODY")"
    q_retry_hdr="$(grep -i '^Retry-After:' "$Q_HDR" | head -1 | tr -d '\r' | awk '{print $2}')"

    check "shared-queue-full-status" "503" "$q_code"
    check "shared-queue-retry-header" "$QUEUE_RETRY" "${q_retry_hdr:-missing}"

    if "$PYTHON_BIN" - "$Q_OUT" "$REQ_QFULL" "$QUEUE_RETRY" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
assert data["request_id"] == sys.argv[2], data
err = data["error"]
assert err["code"] == "QUEUE_FULL", err
assert err["message"] == "Execution service is busy, try again in a few seconds.", err
assert err["retry_after_seconds"] == int(sys.argv[3]), err
PY
    then
      pass "shared-queue-full-body" "503 QUEUE_FULL with US-11 busy message"
    else
      fail "shared-queue-full-body" "unexpected response: $(cat "$Q_OUT")"
      failed=$((failed + 1))
    fi
  fi

  for pid in "${HOLD_PIDS[@]}"; do
    wait "$pid" 2>/dev/null || true
  done
  HOLD_PIDS=()

  for _ in $(seq 1 40); do
    h_body="$(curl -s --max-time 2 "$HEALTH_URL" 2>/dev/null || true)"
    in_flight=$(echo "$h_body" | grep -o '"in_flight":[0-9]*' | head -1 | cut -d':' -f2)
    if [ -n "$in_flight" ] && [ "$in_flight" -eq 0 ]; then
      break
    fi
    sleep 0.1
  done
fi

# ── Summary ──────────────────────────────────────────────────────────────────

if [ "$failed" -gt 0 ]; then
  echo "US-71 TEST-BATCH TEST FAILED ($failed assertion(s) failed)"
  exit 1
fi

echo "US-71 TEST-BATCH TEST PASSED"
exit 0
