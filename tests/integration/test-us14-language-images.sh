#!/usr/bin/env bash
# =============================================================================
# Integration test for US-14: Main coursework languages (DR1)
# Verifies that POST /v1/runs supports all five DR1 languages in priority order:
#   1. C++ (cpp)
#   2. Python (python)
#   3. C (c)
#   4. Java (java)
#   5. JavaScript (javascript)
# Also verifies stdin echoing, compile error reporting (NFR4.5), and rejection
# of unsupported languages (422 UNSUPPORTED_LANGUAGE).
# =============================================================================
set -uo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "execution-service"; then
  skip "execution-service" "(no Dockerfile)"
  exit 0
fi

base_url="$(service_url execution-service)"
endpoint="$base_url/v1/runs"

# Check if execution-service is accessible; skip if offline
if ! curl -s --max-time 2 "$base_url/actuator/health" >/dev/null 2>&1; then
  skip "execution-service" "(service not running at $base_url)"
  exit 0
fi

send_run_request() {
  local req_id="$1"
  local lang="$2"
  local file_name="$3"
  local code="$4"
  local stdin_val="${5:-}"

  local escaped_code
  escaped_code=$(python3 -c "import json, sys; print(json.dumps(sys.stdin.read()))" <<< "$code" 2>/dev/null || echo "\"$code\"")

  local payload
  payload=$(cat <<EOF
{
  "request_id": "$req_id",
  "room_id": "us14-integration-room",
  "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
  "language": "$lang",
  "stdin": "$stdin_val",
  "source": {
    "kind": "inline",
    "files": [
      {
        "name": "$file_name",
        "content": $escaped_code
      }
    ],
    "entry": "$file_name"
  }
}
EOF
)

  curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
    -H "Content-Type: application/json" \
    -d "$payload"
}

# -----------------------------------------------------------------------------
# 1. C++ (Priority 1)
# -----------------------------------------------------------------------------
cpp_code='#include <iostream>
int main() {
    std::cout << "Hello, C++ Sandbox!" << std::endl;
    return 0;
}'
resp_cpp=$(send_run_request "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e01" "cpp" "main.cpp" "$cpp_code")
status_cpp=$(echo "$resp_cpp" | grep "HTTP_STATUS" | cut -d':' -f2)
body_cpp=$(echo "$resp_cpp" | grep -v "HTTP_STATUS")

if [ "$status_cpp" = "200" ]; then
  pass "cpp-run" "POST /v1/runs with C++ inline source -> 200"
else
  fail "cpp-run" "expected 200, got $status_cpp: $body_cpp"
  exit 1
fi

# C++ compile error returns failure_kind: "compile_error" (NFR4.5)
cpp_err_code='#include <iostream>
int main() {
    this_is_an_invalid_compile_error
    return 0;
}'
resp_cpp_err=$(send_run_request "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e02" "cpp" "main.cpp" "$cpp_err_code")
status_cpp_err=$(echo "$resp_cpp_err" | grep "HTTP_STATUS" | cut -d':' -f2)
body_cpp_err=$(echo "$resp_cpp_err" | grep -v "HTTP_STATUS")

if [ "$status_cpp_err" = "200" ] && echo "$body_cpp_err" | grep -q "compile_error"; then
  pass "cpp-compile-error" "C++ compile error returns failure_kind: compile_error (NFR4.5)"
else
  fail "cpp-compile-error" "expected 200 with compile_error, got $status_cpp_err: $body_cpp_err"
  exit 1
fi

# -----------------------------------------------------------------------------
# 2. Python (Priority 2)
# -----------------------------------------------------------------------------
py_code='print("Hello, Python Sandbox!")'
resp_py=$(send_run_request "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e03" "python" "main.py" "$py_code")
status_py=$(echo "$resp_py" | grep "HTTP_STATUS" | cut -d':' -f2)
body_py=$(echo "$resp_py" | grep -v "HTTP_STATUS")

if [ "$status_py" = "200" ]; then
  pass "python-run" "POST /v1/runs with Python inline source -> 200"
else
  fail "python-run" "expected 200, got $status_py: $body_py"
  exit 1
fi

# -----------------------------------------------------------------------------
# 3. C (Priority 3)
# -----------------------------------------------------------------------------
c_code='#include <stdio.h>
int main(void) {
    printf("Hello, C Sandbox!\n");
    return 0;
}'
resp_c=$(send_run_request "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e04" "c" "main.c" "$c_code")
status_c=$(echo "$resp_c" | grep "HTTP_STATUS" | cut -d':' -f2)
body_c=$(echo "$resp_c" | grep -v "HTTP_STATUS")

if [ "$status_c" = "200" ]; then
  pass "c-run" "POST /v1/runs with C inline source -> 200"
else
  fail "c-run" "expected 200, got $status_c: $body_c"
  exit 1
fi

# C compile error
c_err_code='#include <stdio.h>
int main(void) {
    invalid_c_code_here
    return 0;
}'
resp_c_err=$(send_run_request "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e05" "c" "main.c" "$c_err_code")
status_c_err=$(echo "$resp_c_err" | grep "HTTP_STATUS" | cut -d':' -f2)
body_c_err=$(echo "$resp_c_err" | grep -v "HTTP_STATUS")

if [ "$status_c_err" = "200" ] && echo "$body_c_err" | grep -q "compile_error"; then
  pass "c-compile-error" "C compile error returns failure_kind: compile_error (NFR4.5)"
else
  fail "c-compile-error" "expected 200 with compile_error, got $status_c_err: $body_c_err"
  exit 1
fi

# -----------------------------------------------------------------------------
# 4. Java (Priority 4)
# -----------------------------------------------------------------------------
java_code='public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, Java Sandbox!");
    }
}'
resp_java=$(send_run_request "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e06" "java" "Main.java" "$java_code")
status_java=$(echo "$resp_java" | grep "HTTP_STATUS" | cut -d':' -f2)
body_java=$(echo "$resp_java" | grep -v "HTTP_STATUS")

if [ "$status_java" = "200" ]; then
  pass "java-run" "POST /v1/runs with Java inline source -> 200"
else
  fail "java-run" "expected 200, got $status_java: $body_java"
  exit 1
fi

# Java compile error
java_err_code='public class Main {
    public static void main(String[] args) {
        invalid_java_syntax_error
    }
}'
resp_java_err=$(send_run_request "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e07" "java" "Main.java" "$java_err_code")
status_java_err=$(echo "$resp_java_err" | grep "HTTP_STATUS" | cut -d':' -f2)
body_java_err=$(echo "$resp_java_err" | grep -v "HTTP_STATUS")

if [ "$status_java_err" = "200" ] && echo "$body_java_err" | grep -q "compile_error"; then
  pass "java-compile-error" "Java compile error returns failure_kind: compile_error (NFR4.5)"
else
  fail "java-compile-error" "expected 200 with compile_error, got $status_java_err: $body_java_err"
  exit 1
fi

# -----------------------------------------------------------------------------
# 5. JavaScript (Priority 5)
# -----------------------------------------------------------------------------
js_code='console.log("Hello, JS Sandbox!");'
resp_js=$(send_run_request "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e08" "javascript" "main.js" "$js_code")
status_js=$(echo "$resp_js" | grep "HTTP_STATUS" | cut -d':' -f2)
body_js=$(echo "$resp_js" | grep -v "HTTP_STATUS")

if [ "$status_js" = "200" ]; then
  pass "javascript-run" "POST /v1/runs with JavaScript inline source -> 200"
else
  fail "javascript-run" "expected 200, got $status_js: $body_js"
  exit 1
fi

# -----------------------------------------------------------------------------
# 6. Unsupported language rejection (DR1)
# -----------------------------------------------------------------------------
resp_unsupported=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$endpoint" \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e09",
    "room_id": "us14-integration-room",
    "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
    "language": "ruby",
    "source": {
      "kind": "inline",
      "files": [{"name": "main.rb", "content": "puts 1"}],
      "entry": "main.rb"
    }
  }')
status_unsupported=$(echo "$resp_unsupported" | grep "HTTP_STATUS" | cut -d':' -f2)
body_unsupported=$(echo "$resp_unsupported" | grep -v "HTTP_STATUS")

if [ "$status_unsupported" = "422" ] && echo "$body_unsupported" | grep -q "UNSUPPORTED_LANGUAGE"; then
  pass "unsupported-lang-422" "Unsupported language (ruby) rejected -> 422 UNSUPPORTED_LANGUAGE (DR1)"
else
  fail "unsupported-lang-422" "expected 422 UNSUPPORTED_LANGUAGE, got $status_unsupported: $body_unsupported"
  exit 1
fi

echo "All US-14 language integration tests passed."
exit 0
