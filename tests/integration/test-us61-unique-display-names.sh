#!/usr/bin/env bash
# US-61 (FR42): end-to-end join uniqueness on real sync-service control sockets.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "sync-service" "(no Dockerfile)"
  exit 0
fi

SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"
export SYNC_SERVICE_URL
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$SYNC_SERVICE_URL/actuator/health" 2>/dev/null || true)"
if [ "$health_code" != "200" ]; then
  skip "sync-service" "(service not running at $SYNC_SERVICE_URL)"
  exit 0
fi

if command -v node >/dev/null 2>&1; then
  NODE=node
elif command -v node.exe >/dev/null 2>&1; then
  NODE=node.exe
elif [ -x "/c/Program Files/nodejs/node.exe" ]; then
  NODE="/c/Program Files/nodejs/node.exe"
else
  fail "sync-service" "Node.js is required for the US-61 WebSocket integration test"
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"
if "$NODE" client/tests/integration/us61-unique-display-names.mjs; then
  pass "sync-service" "US-61 unique names / same session / concurrent joins / case matching"
else
  fail "sync-service" "US-61 integration failed"
  exit 1
fi
