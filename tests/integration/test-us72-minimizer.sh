#!/usr/bin/env bash
# US-72: real US-70/US-71 Docker test_batch reduction using a seeded program.
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
payload="$(python3 - <<'PY_PAYLOAD'
import json
print(json.dumps({
    'request_id': '1c70c0de-9a27-4a9a-8910-9ea641c4c022',
    'analysis_id': 'f8a2bdb6-119a-4fc9-ac2f-714383a422c0',
    'room_id': 'Zq8-Lw2Ef6Th0Yx4Rn7JbA',
    'request_type': 'failing_input', 'language': 'python',
    'consent': False, 'ai_enabled': False,
    'code': "import sys\ntokens = sys.stdin.read().split()\nif '-5' in tokens:\n    raise RuntimeError('seeded')\nprint('ok')\n",
}))
PY_PAYLOAD
)"
output="$(mktemp)"
trap 'rm -f "$output"' EXIT
status="$(curl -sS --max-time 45 -o "$output" -w '%{http_code}' \
    -H 'Content-Type: application/json' -d "$payload" "$base/v1/analyses/failing-input")"
if [ "$status" != "200" ]; then
  fail "us72-minimizer" "endpoint returned HTTP $status"
  exit 1
fi
if ! python3 - "$output" <<'PY_CHECK'
import json, sys
with open(sys.argv[1], encoding='utf-8') as f:
    result = json.load(f)
assert result['failure_found'] is True, result
assert result['failure_type'] == 'crash', result
assert result['original_input'] == '10 -5 20', result
assert result['minimized_input'] != result['original_input'], result
assert len(result['minimized_input']) < len(result['original_input']), result
assert '-5' in result['minimized_input'].split(), result
assert result['original_size'] == len(result['original_input']), result
assert result['minimized_size'] == len(result['minimized_input']), result
assert result['minimization_rounds'] >= 1, result
assert result['minimization_batches'] >= 1, result
assert result['reference_used'] is False, result
assert result['test_case']['input'] == result['minimized_input'], result
assert result['ai_step']['error_code'] == 'CONSENT_MISSING', result
PY_CHECK
then
  fail "us72-minimizer" "seeded input not reduced through real Docker batches"
  exit 1
fi
pass "us72-minimizer" "seeded multi-token crash reduced via sandbox batches"
