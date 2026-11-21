#!/usr/bin/env bash
# Integration test for US-38 / Issue #171: request edit access in Pair mode (FR19.3, V2.22).
#
# Over real HTTP + WebSocket against sync-service, verifies:
#   - Participant without edit access sends request-edit-access over control socket.
#   - Host control socket receives edit-access-requested with requester's member_id and display_name.
#   - Duplicate request from same member is a no-op (no second message).
#   - Host approves request via POST /v1/rooms/{roomId}/edit-requests/{memberId}/approve:
#     requester receives edit-access-responded (approved: true), roster updates, edits permitted.
#   - Host declines request via POST /v1/rooms/{roomId}/edit-requests/{memberId}/decline:
#     requester receives edit-access-responded (approved: false), access unchanged.
#   - Non-Host caller gets 403 NOT_OWNER.
#   - Open mode and Observer requests refused with INVALID_REQUEST error frame.
#
# Uses a Node.js WebSocket client (same pattern as the US-36 / US-35 scripts).
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
  fail "sync-service" "Node.js is not available to run the US-38 WebSocket client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us38-edit-request.mjs; then
  pass "sync-service" "US-38 edit access request: request / approve / decline / duplicate no-op / NOT_OWNER / Open mode refusal"
else
  fail "sync-service" "US-38 edit access request integration checks failed"
  exit 1
fi
