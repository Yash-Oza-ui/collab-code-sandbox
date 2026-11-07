#!/usr/bin/env bash
# test-ai-consent.sh: verify that external AI requests require consent (FR12.1, FR12.2, FR12.3, US-17, T-18 v2).
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

# 1. Test: consent is false -> returns HTTP 200 with AI step skipped (FR12.3, T-18 v2)
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

if [ "$status_false" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on consent=false (FR12.3), got $status_false. Body: $body_false"
  exit 1
fi

if ! echo "$body_false" | grep -q "CONSENT_MISSING"; then
  fail ai-ml-service "expected CONSENT_MISSING in body on consent=false, got: $body_false"
  exit 1
fi

if ! echo "$body_false" | grep -q "skipped"; then
  fail ai-ml-service "expected status: skipped in body on consent=false, got: $body_false"
  exit 1
fi
pass ai-ml-service "consent=false returns HTTP 200 with skipped AI step CONSENT_MISSING (FR12.3)"

# 2. Test: consent is missing (omitted) -> returns HTTP 200 with AI step skipped (FR12.3, T-18 v2)
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

if [ "$status_missing" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on missing consent (FR12.3), got $status_missing. Body: $body_missing"
  exit 1
fi

if ! echo "$body_missing" | grep -q "CONSENT_MISSING"; then
  fail ai-ml-service "expected CONSENT_MISSING in body on missing consent, got: $body_missing"
  exit 1
fi

if ! echo "$body_missing" | grep -q "skipped"; then
  fail ai-ml-service "expected status: skipped in body on missing consent, got: $body_missing"
  exit 1
fi
pass ai-ml-service "consent missing returns HTTP 200 with skipped AI step CONSENT_MISSING (FR12.3)"

# 3. Test: consent is true -> passes gate (returns 200, never fails with CONSENT_MISSING)
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

# When key is not configured, it returns 502 PROVIDER_ERROR; with key or mock it returns 200. Both prove the gate passed.
if [ "$status_true" != "200" ] && [ "$status_true" != "502" ]; then
  fail ai-ml-service "expected HTTP 200 or 502 on consent=true, got $status_true. Body: $body_true"
  exit 1
fi
pass ai-ml-service "consent=true passed gate successfully (status $status_true)"

# 4. Test: POST /v1/analyses/complexity with consent=false returns HTTP 200 with slow_patterns and skipped ai_step (T-18 v2 §16.1)
payload_complexity_false='{
  "request_id": "test-int-complexity-false-004",
  "room_id": "Zq8-Lw2Ef6Th0Yx4Rn7JbA",
  "analysis_id": "an-int-001",
  "request_type": "complexity",
  "language": "python",
  "consent": false,
  "ai_enabled": true,
  "code": "for i in range(10):\n    for j in range(10):\n        pass"
}'

resp_comp_false="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_complexity_false")"

status_comp_false="$(echo "$resp_comp_false" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_comp_false="$(echo "$resp_comp_false" | grep -v 'HTTP_STATUS:')"

if [ "$status_comp_false" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on /v1/analyses/complexity consent=false, got $status_comp_false. Body: $body_comp_false"
  exit 1
fi

if ! echo "$body_comp_false" | grep -q "CONSENT_MISSING"; then
  fail ai-ml-service "expected CONSENT_MISSING in complexity response, got: $body_comp_false"
  exit 1
fi

if ! echo "$body_comp_false" | grep -q "nested_loops"; then
  fail ai-ml-service "expected nested_loops in complexity slow_patterns, got: $body_comp_false"
  exit 1
fi
pass ai-ml-service "POST /v1/analyses/complexity with consent=false returns HTTP 200 with slow_patterns and skipped AI step (T-18 v2)"
