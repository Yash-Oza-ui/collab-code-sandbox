#!/usr/bin/env bash
# Integration test for US-54 / Issue #132: transfer the Host role (FR31.1, V2.19).
#
# Over real HTTP + WebSocket against sync-service, verifies:
#   make-host (200, no owner_token in the HTTP response), the host-granted control
#   message (new token to the new Host), the roster swap, the old owner token being
#   retired (403 NOT_OWNER), and the new token working.
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
  fail "sync-service" "Node.js is not available to run the US-54 WebSocket client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us54-transfer-host.mjs; then
  pass "sync-service" "US-54 make-host / host-granted / roster swap / token reissue"
else
  fail "sync-service" "US-54 host-transfer integration checks failed"
  exit 1
fi
