#!/usr/bin/env bash
# US-70 endpoint contract integration smoke check (requires ai-ml-service).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
if ! service_present "ai-ml-service"; then
  skip "us70-failing-input" "ai-ml-service not deployed"
  exit 0
fi
base="$(service_url ai-ml-service)"
if ! curl -sf --max-time 5 "$base/health" >/dev/null; then
  if [ "${CI:-false}" = true ]; then
    fail "us70-failing-input" "ai-ml-service unavailable"
    exit 1
  fi
  skip "us70-failing-input" "ai-ml-service not running"
  exit 0
fi
# Missing required fields are rejected rather than running submitted code.
status="$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"request_type":"failing_input"}' "$base/v1/analyses/failing-input")"
if [ "$status" != "422" ]; then
  fail "us70-failing-input" "expected 422, got $status"
  exit 1
fi
pass "us70-failing-input" "endpoint exists, validation active"
