#!/usr/bin/env bash
# Smoke run for the T-22 load harness (Issue #134) so it does not rot.
# Runs a tiny T1-P (2 users, 3 edits) against the live sync-service and checks
# that it measures at least one edit-sync latency sample. Skips cleanly when the
# service is down or Node is unavailable, like the other integration tests.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "load-harness" "(sync-service has no Dockerfile)"
  exit 0
fi

base_url="$(service_url sync-service)"
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base_url/actuator/health" 2>/dev/null || echo 000)"
if [ "$health_code" != "200" ]; then
  skip "load-harness" "(sync-service not running at $base_url; start with tests/integration/up.sh)"
  exit 0
fi

if ! command -v node >/dev/null 2>&1; then
  skip "load-harness" "(node not installed)"
  exit 0
fi

load_dir="$REPO_ROOT/tests/load"
cd "$load_dir"
if [ ! -d node_modules ]; then
  if [ -f package-lock.json ]; then npm ci --silent; else npm install --silent --no-audit --no-fund; fi
fi

out="$load_dir/results/smoke.json"
log="$(mktemp 2>/dev/null || echo "$load_dir/results/smoke.log")"
if node harness.mjs --variant T1-P --users 2 --edits 3 --interval 30 --url "$base_url" --out "$out" >"$log" 2>&1; then
  samples="$(grep -o '"measured_samples":[[:space:]]*[0-9]*' "$out" 2>/dev/null | grep -o '[0-9]*' | head -1)"
  rm -f "$out" "$log"
  if [ -n "${samples:-}" ] && [ "$samples" -ge 1 ]; then
    pass "load-harness" "T1-P smoke measured $samples edit-sync samples"
  else
    fail "load-harness" "harness ran but measured no samples"
    exit 1
  fi
else
  fail "load-harness" "harness run failed:"
  cat "$log" || true
  rm -f "$out" "$log"
  exit 1
fi
