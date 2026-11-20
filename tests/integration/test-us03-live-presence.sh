#!/usr/bin/env bash
# US-03: Verify live room presence and role-change roster broadcasts.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

if ! service_present sync-service; then
  skip "US-03" "sync-service Dockerfile not present"
  exit 0
fi

base_url="$(service_url sync-service)"

if ! curl -fsS --max-time 5 "$base_url/actuator/health" >/dev/null; then
  fail "US-03" "sync-service is not healthy"
  exit 1
fi

export SYNC_SERVICE_URL="$base_url"

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
else
  PYTHON=python
fi

if ! "$PYTHON" "$INTEGRATION_DIR/test-us03-live-presence.py"; then
  fail "US-03" "live presence integration test failed"
  exit 1
fi

pass "US-03" "live presence and roster broadcasts verified"
