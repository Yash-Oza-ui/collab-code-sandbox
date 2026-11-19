#!/usr/bin/env bash
# =============================================================================
# T-14 verification script
# File: execution-service/runtime-images/tests/verify.sh
#
# Purpose:
#   Runs each acceptance-criterion check against the Python runtime image.
#   Every check prints PASS or FAIL with the expected vs. actual result.
#   Run this script AFTER building the image with:
#     docker build -t collab-sandbox-python:0.2.0 \
#                  execution-service/runtime-images/python
#
# Usage (from the repository root):
#   bash execution-service/runtime-images/tests/verify.sh
#
# Prerequisites:
#   - Docker daemon is running
#   - Image collab-sandbox-python:0.2.0 has been built (see README)
#   - Script must be run from the repository root so relative paths work
#
# Environment variable (optional):
#   IMAGE   Override the image tag. Default: collab-sandbox-python:0.2.0
#
# Exit code:
#   0 if all checks pass
#   1 if any check fails
# =============================================================================

set -euo pipefail

# Ensure Git Bash on Windows does not rewrite POSIX container paths (e.g. /sandbox/code)
export MSYS_NO_PATHCONV=1

IMAGE="${IMAGE:-collab-sandbox-python:0.2.0}"
SAMPLE_DIR="execution-service/runtime-images/python/samples"
PASS=0
FAIL=0
RESULTS=()

# Colour codes (disabled when not a terminal)
if [ -t 1 ]; then
  GREEN="\033[0;32m"; RED="\033[0;31m"; YELLOW="\033[0;33m"; RESET="\033[0m"
else
  GREEN=""; RED=""; YELLOW=""; RESET=""
fi

# -----------------------------------------------------------------------------
# Helper: run_check <check-name> <expected-exit> <expected-stdout-pattern>
#   Runs a docker command captured in $CMD_ARGS (array).
#   Compares exit code and stdout against expectations.
# -----------------------------------------------------------------------------
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
echo " T-14 runtime-image verification — image: $IMAGE"
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

# -----------------------------------------------------------------------------
# CHECK 1: Image exists (build succeeded)
# Acceptance criterion 2: A Dockerfile exists and uses a pinned base version.
# -----------------------------------------------------------------------------
echo "[1] Image exists (build check)..."
if docker image inspect "$IMAGE" > /dev/null 2>&1; then
  check_pass "Image '$IMAGE' exists (AC2)"
else
  check_fail "Image '$IMAGE' exists (AC2)" \
    "Image not found. Build it first: docker build -t $IMAGE execution-service/runtime-images/python"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 2: Hello-world without stdin
# Acceptance criterion 3 + 4a: hello-world runs without stdin.
# Expected stdout: Hello, Sandbox!
# Expected exit:   0
# -----------------------------------------------------------------------------
echo "[2] Hello-world (no stdin)..."
STDOUT=$(docker run --rm --network none \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/hello_world.py 2>/dev/null) || HW_EXIT=$?
HW_EXIT="${HW_EXIT:-0}"

if [ "$STDOUT" = "Hello, Sandbox!" ] && [ "$HW_EXIT" = "0" ]; then
  check_pass "Hello-world (no stdin) — stdout='Hello, Sandbox!' exit=0 (AC3, AC4a)"
else
  check_fail "Hello-world (no stdin) (AC3, AC4a)" \
    "Expected stdout='Hello, Sandbox!' exit=0; got stdout='$STDOUT' exit=$HW_EXIT"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 3: Hello-world with stdin piped (ignored)
# Acceptance criterion 4b: runs correctly even when stdin is supplied.
# Expected stdout: Hello, Sandbox!   (stdin is ignored by hello_world.py)
# Expected exit:   0
# -----------------------------------------------------------------------------
echo "[3] Hello-world (with stdin, ignored)..."
STDOUT=$(echo "ignored input" | docker run --rm -i --network none \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/hello_world.py 2>/dev/null) || HW2_EXIT=$?
HW2_EXIT="${HW2_EXIT:-0}"

if [ "$STDOUT" = "Hello, Sandbox!" ] && [ "$HW2_EXIT" = "0" ]; then
  check_pass "Hello-world (stdin ignored) — stdout='Hello, Sandbox!' exit=0 (AC4b)"
else
  check_fail "Hello-world (stdin ignored) (AC4b)" \
    "Expected stdout='Hello, Sandbox!' exit=0; got stdout='$STDOUT' exit=$HW2_EXIT"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 4: stdin is correctly piped into the container
# Acceptance criterion 5: stdin convention.
# Input:           hello sandbox
# Expected stdout: HELLO SANDBOX
# Expected exit:   0
# -----------------------------------------------------------------------------
echo "[4] stdin pipe (uppercase echo)..."
STDOUT=$(echo "hello sandbox" | docker run --rm -i --network none \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/stdin_echo.py 2>/dev/null) || STDIN_EXIT=$?
STDIN_EXIT="${STDIN_EXIT:-0}"

if [ "$STDOUT" = "HELLO SANDBOX" ] && [ "$STDIN_EXIT" = "0" ]; then
  check_pass "stdin pipe — stdout='HELLO SANDBOX' exit=0 (AC5)"
else
  check_fail "stdin pipe (AC5)" \
    "Expected stdout='HELLO SANDBOX' exit=0; got stdout='$STDOUT' exit=$STDIN_EXIT"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 5: stdout captured independently from stderr
# Acceptance criterion 5: stdout/stderr separation.
# -----------------------------------------------------------------------------
echo "[5] stdout/stderr independence..."
CAPTURED_OUT=$(docker run --rm --network none \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/stderr_split.py 2>/tmp/t14_stderr.txt) || SPLIT_EXIT=$?
SPLIT_EXIT="${SPLIT_EXIT:-0}"
CAPTURED_ERR=$(cat /tmp/t14_stderr.txt)
rm -f /tmp/t14_stderr.txt

if [ "$CAPTURED_OUT" = "STDOUT line" ] && \
   [ "$CAPTURED_ERR" = "STDERR line" ] && \
   [ "$SPLIT_EXIT" = "0" ]; then
  check_pass "stdout/stderr independence — each stream captured separately (AC5)"
else
  check_fail "stdout/stderr independence (AC5)" \
    "Expected out='STDOUT line', err='STDERR line', exit=0; got out='$CAPTURED_OUT' err='$CAPTURED_ERR' exit=$SPLIT_EXIT"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 6: nonzero exit code is preserved
# Acceptance criterion 5: exit-code convention.
# The program exits 42; the runner must return 42, not 0.
# -----------------------------------------------------------------------------
echo "[6] Nonzero exit code preserved..."
NZ_EXIT=0
docker run --rm --network none \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/exit_nonzero.py > /dev/null 2>&1 || NZ_EXIT=$?

if [ "$NZ_EXIT" = "42" ]; then
  check_pass "Nonzero exit code — exit code 42 preserved (AC5)"
else
  check_fail "Nonzero exit code (AC5)" \
    "Expected exit=42; got exit=$NZ_EXIT"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 7: Running as non-root user
# NFR3.2: user is 'sandbox', not root.
# -----------------------------------------------------------------------------
echo "[7] Non-root user..."
WHOAMI=$(docker run --rm --network none "$IMAGE" id -u 2>/dev/null) || ID_EXIT=$?
ID_EXIT="${ID_EXIT:-0}"

if [ "$WHOAMI" != "0" ] && [ "$ID_EXIT" = "0" ]; then
  # Also retrieve username for display
  USERNAME=$(docker run --rm --network none "$IMAGE" id -un 2>/dev/null || echo "unknown")
  check_pass "Non-root user — UID=$WHOAMI (user '$USERNAME'), not root (NFR3.2)"
else
  check_fail "Non-root user (NFR3.2)" \
    "Expected UID != 0; got UID=$WHOAMI exit=$ID_EXIT"
fi

# Probe program verification: user_id_probe.py
UID_OUT=$(docker run --rm --network none \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/user_id_probe.py 2>/dev/null; echo "exit:$?") \
  || true
UID_EXIT=$(echo "$UID_OUT" | grep -o 'exit:[0-9]*' | cut -d: -f2)
UID_STDOUT=$(echo "$UID_OUT" | grep -v 'exit:')

if [ "$UID_EXIT" = "0" ] && echo "$UID_STDOUT" | grep -q "NON-ROOT USER"; then
  check_pass "Non-root user probe — user_id_probe.py confirmed UID != 0 (NFR3.2)"
else
  check_fail "Non-root user probe (NFR3.2)" \
    "Unexpected exit=$UID_EXIT stdout='$UID_STDOUT'"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 8: Network is blocked (--network none)
# NFR3.1: code must run with no network access.
# network_probe.py exits 1 when network is blocked (expected outcome).
# It exits 2 if the network was reachable (sandbox failure).
# -----------------------------------------------------------------------------
echo "[8] Network isolation (--network none)..."
NETOUT=$(docker run --rm --network none \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/network_probe.py 2>/dev/null; echo "exit:$?") \
  || true
NET_EXIT=$(echo "$NETOUT" | grep -o 'exit:[0-9]*' | cut -d: -f2)
NET_STDOUT=$(echo "$NETOUT" | grep -v 'exit:')

if [ "$NET_EXIT" = "1" ] && echo "$NET_STDOUT" | grep -q "NETWORK BLOCKED"; then
  check_pass "Network isolation — connection refused as expected (NFR3.1)"
elif [ "$NET_EXIT" = "2" ]; then
  check_fail "Network isolation (NFR3.1)" \
    "Network was REACHABLE — --network none did not isolate the container"
else
  check_fail "Network isolation (NFR3.1)" \
    "Unexpected exit=$NET_EXIT stdout='$NET_STDOUT'"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 8b: Read-only code directory mount
# NFR3.3: code directory is mounted read-only.
# write_code_dir_probe.py exits 1 when writing is blocked (expected).
# -----------------------------------------------------------------------------
echo "[8b] Read-only code directory (NFR3.3)..."
WROUT=$(docker run --rm --network none \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/write_code_dir_probe.py 2>/dev/null; echo "exit:$?") \
  || true
WR_EXIT=$(echo "$WROUT" | grep -o 'exit:[0-9]*' | cut -d: -f2)
WR_STDOUT=$(echo "$WROUT" | grep -v 'exit:')

if [ "$WR_EXIT" = "1" ] && echo "$WR_STDOUT" | grep -q "WRITE BLOCKED"; then
  check_pass "Read-only code mount — writing to /sandbox/code rejected as expected (NFR3.3)"
elif [ "$WR_EXIT" = "2" ]; then
  check_fail "Read-only code mount (NFR3.3)" \
    "Write SUCCEEDED — code directory is not read-only"
else
  check_fail "Read-only code mount (NFR3.3)" \
    "Unexpected exit=$WR_EXIT stdout='$WR_STDOUT'"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 8c: Read-only root filesystem (--read-only)
# NFR3.3: root filesystem is read-only.
# -----------------------------------------------------------------------------
echo "[8c] Read-only root filesystem (NFR3.3)..."
FS_EXIT=0
docker run --rm --network none --read-only --tmpfs /tmp:rw,noexec,nosuid,size=16m \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" sh -c 'touch /etc/test_ro 2>/dev/null' || FS_EXIT=$?
if [ "$FS_EXIT" != "0" ]; then
  check_pass "Read-only root filesystem — modifying root directories rejected (NFR3.3)"
else
  check_fail "Read-only root filesystem (NFR3.3)" \
    "Write outside /tmp succeeded under --read-only"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 8d: Host filesystem isolation
# NFR3.4: no host paths mounted; sensitive host/system paths cannot be reached.
# -----------------------------------------------------------------------------
echo "[8d] Host filesystem isolation (NFR3.4)..."
HOST_OUT=$(docker run --rm --network none \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/list_root_probe.py 2>/dev/null; echo "exit:$?") \
  || true
HOST_EXIT=$(echo "$HOST_OUT" | grep -o 'exit:[0-9]*' | cut -d: -f2)
HOST_STDOUT=$(echo "$HOST_OUT" | grep -v 'exit:')

if [ "$HOST_EXIT" = "0" ] && echo "$HOST_STDOUT" | grep -q "ROOT ISOLATED"; then
  check_pass "Host filesystem isolation — no host paths or sensitive files accessible (NFR3.4)"
else
  check_fail "Host filesystem isolation (NFR3.4)" \
    "Unexpected exit=$HOST_EXIT stdout='$HOST_STDOUT'"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 8e: Cross-run isolation
# NFR3.4: run cannot access another run's files or host workspaces.
# -----------------------------------------------------------------------------
echo "[8e] Cross-run isolation (NFR3.4)..."
XR_OUT=$(docker run --rm --network none \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/read_other_run_probe.py 2>/dev/null; echo "exit:$?") \
  || true
XR_EXIT=$(echo "$XR_OUT" | grep -o 'exit:[0-9]*' | cut -d: -f2)
XR_STDOUT=$(echo "$XR_OUT" | grep -v 'exit:')

if [ "$XR_EXIT" = "0" ] && echo "$XR_STDOUT" | grep -q "RUN ISOLATED"; then
  check_pass "Cross-run isolation — run cannot see other runs' workspaces (NFR3.4)"
else
  check_fail "Cross-run isolation (NFR3.4)" \
    "Unexpected exit=$XR_EXIT stdout='$XR_STDOUT'"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 9: Source code is NOT baked into the image
# FR5.2 / DR2: code is supplied per run, not embedded.
# Verify that the samples directory does NOT exist inside the image baseline.
# -----------------------------------------------------------------------------
echo "[9] Source code not baked into image..."
BAKED=$(docker run --rm --network none "$IMAGE" \
  sh -c 'test -f /sandbox/code/hello_world.py && echo FOUND || echo ABSENT' 2>/dev/null)

if [ "$BAKED" = "ABSENT" ]; then
  check_pass "Code not baked in — /sandbox/code/hello_world.py absent in bare image (FR5.2)"
else
  check_fail "Code not baked in (FR5.2)" \
    "hello_world.py was found inside the image at /sandbox/code — code must not be baked in"
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 10: Container cleanup (--rm flag)
# FR5.2: the container is destroyed after the run.
# We run a container with a known unique name, verify it is gone after exit.
# -----------------------------------------------------------------------------
echo "[10] Container cleanup..."
UNIQUE_NAME="t14-cleanup-$$"
docker run --rm --network none --name "$UNIQUE_NAME" \
  -v "$(pwd)/$SAMPLE_DIR:/sandbox/code:ro" \
  "$IMAGE" python /sandbox/code/hello_world.py > /dev/null 2>&1 || true

# Container should be gone now (--rm was passed)
RUNNING=$(docker ps -a --filter "name=^/${UNIQUE_NAME}$" --format '{{.Names}}')
if [ -z "$RUNNING" ]; then
  check_pass "Container cleanup — container removed after exit (FR5.2)"
else
  check_fail "Container cleanup (FR5.2)" \
    "Container '$UNIQUE_NAME' still exists after run"
  # Clean up regardless
  docker rm -f "$UNIQUE_NAME" > /dev/null 2>&1 || true
fi
echo ""

# -----------------------------------------------------------------------------
# CHECK 11: Image size (informational — not a pass/fail)
# Acceptance criterion 8: image size recorded in README.
# -----------------------------------------------------------------------------
echo "[11] Image size (informational)..."
SIZE=$(docker image inspect "$IMAGE" \
  --format '{{.Size}}' 2>/dev/null | awk '{printf "%.1f MB", $1/1048576}') || true
if [ -n "$SIZE" ]; then
  check_pass "Image size: $SIZE — record this in the README (AC8)"
  echo "    NOTE: Update README.md with the measured size above."
else
  check_skip "Image size" "Could not measure (image may not exist)"
fi
echo ""

# =============================================================================
# SUMMARY
# =============================================================================
echo "============================================================"
echo " Results: ${PASS} passed, ${FAIL} failed"
echo "============================================================"
for r in "${RESULTS[@]}"; do
  echo -e "  $r"
done
echo ""

if [ "$FAIL" -eq 0 ]; then
  echo -e "${GREEN}All checks passed.${RESET}"
  exit 0
else
  echo -e "${RED}${FAIL} check(s) failed. Review the output above.${RESET}"
  exit 1
fi
