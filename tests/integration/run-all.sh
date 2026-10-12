#!/usr/bin/env bash
# Run the smoke test, then every integration test named test-*.sh (T-08).
#
#   bash tests/integration/run-all.sh
#
# Stops early if the smoke test fails, because the other tests need the services up.
set -uo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "== smoke.sh =="
if ! bash "$dir/smoke.sh"; then
  echo "Smoke test failed; skipping the other integration tests."
  exit 1
fi

failed=()
shopt -s nullglob
for t in "$dir"/test-*.sh; do
  echo "== $(basename "$t") =="
  if bash "$t"; then
    echo "PASS  $(basename "$t")"
  else
    echo "FAIL  $(basename "$t")"
    failed+=("$(basename "$t")")
  fi
done

echo "===="
if [ "${#failed[@]}" -gt 0 ]; then
  echo "INTEGRATION TESTS FAILED: ${failed[*]}"
  exit 1
fi
echo "ALL INTEGRATION TESTS PASSED"
