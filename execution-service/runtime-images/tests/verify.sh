#!/usr/bin/env bash
# =============================================================================
# US-14 & T-14 runtime-images verification script
# File: execution-service/runtime-images/tests/verify.sh
#
# Purpose:
#   Runs each acceptance-criterion check against all supported runtime images
#   in the required testing priority order (DR1):
#     1. C++        (collab-sandbox-cpp:0.1.0)
#     2. Python     (collab-sandbox-python:0.2.0)
#     3. C          (collab-sandbox-c:0.1.0)
#     4. Java       (collab-sandbox-java:0.1.0)
#     5. JavaScript (collab-sandbox-javascript:0.1.0)
#
# Usage:
#   cd execution-service/runtime-images && bash tests/verify.sh
#   # or from repository root:
#   bash execution-service/runtime-images/tests/verify.sh
# =============================================================================

set -euo pipefail

# Ensure Git Bash on Windows does not rewrite POSIX container paths (e.g. /sandbox/code)
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

PASS=0
FAIL=0
RESULTS=()

if [ -t 1 ]; then
  GREEN="\033[0;32m"; RED="\033[0;31m"; YELLOW="\033[0;33m"; CYAN="\033[0;36m"; RESET="\033[0m"
else
  GREEN=""; RED=""; YELLOW=""; CYAN=""; RESET=""
fi

check_pass() {
  local name="$1"
  PASS=$((PASS + 1))
  RESULTS+=("${GREEN}PASS${RESET}  $name")
  echo -e "${GREEN}PASS${RESET}  $name"
}
check_fail() {
  local name="$1"; shift
  local msg="$*"
  FAIL=$((FAIL + 1))
  RESULTS+=("${RED}FAIL${RESET}  $name — $msg")
  echo -e "${RED}FAIL${RESET}  $name — $msg"
}
check_skip() {
  local name="$1"; shift
  local msg="$*"
  RESULTS+=("${YELLOW}SKIP${RESET}  $name — $msg")
  echo -e "${YELLOW}SKIP${RESET}  $name — $msg"
}

echo "============================================================"
echo " US-14 runtime-images multi-language verification (DR1)"
echo " Priority order: C++ -> Python -> C -> Java -> JavaScript"
echo "============================================================"
echo ""

# -----------------------------------------------------------------------------
# CHECK 0: Docker daemon is reachable
# -----------------------------------------------------------------------------
echo "[0] Checking Docker daemon..."
if ! docker info > /dev/null 2>&1; then
  echo -e "${RED}FATAL${RESET}: Docker daemon is not running. Cannot proceed."
  exit 1
fi
echo "    Docker daemon: OK"
echo ""

# Helper to verify a single language runtime
verify_language() {
  local lang="$1"
  local image="$2"
  local sample_dir="$RUNTIME_DIR/$lang/samples"
  local is_compiled="$3"

  echo -e "${CYAN}------------------------------------------------------------${RESET}"
  echo -e "${CYAN} Verifying language: $lang ($image)${RESET}"
  echo -e "${CYAN}------------------------------------------------------------${RESET}"

  # 1. Image exists
  if docker image inspect "$image" > /dev/null 2>&1; then
    check_pass "[$lang] Image '$image' exists"
  else
    check_fail "[$lang] Image '$image' exists" \
      "Image not found. Build it with: docker build -t $image execution-service/runtime-images/$lang"
    return
  fi

  # 2. Hello-world without stdin
  local hw_out="" hw_exit=0
  case "$lang" in
    cpp)
      hw_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "g++ -O2 /sandbox/code/hello_world.cpp -o /tmp/hw && /tmp/hw" 2>/dev/null) || hw_exit=$?
      ;;
    python)
      hw_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        python /sandbox/code/hello_world.py 2>/dev/null) || hw_exit=$?
      ;;
    c)
      hw_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "gcc -O2 /sandbox/code/hello_world.c -o /tmp/hw && /tmp/hw" 2>/dev/null) || hw_exit=$?
      ;;
    java)
      hw_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "javac -d /tmp /sandbox/code/Main.java && java -cp /tmp Main" 2>/dev/null) || hw_exit=$?
      ;;
    javascript)
      hw_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        node /sandbox/code/hello_world.js 2>/dev/null) || hw_exit=$?
      ;;
  esac
  hw_exit="${hw_exit:-0}"

  if [ "$hw_out" = "Hello, Sandbox!" ] && [ "$hw_exit" = "0" ]; then
    check_pass "[$lang] Hello-world (no stdin) — stdout='Hello, Sandbox!' exit=0"
  else
    check_fail "[$lang] Hello-world (no stdin)" "Expected 'Hello, Sandbox!' exit=0; got '$hw_out' exit=$hw_exit"
  fi

  # 3. Hello-world with stdin supplied (stdin ignored)
  local hw2_out="" hw2_exit=0
  case "$lang" in
    cpp)
      hw2_out=$(echo "ignored input" | docker run --rm -i --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "g++ -O2 /sandbox/code/hello_world.cpp -o /tmp/hw && /tmp/hw" 2>/dev/null) || hw2_exit=$?
      ;;
    python)
      hw2_out=$(echo "ignored input" | docker run --rm -i --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        python /sandbox/code/hello_world.py 2>/dev/null) || hw2_exit=$?
      ;;
    c)
      hw2_out=$(echo "ignored input" | docker run --rm -i --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "gcc -O2 /sandbox/code/hello_world.c -o /tmp/hw && /tmp/hw" 2>/dev/null) || hw2_exit=$?
      ;;
    java)
      hw2_out=$(echo "ignored input" | docker run --rm -i --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "javac -d /tmp /sandbox/code/Main.java && java -cp /tmp Main" 2>/dev/null) || hw2_exit=$?
      ;;
    javascript)
      hw2_out=$(echo "ignored input" | docker run --rm -i --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        node /sandbox/code/hello_world.js 2>/dev/null) || hw2_exit=$?
      ;;
  esac
  hw2_exit="${hw2_exit:-0}"

  if [ "$hw2_out" = "Hello, Sandbox!" ] && [ "$hw2_exit" = "0" ]; then
    check_pass "[$lang] Hello-world (stdin ignored) — stdout='Hello, Sandbox!' exit=0"
  else
    check_fail "[$lang] Hello-world (stdin ignored)" "Expected 'Hello, Sandbox!' exit=0; got '$hw2_out' exit=$hw2_exit"
  fi

  # 4. stdin pipe (uppercase echo)
  local in_out="" in_exit=0
  case "$lang" in
    cpp)
      in_out=$(echo "hello sandbox" | docker run --rm -i --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "g++ -O2 /sandbox/code/stdin_echo.cpp -o /tmp/echo && /tmp/echo" 2>/dev/null) || in_exit=$?
      ;;
    python)
      in_out=$(echo "hello sandbox" | docker run --rm -i --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        python /sandbox/code/stdin_echo.py 2>/dev/null) || in_exit=$?
      ;;
    c)
      in_out=$(echo "hello sandbox" | docker run --rm -i --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "gcc -O2 /sandbox/code/stdin_echo.c -o /tmp/echo && /tmp/echo" 2>/dev/null) || in_exit=$?
      ;;
    java)
      in_out=$(echo "hello sandbox" | docker run --rm -i --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "javac -d /tmp /sandbox/code/StdinEcho.java && java -cp /tmp StdinEcho" 2>/dev/null) || in_exit=$?
      ;;
    javascript)
      in_out=$(echo "hello sandbox" | docker run --rm -i --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        node /sandbox/code/stdin_echo.js 2>/dev/null) || in_exit=$?
      ;;
  esac
  in_exit="${in_exit:-0}"

  if [ "$in_out" = "HELLO SANDBOX" ] && [ "$in_exit" = "0" ]; then
    check_pass "[$lang] stdin pipe — stdout='HELLO SANDBOX' exit=0"
  else
    check_fail "[$lang] stdin pipe" "Expected 'HELLO SANDBOX' exit=0; got '$in_out' exit=$in_exit"
  fi

  # 5. stdout / stderr independence
  local split_exit=0 err_file="/tmp/err_${lang}_$$.txt"
  case "$lang" in
    cpp)
      split_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "g++ -O2 /sandbox/code/stderr_split.cpp -o /tmp/split && /tmp/split" 2>"$err_file") || split_exit=$?
      ;;
    python)
      split_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        python /sandbox/code/stderr_split.py 2>"$err_file") || split_exit=$?
      ;;
    c)
      split_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "gcc -O2 /sandbox/code/stderr_split.c -o /tmp/split && /tmp/split" 2>"$err_file") || split_exit=$?
      ;;
    java)
      split_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "javac -d /tmp /sandbox/code/StderrSplit.java && java -cp /tmp StderrSplit" 2>"$err_file") || split_exit=$?
      ;;
    javascript)
      split_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        node /sandbox/code/stderr_split.js 2>"$err_file") || split_exit=$?
      ;;
  esac
  split_exit="${split_exit:-0}"
  local split_err=""
  if [ -f "$err_file" ]; then split_err=$(cat "$err_file"); rm -f "$err_file"; fi

  if [ "$split_out" = "STDOUT line" ] && [ "$split_err" = "STDERR line" ] && [ "$split_exit" = "0" ]; then
    check_pass "[$lang] stdout/stderr independence — streams captured separately"
  else
    check_fail "[$lang] stdout/stderr independence" "Expected out='STDOUT line', err='STDERR line'; got out='$split_out', err='$split_err' exit=$split_exit"
  fi

  # 6. Nonzero exit preserved
  local nz_exit=0
  case "$lang" in
    cpp)
      docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "g++ -O2 /sandbox/code/exit_nonzero.cpp -o /tmp/nz && /tmp/nz" >/dev/null 2>&1 || nz_exit=$?
      ;;
    python)
      docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        python /sandbox/code/exit_nonzero.py >/dev/null 2>&1 || nz_exit=$?
      ;;
    c)
      docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "gcc -O2 /sandbox/code/exit_nonzero.c -o /tmp/nz && /tmp/nz" >/dev/null 2>&1 || nz_exit=$?
      ;;
    java)
      docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "javac -d /tmp /sandbox/code/ExitNonzero.java && java -cp /tmp ExitNonzero" >/dev/null 2>&1 || nz_exit=$?
      ;;
    javascript)
      docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        node /sandbox/code/exit_nonzero.js >/dev/null 2>&1 || nz_exit=$?
      ;;
  esac

  if [ "$nz_exit" = "42" ]; then
    check_pass "[$lang] Nonzero exit code preserved (exit=42)"
  else
    check_fail "[$lang] Nonzero exit code" "Expected exit=42; got exit=$nz_exit"
  fi

  # 7. Non-root user (NFR3.2)
  local whoami
  whoami=$(docker run --rm --network none "$image" id -u 2>/dev/null || echo "0")
  if [ "$whoami" = "1001" ]; then
    check_pass "[$lang] Non-root user UID 1001 (NFR3.2)"
  else
    check_fail "[$lang] Non-root user (NFR3.2)" "Expected UID 1001; got UID=$whoami"
  fi

  # 8. Network isolation (NFR3.1)
  local net_out="" net_exit=0
  case "$lang" in
    cpp)
      net_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "g++ -O2 /sandbox/code/network_probe.cpp -o /tmp/net && /tmp/net" 2>/dev/null; echo "exit:$?") || true
      ;;
    python)
      net_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        python /sandbox/code/network_probe.py 2>/dev/null; echo "exit:$?") || true
      ;;
    c)
      net_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "gcc -O2 /sandbox/code/network_probe.c -o /tmp/net && /tmp/net" 2>/dev/null; echo "exit:$?") || true
      ;;
    java)
      net_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        sh -c "javac -d /tmp /sandbox/code/NetworkProbe.java && java -cp /tmp NetworkProbe" 2>/dev/null; echo "exit:$?") || true
      ;;
    javascript)
      net_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
        node /sandbox/code/network_probe.js 2>/dev/null; echo "exit:$?") || true
      ;;
  esac
  net_exit=$(echo "$net_out" | grep -o 'exit:[0-9]*' | cut -d: -f2)

  if [ "$net_exit" = "1" ] && echo "$net_out" | grep -q "NETWORK BLOCKED"; then
    check_pass "[$lang] Network isolation — blocked as expected (NFR3.1)"
  else
    check_fail "[$lang] Network isolation" "Expected exit=1 with NETWORK BLOCKED; got exit=$net_exit output='$net_out'"
  fi

  # 9. Source code not baked into image (FR5.2)
  local baked
  baked=$(docker run --rm --network none "$image" \
    sh -c 'test -d /sandbox/code && ls /sandbox/code | wc -l || echo 0' 2>/dev/null)
  if [ "${baked:-0}" = "0" ]; then
    check_pass "[$lang] Code not baked into image (FR5.2)"
  else
    check_fail "[$lang] Code not baked in" "Files found in bare image /sandbox/code"
  fi

  # 10. Compiled languages: Compile error returns non-zero exit with compiler output in stderr (NFR4.5)
  if [ "$is_compiled" = "true" ]; then
    local comp_err_file="/tmp/comperr_${lang}_$$.txt"
    local comp_exit=0
    case "$lang" in
      cpp)
        docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
          g++ /sandbox/code/compile_error.cpp -o /tmp/a.out 2>"$comp_err_file" >/dev/null || comp_exit=$?
        ;;
      c)
        docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
          gcc /sandbox/code/compile_error.c -o /tmp/a.out 2>"$comp_err_file" >/dev/null || comp_exit=$?
        ;;
      java)
        docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
          javac -d /tmp /sandbox/code/CompileError.java 2>"$comp_err_file" >/dev/null || comp_exit=$?
        ;;
    esac

    local comp_err=""
    if [ -f "$comp_err_file" ]; then comp_err=$(cat "$comp_err_file"); rm -f "$comp_err_file"; fi

    if [ "$comp_exit" != "0" ] && [ -n "$comp_err" ]; then
      check_pass "[$lang] Compile error verification — exit=$comp_exit with compiler stderr (NFR4.5)"
    else
      check_fail "[$lang] Compile error verification" "Expected non-zero exit and compiler stderr; got exit=$comp_exit"
    fi
  fi

  # 11. C and C++ sanitizer toolchain support (US-73 ready)
  if [ "$lang" = "cpp" ] || [ "$lang" = "c" ]; then
    local san_out="" san_exit=0
    case "$lang" in
      cpp)
        san_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
          sh -c "g++ -fsanitize=address,undefined -no-pie -g -O1 /sandbox/code/sanitizer_sample.cpp -o /tmp/san && /tmp/san" 2>/dev/null) || san_exit=$?
        ;;
      c)
        san_out=$(docker run --rm --network none -v "$sample_dir:/sandbox/code:ro" "$image" \
          sh -c "gcc -fsanitize=address,undefined -no-pie -g -O1 /sandbox/code/sanitizer_sample.c -o /tmp/san && /tmp/san" 2>/dev/null) || san_exit=$?
        ;;
    esac
    san_exit="${san_exit:-0}"

    if [ "$san_out" = "SANITIZER_SUPPORT_OK" ] && [ "$san_exit" = "0" ]; then
      check_pass "[$lang] Sanitizer toolchain ready (AddressSanitizer + UndefinedBehaviorSanitizer) (US-73)"
    else
      check_fail "[$lang] Sanitizer toolchain" "Failed to build or run sanitizer sample; exit=$san_exit out='$san_out'"
    fi
  fi

  # 12. Image size (informational)
  local size
  size=$(docker image inspect "$image" --format '{{.Size}}' 2>/dev/null | awk '{printf "%.1f MB", $1/1048576}') || true
  if [ -n "$size" ]; then
    check_pass "[$lang] Image size: $size (recorded in README)"
  else
    check_skip "[$lang] Image size" "Could not measure size"
  fi
  echo ""
}

# Run in strict priority order (DR1): C++ -> Python -> C -> Java -> JavaScript
verify_language "cpp"        "collab-sandbox-cpp:0.1.0"        "true"
verify_language "python"     "collab-sandbox-python:0.2.0"     "false"
verify_language "c"          "collab-sandbox-c:0.1.0"          "true"
verify_language "java"       "collab-sandbox-java:0.1.0"       "true"
verify_language "javascript" "collab-sandbox-javascript:0.1.0" "false"

# -----------------------------------------------------------------------------
# US-12 Sandbox Isolation Verification Checks (NFR3.1 - NFR3.4)
# -----------------------------------------------------------------------------
echo "============================================================"
echo " US-12 Sandbox Isolation Verification (NFR3.1 - NFR3.4)"
echo "============================================================"
echo ""

# Probe program verification: user_id_probe.py (NFR3.2)
echo "[isolation] Non-root user probe (NFR3.2)..."
UID_OUT=$(docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  "collab-sandbox-python:0.2.0" python /sandbox/code/user_id_probe.py 2>/dev/null; echo "exit:$?") \
  || true
UID_EXIT=$(echo "$UID_OUT" | grep -o 'exit:[0-9]*' | cut -d: -f2)
UID_STDOUT=$(echo "$UID_OUT" | grep -v 'exit:')

if [ "$UID_EXIT" = "0" ] && echo "$UID_STDOUT" | grep -q "NON-ROOT USER"; then
  check_pass "Non-root user probe — user_id_probe.py confirmed UID != 0 (NFR3.2)"
else
  check_fail "Non-root user probe (NFR3.2)" "Unexpected exit=$UID_EXIT stdout='$UID_STDOUT'"
fi
echo ""

# Read-only code directory (NFR3.3)
echo "[isolation] Read-only code directory (NFR3.3)..."
WROUT=$(docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  "collab-sandbox-python:0.2.0" python /sandbox/code/write_code_dir_probe.py 2>/dev/null; echo "exit:$?") \
  || true
WR_EXIT=$(echo "$WROUT" | grep -o 'exit:[0-9]*' | cut -d: -f2)
WR_STDOUT=$(echo "$WROUT" | grep -v 'exit:')

if [ "$WR_EXIT" = "1" ] && echo "$WR_STDOUT" | grep -q "WRITE BLOCKED"; then
  check_pass "Read-only code mount — writing to /sandbox/code rejected as expected (NFR3.3)"
elif [ "$WR_EXIT" = "2" ]; then
  check_fail "Read-only code mount (NFR3.3)" "Write SUCCEEDED — code directory is not read-only"
else
  check_fail "Read-only code mount (NFR3.3)" "Unexpected exit=$WR_EXIT stdout='$WR_STDOUT'"
fi
echo ""

# Read-only root filesystem (NFR3.3)
echo "[isolation] Read-only root filesystem (NFR3.3)..."
FS_EXIT=0
docker run --rm --network none --read-only --tmpfs /tmp:rw,noexec,nosuid,size=16m \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  "collab-sandbox-python:0.2.0" sh -c 'touch /etc/test_ro 2>/dev/null' || FS_EXIT=$?
if [ "$FS_EXIT" != "0" ]; then
  check_pass "Read-only root filesystem — modifying root directories rejected (NFR3.3)"
else
  check_fail "Read-only root filesystem (NFR3.3)" "Write outside /tmp succeeded under --read-only"
fi
echo ""

# Host filesystem isolation (NFR3.4)
echo "[isolation] Host filesystem isolation (NFR3.4)..."
HOST_OUT=$(docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  "collab-sandbox-python:0.2.0" python /sandbox/code/list_root_probe.py 2>/dev/null; echo "exit:$?") \
  || true
HOST_EXIT=$(echo "$HOST_OUT" | grep -o 'exit:[0-9]*' | cut -d: -f2)
HOST_STDOUT=$(echo "$HOST_OUT" | grep -v 'exit:')

if [ "$HOST_EXIT" = "0" ] && echo "$HOST_STDOUT" | grep -q "ROOT ISOLATED"; then
  check_pass "Host filesystem isolation — no host paths or sensitive files accessible (NFR3.4)"
else
  check_fail "Host filesystem isolation (NFR3.4)" "Unexpected exit=$HOST_EXIT stdout='$HOST_STDOUT'"
fi
echo ""

# Cross-run isolation (NFR3.4)
echo "[isolation] Cross-run isolation (NFR3.4)..."
XR_OUT=$(docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  "collab-sandbox-python:0.2.0" python /sandbox/code/read_other_run_probe.py 2>/dev/null; echo "exit:$?") \
  || true
XR_EXIT=$(echo "$XR_OUT" | grep -o 'exit:[0-9]*' | cut -d: -f2)
XR_STDOUT=$(echo "$XR_OUT" | grep -v 'exit:')

if [ "$XR_EXIT" = "0" ] && echo "$XR_STDOUT" | grep -q "RUN ISOLATED"; then
  check_pass "Cross-run isolation — run cannot see other runs' workspaces (NFR3.4)"
else
  check_fail "Cross-run isolation (NFR3.4)" "Unexpected exit=$XR_EXIT stdout='$XR_STDOUT'"
fi
echo ""

# =============================================================================
# SUMMARY
# -----------------------------------------------------------------------------
echo "============================================================"
echo " Results: ${PASS} passed, ${FAIL} failed"
echo "============================================================"
for r in "${RESULTS[@]}"; do
  echo -e "  $r"
done
echo ""

if [ "$FAIL" -eq 0 ]; then
  echo -e "${GREEN}All checks passed for all five languages.${RESET}"
  exit 0
else
  echo -e "${RED}${FAIL} check(s) failed. Review the output above.${RESET}"
  exit 1
fi
