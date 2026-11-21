#!/usr/bin/env bash
# Integration test for US-46 / Issue #174: Mark a module runnable and set the entry file (FR20.2, FR20.3, V2.6).
#
# Over real HTTP + WebSocket against sync-service, verifies:
#   - Module owner can mark/unmark a module runnable (200 OK, module-updated broadcast).
#   - Non-owner attempting to mark runnable gets 403 NOT_MODULE_OWNER.
#   - Host designates entry file (200 OK, module-updated broadcast).
#   - Replacing entry file leaves exactly one entry file.
#   - Non-Host attempting to designate entry file gets 403 NOT_OWNER.
#   - Setting non-existent module as entry file gets 404 MODULE_NOT_FOUND.
#
# Uses Node.js WebSocket client (client/tests/integration/us46-runnable-entry.mjs).
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "sync-service" "(no Dockerfile)"
  exit 0
fi

SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"
export SYNC_SERVICE_URL

# Skip cleanly if sync-service is offline (same convention as other tests).
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
  fail "sync-service" "Node.js is not available to run the US-46 WebSocket client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us46-runnable-entry.mjs; then
  pass "sync-service" "US-46: owner toggles runnable / NOT_MODULE_OWNER / Host sets and replaces entry file / NOT_OWNER / MODULE_NOT_FOUND / live module-updated push"
else
  fail "sync-service" "US-46 runnable and entry file integration checks failed"
  exit 1
fi
