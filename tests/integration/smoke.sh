#!/usr/bin/env bash
# Smoke test (T-08): is every service that exists alive?
#
#   bash tests/integration/smoke.sh
#
# Prints one line per service:
#   PASS  the health URL answered HTTP 200
#   SKIP  the service folder is still a placeholder (no Dockerfile)
#   FAIL  the service exists but did not answer 200 before SMOKE_TIMEOUT
# Exits non-zero if any service FAILs. Read-only: it only sends GET requests.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

timeout_s="${SMOKE_TIMEOUT:-60}"
deadline=$(( $(date +%s) + timeout_s ))
failed=()
checked=0

for line in "${SERVICES[@]}"; do
  read -r name folder port_var path_var <<<"$line"

  if ! service_present "$folder"; then
    skip "$name" "($folder/ has no Dockerfile yet)"
    continue
  fi

  checked=$((checked + 1))
  url="http://localhost:${!port_var}${!path_var}"
  code="000"
  # Poll until HTTP 200 or the shared deadline passes (services may still be starting).
  while :; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" 2>/dev/null)" || true
    [ -z "$code" ] && code="000"
    [ "$code" = "200" ] && break
    [ "$(date +%s)" -ge "$deadline" ] && break
    sleep 2
  done

  if [ "$code" = "200" ]; then
    pass "$name" "$url -> 200"
  elif [ "$code" = "000" ]; then
    fail "$name" "$url -> no response (is '$name' running? try: docker compose ps)"
    failed+=("$name")
  else
    fail "$name" "$url -> HTTP $code (expected 200)"
    failed+=("$name")
  fi
done

echo "----"
if [ "${#failed[@]}" -gt 0 ]; then
  echo "SMOKE TEST FAILED. Service(s) down: ${failed[*]}"
  exit 1
fi
if [ "$checked" -eq 0 ]; then
  echo "SMOKE TEST PASSED (no service has a Dockerfile yet, nothing to check)"
else
  echo "SMOKE TEST PASSED ($checked service(s) up)"
fi
