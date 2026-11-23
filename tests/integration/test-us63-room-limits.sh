#!/usr/bin/env bash
# US-63: live WebSocket and HTTP tests for room and module limits.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present sync-service; then
  skip us63-room-limits '(sync-service Dockerfile missing)'
  exit 0
fi

export SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"
health="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$SYNC_SERVICE_URL/actuator/health" 2>/dev/null || true)"
if [ "$health" != '200' ]; then
  skip us63-room-limits "(service not running at $SYNC_SERVICE_URL)"
  exit 0
fi

if command -v node >/dev/null 2>&1; then
  NODE=node
elif command -v node.exe >/dev/null 2>&1; then
  NODE=node.exe
else
  fail us63-room-limits 'Node.js is required'
  exit 1
fi

cd "$REPO_ROOT"
if "$NODE" client/tests/integration/us63-room-limits.mjs; then
  pass us63-room-limits 'live member cap, module limit, approval, isolation, Pair mode'
else
  fail us63-room-limits 'integration failed'
  exit 1
fi
