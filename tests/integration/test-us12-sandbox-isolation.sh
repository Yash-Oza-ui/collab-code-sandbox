#!/usr/bin/env bash
# US-12 (NFR3.1 to NFR3.4) integration test: Sandbox isolation in execution-service.
#
#   bash tests/integration/test-us12-sandbox-isolation.sh
#
# Requires execution-service running (started by up.sh).
# Skipped automatically if execution-service has no Dockerfile yet.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SERVICE="execution-service"

if ! service_present "$SERVICE"; then
  skip "$SERVICE" "(no Dockerfile yet - US-12 sandbox isolation test skipped)"
  exit 0
fi

BASE="$(service_url "$SERVICE")"
RUNS_URL="$BASE/v1/runs"
HEALTH_URL="$BASE/actuator/health"

failed=0

check() {
  local label="$1" want="$2" got="$3"
  if [ "$got" = "$want" ]; then
    pass "$label" "= $got"
  else
    fail "$label" "expected '$want', got '$got'"
    failed=$((failed + 1))
  fi
}

check_contains() {
  local label="$1" needle="$2" haystack="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    pass "$label" "contains '$needle'"
  else
    fail "$label" "expected to contain '$needle', got '$haystack'"
    failed=$((failed + 1))
  fi
}

check_not_contains() {
  local label="$1" needle="$2" haystack="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    pass "$label" "does not contain '$needle'"
  else
    fail "$label" "expected NOT to contain '$needle', but found in '$haystack'"
    failed=$((failed + 1))
  fi
}

new_uuid() {
  printf -v REPLY '%04x%04x-%04x-4%03x-%x%03x-%04x%04x%04x' \
    $RANDOM $RANDOM $RANDOM $((RANDOM % 4096)) $((8 + RANDOM % 4)) $((RANDOM % 4096)) \
    $RANDOM $RANDOM $RANDOM
}

# ── Step 1: Confirm service health ──────────────────────────────────────────

health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  fail "health" "$HEALTH_URL -> $health_code (service not up?)"
  exit 1
fi
pass "health" "$HEALTH_URL -> 200"

ROOM_ID="room-us12-isolation"

# ── Case 1: Execution has no network access (NFR3.1) ────────────────────────
# A program opening an outbound network socket must fail.

new_uuid; REQ_ID_NET="$REPLY"
new_uuid; SESS_ID_NET="$REPLY"
NET_BODY=$(cat <<EOF
{
  "request_id": "$REQ_ID_NET",
  "room_id":    "$ROOM_ID",
  "session_id": "$SESS_ID_NET",
  "language":   "python",
  "source": {
    "kind": "inline",
    "files": [ {
      "name": "main.py",
      "content": "import socket, sys\ntry:\n    socket.create_connection(('8.8.8.8', 53), timeout=3).close()\n    print('NETWORK REACHABLE')\n    sys.exit(2)\nexcept OSError:\n    print('NETWORK BLOCKED')\n    sys.exit(1)\n"
    } ],
    "entry": "main.py"
  }
}
EOF
)

NET_OUT="$(mktemp)"
net_code="$(curl -s -o "$NET_OUT" -w '%{http_code}' --max-time 15 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$NET_BODY" 2>/dev/null || echo "000")"

net_body="$(cat "$NET_OUT")"
rm -f "$NET_OUT"

check "network-http-status" "200" "$net_code"
check_contains "network-blocked-stdout" "NETWORK BLOCKED" "$net_body"
check_not_contains "network-not-reachable" "NETWORK REACHABLE" "$net_body"
check_contains "network-exit-code" '"exit_code":1' "$net_body"

# ── Case 2: Code runs as non-root user (NFR3.2) ──────────────────────────────
# Runtime UID must not be 0 (runs as UID 1001 sandbox user).

new_uuid; REQ_ID_USER="$REPLY"
new_uuid; SESS_ID_USER="$REPLY"
USER_BODY=$(cat <<EOF
{
  "request_id": "$REQ_ID_USER",
  "room_id":    "$ROOM_ID",
  "session_id": "$SESS_ID_USER",
  "language":   "python",
  "source": {
    "kind": "inline",
    "files": [ {
      "name": "main.py",
      "content": "import os, sys\nuid = os.getuid()\ngid = os.getgid()\nif uid == 0:\n    print('ROOT USER')\n    sys.exit(2)\nprint(f'NON_ROOT UID={uid} GID={gid}')\n"
    } ],
    "entry": "main.py"
  }
}
EOF
)

USER_OUT="$(mktemp)"
user_code="$(curl -s -o "$USER_OUT" -w '%{http_code}' --max-time 15 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$USER_BODY" 2>/dev/null || echo "000")"

user_body="$(cat "$USER_OUT")"
rm -f "$USER_OUT"

check "user-http-status" "200" "$user_code"
check_contains "user-non-root-stdout" "NON_ROOT UID=1001" "$user_body"
check_not_contains "user-not-root" "ROOT USER" "$user_body"
check_contains "user-exit-code" '"exit_code":0' "$user_body"

# ── Case 3: Code directory is mounted read-only (NFR3.3) ─────────────────────
# Writing to /sandbox/code must raise an error and fail.

new_uuid; REQ_ID_RO="$REPLY"
new_uuid; SESS_ID_RO="$REPLY"
RO_BODY=$(cat <<EOF
{
  "request_id": "$REQ_ID_RO",
  "room_id":    "$ROOM_ID",
  "session_id": "$SESS_ID_RO",
  "language":   "python",
  "source": {
    "kind": "inline",
    "files": [ {
      "name": "main.py",
      "content": "import sys\nfor path in ['/sandbox/code/evil.txt', 'evil.txt']:\n    try:\n        with open(path, 'w') as f:\n            f.write('untrusted')\n        print('WRITE SUCCEEDED')\n        sys.exit(2)\n    except OSError:\n        pass\nprint('WRITE BLOCKED')\nsys.exit(1)\n"
    } ],
    "entry": "main.py"
  }
}
EOF
)

RO_OUT="$(mktemp)"
ro_code="$(curl -s -o "$RO_OUT" -w '%{http_code}' --max-time 15 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$RO_BODY" 2>/dev/null || echo "000")"

ro_body="$(cat "$RO_OUT")"
rm -f "$RO_OUT"

check "ro-http-status" "200" "$ro_code"
check_contains "ro-write-blocked-stdout" "WRITE BLOCKED" "$ro_body"
check_not_contains "ro-not-succeeded" "WRITE SUCCEEDED" "$ro_body"
check_contains "ro-exit-code" '"exit_code":1' "$ro_body"

# ── Case 4: Temporary file isolation across runs (NFR3.4) ────────────────────
# Run 1 writes a temporary file; Run 2 must not see it.

new_uuid; REQ_ID_R1="$REPLY"
new_uuid; SESS_ID_R1="$REPLY"
R1_BODY=$(cat <<EOF
{
  "request_id": "$REQ_ID_R1",
  "room_id":    "$ROOM_ID",
  "session_id": "$SESS_ID_R1",
  "language":   "python",
  "source": {
    "kind": "inline",
    "files": [ {
      "name": "main.py",
      "content": "with open('/tmp/secret_token.txt', 'w') as f:\n    f.write('run1_secret')\nprint('RUN1_WRITTEN')\n"
    } ],
    "entry": "main.py"
  }
}
EOF
)

R1_OUT="$(mktemp)"
r1_code="$(curl -s -o "$R1_OUT" -w '%{http_code}' --max-time 15 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$R1_BODY" 2>/dev/null || echo "000")"

r1_body="$(cat "$R1_OUT")"
rm -f "$R1_OUT"

check "run1-http-status" "200" "$r1_code"
check_contains "run1-stdout" "RUN1_WRITTEN" "$r1_body"

new_uuid; REQ_ID_R2="$REPLY"
new_uuid; SESS_ID_R2="$REPLY"
R2_BODY=$(cat <<EOF
{
  "request_id": "$REQ_ID_R2",
  "room_id":    "$ROOM_ID-separate",
  "session_id": "$SESS_ID_R2",
  "language":   "python",
  "source": {
    "kind": "inline",
    "files": [ {
      "name": "main.py",
      "content": "import os\nif os.path.exists('/tmp/secret_token.txt'):\n    print('FOUND OTHER RUN DATA')\nelse:\n    print('OTHER RUN DATA NOT VISIBLE')\n"
    } ],
    "entry": "main.py"
  }
}
EOF
)

R2_OUT="$(mktemp)"
r2_code="$(curl -s -o "$R2_OUT" -w '%{http_code}' --max-time 15 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$R2_BODY" 2>/dev/null || echo "000")"

r2_body="$(cat "$R2_OUT")"
rm -f "$R2_OUT"

check "run2-http-status" "200" "$r2_code"
check_contains "run2-isolated-stdout" "OTHER RUN DATA NOT VISIBLE" "$r2_body"
check_not_contains "run2-not-leaked" "FOUND OTHER RUN DATA" "$r2_body"
check_contains "run2-exit-code" '"exit_code":0' "$r2_body"

# ── Case 5: Host files and sensitive system files unreachable (NFR3.4) ───────
# No host directories mounted; sensitive root files are unreadable.

new_uuid; REQ_ID_HOST="$REPLY"
new_uuid; SESS_ID_HOST="$REPLY"
HOST_BODY=$(cat <<EOF
{
  "request_id": "$REQ_ID_HOST",
  "room_id":    "$ROOM_ID",
  "session_id": "$SESS_ID_HOST",
  "language":   "python",
  "source": {
    "kind": "inline",
    "files": [ {
      "name": "main.py",
      "content": "import os, sys\nleak = False\nfor p in ['/var/run/docker.sock', '/host', '/host_root', '/tmp/collab-code-workspaces']:\n    if os.path.exists(p):\n        leak = True\nif os.path.exists('/etc/shadow'):\n    try:\n        with open('/etc/shadow', 'rb') as f:\n            f.read(1)\n        leak = True\n    except OSError:\n        pass\nif leak:\n    print('HOST LEAK')\n    sys.exit(2)\nprint('HOST ISOLATED')\n"
    } ],
    "entry": "main.py"
  }
}
EOF
)

HOST_OUT="$(mktemp)"
host_code="$(curl -s -o "$HOST_OUT" -w '%{http_code}' --max-time 15 \
  -X POST "$RUNS_URL" \
  -H "Content-Type: application/json" \
  -d "$HOST_BODY" 2>/dev/null || echo "000")"

host_body="$(cat "$HOST_OUT")"
rm -f "$HOST_OUT"

check "host-http-status" "200" "$host_code"
check_contains "host-isolated-stdout" "HOST ISOLATED" "$host_body"
check_not_contains "host-not-leaked" "HOST LEAK" "$host_body"
check_contains "host-exit-code" '"exit_code":0' "$host_body"

# ── Final result ─────────────────────────────────────────────────────────────

if [ "$failed" -gt 0 ]; then
  echo "US-12 SANDBOX-ISOLATION TEST FAILED ($failed assertion(s) failed)"
  exit 1
fi

echo "US-12 SANDBOX-ISOLATION TEST PASSED"
exit 0
