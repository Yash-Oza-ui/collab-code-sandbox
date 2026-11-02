#!/usr/bin/env bash
# test-ai-consent.sh: verify that external AI requests require consent (FR12.1, FR12.2, US-17).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

base="$(service_url ai-ml-service)"   # -> http://localhost:8000

# Skip cleanly if the service folder has no Dockerfile yet.
service_present ai-ml-service || { skip ai-ml-service "not built yet"; exit 0; }

# Check service liveness; skip if container is not running locally.
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  skip ai-ml-service "service not running on $base (start with tests/integration/up.sh)"
  exit 0
fi

# 1. Test: consent is false -> must return HTTP 403 with code CONSENT_MISSING
payload_consent_false='{
  "request_id": "test-int-consent-false-001",
  "room_id": "room-int-01",
  "request_type": "explanation",
  "language": "python",
  "consent": false,
  "code": "print(1 / 0)",
  "stderr": "ZeroDivisionError: division by zero",
  "exit_code": 1,
  "execution_time_ms": 25.0,
  "peak_memory_mb": 5.0,
  "timed_out": false,
  "oom_killed": false,
  "failure_kind": "non_zero_exit"
}'

resp_false="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/explain" \
  -H "Content-Type: application/json" \
  -d "$payload_consent_false")"

status_false="$(echo "$resp_false" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_false="$(echo "$resp_false" | grep -v 'HTTP_STATUS:')"

if [ "$status_false" != "403" ]; then
  fail ai-ml-service "expected HTTP 403 on consent=false, got $status_false. Body: $body_false"
  exit 1
fi

if ! echo "$body_false" | grep -q "CONSENT_MISSING"; then
  fail ai-ml-service "expected CONSENT_MISSING in body on consent=false, got: $body_false"
  exit 1
fi
pass ai-ml-service "consent=false returns HTTP 403 CONSENT_MISSING (gate blocked external AI)"

# 2. Test: consent is missing (omitted) -> must return HTTP 403 with code CONSENT_MISSING
payload_consent_missing='{
  "request_id": "test-int-consent-missing-002",
  "room_id": "room-int-01",
  "request_type": "explanation",
  "language": "python",
  "code": "print(1 / 0)",
  "stderr": "ZeroDivisionError: division by zero",
  "exit_code": 1,
  "execution_time_ms": 25.0,
  "peak_memory_mb": 5.0,
  "timed_out": false,
  "oom_killed": false,
  "failure_kind": "non_zero_exit"
}'

resp_missing="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/explain" \
  -H "Content-Type: application/json" \
  -d "$payload_consent_missing")"

status_missing="$(echo "$resp_missing" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_missing="$(echo "$resp_missing" | grep -v 'HTTP_STATUS:')"

if [ "$status_missing" != "403" ]; then
  fail ai-ml-service "expected HTTP 403 on missing consent, got $status_missing. Body: $body_missing"
  exit 1
fi

if ! echo "$body_missing" | grep -q "CONSENT_MISSING"; then
  fail ai-ml-service "expected CONSENT_MISSING in body on missing consent, got: $body_missing"
  exit 1
fi
pass ai-ml-service "consent missing returns HTTP 403 CONSENT_MISSING (gate blocked external AI)"

# 3. Test: consent is true -> passes gate (returns 200 or 502 PROVIDER_ERROR if API key unset, never 403)
payload_consent_true='{
  "request_id": "test-int-consent-true-003",
  "room_id": "room-int-01",
  "request_type": "explanation",
  "language": "python",
  "consent": true,
  "code": "print(1 / 0)",
  "stderr": "ZeroDivisionError: division by zero",
  "exit_code": 1,
  "execution_time_ms": 25.0,
  "peak_memory_mb": 5.0,
  "timed_out": false,
  "oom_killed": false,
  "failure_kind": "non_zero_exit"
}'

resp_true="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/explain" \
  -H "Content-Type: application/json" \
  -d "$payload_consent_true")"

status_true="$(echo "$resp_true" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_true="$(echo "$resp_true" | grep -v 'HTTP_STATUS:')"

if [ "$status_true" = "403" ]; then
  fail ai-ml-service "consent=true should not return 403. Body: $body_true"
  exit 1
fi

# When key is not configured, it returns 502 PROVIDER_ERROR; with key it returns 200. Both prove the gate passed.
if [ "$status_true" != "200" ] && [ "$status_true" != "502" ]; then
  fail ai-ml-service "expected HTTP 200 or 502 on consent=true, got $status_true. Body: $body_true"
  exit 1
fi
pass ai-ml-service "consent=true passed gate successfully (status $status_true, not 403)"
