#!/usr/bin/env bash
# Smoke run for the T-22 load harness (Issue #134) so it does not rot.
# Runs a TINY T1-P, T1-A and T1-O against the live sync-service and checks that
# each variant builds its topology, connects, propagates updates and collects at
# least one latency sample. These are NOT benchmark numbers. Skips cleanly when
# the service or Node is unavailable, like the other integration tests.
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

run_variant() {
  local name="$1"; shift
  local out="$load_dir/results/smoke-$name.json"
  local log; log="$(mktemp 2>/dev/null || echo "$load_dir/results/smoke-$name.log")"
  if node harness.mjs "$@" --label smoke --url "$base_url" --out "$out" >"$log" 2>&1; then
    local samples; samples="$(grep -o '"samples":[[:space:]]*[0-9]*' "$out" 2>/dev/null | grep -o '[0-9]*' | head -1)"
    rm -f "$out" "$log"
    if [ -n "${samples:-}" ] && [ "$samples" -ge 1 ]; then
      pass "load-harness" "$name smoke: measured $samples samples"
    else
      fail "load-harness" "$name ran but measured no samples"; return 1
    fi
  else
    fail "load-harness" "$name harness run failed:"; cat "$log" || true; rm -f "$out" "$log"; return 1
  fi
}

# Tiny topologies that still exercise each variant's edit/read roles:
#   T1-P: 1 typist + 2 readers; T1-A: 3 typists; T1-O: 2 module owners + 2 readers.
run_variant T1-P --variant T1-P --users 3 --edits 3 --interval 30 --seed-lines 40
run_variant T1-A --variant T1-A --users 3 --edits 3 --interval 30 --seed-lines 40
run_variant T1-O --variant T1-O --users 4 --modules 2 --edits 3 --interval 30 --seed-lines 40
