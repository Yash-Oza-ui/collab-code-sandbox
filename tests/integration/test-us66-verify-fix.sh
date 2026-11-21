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
  "base_input_size": 750,
  "consent": true,
  "ai_enabled": true,
  "code": "import sys, time\nn = int(sys.stdin.read().strip() or \"100\")\ntime.sleep(0.05)\nprint(f\"res:{n}\")",
  "suggested_patch": "import sys\nn = int(sys.stdin.read().strip() or \"100\")\nprint(f\"res:{n}\")"
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
  "code": "import sys\nn = int(sys.stdin.read().strip() or \"100\")\nprint(f\"res:{n}\")",
  "suggested_patch": "import sys, time\nn = int(sys.stdin.read().strip() or \"100\")\ntime.sleep(0.05)\nprint(f\"res:{n}\")"
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
  "code": "import sys\nn = int(sys.stdin.read().strip() or \"100\")\nprint(f\"res:{n}\")",
  "suggested_patch": "import sys\nn = int(sys.stdin.read().strip() or \"100\")\nprint(f\"res:{n}\")"
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

if ! echo "$body_noise" | grep -q -E "within the 5.0% noise margin|is slower than original"; then
  fail ai-ml-service "expected explanation mentioning 5.0% noise margin or slowdown, got: $body_noise"
  exit 1
fi

pass ai-ml-service "fix within 5.0% noise margin not labeled verified (US-66, FR36.3)"

# 4. Test: Consent missing skips AI fix generation while candidate fix is verified in sandbox (US-66, FR36.3)
payload_no_consent='{
  "request_id": "ba7f6b54-9c8d-4a2c-3b45-456789abcdef",
  "room_id": "room-us66-test",
  "analysis_id": "cb8f7c65-0d9e-4c3e-4d56-456789abcdef",
  "request_type": "complexity",
  "language": "python",
  "base_input_size": 750,
  "consent": false,
  "ai_enabled": false,
  "code": "import sys, time\nn = int(sys.stdin.read().strip() or \"100\")\ntime.sleep(0.05)\nprint(f\"res:{n}\")",
  "suggested_patch": "import sys\nn = int(sys.stdin.read().strip() or \"100\")\nprint(f\"res:{n}\")"
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

if ! echo "$body_no_consent" | grep -q '"suggested_fix":null'; then
  fail ai-ml-service "expected suggested_fix:null when consent:false, got: $body_no_consent"
  exit 1
fi

if ! echo "$body_no_consent" | grep -q '"verified":true'; then
  fail ai-ml-service "expected candidate fix verified:true even when consent:false, got: $body_no_consent"
  exit 1
fi

if ! echo "$body_no_consent" | grep -q '"estimated_complexity"'; then
  fail ai-ml-service "expected estimated_complexity returned when consent:false, got: $body_no_consent"
  exit 1
fi

pass ai-ml-service "candidate fix verified in sandbox without consent (US-66, FR36.3, NFR7.5)"

# 5. Test: Output mismatch fails closed with verified:false and OUTPUT_MISMATCH
payload_mismatch='{
  "request_id": "ca8f7b65-0c9d-4e3f-4a56-456789abcdef",
  "room_id": "room-us66-test",
  "analysis_id": "db9f8c76-1e0f-4d4a-5b67-456789abcdef",
  "request_type": "complexity",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "code": "import sys\nn = int(sys.stdin.read().strip() or \"100\")\ntotal = sum(i for i in range(n) for _ in range(n))\nprint(f\"res:{n}\")",
  "suggested_patch": "import sys\nn = int(sys.stdin.read().strip() or \"100\")\nprint(f\"wrong_res:{n}\")"
}'

resp_mismatch="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_mismatch")"

status_mismatch="$(echo "$resp_mismatch" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_mismatch="$(echo "$resp_mismatch" | grep -v 'HTTP_STATUS:')"

if [ "$status_mismatch" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on output mismatch payload, got $status_mismatch. Body: $body_mismatch"
  exit 1
fi

if ! echo "$body_mismatch" | grep -q '"verified":false'; then
  fail ai-ml-service "expected verified:false on output mismatch, got: $body_mismatch"
  exit 1
fi

if ! echo "$body_mismatch" | grep -q "Output mismatch on input"; then
  fail ai-ml-service "expected output mismatch explanation, got: $body_mismatch"
  exit 1
fi

pass ai-ml-service "output mismatch fails closed without verified label (US-66, FR36.3)"

