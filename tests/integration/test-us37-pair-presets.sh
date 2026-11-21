#!/usr/bin/env bash
# Integration test for US-37 / Issue #175: Pair mode room presets: Teach/Interview and Debug together (FR19.2).
#
# Over real HTTP + WebSocket against sync-service, verifies:
#   - In teach_interview, Host starts with can_edit=true; participant starts with can_edit=false (edits dropped with edit-rejected).
#   - Later joiner in teach_interview starts with can_edit=false.
#   - In debug_together, Host and all participants (first and later joiners) start with can_edit=true and edits reach other members.
#   - Observer cannot edit in either preset (edits dropped with edit-rejected reason observer).
#   - Modified client claiming can_edit at join is overridden by server (NFR13).
#
# Uses a Node.js WebSocket client (same pattern as the US-35 / US-36 scripts).
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
  fail "sync-service" "Node.js is not available to run the US-37 WebSocket client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us37-pair-presets.mjs; then
  pass "sync-service" "US-37 pair presets: teach_interview / debug_together / observer / modified client"
else
  fail "sync-service" "US-37 pair presets integration checks failed"
  exit 1
fi
