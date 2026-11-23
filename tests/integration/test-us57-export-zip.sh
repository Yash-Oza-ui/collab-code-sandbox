#!/usr/bin/env bash
# Integration test for US-57 / Issue #210: Export room files as ZIP archive (FR33).
#
# Over real HTTP + WebSocket against sync-service, verifies:
#   - Pair mode export captures single file under main.<ext> with exact live text.
#   - Open mode export captures all approved modules under module file names with live text.
#   - Pending unapproved modules are excluded from the export archive.
#   - Directory traversal ('..'), folder paths, and leading slashes are refused.
#   - Empty workspace produces no zip.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "sync-service" "(no Dockerfile)"
  exit 0
fi

SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"
export SYNC_SERVICE_URL

health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$SYNC_SERVICE_URL/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  skip "sync-service" "(service not running at $SYNC_SERVICE_URL; start with tests/integration/up.sh)"
  exit 0
fi

if command -v node >/dev/null 2>&1; then
  NODE=node
elif command -v node.exe >/dev/null 2>&1; then
  NODE=node.exe
elif [ -x "/c/Program Files/nodejs/node.exe" ]; then
  NODE="/c/Program Files/nodejs/node.exe"
else
  fail "sync-service" "Node.js is not available to run the US-57 export zip integration test."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us57-export-zip.mjs; then
  pass "sync-service" "US-57 export zip: pair and open mode exports with live text snapshot"
else
  fail "sync-service" "US-57 export zip integration test failed"
  exit 1
fi
