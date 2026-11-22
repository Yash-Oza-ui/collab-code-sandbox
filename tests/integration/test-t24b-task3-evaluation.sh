#!/usr/bin/env bash
# T-24b synthetic evaluation: source runs only in Docker test_batch.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"
if ! service_present ai-ml-service || ! service_present execution-service; then
  skip "t24b-evaluation" "Docker-backed services unavailable"
  exit 0
fi
AI_ML_SERVICE_URL="${AI_ML_SERVICE_URL:-$(service_url ai-ml-service)}"
export AI_ML_SERVICE_URL
status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 4 "$AI_ML_SERVICE_URL/health" || true)"
if [ "$status" != 200 ]; then
  if [ "${CI:-false}" = true ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
    fail "t24b-evaluation" "ai-ml-service unavailable"
    exit 1
  fi
  skip "t24b-evaluation" "start tests/integration/up.sh first"
  exit 0
fi
if [ -z "${INTERNAL_API_TOKEN:-}" ]; then
  fail "t24b-evaluation" "no internal token for trusted reference runs"
  exit 1
fi
sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"
output="$REPO_ROOT/tests/integration/results/task3-evaluation.json"
mkdir -p "$(dirname "$output")"
if python3 "$REPO_ROOT/ai-ml-service/tests/task3_evaluation.py" \
    --base-url "$AI_ML_SERVICE_URL" --sha "$sha" --out "$output" \
    --report-out "$REPO_ROOT/tests/integration/results/task3-evaluation.md"; then
  pass "t24b-evaluation" "Docker Task 3 evidence generated"
else
  fail "t24b-evaluation" "evaluation assertions failed; see raw evidence"
  exit 1
fi
