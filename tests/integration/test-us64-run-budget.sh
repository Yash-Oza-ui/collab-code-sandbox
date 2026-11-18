#!/usr/bin/env bash
# US-64 (NFR14, CR10, T-18 Decision 7) integration test:
# Verifies POST /internal/runs authentication, per-run 5s limit, runs-per-request
# cap (429 RUN_BUDGET_EXCEEDED), aggregate time budget (429 RUN_BUDGET_EXCEEDED),
# per-request budget isolation, and shared RunQueueGate refusal (503 QUEUE_FULL).
#
#   bash tests/integration/test-us64-run-budget.sh
#
# Requires execution-service running (started by tests/integration/up.sh).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SERVICE="execution-service"

if ! service_present "$SERVICE"; then
  skip "$SERVICE" "(no Dockerfile yet - US-64 run-budget test skipped)"
  exit 0
fi

BASE="$(service_url "$SERVICE")"
INTERNAL_RUNS_URL="$BASE/internal/runs"
PUBLIC_RUNS_URL="$BASE/v1/runs"
HEALTH_URL="$BASE/actuator/health"
TOKEN="${INTERNAL_API_TOKEN}"

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

# Helper matching test-us52-run-record.sh / test-sync-internal-endpoints.sh so
# auth headers are passed via variables rather than inline curl -H literals.
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

# ── Step 1: Confirm service liveness ─────────────────────────────────────────

health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  if [ "${CI:-false}" = "true" ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
    fail "health" "$HEALTH_URL -> $health_code (service not running in CI)"
    exit 1
  fi
  skip "us64-run-budget" "execution-service not running on $BASE (start with tests/integration/up.sh)"
  exit 0
fi
pass "health" "$HEALTH_URL -> 200"

MAX_RUNS="${APP_EXECUTION_LIMITS_MAX_RUNS_PER_REQUEST:-10}"
BUDGET_MS="${APP_EXECUTION_LIMITS_AGGREGATE_TIME_BUDGET_MS:-20000}"
BUDGET_RETRY="${APP_EXECUTION_LIMITS_BUDGET_RETRY_AFTER_SECONDS:-30}"
WALL_SEC="${APP_EXECUTION_LIMITS_WALL_TIME_SECONDS:-5}"
ROOM_ID="Zq8-Lw2Ef6Th0Yx4Rn7JbA"

echo "Configured budget: max_runs_per_request=$MAX_RUNS, aggregate_time_budget_ms=$BUDGET_MS, retry_after=${BUDGET_RETRY}s, wall_time=${WALL_SEC}s"

# ── Step 2: Auth check (X-Internal-Token required) ───────────────────────────

new_uuid; REQ_AUTH="$REPLY"
AUTH_BODY="{\"request_id\":\"$REQ_AUTH\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"print(1)\",\"inputs\":[\"1\"]}"

no_tok_code="$(post_internal 10 "" /dev/null "" "$AUTH_BODY")"
check "auth-missing-token" "401" "$no_tok_code"

bad_tok_code="$(post_internal 10 "$WRONG_AUTH_HDR" /dev/null "" "$AUTH_BODY")"
check "auth-invalid-token" "401" "$bad_tok_code"

# ── Step 3: Happy path (test_batch & measurement within budget) ──────────────

new_uuid; REQ_OK="$REPLY"
OK_OUT="$(mktemp)"; TMP_FILES+=("$OK_OUT")
OK_BODY="{\"request_id\":\"$REQ_OK\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"print(input().upper())\",\"inputs\":[\"alpha\",\"beta\"]}"

ok_code="$(post_internal 30 "$AUTH_HDR" "$OK_OUT" "" "$OK_BODY")"
check "test-batch-ok-status" "200" "$ok_code"

if "$PYTHON_BIN" - "$OK_OUT" "$REQ_OK" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
assert data["request_id"] == sys.argv[2], data
assert data["purpose"] == "test_batch", data
results = data["batch_results"]
assert len(results) == 2, results
assert results[0]["status"] == "pass" and results[0]["stdout"].strip() == "ALPHA", results[0]
assert results[1]["status"] == "pass" and results[1]["stdout"].strip() == "BETA", results[1]
PY
then
  pass "test-batch-ok-body" "2 passed batch results with expected stdout"
else
  fail "test-batch-ok-body" "unexpected body: $(cat "$OK_OUT")"
  failed=$((failed + 1))
fi

# ── Step 4: Per-run 5s wall-time limit inside repeated run (NFR4.1) ──────────

new_uuid; REQ_TO="$REPLY"
TO_OUT="$(mktemp)"; TMP_FILES+=("$TO_OUT")
TO_BODY="{\"request_id\":\"$REQ_TO\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"while True:\n    pass\n\",\"inputs\":[\"1\"]}"

to_code="$(post_internal 25 "$AUTH_HDR" "$TO_OUT" "" "$TO_BODY")"
check "per-run-timeout-status" "200" "$to_code"

if "$PYTHON_BIN" - "$TO_OUT" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
r = data["batch_results"][0]
assert r["status"] == "timeout", r
assert r["exit_code"] == 124, r
PY
then
  pass "per-run-timeout-body" "individual run killed at configured 5s limit (status=timeout, exit_code=124)"
else
  fail "per-run-timeout-body" "unexpected body: $(cat "$TO_OUT")"
  failed=$((failed + 1))
fi

# ── Step 5: Runs-per-request cap exceeded -> 429 RUN_BUDGET_EXCEEDED ─────────

new_uuid; REQ_CAP="$REPLY"
CAP_OUT="$(mktemp)"; CAP_HDR="$(mktemp)"; TMP_FILES+=("$CAP_OUT" "$CAP_HDR")
CAP_INPUTS="$("$PYTHON_BIN" -c "import json; print(json.dumps([str(i) for i in range($MAX_RUNS + 1)]))")"
CAP_BODY="{\"request_id\":\"$REQ_CAP\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"print(1)\",\"inputs\":$CAP_INPUTS}"

cap_code="$(post_internal 60 "$AUTH_HDR" "$CAP_OUT" "$CAP_HDR" "$CAP_BODY")"

cap_retry_hdr="$(grep -i '^Retry-After:' "$CAP_HDR" | head -1 | tr -d '\r' | awk '{print $2}')"
check "run-cap-status" "429" "$cap_code"
check "run-cap-retry-after-header" "$BUDGET_RETRY" "${cap_retry_hdr:-missing}"

if "$PYTHON_BIN" - "$CAP_OUT" "$REQ_CAP" "$BUDGET_RETRY" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
assert data["request_id"] == sys.argv[2], data
err = data["error"]
assert err["code"] == "RUN_BUDGET_EXCEEDED", err
assert err["reason"] == "max_runs_per_request", err
assert err["retry_after_seconds"] == int(sys.argv[3]), err
assert "runs per request" in err["message"], err
PY
then
  pass "run-cap-error-shape" "429 RUN_BUDGET_EXCEEDED (reason=max_runs_per_request)"
else
  fail "run-cap-error-shape" "unexpected body: $(cat "$CAP_OUT")"
  failed=$((failed + 1))
fi

# ── Step 6: Aggregate time budget exceeded -> 429 RUN_BUDGET_EXCEEDED ────────

new_uuid; REQ_TIME="$REPLY"
TIME_OUT="$(mktemp)"; TIME_HDR="$(mktemp)"; TMP_FILES+=("$TIME_OUT" "$TIME_HDR")
# 6 inputs of infinite loop: each hits 5s wall-time -> 4 runs >= 20000ms -> 5th refused before MAX_RUNS (10)
TIME_INPUT_COUNT="$("$PYTHON_BIN" -c "import math; needed = math.ceil($BUDGET_MS / ($WALL_SEC * 1000.0)) + 2; print(min(needed, $MAX_RUNS))")"
TIME_INPUTS="$("$PYTHON_BIN" -c "import json; print(json.dumps([str(i) for i in range($TIME_INPUT_COUNT)]))")"
TIME_BODY="{\"request_id\":\"$REQ_TIME\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"test_batch\",\"language\":\"python\",\"code\":\"while True:\n    pass\n\",\"inputs\":$TIME_INPUTS}"

time_code="$(post_internal 90 "$AUTH_HDR" "$TIME_OUT" "$TIME_HDR" "$TIME_BODY")"

time_retry_hdr="$(grep -i '^Retry-After:' "$TIME_HDR" | head -1 | tr -d '\r' | awk '{print $2}')"
check "time-budget-status" "429" "$time_code"
check "time-budget-retry-after-header" "$BUDGET_RETRY" "${time_retry_hdr:-missing}"

if "$PYTHON_BIN" - "$TIME_OUT" "$REQ_TIME" "$BUDGET_RETRY" "$BUDGET_MS" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
assert data["request_id"] == sys.argv[2], data
err = data["error"]
assert err["code"] == "RUN_BUDGET_EXCEEDED", err
assert err["reason"] == "aggregate_time_budget", err
assert err["retry_after_seconds"] == int(sys.argv[3]), err
assert f"{sys.argv[4]}ms" in err["message"], err
PY
then
  pass "time-budget-error-shape" "429 RUN_BUDGET_EXCEEDED (reason=aggregate_time_budget)"
else
  fail "time-budget-error-shape" "unexpected body: $(cat "$TIME_OUT")"
  failed=$((failed + 1))
fi

# ── Step 7: Per-request isolation (fresh request succeeds immediately after 429) ─

new_uuid; REQ_FRESH="$REPLY"
FRESH_BODY="{\"request_id\":\"$REQ_FRESH\",\"room_id\":\"$ROOM_ID\",\"purpose\":\"measurement\",\"language\":\"python\",\"code\":\"print(1)\",\"inputs\":[\"100\"],\"repeats\":1}"
fresh_code="$(post_internal 20 "$AUTH_HDR" /dev/null "" "$FRESH_BODY")"
check "per-request-isolation" "200" "$fresh_code"

# ── Step 8: Shared RunQueueGate refusal (US-11 / FR16 -> 503 QUEUE_FULL) ─────

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
      fail "shared-queue-full-body" "unexpected body: $(cat "$Q_OUT")"
      failed=$((failed + 1))
    fi
  else
    fail "shared-queue-saturation" "could not saturate queue (required $MAX_QUEUE slots)"
    failed=$((failed + 1))
  fi

  for pid in "${HOLD_PIDS[@]}"; do
    wait "$pid" 2>/dev/null || true
  done
  HOLD_PIDS=()

  # Wait until queue drains back to 0 so subsequent integration tests start clean
  for _ in $(seq 1 40); do
    h_body="$(curl -s --max-time 2 "$HEALTH_URL" 2>/dev/null || true)"
    in_flight=$(echo "$h_body" | grep -o '"in_flight":[0-9]*' | head -1 | cut -d':' -f2)
    if [ -n "$in_flight" ] && [ "$in_flight" -eq 0 ]; then
      break
    fi
    sleep 0.1
  done
else
  fail "health-details" "$HEALTH_URL has no queue details; start stack with MANAGEMENT_HEALTH_SHOW_DETAILS=always"
  failed=$((failed + 1))
fi

# ── Final result ─────────────────────────────────────────────────────────────

if [ "$failed" -gt 0 ]; then
  echo "US-64 RUN-BUDGET TEST FAILED ($failed assertion(s) failed)"
  exit 1
fi

echo "US-64 RUN-BUDGET TEST PASSED"
exit 0
