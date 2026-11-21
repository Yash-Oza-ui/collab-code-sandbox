#!/usr/bin/env bash
# test-us66-verify-fix.sh: verify optimization fix sandbox verification (US-66, FR36.3, Task 1).
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

# 1. Test: Verified improvement when fix is faster (speedup > 5.0% noise margin)
payload_verified='{
  "request_id": "8a4f3b21-6c5d-4e9a-0f12-3456789abcde",
  "room_id": "room-us66-test",
  "analysis_id": "9b5f4c32-7d6e-4f0b-1a23-456789abcdef",
  "request_type": "complexity",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "code": "def solve(n): pass",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [2.0, 2.1, 2.0]},
    {"input_size": 200, "runtimes_ms": [8.0, 8.1, 8.0]},
    {"input_size": 400, "runtimes_ms": [32.0, 32.2, 32.1]}
  ],
  "suggested_patch": "def solve_fast(n): return n",
  "suggested_patch_runtimes_ms": [2.0, 2.1, 2.0]
}'

resp_verified="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_verified")"

status_verified="$(echo "$resp_verified" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_verified="$(echo "$resp_verified" | grep -v 'HTTP_STATUS:')"

if [ "$status_verified" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on verified fix (US-66), got $status_verified. Body: $body_verified"
  exit 1
fi

if ! echo "$body_verified" | grep -q '"verified":true'; then
  fail ai-ml-service "expected verified:true for faster fix, got: $body_verified"
  exit 1
fi

if ! echo "$body_verified" | grep -q '"original_runtime_ms"'; then
  fail ai-ml-service "expected original_runtime_ms in ai_step, got: $body_verified"
  exit 1
fi

if ! echo "$body_verified" | grep -q '"patch_runtime_ms"'; then
  fail ai-ml-service "expected patch_runtime_ms in ai_step, got: $body_verified"
  exit 1
fi

pass ai-ml-service "faster fix correctly labeled verified improvement with measured runtimes (US-66, FR36.3)"

# 2. Test: Slower fix shown without verified label, with measured numbers
payload_slower='{
  "request_id": "9a5f4b32-7c6d-4e0a-1f23-456789abcdef",
  "room_id": "room-us66-test",
  "analysis_id": "0b6f5c43-8d7e-4a1c-2b34-456789abcdef",
  "request_type": "complexity",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "code": "def solve(n): pass",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [2.0, 2.1, 2.0]},
    {"input_size": 200, "runtimes_ms": [8.0, 8.1, 8.0]},
    {"input_size": 400, "runtimes_ms": [16.0, 16.2, 16.1]}
  ],
  "suggested_patch": "def solve_slower(n): pass",
  "suggested_patch_runtimes_ms": [40.0, 40.5, 40.2]
}'

resp_slower="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_slower")"

status_slower="$(echo "$resp_slower" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_slower="$(echo "$resp_slower" | grep -v 'HTTP_STATUS:')"

if [ "$status_slower" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on slower fix, got $status_slower. Body: $body_slower"
  exit 1
fi

if ! echo "$body_slower" | grep -q '"verified":false'; then
  fail ai-ml-service "expected verified:false for slower fix, got: $body_slower"
  exit 1
fi

if ! echo "$body_slower" | grep -q '"verified_speedup_pct":null'; then
  fail ai-ml-service "expected verified_speedup_pct:null for slower fix, got: $body_slower"
  exit 1
fi

pass ai-ml-service "slower fix shown without verified label and with measured numbers (US-66, FR36.3)"

# 3. Test: Fix within noise margin (< 5.0%) is not verified
payload_noise='{
  "request_id": "aa6f5b43-8c7d-4f1b-2a34-456789abcdef",
  "room_id": "room-us66-test",
  "analysis_id": "ba7f6c54-9d8e-4b2d-3c45-456789abcdef",
  "request_type": "complexity",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "code": "def solve(n): pass",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [10.0, 10.0, 10.0]},
    {"input_size": 200, "runtimes_ms": [20.0, 20.0, 20.0]},
    {"input_size": 400, "runtimes_ms": [100.0, 100.0, 100.0]}
  ],
  "suggested_patch": "def solve_marginal(n): pass",
  "suggested_patch_runtimes_ms": [97.0, 97.0, 97.0]
}'

resp_noise="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_noise")"

status_noise="$(echo "$resp_noise" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_noise="$(echo "$resp_noise" | grep -v 'HTTP_STATUS:')"

if [ "$status_noise" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on noise-margin fix, got $status_noise. Body: $body_noise"
  exit 1
fi

if ! echo "$body_noise" | grep -q '"verified":false'; then
  fail ai-ml-service "expected verified:false for fix within noise margin, got: $body_noise"
  exit 1
fi

if ! echo "$body_noise" | grep -q "within the 5.0% noise margin"; then
  fail ai-ml-service "expected explanation mentioning 5.0% noise margin, got: $body_noise"
  exit 1
fi

pass ai-ml-service "fix within 5.0% noise margin not labeled verified (US-66, FR36.3)"

# 4. Test: Consent missing skips AI step while returning AI-free parts intact (FR12.3, NFR7.5)
payload_no_consent='{
  "request_id": "ba7f6b54-9c8d-4a2c-3b45-456789abcdef",
  "room_id": "room-us66-test",
  "analysis_id": "cb8f7c65-0d9e-4c3e-4d56-456789abcdef",
  "request_type": "complexity",
  "language": "python",
  "consent": false,
  "ai_enabled": false,
  "code": "def solve(n): pass",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [2.0, 2.0, 2.0]},
    {"input_size": 200, "runtimes_ms": [8.0, 8.0, 8.0]},
    {"input_size": 400, "runtimes_ms": [32.0, 32.0, 32.0]}
  ],
  "suggested_patch": "def solve_fast(n): return n",
  "suggested_patch_runtimes_ms": [2.0, 2.0, 2.0]
}'

resp_no_consent="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_no_consent")"

status_no_consent="$(echo "$resp_no_consent" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_no_consent="$(echo "$resp_no_consent" | grep -v 'HTTP_STATUS:')"

if [ "$status_no_consent" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 with consent:false, got $status_no_consent. Body: $body_no_consent"
  exit 1
fi

if ! echo "$body_no_consent" | grep -q '"status":"skipped"'; then
  fail ai-ml-service "expected ai_step status:skipped with consent:false, got: $body_no_consent"
  exit 1
fi

if ! echo "$body_no_consent" | grep -q '"error_code":"CONSENT_MISSING"'; then
  fail ai-ml-service "expected error_code:CONSENT_MISSING, got: $body_no_consent"
  exit 1
fi

if ! echo "$body_no_consent" | grep -q '"estimated_complexity"'; then
  fail ai-ml-service "expected estimated_complexity returned when consent:false, got: $body_no_consent"
  exit 1
fi

pass ai-ml-service "declining consent skips AI step while returning AI-free parts intact (FR12.3, NFR7.5, US-66)"

