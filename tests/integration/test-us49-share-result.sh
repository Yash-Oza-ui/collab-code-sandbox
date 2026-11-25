#!/usr/bin/env bash
# Integration test for US-49 / Issue #213: Keep a run result private or share it (FR27, FR4.5, NFR9).
#
# Real HTTP + WebSocket tests against sync-service and execution-service:
#   - In Open mode, run results have visibility: "private" and are delivered only to runner's connections (FR27).
#   - Observers and other participants do not receive private results (FR9.4, FR27).
#   - POST /v1/runs/{request_id}/share is allowed only to the runner (403 NOT_RUNNER from others).
#   - Sharing sets visibility: "room" and broadcasts the result to everyone in the room.
#   - Sharing twice is harmless (idempotent, 200 OK, no duplicate broadcast).
#   - In Pair mode, results have visibility: "room" (FR4.5) and share is a harmless no-op.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "sync-service" "(no Dockerfile)"
  exit 0
fi

if ! service_present "execution-service"; then
  skip "execution-service" "(no Dockerfile)"
  exit 0
fi

SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"
export SYNC_SERVICE_URL

EXECUTION_API_URL="${EXECUTION_API_URL:-$(service_url execution-service)}"
export EXECUTION_API_URL

# Skip cleanly if either service is offline.
health_sync="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$SYNC_SERVICE_URL/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_sync" != "200" ]; then
  skip "sync-service" "(service not running at $SYNC_SERVICE_URL; start with tests/integration/up.sh)"
  exit 0
fi

health_exec="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$EXECUTION_API_URL/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_exec" != "200" ]; then
  skip "execution-service" "(service not running at $EXECUTION_API_URL; start with tests/integration/up.sh)"
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
  fail "sync-service" "Node.js is not available to run the US-49 integration client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us49-share-result.mjs; then
  pass "execution-service" "US-49: private run results / share to room / NOT_RUNNER / idempotent sharing / pair room visibility"
else
  fail "execution-service" "US-49 private or share run result integration checks failed"
  exit 1
fi
