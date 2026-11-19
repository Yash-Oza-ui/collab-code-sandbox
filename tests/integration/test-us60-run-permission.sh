#!/usr/bin/env bash
# Integration test for US-60 / Issue #130: control who can run (FR41.1 to FR41.4, V2.20).
#
# Over real HTTP + WebSocket against sync-service, verifies: Run taken away from a
# participant and given back, Host-only running on and off, the roster that follows,
# NOT_OWNER for a non-Host, and the session lookup (INTERNAL_API_TOKEN).
#
# The run-refusal steps against execution-service (403 RUN_NOT_ALLOWED and its reason)
# need the real permission lookup, so they run only when the stack was started with
#   EXECUTION_SPRING_PROFILES= bash tests/integration/up.sh
# (the default stack keeps the in-memory permission stub for the older run scripts).
#
# Uses a Node.js WebSocket client (same pattern as the US-04 / US-28 scripts),
# because joining a room is a control-socket action, not an HTTP call.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "sync-service" "(no Dockerfile)"
  exit 0
fi

SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"
export SYNC_SERVICE_URL

# Skip cleanly if sync-service is offline (same convention as the other tests).
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$SYNC_SERVICE_URL/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  skip "sync-service" "(service not running at $SYNC_SERVICE_URL; start with tests/integration/up.sh)"
  exit 0
fi

# Locate Node.js on Linux, Git Bash, or Windows.
if command -v node >/dev/null 2>&1; then
  NODE=node
elif command -v node.exe >/dev/null 2>&1; then
  NODE=node.exe
elif [ -x "/c/Program Files/nodejs/node.exe" ]; then
  NODE="/c/Program Files/nodejs/node.exe"
else
  fail "sync-service" "Node.js is not available to run the US-60 WebSocket client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

# INTERNAL_API_TOKEN for the lookup: the shell wins, then .env at the repo root.
if [ -z "${INTERNAL_API_TOKEN:-}" ] && [ -f "$ROOT/.env" ]; then
  INTERNAL_API_TOKEN="$(sed -n 's/^INTERNAL_API_TOKEN=//p' "$ROOT/.env" | tail -n 1 | tr -d '\r"'"'"'')"
fi
export INTERNAL_API_TOKEN="${INTERNAL_API_TOKEN:-}"

# Real run-refusal checks only when the stack runs the real lookup (variable set to empty).
if [ "${EXECUTION_SPRING_PROFILES-stub-permissions}" != "stub-permissions" ]; then
  export US60_CHECK_RUN=1
  export EXECUTION_API_URL="${EXECUTION_API_URL:-$(service_url execution-service)}"
fi

if "$NODE" client/tests/integration/us60-run-permission.mjs; then
  pass "sync-service" "US-60 run permission: take away / give back / Host-only / roster / NOT_OWNER / lookup"
else
  fail "sync-service" "US-60 run-permission integration checks failed"
  exit 1
fi
