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

# Detect Python binary: prefer python3 on Linux/CI, fallback to python on Windows
PYTHON_BIN="python3"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1 || ! "$PYTHON_BIN" --version >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

validate_pair_json() {
  local body="$1"
  if command -v "$PYTHON_BIN" >/dev/null 2>&1 && "$PYTHON_BIN" --version >/dev/null 2>&1; then
    printf '%s' "$body" | "$PYTHON_BIN" -c '
import sys, json, re
try:
    data = json.load(sys.stdin)
    runner = data.get("runner")
    if not isinstance(runner, dict):
        sys.exit("runner field is missing or not a dict")
    display_name = runner.get("display_name")
    if not isinstance(display_name, str) or not display_name.strip():
        sys.exit(f"runner.display_name must be a non-empty string, got {display_name!r}")
    
    ran_at = data.get("ran_at")
    if not isinstance(ran_at, str) or not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$", ran_at):
        sys.exit(f"ran_at is not a valid ISO 8601 UTC timestamp: {ran_at!r}")
        
    saved_versions = data.get("saved_versions")
    if saved_versions != []:
        sys.exit(f"saved_versions in Pair mode must be empty list [], got {saved_versions!r}")
        
    print("OK")
except Exception as e:
    sys.exit(f"Validation failed: {e}")
' 2>&1
  else
    if echo "$body" | grep -q '"runner"' && echo "$body" | grep -q '"display_name"' && echo "$body" | grep -q '"ran_at"' && echo "$body" | grep -q '"saved_versions":\[\]'; then
      echo "OK"
    else
      echo "Pair body validation failed: $body"
    fi
  fi
}

validate_open_json() {
  local body="$1"
  if command -v "$PYTHON_BIN" >/dev/null 2>&1 && "$PYTHON_BIN" --version >/dev/null 2>&1; then
    printf '%s' "$body" | "$PYTHON_BIN" -c '
import sys, json, re
try:
    data = json.load(sys.stdin)
    runner = data.get("runner")
    if not isinstance(runner, dict):
        sys.exit("runner field is missing or not a dict")
    display_name = runner.get("display_name")
    if not isinstance(display_name, str) or not display_name.strip():
        sys.exit(f"runner.display_name must be a non-empty string, got {display_name!r}")
    
    ran_at = data.get("ran_at")
    if not isinstance(ran_at, str) or not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$", ran_at):
        sys.exit(f"ran_at is not a valid ISO 8601 UTC timestamp: {ran_at!r}")
        
    saved_versions = data.get("saved_versions")
    if not isinstance(saved_versions, list) or len(saved_versions) == 0:
        sys.exit(f"saved_versions in Open mode must be a non-empty list, got {saved_versions!r}")
    for sv in saved_versions:
        if not isinstance(sv.get("name"), str) or not sv.get("name"):
            sys.exit(f"saved_version entry missing name: {sv}")
        if not isinstance(sv.get("version"), int):
            sys.exit(f"saved_version entry missing integer version: {sv}")
        if not isinstance(sv.get("module_id"), str) or not sv.get("module_id"):
            sys.exit(f"saved_version entry missing module_id: {sv}")

    vis = data.get("visibility")
    if vis != "private":
        sys.exit(f"expected visibility: private, got {vis!r}")
        
    print("OK")
except Exception as e:
    sys.exit(f"Validation failed: {e}")
' 2>&1
  else
    if echo "$body" | grep -q '"saved_versions":\[{' && echo "$body" | grep -q '"visibility":"private"'; then
      echo "OK"
    else
      echo "Open body validation failed: $body"
    fi
  fi
}

# 1. Pair mode run result carries runner (display_name), ran_at (ISO UTC), and empty saved_versions (FR26)
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

if [ "$status_pair" != "200" ]; then
  fail "us50-pair-runner-label" "expected 200, got $status_pair: $body_pair"
  exit 1
fi

result_pair="$(validate_pair_json "$body_pair")"
if [ "$result_pair" = "OK" ]; then
  pass "us50-pair-runner-label" "Pair mode run returns valid runner.display_name, ISO ran_at, and empty saved_versions"
else
  fail "us50-pair-runner-label" "$result_pair"
  exit 1
fi

# 2. Open mode run result carries saved_versions array with module metadata and private visibility
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

if [ "$status_open" != "200" ]; then
  fail "us50-open-saved-versions" "expected 200, got $status_open: $body_open"
  exit 1
fi

result_open="$(validate_open_json "$body_open")"
if [ "$result_open" = "OK" ]; then
  pass "us50-open-saved-versions" "Open mode run returns valid saved_versions details and private visibility"
else
  fail "us50-open-saved-versions" "$result_open"
  exit 1
fi

exit 0
