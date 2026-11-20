#!/usr/bin/env bash
# Integration test for US-36 / Issue #179: give and take back edit access in Pair mode (FR19.1, V2.21).
#
# Over real HTTP + WebSocket against sync-service, verifies:
#   - Participant initially cannot edit in teach_interview Pair room (update dropped, edit-rejected no_edit_access).
#   - Host grants edit access via PATCH /v1/rooms/{roomId}/members/{memberId}/edit-access (roster updated, next change forwarded).
#   - Host withdraws edit access (roster updated, next change dropped with edit-rejected).
#   - Non-Host gets 403 NOT_OWNER.
#   - Open mode room refuses call with 422 INVALID_REQUEST.
#   - Observer target refuses call with 422 INVALID_REQUEST.
#
# Uses a Node.js WebSocket client (same pattern as the US-35 / US-60 scripts).
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
  fail "sync-service" "Node.js is not available to run the US-36 WebSocket client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us36-edit-access.mjs; then
  pass "sync-service" "US-36 edit access: grant / withdraw / roster / immediate effect / NOT_OWNER / Open mode refusal"
else
  fail "sync-service" "US-36 edit access integration checks failed"
  exit 1
fi
