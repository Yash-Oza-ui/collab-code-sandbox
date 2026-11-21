#!/usr/bin/env bash
# US-59 real HTTP + two joined WebSocket clients: Host-only AI switch and defaults.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "us59-ai-switch" "sync-service not deployed"
  exit 0
fi
SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"
export SYNC_SERVICE_URL
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$SYNC_SERVICE_URL/actuator/health" 2>/dev/null || echo 000)"
if [ "$health_code" != "200" ]; then
  if [ "${CI:-false}" = "true" ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
    fail "us59-ai-switch" "sync-service unavailable"
    exit 1
  fi
  skip "us59-ai-switch" "sync-service not running"
  exit 0
fi

if command -v node >/dev/null 2>&1; then
  NODE=node
elif command -v node.exe >/dev/null 2>&1; then
  NODE=node.exe
else
  fail "us59-ai-switch" "Node.js required"
  exit 1
fi
cd "$REPO_ROOT"
if "$NODE" client/tests/integration/us59-ai-switch.mjs; then
  pass "us59-ai-switch" "three defaults, two clients, Host-only toggle, realtime updates, authoritative lookup"
else
  fail "us59-ai-switch" "US-59 integration failed"
  exit 1
fi
