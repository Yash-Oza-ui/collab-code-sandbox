#!/usr/bin/env bash
# test-us52-run-record.sh: verify US-52 / FR29 run-record persistence on result delivery,
# secret/session exclusion, input_hash filtering newest-first (US-68), and delete-by-room (FR32 / US-56).
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

service_present sync-service || { skip us52-run-record "sync-service not built yet"; exit 0; }

base="$(service_url sync-service)"
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  if [ "${CI:-false}" = "true" ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
    fail us52-run-record "sync-service not running on $base (actuator/health returned $health_code in CI)"
    exit 1
  fi
  skip us52-run-record "sync-service not running on $base (start with tests/integration/up.sh)"
  exit 0
fi

token="${INTERNAL_API_TOKEN}"

PYTHON_BIN="python3"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1 || ! "$PYTHON_BIN" --version >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

do_req() {
  local method="$1" url="$2" hdr="$3" data="$4"
  local cmd=(curl -sS --max-time 10 -w "\nHTTP_STATUS:%{http_code}" -X "$method" "$url")
  if [ -n "$hdr" ]; then cmd+=(-H "$hdr"); fi
  if [ -n "$data" ]; then
    cmd+=(-H "Content-Type: application/json" -d "$data")
  fi
  "${cmd[@]}"
}

# Create two rooms (owner tokens stay in variables and are never printed)
create1="$(do_req POST "$base/v1/rooms" "" '{"mode":"pair","preset":"debug_together"}')"
status1="${create1##*HTTP_STATUS:}"
body1="${create1%$'\n'HTTP_STATUS:*}"
if [ "$status1" != "201" ]; then
  fail us52-run-record "failed to create room 1: HTTP $status1"
  exit 1
fi
ROOM1="$("$PYTHON_BIN" -c 'import sys,json; print(json.loads(sys.argv[1])["room_id"])' "$body1")"
OWNER1="$("$PYTHON_BIN" -c 'import sys,json; print(json.loads(sys.argv[1])["owner_token"])' "$body1")"

create2="$(do_req POST "$base/v1/rooms" "" '{"mode":"pair","preset":"debug_together"}')"
status2="${create2##*HTTP_STATUS:}"
body2="${create2%$'\n'HTTP_STATUS:*}"
if [ "$status2" != "201" ]; then
  fail us52-run-record "failed to create room 2: HTTP $status2"
  exit 1
fi
ROOM2="$("$PYTHON_BIN" -c 'import sys,json; print(json.loads(sys.argv[1])["room_id"])' "$body2")"

# 1. Auth checks on GET and DELETE /internal/rooms/{room_id}/runs
r="$(do_req GET "$base/internal/rooms/$ROOM1/runs" "" "")"
[ "${r##*HTTP_STATUS:}" = "401" ] || { fail us52-run-record "GET /runs without token should be 401"; exit 1; }

r="$(do_req GET "$base/internal/rooms/$ROOM1/runs" "X-Internal-Token: wrong-token" "")"
[ "${r##*HTTP_STATUS:}" = "401" ] || { fail us52-run-record "GET /runs with wrong token should be 401"; exit 1; }

r="$(do_req DELETE "$base/internal/rooms/$ROOM1/runs" "X-Internal-Token: wrong-token" "")"
[ "${r##*HTTP_STATUS:}" = "401" ] || { fail us52-run-record "DELETE /runs with wrong token should be 401"; exit 1; }
pass us52-run-record "GET/DELETE /internal/rooms/{id}/runs enforce X-Internal-Token (401)"

# Compute canonical SHA-256 input hashes in Python (matches InputHasher)
HASH_A="$("$PYTHON_BIN" -c 'import hashlib; print(hashlib.sha256(b"same-stdin-input\n").hexdigest())')"
HASH_B="$("$PYTHON_BIN" -c 'import hashlib; print(hashlib.sha256(b"different-stdin-input\n").hexdigest())')"
SECRET_SESSION="99999999-9999-4999-8999-999999999999"
RAW_STDIN="same-stdin-input"
SOURCE_CODE="print('SECRET_SOURCE_CODE_US52')"

# 2. Deliver non-run-result message (must NOT be recorded)
r="$(do_req POST "$base/internal/rooms/$ROOM1/messages" "X-Internal-Token: $token" \
  "{\"type\":\"module-updated\",\"room_id\":\"$ROOM1\",\"module_id\":\"mod_1\"}")"
[ "${r##*HTTP_STATUS:}" = "200" ] || { fail us52-run-record "module-updated delivery failed"; exit 1; }

# 3. Deliver two runs with HASH_A (different timestamps) and one run with HASH_B to ROOM1,
#    including synthetic secrets (owner_token, runner.session_id, raw stdin, file content) to verify stripping.
payload_1="$(cat <<EOF
{
  "audience": { "kind": "room" },
  "type": "run-result",
  "request_id": "10000000-0000-4000-8000-000000000001",
  "room_id": "$ROOM1",
  "owner_token": "$OWNER1",
  "session_id": "$SECRET_SESSION",
  "stdin": "$RAW_STDIN\n",
  "language": "python",
  "stdout": "ok\n",
  "stderr": "",
  "exit_code": 0,
  "execution_time_ms": 22.5,
  "peak_memory_mb": 7.1,
  "runner": {
    "member_id": "m_ada",
    "display_name": "Ada",
    "session_id": "$SECRET_SESSION"
  },
  "ran_at": "2026-11-01T10:00:01.000Z",
  "visibility": "room",
  "saved_versions": [],
  "files": [{ "name": "main.py", "content": "$SOURCE_CODE" }]
}
EOF
)"

payload_2="$(cat <<EOF
{
  "audience": { "kind": "room" },
  "type": "run-result",
  "request_id": "10000000-0000-4000-8000-000000000002",
  "room_id": "$ROOM1",
  "language": "python",
  "stdout": "ok\n",
  "stderr": "",
  "exit_code": 0,
  "execution_time_ms": 95.0,
  "peak_memory_mb": 9.3,
  "runner": { "member_id": "m_lin", "display_name": "Lin" },
  "ran_at": "2026-11-01T10:00:05.000Z",
  "visibility": "room",
  "saved_versions": [],
  "input_hash": "$HASH_B",
  "files": ["main.py"]
}
EOF
)"

payload_3="$(cat <<EOF
{
  "audience": { "kind": "room" },
  "type": "run-result",
  "request_id": "10000000-0000-4000-8000-000000000003",
  "room_id": "$ROOM1",
  "language": "python",
  "stdout": "ok\n",
  "stderr": "",
  "exit_code": 0,
  "execution_time_ms": 310.0,
  "peak_memory_mb": 11.4,
  "runner": { "member_id": "m_ada", "display_name": "Ada" },
  "ran_at": "2026-11-01T10:00:10.000Z",
  "visibility": "room",
  "saved_versions": [],
  "input_hash": "$HASH_A",
  "files": ["main.py"]
}
EOF
)"

payload_room2="$(cat <<EOF
{
  "audience": { "kind": "room" },
  "type": "run-result",
  "request_id": "10000000-0000-4000-8000-000000000004",
  "room_id": "$ROOM2",
  "language": "python",
  "stdout": "ok\n",
  "stderr": "",
  "exit_code": 0,
  "execution_time_ms": 15.0,
  "peak_memory_mb": 6.0,
  "runner": { "member_id": "m_bob", "display_name": "Bob" },
  "ran_at": "2026-11-01T10:00:15.000Z",
  "visibility": "room",
  "saved_versions": [],
  "input_hash": "$HASH_A",
  "files": ["app.py"]
}
EOF
)"

for p in "$payload_1" "$payload_2" "$payload_3"; do
  r="$(do_req POST "$base/internal/rooms/$ROOM1/messages" "X-Internal-Token: $token" "$p")"
  [ "${r##*HTTP_STATUS:}" = "200" ] || { fail us52-run-record "run-result delivery to ROOM1 failed"; exit 1; }
done
r="$(do_req POST "$base/internal/rooms/$ROOM2/messages" "X-Internal-Token: $token" "$payload_room2")"
[ "${r##*HTTP_STATUS:}" = "200" ] || { fail us52-run-record "run-result delivery to ROOM2 failed"; exit 1; }
pass us52-run-record "delivered run-result messages return 200 and write run records"

# 4. Query ROOM1 filtered by HASH_A -> 2 matching runs, newest first, all FR29 fields, zero secrets
r="$(do_req GET "$base/internal/rooms/$ROOM1/runs?input_hash=$HASH_A" "X-Internal-Token: $token" "")"
status="${r##*HTTP_STATUS:}"
body="${r%$'\n'HTTP_STATUS:*}"
[ "$status" = "200" ] || { fail us52-run-record "GET /runs?input_hash failed with HTTP $status"; exit 1; }

"$PYTHON_BIN" - "$body" "$ROOM1" "$HASH_A" "$OWNER1" "$SECRET_SESSION" "$RAW_STDIN" "$SOURCE_CODE" <<'PY'
import json, sys

body, room_id, hash_a, owner_token, secret_session, raw_stdin, source_code = sys.argv[1:8]

# Secret exclusion checks (AC2)
for forbidden in [owner_token, secret_session, raw_stdin, source_code, "session_id", "owner_token"]:
    if forbidden in body:
        sys.exit(f"Forbidden secret/field leaked into stored run record response: {forbidden[:12]}...")

data = json.loads(body)
assert data["room_id"] == room_id, f"unexpected room_id: {data}"
runs = data["runs"]
assert len(runs) == 2, f"expected 2 matching runs for HASH_A, got {len(runs)}"

# Newest first ordering (US-68)
assert runs[0]["ran_at"] == "2026-11-01T10:00:10.000Z", f"expected newest first, got {runs[0]['ran_at']}"
assert runs[0]["execution_time_ms"] == 310.0
assert runs[0]["peak_memory_mb"] == 11.4

assert runs[1]["ran_at"] == "2026-11-01T10:00:01.000Z"
assert runs[1]["runner_display_name"] == "Ada"
assert runs[1]["language"] == "python"
assert runs[1]["files"] == ["main.py"]
assert runs[1]["saved_versions"] == []
assert runs[1]["input_hash"] == hash_a
assert runs[1]["execution_time_ms"] == 22.5
assert runs[1]["peak_memory_mb"] == 7.1
PY
pass us52-run-record "GET /runs?input_hash returns matching runs newest-first with all FR29 fields and zero secrets"

# 5. Delete ROOM1 records by room key -> ROOM1 becomes empty, ROOM2 remains intact (FR32 / US-56)
r="$(do_req DELETE "$base/internal/rooms/$ROOM1/runs" "X-Internal-Token: $token" "")"
status="${r##*HTTP_STATUS:}"
body="${r%$'\n'HTTP_STATUS:*}"
[ "$status" = "200" ] || { fail us52-run-record "DELETE /runs failed with HTTP $status"; exit 1; }
"$PYTHON_BIN" -c 'import sys,json; d=json.loads(sys.argv[1]); assert d["deleted"]==3, d' "$body"

r="$(do_req GET "$base/internal/rooms/$ROOM1/runs" "X-Internal-Token: $token" "")"
body="${r%$'\n'HTTP_STATUS:*}"
"$PYTHON_BIN" -c 'import sys,json; d=json.loads(sys.argv[1]); assert len(d["runs"])==0, d' "$body"

r="$(do_req GET "$base/internal/rooms/$ROOM2/runs?input_hash=$HASH_A" "X-Internal-Token: $token" "")"
body="${r%$'\n'HTTP_STATUS:*}"
"$PYTHON_BIN" -c 'import sys,json; d=json.loads(sys.argv[1]); assert len(d["runs"])==1, d' "$body"

# Clean up ROOM2
do_req DELETE "$base/internal/rooms/$ROOM2/runs" "X-Internal-Token: $token" "" >/dev/null
pass us52-run-record "DELETE /runs deletes only target room records and preserves other rooms"
