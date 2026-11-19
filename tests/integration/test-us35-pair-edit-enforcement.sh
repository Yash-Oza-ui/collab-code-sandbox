#!/usr/bin/env bash
# Integration test for US-35 / Issue #128: only people with edit access can edit in Pair mode
# (FR18, NFR13, CR13).
#
# Over real HTTP + WebSocket against sync-service, a script that behaves like a modified
# client sends real Yjs update frames from a participant without edit access, in the normal
# encoding and in encodings the relay must not be fooled by, and verifies: the frames are not
# forwarded, the sender gets edit-rejected (no_edit_access / observer), awareness frames still
# pass, a member with edit access still edits, and an access change mid-session applies to the
# next frame.
#
# Uses a Node.js WebSocket client (same pattern as the US-04 / US-28 / US-54 scripts).
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
  fail "sync-service" "Node.js is not available to run the US-35 WebSocket client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us35-pair-edit-enforcement.mjs; then
  pass "sync-service" "US-35 Pair edit enforcement: dropped updates, edit-rejected, awareness relayed, access change"
else
  fail "sync-service" "US-35 Pair edit enforcement integration checks failed"
  exit 1
fi
