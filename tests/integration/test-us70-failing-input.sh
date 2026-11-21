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

# Functional US-70 integration: real Python execution through US-71's Docker batch.
# No reference or AI consent required for crash detection.
request_json='{"request_id":"2ba440c4-bfa2-45d9-90ce-e6c8bb45a370","analysis_id":"a1d450f6-5aba-4792-a820-36b3aa1c504f","room_id":"Zq8-Lw2Ef6Th0Yx4Rn7JbA","request_type":"failing_input","language":"python","consent":false,"ai_enabled":false,"code":"print(int(input()))"}'
output="$(mktemp)"
trap 'rm -f "$output"' EXIT
status="$(curl -sS --max-time 40 -o "$output" -w '%{http_code}' -H 'Content-Type: application/json' -d "$request_json" "$base/v1/analyses/failing-input")"
if [ "$status" != "200" ]; then
  fail "us70-failing-input" "expected 200 for functional search, got $status"
  exit 1
fi
if ! python3 - "$output" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as f:
    result = json.load(f)
assert result["failure_found"] is True, result
assert result["failure_type"] == "crash", result
assert result["original_input"] == "", result
assert result["tests_executed"] >= 1, result
assert result["ai_step"]["error_code"] == "CONSENT_MISSING", result
PY
then
  fail "us70-failing-input" "search did not find seeded empty-input crash"
  exit 1
fi
pass "us70-failing-input" "seeded crash found via real test_batch sandbox"
