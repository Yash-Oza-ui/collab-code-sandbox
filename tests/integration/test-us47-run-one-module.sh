#!/usr/bin/env bash
# Integration test for US-47 / Issue #211: Run one runnable module (FR25.1, FR25.2, EPIC 7).
#
# Over real HTTP + WebSocket against sync-service and execution-service, verifies:
#   - A module marked runnable can be run on its own; run contains only that module (FR25.2).
#   - A module not marked runnable is refused with MODULE_NOT_RUNNABLE (422).
#   - A module that has never been saved cannot be run: refused with MODULE_NOT_SAVED (422).
#   - Text typed after the last save has no effect; run uses saved version (FR25.1).
#   - Observer and person with run permission revoked get 403 RUN_NOT_ALLOWED.
#   - Open mode inline code is rejected with INVALID_REQUEST (422).
#   - Result visibility is private and delivered only to the runner's session.
#
# Uses Node.js WebSocket client (client/tests/integration/us47-run-one-module.mjs).
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
  fail "sync-service" "Node.js is not available to run the US-47 integration client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

if [ -z "${INTERNAL_API_TOKEN:-}" ] && [ -f "$ROOT/.env" ]; then
  INTERNAL_API_TOKEN="$(sed -n 's/^INTERNAL_API_TOKEN=//p' "$ROOT/.env" | tail -n 1 | tr -d '\r"'"'"'')"
fi
export INTERNAL_API_TOKEN="${INTERNAL_API_TOKEN:-}"

if service_present "execution-service"; then
  EXEC_URL="${EXECUTION_API_URL:-$(service_url execution-service)}"
  if curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$EXEC_URL/actuator/health" 2>/dev/null | grep -q "200"; then
    export EXECUTION_API_URL="$EXEC_URL"
  fi
fi

if "$NODE" client/tests/integration/us47-run-one-module.mjs; then
  pass "us47-run-one-module" "US-47: run one runnable module / MODULE_NOT_RUNNABLE / MODULE_NOT_SAVED / saved version isolation / private delivery"
else
  fail "us47-run-one-module" "US-47 run one runnable module integration checks failed"
  exit 1
fi
