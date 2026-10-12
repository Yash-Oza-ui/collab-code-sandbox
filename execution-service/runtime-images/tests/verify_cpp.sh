#!/usr/bin/env bash
# =============================================================================
# T-14 C++ runtime-image verification script (Quincy's checks)
# File: execution-service/runtime-images/tests/verify_cpp.sh
#
# Purpose:
#   Runs each acceptance-criterion check against the C++ runtime image.
#   Proves the second-language extension procedure (DoD item 3).
#   Run this script AFTER building the image with:
#     docker build -t collab-sandbox-cpp:0.1.0 \
#                  execution-service/runtime-images/cpp
#
# Usage (from the repository root):
#   bash execution-service/runtime-images/tests/verify_cpp.sh
# =============================================================================

set -euo pipefail

export MSYS_NO_PATHCONV=1

IMAGE="${IMAGE:-collab-sandbox-cpp:0.1.0}"
SAMPLE_DIR="execution-service/runtime-images/cpp/samples"
PASS=0
FAIL=0
RESULTS=()

if [ -t 1 ]; then
  GREEN="\033[0;32m"; RED="\033[0;31m"; YELLOW="\033[0;33m"; RESET="\033[0m"
else
  GREEN=""; RED=""; YELLOW=""; RESET=""
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

echo "============================================================"
echo " T-14 C++ runtime-image verification — image: $IMAGE"
echo "============================================================"
echo ""

# CHECK 0: Docker daemon is reachable
echo "[0] Checking Docker daemon..."
if ! docker info > /dev/null 2>&1; then
  echo -e "${RED}FATAL${RESET}: Docker daemon is not running. Cannot proceed."
  exit 1
fi
echo "    Docker daemon: OK"
echo ""

# CHECK 1: Image exists
echo "[1] Image exists (build check)..."
if docker image inspect "$IMAGE" > /dev/null 2>&1; then
  check_pass "Image '$IMAGE' exists (AC2)"
else
  check_fail "Image '$IMAGE' exists (AC2)" \
    "Image not found. Build it first: docker build -t $IMAGE execution-service/runtime-images/cpp"
fi
echo ""

# CHECK 2: Hello-world (no stdin)
echo "[2] Hello-world (no stdin)..."
CMD="sh -c 'g++ -O2 -std=c++20 /sandbox/code/hello_world.cpp -o /sandbox/run/hello && /sandbox/run/hello'"
ACTUAL=$(docker run --rm --network none \
  --tmpfs /sandbox/run:rw,noexec=false,size=64m \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" eval "$CMD" 2>/dev/null || true)
if [ "$ACTUAL" = "Hello, Sandbox!" ]; then
  check_pass "Hello-world (no stdin) — stdout='Hello, Sandbox!' exit=0 (AC3, AC4a)"
else
  check_fail "Hello-world (no stdin) (AC3, AC4a)" "Expected 'Hello, Sandbox!', got '$ACTUAL'"
fi
echo ""

# CHECK 3: Hello-world (with stdin, ignored)
echo "[3] Hello-world (with stdin, ignored)..."
CMD="sh -c 'g++ -O2 -std=c++20 /sandbox/code/hello_world.cpp -o /sandbox/run/hello && /sandbox/run/hello'"
ACTUAL=$(echo "ignored input" | docker run --rm -i --network none \
  --tmpfs /sandbox/run:rw,noexec=false,size=64m \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" eval "$CMD" 2>/dev/null || true)
if [ "$ACTUAL" = "Hello, Sandbox!" ]; then
  check_pass "Hello-world (stdin ignored) — stdout='Hello, Sandbox!' exit=0 (AC4b)"
else
  check_fail "Hello-world (stdin ignored) (AC4b)" "Expected 'Hello, Sandbox!', got '$ACTUAL'"
fi
echo ""

# CHECK 4: stdin piped to program (uppercase echo)
echo "[4] stdin pipe (uppercase echo)..."
CMD="sh -c 'g++ -O2 -std=c++20 /sandbox/code/stdin_echo.cpp -o /sandbox/run/stdin_echo && /sandbox/run/stdin_echo'"
ACTUAL=$(echo "hello sandbox" | docker run --rm -i --network none \
  --tmpfs /sandbox/run:rw,noexec=false,size=64m \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" eval "$CMD" 2>/dev/null || true)
if [ "$ACTUAL" = "HELLO SANDBOX" ]; then
  check_pass "stdin pipe — stdout='HELLO SANDBOX' exit=0 (AC5)"
else
  check_fail "stdin pipe (AC5)" "Expected 'HELLO SANDBOX', got '$ACTUAL'"
fi
echo ""

# CHECK 5: stdout and stderr captured independently
echo "[5] stdout/stderr independence..."
TMP_ERR=$(mktemp /tmp/t14_cpp_stderr_XXXXXX)
CMD="sh -c 'g++ -O2 -std=c++20 /sandbox/code/stderr_split.cpp -o /sandbox/run/stderr_split && /sandbox/run/stderr_split'"
STDOUT_ACTUAL=$(docker run --rm --network none \
  --tmpfs /sandbox/run:rw,noexec=false,size=64m \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" eval "$CMD" 2>"$TMP_ERR" || true)
STDERR_ACTUAL=$(cat "$TMP_ERR")
rm -f "$TMP_ERR"
if [ "$STDOUT_ACTUAL" = "STDOUT line" ] && [ "$STDERR_ACTUAL" = "STDERR line" ]; then
  check_pass "stdout/stderr independence — each stream captured separately (AC5)"
else
  check_fail "stdout/stderr independence (AC5)" \
    "Expected stdout='STDOUT line' stderr='STDERR line', got stdout='$STDOUT_ACTUAL' stderr='$STDERR_ACTUAL'"
fi
echo ""

# CHECK 6: Nonzero exit code preserved
echo "[6] Nonzero exit code preserved..."
CMD="sh -c 'g++ -O2 -std=c++20 /sandbox/code/exit_nonzero.cpp -o /sandbox/run/exit_nonzero && /sandbox/run/exit_nonzero'"
EXIT_CODE=0
docker run --rm --network none \
  --tmpfs /sandbox/run:rw,noexec=false,size=64m \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" eval "$CMD" > /dev/null 2>&1 || EXIT_CODE=$?
if [ "$EXIT_CODE" -eq 42 ]; then
  check_pass "Nonzero exit code — exit code 42 preserved (AC5)"
else
  check_fail "Nonzero exit code (AC5)" "Expected exit=42, got exit=$EXIT_CODE"
fi
echo ""

# CHECK 7: Non-root user
echo "[7] Non-root user..."
UID_ACTUAL=$(docker run --rm --network none "$IMAGE" id -u 2>/dev/null || true)
USER_ACTUAL=$(docker run --rm --network none "$IMAGE" id -un 2>/dev/null || true)
if [ -n "$UID_ACTUAL" ] && [ "$UID_ACTUAL" -ne 0 ]; then
  check_pass "Non-root user — UID=$UID_ACTUAL (user '$USER_ACTUAL'), not root (NFR3.2)"
else
  check_fail "Non-root user (NFR3.2)" "Expected non-zero UID, got '$UID_ACTUAL'"
fi
echo ""

# CHECK 8: Network isolation
echo "[8] Network isolation..."
CMD="sh -c 'g++ -O2 -std=c++20 /sandbox/code/network_probe.cpp -o /sandbox/run/network_probe && /sandbox/run/network_probe'"
NET_OUT=""
NET_EXIT=0
NET_OUT=$(docker run --rm --network none \
  --tmpfs /sandbox/run:rw,noexec=false,size=64m \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" eval "$CMD" 2>/dev/null || NET_EXIT=$?)
if [ "$NET_EXIT" -eq 1 ] && echo "$NET_OUT" | grep -q "NETWORK BLOCKED"; then
  check_pass "Network isolation — connection blocked as expected (NFR3.1)"
else
  check_fail "Network isolation (NFR3.1)" "Expected exit=1 with NETWORK BLOCKED, got exit=$NET_EXIT output='$NET_OUT'"
fi
echo ""

# CHECK 9: Source code not baked into image
echo "[9] Source code not baked into image..."
BAKED=$(docker run --rm --network none "$IMAGE" \
  sh -c 'test -f /sandbox/code/hello_world.cpp && echo FOUND || echo ABSENT' 2>/dev/null || true)
if [ "$BAKED" = "ABSENT" ]; then
  check_pass "Code not baked in — /sandbox/code/hello_world.cpp absent in bare image (FR5.2)"
else
  check_fail "Code not baked in (FR5.2)" "hello_world.cpp was found in bare image"
fi
echo ""

# CHECK 10: Container cleanup
echo "[10] Container cleanup..."
UNIQUE_NAME="t14-cpp-cleanup-$$"
CMD="sh -c 'g++ -O2 -std=c++20 /sandbox/code/hello_world.cpp -o /sandbox/run/hello && /sandbox/run/hello'"
docker run --rm --network none --name "$UNIQUE_NAME" \
  --tmpfs /sandbox/run:rw,noexec=false,size=64m \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" eval "$CMD" > /dev/null 2>&1 || true

if [ -z "$(docker ps -a --filter "name=$UNIQUE_NAME" --format '{{.Names}}')" ]; then
  check_pass "Container cleanup — container removed after exit (FR5.2)"
else
  check_fail "Container cleanup (FR5.2)" "Container '$UNIQUE_NAME' still exists after run"
  docker rm -f "$UNIQUE_NAME" > /dev/null 2>&1 || true
fi
echo ""

# CHECK 11: Image size
echo "[11] Image size (informational)..."
RAW_SIZE=$(docker image inspect "$IMAGE" --format '{{.Size}}' 2>/dev/null || true)
if [ -n "$RAW_SIZE" ]; then
  SIZE_MB=$(awk "BEGIN {printf \"%.1f\", $RAW_SIZE / 1048576}")
  check_pass "Image size: ${SIZE_MB} MB — record this in the README (AC8)"
else
  check_fail "Image size (AC8)" "Could not determine image size"
fi
echo ""

echo "============================================================"
echo " Results: $PASS passed, $FAIL failed"
echo "============================================================"
for r in "${RESULTS[@]}"; do
  echo -e "  $r"
done
echo ""

if [ "$FAIL" -eq 0 ]; then
  echo -e "${GREEN}All C++ checks passed.${RESET}"
  exit 0
else
  echo -e "${RED}$FAIL check(s) failed.${RESET}"
  exit 1
fi
