#!/usr/bin/env bash
# US-72 real endpoint integration smoke check (requires ai-ml-service + execution-service).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
if ! service_present "ai-ml-service"; then
  skip "us72-minimizer" "ai-ml-service not deployed"
  exit 0
fi
base="$(service_url ai-ml-service)"
if ! curl -sf --max-time 5 "$base/health" >/dev/null; then
  if [ "${CI:-false}" = true ]; then
    fail "us72-minimizer" "ai-ml-service unavailable"
    exit 1
  fi
  skip "us72-minimizer" "ai-ml-service not running"
  exit 0
fi
# The deterministic US-70 empty-input crash is already minimal. This test
# validates that US-72 preserves the failure while attaching minimiser metadata.
payload='{"request_id":"1c70c0de-9a27-4a9a-8910-9ea641c4c022","analysis_id":"f8a2bdb6-119a-4fc9-ac2f-714383a422c0","room_id":"Zq8-Lw2Ef6Th0Yx4Rn7JbA","request_type":"failing_input","language":"python","consent":false,"ai_enabled":false,"code":"print(int(input()))"}'
output="$(mktemp)"
trap 'rm -f "$output"' EXIT
status="$(curl -sS --max-time 45 -o "$output" -w '%{http_code}' -H 'Content-Type: application/json' -d "$payload" "$base/v1/analyses/failing-input")"
if [ "$status" != "200" ]; then
  fail "us72-minimizer" "endpoint returned HTTP $status"
  exit 1
fi
if ! python3 - "$output" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as f:
    result = json.load(f)
assert result['failure_found'] is True, result
assert result['failure_type'] == 'crash', result
assert result['original_input'] == '', result
assert result['minimized_input'] == '', result
assert result['original_size'] == result['minimized_size'] == 0, result
assert result['reference_used'] is False, result
assert isinstance(result['minimization_rounds'], int), result
assert isinstance(result['minimization_batches'], int), result
assert result['test_case']['input'] == result['minimized_input'], result
assert result['ai_step']['error_code'] == 'CONSENT_MISSING', result
PY
then
  fail "us72-minimizer" "response violates minimisation contract"
  exit 1
fi
pass "us72-minimizer" "US-72 response verified with real sandbox batch"
