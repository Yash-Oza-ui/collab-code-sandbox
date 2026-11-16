#!/usr/bin/env bash
# test-ai-rules.sh: verify rules-first static analysis before AI fallback (FR36.4, US-67, Sprint 2).
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

# 1. Test: Seeded nested loops program -> rule fires by default, AI step skipped without outbound call
payload_nested_loop='{
  "request_id": "test-int-rules-nested-001",
  "room_id": "room-int-rules",
  "analysis_id": "an-rules-nested-001",
  "request_type": "complexity",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "code": "def find_duplicates(arr):\n    dups = []\n    for i in range(len(arr)):\n        for j in range(i + 1, len(arr)):\n            if arr[i] == arr[j]:\n                dups.append(arr[i])\n    return dups\n",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
    {"input_size": 200, "runtimes_ms": [4.0, 4.2]}
  ]
}'

resp_nested="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_nested_loop")"

status_nested="$(echo "$resp_nested" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_nested="$(echo "$resp_nested" | grep -v 'HTTP_STATUS:')"

if [ "$status_nested" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on seeded nested loop (US-67), got $status_nested. Body: $body_nested"
  exit 1
fi

if ! echo "$body_nested" | grep -q "nested_loops"; then
  fail ai-ml-service "expected nested_loops in slow_patterns for seeded program, got: $body_nested"
  exit 1
fi

if ! echo "$body_nested" | grep -q '"status":"skipped"'; then
  fail ai-ml-service "expected ai_step status skipped when rule fires, got: $body_nested"
  exit 1
fi
pass ai-ml-service "seeded nested loops caught by rule and AI step skipped (US-67, FR36.4)"

# 2. Test: Seeded repeated string concatenation program -> rule fires, AI step skipped
payload_string_concat='{
  "request_id": "test-int-rules-concat-002",
  "room_id": "room-int-rules",
  "analysis_id": "an-rules-concat-002",
  "request_type": "complexity",
  "language": "python",
  "consent": true,
  "ai_enabled": true,
  "rules_first": true,
  "code": "def build_str(items):\n    s = \"\"\n    for x in items:\n        s += str(x)\n    return s\n",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
    {"input_size": 200, "runtimes_ms": [2.0, 2.1]}
  ]
}'

resp_concat="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_string_concat")"

status_concat="$(echo "$resp_concat" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_concat="$(echo "$resp_concat" | grep -v 'HTTP_STATUS:')"

if [ "$status_concat" != "200" ]; then
  fail ai-ml-service "expected HTTP 200 on seeded string concat (US-67), got $status_concat. Body: $body_concat"
  exit 1
fi

if ! echo "$body_concat" | grep -q "repeated_string_concat"; then
  fail ai-ml-service "expected repeated_string_concat in slow_patterns, got: $body_concat"
  exit 1
fi

if ! echo "$body_concat" | grep -q '"status":"skipped"'; then
  fail ai-ml-service "expected ai_step status skipped when string concat rule fires, got: $body_concat"
  exit 1
fi
pass ai-ml-service "seeded repeated string concat caught by rule and AI step skipped (US-67)"

# 3. Test: Sequential loops (false-positive check) -> NOT reported as nested
payload_seq='{
  "request_id": "test-int-rules-seq-003",
  "room_id": "room-int-rules",
  "analysis_id": "an-rules-seq-003",
  "request_type": "complexity",
  "language": "python",
  "consent": false,
  "ai_enabled": true,
  "rules_first": true,
  "code": "def two_stages(arr):\n    for a in arr:\n        pass\n    for b in arr:\n        pass\n",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
    {"input_size": 200, "runtimes_ms": [2.0, 2.1]}
  ]
}'

resp_seq="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_seq")"

body_seq="$(echo "$resp_seq" | grep -v 'HTTP_STATUS:')"

if echo "$body_seq" | grep -q "nested_loops"; then
  fail ai-ml-service "false positive: sequential loops reported as nested: $body_seq"
  exit 1
fi
pass ai-ml-service "false-positive check: sequential loops not reported as nested (US-67)"

# 4. Test: Clean program with consent=false -> AI step skipped with CONSENT_MISSING, rules returned
payload_clean_no_consent='{
  "request_id": "test-int-rules-noconsent-004",
  "room_id": "room-int-rules",
  "analysis_id": "an-rules-noconsent-004",
  "request_type": "complexity",
  "language": "python",
  "consent": false,
  "ai_enabled": true,
  "rules_first": true,
  "code": "def clean_fn(x):\n    return x + 1\n",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
    {"input_size": 200, "runtimes_ms": [1.0, 1.1]}
  ]
}'

resp_noconsent="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_clean_no_consent")"

body_noconsent="$(echo "$resp_noconsent" | grep -v 'HTTP_STATUS:')"

if ! echo "$body_noconsent" | grep -q "CONSENT_MISSING"; then
  fail ai-ml-service "expected CONSENT_MISSING for clean program with consent=false, got: $body_noconsent"
  exit 1
fi
pass ai-ml-service "clean program with consent=false skips AI step with CONSENT_MISSING (US-67, FR12)"

# 5. Regression Test: Python numeric accumulation (total += i) -> NOT reported as repeated_string_concat
payload_accum='{
  "request_id": "test-int-rules-accum-005",
  "room_id": "room-int-rules",
  "analysis_id": "an-rules-accum-005",
  "request_type": "complexity",
  "language": "python",
  "consent": false,
  "ai_enabled": true,
  "code": "def sum_nums(arr):\n    total = 0\n    for i in arr:\n        total += i\n    return total\n",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
    {"input_size": 200, "runtimes_ms": [2.0, 2.1]}
  ]
}'

resp_accum="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_accum")"

body_accum="$(echo "$resp_accum" | grep -v 'HTTP_STATUS:')"

if echo "$body_accum" | grep -q "repeated_string_concat"; then
  fail ai-ml-service "false positive: numeric accumulation reported as repeated_string_concat: $body_accum"
  exit 1
fi
pass ai-ml-service "regression check: numeric accumulation not reported as repeated_string_concat (US-67)"

# 6. Regression Test: C++ sequential unbraced loops with strings containing comment markers
payload_cpp_unbraced='{
  "request_id": "test-int-rules-cpp-006",
  "room_id": "room-int-rules",
  "analysis_id": "an-rules-cpp-006",
  "request_type": "complexity",
  "language": "cpp",
  "consent": false,
  "ai_enabled": true,
  "code": "void run() {\n    const char* url = \"http://example.com\";\n    for (int i = 0; i < n; i++) x++;\n    for (int j = 0; j < n; j++) y++;\n}\n",
  "measurements": [
    {"input_size": 100, "runtimes_ms": [1.0, 1.1]},
    {"input_size": 200, "runtimes_ms": [2.0, 2.1]}
  ]
}'

resp_cpp="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/analyses/complexity" \
  -H "Content-Type: application/json" \
  -d "$payload_cpp_unbraced")"

body_cpp="$(echo "$resp_cpp" | grep -v 'HTTP_STATUS:')"

if echo "$body_cpp" | grep -q "nested_loops"; then
  fail ai-ml-service "false positive: C++ sequential unbraced loops reported as nested: $body_cpp"
  exit 1
fi
pass ai-ml-service "regression check: C++ sequential unbraced loops and string comments not reported as nested (US-67)"

pass ai-ml-service "all US-67 rules-first integration assertions passed"

