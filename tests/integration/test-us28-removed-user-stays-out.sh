#!/usr/bin/env bash
# Integration test for US-28 / Issue #133: a removed user stays out (FR9.6, CR04, V2.18).
#
# Over real HTTP + WebSocket against sync-service, verifies:
#   remove (connected socket closed 4403), the blocked list (never the session_id),
#   a refused rejoin (USER_BLOCKED), re-admit (fresh member_id), and regenerate-link
#   (the old id returns 404 ROOM_NOT_FOUND).
#
# Uses a Node.js WebSocket client (same pattern as the US-04 integration scripts),
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

# Locate Node.js on Linux, Git Bash, or Windows (same as test-us04-reconnect-resync.sh).
if command -v node >/dev/null 2>&1; then
  NODE=node
elif command -v node.exe >/dev/null 2>&1; then
  NODE=node.exe
elif [ -x "/c/Program Files/nodejs/node.exe" ]; then
  NODE="/c/Program Files/nodejs/node.exe"
else
  fail "sync-service" "Node.js is not available to run the US-28 WebSocket client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us28-removed-user-stays-out.mjs; then
  pass "sync-service" "US-28 remove / rejoin-refused / blocked-list / re-admit / regenerate"
else
  fail "sync-service" "US-28 removed-user integration checks failed"
  exit 1
fi
