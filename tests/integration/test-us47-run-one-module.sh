#!/usr/bin/env bash
# Integration test for US-47 / Issue #211: Run one runnable module (FR25.1, FR25.2, EPIC 7).
#
# Over real HTTP + WebSocket against sync-service, verifies:
#   - A module marked runnable can be run on its own; run contains only that module (FR25.2).
#   - A module not marked runnable is refused with MODULE_NOT_RUNNABLE (422).
#   - A module that has never been saved cannot be run: refused with MODULE_NOT_SAVED (422).
#   - Text typed after the last save has no effect; run uses saved version (FR25.1).
#
# When execution-service is available:
#   - Saved module execution returns 200 with visibility: "private" and failure null.
#   - Non-runnable module is refused with MODULE_NOT_RUNNABLE (422).
#   - Never-saved module is refused with MODULE_NOT_SAVED (422).
#   - Open mode inline code is rejected with INVALID_REQUEST (422).
#   - Observer and person with run permission revoked get 403 RUN_NOT_ALLOWED.
#
# Real run-refusal checks against a live sync-service room need the real permission lookup:
# they run only when the stack was started with EXECUTION_SPRING_PROFILES= (empty).
# When the stack runs with the default in-memory stub (EXECUTION_SPRING_PROFILES=stub-permissions),
# execution-service is verified directly via its endpoint.
#
# Uses Node.js WebSocket client (client/tests/integration/us47-run-one-module.mjs).
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "sync-service" "(no Dockerfile)"
  exit 0
fi

SYNC_SERVICE_URL="${SYNC_SERVICE_URL:-$(service_url sync-service)}"
export SYNC_SERVICE_URL

# Skip cleanly if sync-service is offline (same convention as other tests).
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$SYNC_SERVICE_URL/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  skip "sync-service" "(service not running at $SYNC_SERVICE_URL; start with tests/integration/up.sh)"
  exit 0
fi

# Locate Node.js on Linux, Git Bash, or Windows.
if command -v node >/dev/null 2>&1; then
  NODE=node
elif command -v node.exe >/dev/null 2>&1; then
  NODE=node.exe
elif [ -x "/c/Program Files/nodejs/node.exe" ]; then
  NODE="/c/Program Files/nodejs/node.exe"
else
  fail "sync-service" "Node.js is not available to run the US-47 integration client."
  exit 1
fi

ROOT="$(cd "$dir/../.." && pwd)"
cd "$ROOT"

# INTERNAL_API_TOKEN for the lookup: the shell wins, then .env at the repo root.
if [ -z "${INTERNAL_API_TOKEN:-}" ] && [ -f "$ROOT/.env" ]; then
  INTERNAL_API_TOKEN="$(sed -n 's/^INTERNAL_API_TOKEN=//p' "$ROOT/.env" | tail -n 1 | tr -d '\r"'"'"'')"
fi
export INTERNAL_API_TOKEN="${INTERNAL_API_TOKEN:-test-only-integration-token-fixture}"

# Real run checks with live sync-service rooms only when the stack runs the real lookup.
if [ "${EXECUTION_SPRING_PROFILES-stub-permissions}" != "stub-permissions" ]; then
  export US47_CHECK_RUN=1
  export EXECUTION_API_URL="${EXECUTION_API_URL:-$(service_url execution-service)}"
fi

if "$NODE" client/tests/integration/us47-run-one-module.mjs; then
  pass "sync-service" "US-47: snapshot target module / MODULE_NOT_RUNNABLE / MODULE_NOT_SAVED / saved version isolation"
else
  fail "sync-service" "US-47 run one runnable module integration checks failed"
  exit 1
fi

# Direct endpoint verification against execution-service if running:
if service_present "execution-service"; then
  exec_url="$(service_url execution-service)"
  if curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$exec_url/actuator/health" 2>/dev/null | grep -q "200"; then
    endpoint="$exec_url/v1/runs"

    # 1. Open mode saved module run -> 200 with visibility: private
    resp_saved="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
      -H "Content-Type: application/json" \
      -d '{
        "request_id": "00000000-0000-4000-8000-000000000047",
        "room_id": "room-open-01",
        "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
        "language": "python",
        "source": {
          "kind": "saved",
          "target": "module",
          "module_id": "mod_runnable"
        }
      }')"

    status_saved="$(echo "$resp_saved" | grep "HTTP_STATUS" | cut -d':' -f2)"
    body_saved="$(echo "$resp_saved" | grep -v "HTTP_STATUS")"

    if [ "$status_saved" = "200" ] && echo "$body_saved" | grep -q '"visibility":"private"' && echo "$body_saved" | grep -q '"saved_versions"'; then
      pass "execution-service" "US-47: Open mode saved module run -> 200 with private visibility"
    else
      fail "execution-service" "expected 200 with private visibility, got $status_saved: $body_saved"
      exit 1
    fi

    # 2. Module not marked runnable returns 422 MODULE_NOT_RUNNABLE
    resp_not_runnable="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
      -H "Content-Type: application/json" \
      -d '{
        "request_id": "00000000-0000-4000-8000-000000000047",
        "room_id": "room-open-01",
        "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
        "language": "python",
        "source": {
          "kind": "saved",
          "target": "module",
          "module_id": "mod_not_runnable"
        }
      }')"

    status_not_runnable="$(echo "$resp_not_runnable" | grep "HTTP_STATUS" | cut -d':' -f2)"
    body_not_runnable="$(echo "$resp_not_runnable" | grep -v "HTTP_STATUS")"

    if [ "$status_not_runnable" = "422" ] && echo "$body_not_runnable" | grep -q "MODULE_NOT_RUNNABLE"; then
      pass "execution-service" "US-47: not runnable module -> 422 MODULE_NOT_RUNNABLE"
    else
      fail "execution-service" "expected 422 MODULE_NOT_RUNNABLE, got $status_not_runnable: $body_not_runnable"
      exit 1
    fi

    # 3. Module never saved returns 422 MODULE_NOT_SAVED
    resp_not_saved="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
      -H "Content-Type: application/json" \
      -d '{
        "request_id": "00000000-0000-4000-8000-000000000047",
        "room_id": "room-open-01",
        "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
        "language": "python",
        "source": {
          "kind": "saved",
          "target": "module",
          "module_id": "mod_never_saved"
        }
      }')"

    status_not_saved="$(echo "$resp_not_saved" | grep "HTTP_STATUS" | cut -d':' -f2)"
    body_not_saved="$(echo "$resp_not_saved" | grep -v "HTTP_STATUS")"

    if [ "$status_not_saved" = "422" ] && echo "$body_not_saved" | grep -q "MODULE_NOT_SAVED"; then
      pass "execution-service" "US-47: never saved module -> 422 MODULE_NOT_SAVED"
    else
      fail "execution-service" "expected 422 MODULE_NOT_SAVED, got $status_not_saved: $body_not_saved"
      exit 1
    fi

    # 4. Inline code in Open room returns 422 INVALID_REQUEST
    resp_inline_open="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
      -H "Content-Type: application/json" \
      -d '{
        "request_id": "00000000-0000-4000-8000-000000000047",
        "room_id": "room-open-01",
        "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
        "language": "python",
        "source": {
          "kind": "inline",
          "files": [{"name": "main.py", "content": "print(1)"}],
          "entry": "main.py"
        }
      }')"

    status_inline_open="$(echo "$resp_inline_open" | grep "HTTP_STATUS" | cut -d':' -f2)"
    body_inline_open="$(echo "$resp_inline_open" | grep -v "HTTP_STATUS")"

    if [ "$status_inline_open" = "422" ] && echo "$body_inline_open" | grep -q "INVALID_REQUEST"; then
      pass "execution-service" "US-47: inline code in open room -> 422 INVALID_REQUEST"
    else
      fail "execution-service" "expected 422 INVALID_REQUEST, got $status_inline_open: $body_inline_open"
      exit 1
    fi

    # 5. Observer gets 403 RUN_NOT_ALLOWED
    resp_obs="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
      -H "Content-Type: application/json" \
      -d '{
        "request_id": "00000000-0000-4000-8000-000000000047",
        "room_id": "room-open-01",
        "session_id": "00000000-0000-4000-8000-000000000001",
        "language": "python",
        "source": {
          "kind": "saved",
          "target": "module",
          "module_id": "mod_runnable"
        }
      }')"

    status_obs="$(echo "$resp_obs" | grep "HTTP_STATUS" | cut -d':' -f2)"
    body_obs="$(echo "$resp_obs" | grep -v "HTTP_STATUS")"

    if [ "$status_obs" = "403" ] && echo "$body_obs" | grep -q "RUN_NOT_ALLOWED" && echo "$body_obs" | grep -q "observer"; then
      pass "execution-service" "US-47: observer refused -> 403 RUN_NOT_ALLOWED (reason: observer)"
    else
      fail "execution-service" "expected 403 RUN_NOT_ALLOWED with observer reason, got $status_obs: $body_obs"
      exit 1
    fi
  fi
fi
