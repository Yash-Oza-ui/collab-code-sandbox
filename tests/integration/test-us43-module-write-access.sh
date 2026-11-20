#!/usr/bin/env bash
# Integration test for US-43 / Issue #177: Control write access to a module (FR22.1, FR22.2, NFR13, CR13).
#
# Verifies over real HTTP and WebSocket:
#   - Member without write access has edits dropped and gets edit-rejected (not_module_writer).
#   - Owner or Host can grant write access (POST .../writers) and revoke it (DELETE .../writers/{id}).
#   - Changes take effect immediately without reload; module-updated message broadcast to room.
#   - Non-owner / non-Host grant/revoke is refused (403 NOT_MODULE_OWNER).
#   - Module owner cannot lose write access (422 INVALID_REQUEST).
#   - Observers in writers still cannot edit (edit-rejected reason: observer).
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "sync-service" "(no Dockerfile)"
  exit 0
fi

SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"
export SYNC_SERVICE_URL

# Skip cleanly if sync-service is offline (same convention as other integration tests).
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
  fail "sync-service" "Node.js is not available to run the US-43 WebSocket client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if "$NODE" client/tests/integration/us43-module-write-access.mjs; then
  pass "sync-service" "US-43 module write access: dropped edits, grant/revoke write, owner retention, observer check"
else
  fail "sync-service" "US-43 module write access integration checks failed"
  exit 1
fi
