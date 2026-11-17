#!/usr/bin/env bash
# Integration test for US-50 / FR26 run result labeling (Niranjan Panchal).
# Verifies that POST /v1/runs attaches the runner (display_name), ran_at timestamp,
# and saved_versions fields matching the V2.9 execution contract.
set -uo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "execution-service"; then
  skip "us50-label-results" "(execution-service Dockerfile missing)"
  exit 0
fi

base_url="$(service_url execution-service)"
endpoint="$base_url/v1/runs"

# Check if execution-service is accessible; skip if offline
if ! curl -s --max-time 2 "$base_url/actuator/health" >/dev/null 2>&1; then
  skip "us50-label-results" "(service not running at $base_url)"
  exit 0
fi

# 1. Pair mode run result carries runner, ran_at, and empty saved_versions (FR26)
resp_pair="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e50",
    "room_id": "integration-test-room-us50",
    "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
    "language": "python",
    "stdin": "",
    "source": {
      "kind": "inline",
      "files": [
        {
          "name": "main.py",
          "content": "print(\"hello US-50\")\n"
        }
      ],
      "entry": "main.py"
    }
  }')"

status_pair="$(echo "$resp_pair" | grep "HTTP_STATUS" | cut -d':' -f2)"
body_pair="$(echo "$resp_pair" | grep -v "HTTP_STATUS")"

if [ "$status_pair" = "200" ]; then
  if echo "$body_pair" | grep -q '"runner"' && echo "$body_pair" | grep -q '"ran_at"' && echo "$body_pair" | grep -q '"saved_versions"'; then
    pass "us50-pair-runner-label" "Pair mode run returns runner, ran_at, and saved_versions fields (FR26)"
  else
    fail "us50-pair-runner-label" "expected runner, ran_at, and saved_versions in body: $body_pair"
    exit 1
  fi
else
  fail "us50-pair-runner-label" "expected 200, got $status_pair: $body_pair"
  exit 1
fi

# 2. Open mode run result carries saved_versions array with module metadata
resp_open="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e51",
    "room_id": "room-open-us50",
    "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
    "language": "python",
    "source": {
      "kind": "saved",
      "target": "module",
      "module_id": "mod_9Qx7mBvA2nR0"
    }
  }')"

status_open="$(echo "$resp_open" | grep "HTTP_STATUS" | cut -d':' -f2)"
body_open="$(echo "$resp_open" | grep -v "HTTP_STATUS")"

if [ "$status_open" = "200" ]; then
  if echo "$body_open" | grep -q '"saved_versions"' && echo "$body_open" | grep -q '"visibility":"private"'; then
    pass "us50-open-saved-versions" "Open mode run returns saved_versions and private visibility (FR26)"
  else
    fail "us50-open-saved-versions" "expected saved_versions in Open mode: $body_open"
    exit 1
  fi
else
  fail "us50-open-saved-versions" "expected 200, got $status_open: $body_open"
  exit 1
fi

exit 0
