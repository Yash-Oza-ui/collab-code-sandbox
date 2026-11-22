#!/usr/bin/env bash
# Integration test for US-58 / Issue #217: Audit log of permission changes (FR34, V2.23).
#
# Over real HTTP + WebSocket against sync-service, verifies:
#   - Every successful role change, edit access change, write access change, and run permission change records an audit entry.
#   - Refused actions (e.g. NOT_OWNER) add no entry.
#   - Each entry contains who, whom, what changed, old/new value, and timestamp.
#   - No session_id, user code or editor text is leaked.
#   - Real-time audit-entry event pushed to open WebSocket clients without refresh.
#   - GET /v1/rooms/{roomId}/audit-log accessible to all room members (Host, Participant, Observer).
#   - Modified client cannot create, edit, or delete an entry (405 Method Not Allowed).
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
  fail "sync-service" "Node.js is not available to run the US-58 WebSocket client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us58-audit-log.mjs; then
  pass "sync-service" "US-58 audit log: record / live push / GET audit-log / bounded store / 405 write-rejection"
else
  fail "sync-service" "US-58 audit log integration checks failed"
  exit 1
fi
