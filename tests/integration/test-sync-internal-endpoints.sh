#!/usr/bin/env bash
# test-sync-internal-endpoints.sh: verify session lookup, internal targeted message delivery, and contract error codes (T-20, Issue #65, T-17 V2.10, V2.11, V2.14).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

base="$(service_url sync-service)"

# Skip cleanly if the sync-service folder has no Dockerfile yet.
service_present sync-service || { skip sync-service "not built yet"; exit 0; }

# Check service liveness; fail in CI, skip cleanly when running locally without containers.
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  if [ "${CI:-false}" = "true" ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
    fail sync-service "service not running on $base (actuator/health returned $health_code in CI)"
    exit 1
  fi
  skip sync-service "service not running on $base (start with tests/integration/up.sh)"
  exit 0
fi

token="${INTERNAL_API_TOKEN:-test-internal-token}"

# Detect Python binary
PYTHON_BIN="python3"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1 || ! "$PYTHON_BIN" --version >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

# Helper to assert contract error envelope via structured JSON validation
assert_json_error() {
  local json_str="$1"
  local expected_code="$2"
  local expected_req_id="${3:-}"
  "$PYTHON_BIN" -c "
import sys, json
try:
    data = json.loads(sys.argv[1])
    err = data.get('error', {})
    code = err.get('code')
    if code != sys.argv[2]:
        sys.exit(f'Expected error code {sys.argv[2]}, got {code}: {data}')
    if len(sys.argv) > 3 and sys.argv[3]:
        req_id = data.get('request_id')
        if req_id != sys.argv[3]:
            sys.exit(f'Expected request_id {sys.argv[3]}, got {req_id}: {data}')
except Exception as e:
    sys.exit(f'JSON parsing / assertion failed: {e}; raw: {sys.argv[1]}')
" "$json_str" "$expected_code" "$expected_req_id"
}

# Helper to execute curl and separate HTTP status and response body
do_request() {
  local method="$1" url="$2" hdr="$3" ct="$4" data="$5"
  local cmd=(curl -s -w "\nHTTP_STATUS:%{http_code}" -X "$method" "$url")
  if [ -n "$hdr" ]; then cmd+=(-H "$hdr"); fi
  if [ -n "$ct" ]; then cmd+=(-H "Content-Type: $ct"); fi
  if [ -n "$data" ]; then cmd+=(-d "$data"); fi
  "${cmd[@]}"
}

# 1. Auth check: session lookup without token -> 401 UNAUTHORIZED
resp="$(do_request GET "$base/internal/rooms/test-room/sessions/a0000000-0000-4000-8000-000000000001" "" "" "")"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "401" ]; then
  fail sync-service "expected 401 for session lookup without token, got $status: $body"
  exit 1
fi
assert_json_error "$body" "UNAUTHORIZED"
pass sync-service "session lookup rejects missing token with 401 UNAUTHORIZED"

# 2. Auth check: session lookup with invalid token -> 401 UNAUTHORIZED
resp="$(do_request GET "$base/internal/rooms/test-room/sessions/a0000000-0000-4000-8000-000000000001" "X-Internal-Token: wrong-token" "" "")"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "401" ]; then
  fail sync-service "expected 401 for session lookup with invalid token, got $status: $body"
  exit 1
fi
assert_json_error "$body" "UNAUTHORIZED"
pass sync-service "session lookup rejects invalid token with 401 UNAUTHORIZED"

# 3. Auth check: internal broadcast without token -> 401 UNAUTHORIZED
resp="$(do_request POST "$base/internal/rooms/test-room/messages" "" "application/json" '{"type":"run_result"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "401" ]; then
  fail sync-service "expected 401 for broadcast without token, got $status: $body"
  exit 1
fi
assert_json_error "$body" "UNAUTHORIZED"
pass sync-service "internal broadcast rejects missing token with 401 UNAUTHORIZED"

# 4. Auth check: internal broadcast with invalid token -> 401 UNAUTHORIZED
resp="$(do_request POST "$base/internal/rooms/test-room/messages" "X-Internal-Token: wrong-token" "application/json" '{"type":"run_result"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "401" ]; then
  fail sync-service "expected 401 for broadcast with invalid token, got $status: $body"
  exit 1
fi
assert_json_error "$body" "UNAUTHORIZED"
pass sync-service "internal broadcast rejects invalid token with 401 UNAUTHORIZED"

# 5. Create a room for HTTP lifecycle error tests (POST /v1/rooms)
create_resp="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base/v1/rooms" \
  -H "Content-Type: application/json" \
  -d '{"mode":"pair","preset":"debug_together"}')"
create_status="$(echo "$create_resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
create_body="$(echo "$create_resp" | grep -v 'HTTP_STATUS:')"
if [ "$create_status" != "201" ]; then
  fail sync-service "expected 201 on room creation, got $create_status: $create_body"
  exit 1
fi
room_id="$(echo "$create_body" | grep -o '"room_id":"[^"]*' | cut -d'"' -f4)"
if [ -z "$room_id" ]; then
  fail sync-service "could not extract room_id from: $create_body"
  exit 1
fi
pass sync-service "room created: $room_id"

# 6. Session lookup: nonexistent room -> 404 ROOM_NOT_FOUND
resp="$(do_request GET "$base/internal/rooms/nonexistent-room-99999/sessions/a0000000-0000-4000-8000-000000000001" "X-Internal-Token: $token" "" "")"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "404" ]; then
  fail sync-service "expected 404 for nonexistent room, got $status: $body"
  exit 1
fi
assert_json_error "$body" "ROOM_NOT_FOUND"
pass sync-service "session lookup returns 404 ROOM_NOT_FOUND on unknown room"

# 7. Session lookup: session not joined -> 404 SESSION_NOT_FOUND
resp="$(do_request GET "$base/internal/rooms/$room_id/sessions/a0000000-0000-4000-8000-000000000001" "X-Internal-Token: $token" "" "")"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "404" ]; then
  fail sync-service "expected 404 for unjoined session, got $status: $body"
  exit 1
fi
assert_json_error "$body" "SESSION_NOT_FOUND"
pass sync-service "session lookup returns 404 SESSION_NOT_FOUND on unjoined session"

# 8. Internal broadcast: nonexistent room -> 404 ROOM_NOT_FOUND
resp="$(do_request POST "$base/internal/rooms/nonexistent-room-99999/messages" "X-Internal-Token: $token" "application/json" '{"type":"run_result"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "404" ]; then
  fail sync-service "expected 404 for broadcast to nonexistent room, got $status: $body"
  exit 1
fi
assert_json_error "$body" "ROOM_NOT_FOUND"
pass sync-service "internal broadcast returns 404 ROOM_NOT_FOUND on unknown room"

# 9. Internal broadcast: unsupported Content-Type -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "text/plain" '{"type":"run_result"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ]; then
  fail sync-service "expected 400 on non-JSON Content-Type, got $status: $body"
  exit 1
fi
assert_json_error "$body" "INVALID_MESSAGE"
pass sync-service "internal broadcast rejects non-JSON Content-Type with 400 INVALID_MESSAGE"

# 10. Internal broadcast: empty body -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" "")"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ]; then
  fail sync-service "expected 400 on empty body, got $status: $body"
  exit 1
fi
assert_json_error "$body" "INVALID_MESSAGE"
pass sync-service "internal broadcast rejects empty body with 400 INVALID_MESSAGE"

# 11. Internal broadcast: malformed JSON -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"type": unquoted}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ]; then
  fail sync-service "expected 400 on malformed JSON, got $status: $body"
  exit 1
fi
assert_json_error "$body" "INVALID_MESSAGE"
pass sync-service "internal broadcast rejects malformed JSON with 400 INVALID_MESSAGE"

# 12. Internal broadcast: non-object JSON root -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '["not","an","object"]')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ]; then
  fail sync-service "expected 400 on non-object JSON root, got $status: $body"
  exit 1
fi
assert_json_error "$body" "INVALID_MESSAGE"
pass sync-service "internal broadcast rejects non-object root with 400 INVALID_MESSAGE"

# 13. Internal broadcast: missing type field -> 400 INVALID_MESSAGE with preserved request_id
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"request_id":"int-req-99","payload":"no-type"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ]; then
  fail sync-service "expected 400 preserving request_id, got $status: $body"
  exit 1
fi
assert_json_error "$body" "INVALID_MESSAGE" "int-req-99"
pass sync-service "internal broadcast rejects missing type with 400 INVALID_MESSAGE preserving request_id"

# 14. Internal broadcast: blank type field -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"type":"   "}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ]; then
  fail sync-service "expected 400 on blank type, got $status: $body"
  exit 1
fi
assert_json_error "$body" "INVALID_MESSAGE"
pass sync-service "internal broadcast rejects blank type with 400 INVALID_MESSAGE"

# 15. Internal broadcast: invalid audience kind -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"type":"run_result","audience":{"kind":"bogus"}}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ]; then
  fail sync-service "expected 400 on bogus audience kind, got $status: $body"
  exit 1
fi
assert_json_error "$body" "INVALID_MESSAGE"
pass sync-service "internal broadcast rejects unknown audience kind with 400 INVALID_MESSAGE"

# 16. Internal broadcast: audience sessions with empty session_ids -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"type":"run_result","audience":{"kind":"sessions","session_ids":[]}}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ]; then
  fail sync-service "expected 400 on empty session_ids array, got $status: $body"
  exit 1
fi
assert_json_error "$body" "INVALID_MESSAGE"
pass sync-service "internal broadcast rejects empty session_ids with 400 INVALID_MESSAGE"

# 17. Valid room broadcast -> 200 OK
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"type":"run_result","status":"success","output":"hello world"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "200" ] || ! echo "$body" | grep -q "delivered"; then
  fail sync-service "expected 200 with delivered on valid room broadcast, got $status: $body"
  exit 1
fi
pass sync-service "valid room broadcast returns 200 OK with delivered status"

# 18. Valid targeted session delivery -> 200 OK
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"type":"run_result","audience":{"kind":"sessions","session_ids":["a0000000-0000-4000-8000-000000000001"]}}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "200" ] || ! echo "$body" | grep -q "delivered"; then
  fail sync-service "expected 200 with delivered on valid targeted delivery, got $status: $body"
  exit 1
fi
pass sync-service "valid targeted session delivery returns 200 OK with delivered status"

# 19. Full-fidelity WebSocket integration: multi-tab, targeted delivery, audience stripping, room isolation
"$PYTHON_BIN" - "$base" "$token" <<'PY_EOF'
import sys, os, socket, base64, struct, json, urllib.request, urllib.error, time

base_url = sys.argv[1].rstrip('/')
token = sys.argv[2]

# Parse host and port from base_url
if '://' in base_url:
    proto, rest = base_url.split('://', 1)
else:
    rest = base_url
if '/' in rest:
    rest = rest.split('/', 1)[0]
if ':' in rest:
    host, port_str = rest.split(':')
    port = int(port_str)
else:
    host = rest
    port = 80

def http_post(path, body_obj, headers=None):
    url = f"{base_url}{path}"
    req_headers = {'Content-Type': 'application/json'}
    if headers:
        req_headers.update(headers)
    data = json.dumps(body_obj).encode('utf-8')
    req = urllib.request.Request(url, data=data, headers=req_headers, method='POST')
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8')
        try:
            parsed = json.loads(body)
        except Exception:
            parsed = body
        return e.code, parsed

def http_get(path, headers=None):
    url = f"{base_url}{path}"
    req_headers = {}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers, method='GET')
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8')
        try:
            parsed = json.loads(body)
        except Exception:
            parsed = body
        return e.code, parsed

class WsClient:
    def __init__(self, room_id, session_id):
        self.sock = socket.create_connection((host, port), timeout=5)
        key = base64.b64encode(os.urandom(16)).decode('ascii')
        req = (
            f"GET /ws/rooms/{room_id}?session_id={session_id} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Origin: http://localhost:3000\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(req.encode('utf-8'))
        
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("Connection closed during WebSocket handshake")
            resp += chunk
        
        status_line = resp.split(b"\r\n")[0].decode('latin1', errors='replace')
        if " 101 " not in status_line:
            raise ConnectionError(f"WebSocket upgrade failed: {status_line}")

    def send_json(self, obj):
        data = json.dumps(obj).encode('utf-8')
        length = len(data)
        mask = os.urandom(4)
        header = bytearray([0x81])
        if length <= 125:
            header.append(0x80 | length)
        elif length <= 65535:
            header.append(0x80 | 126)
            header.extend(struct.pack('>H', length))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack('>Q', length))
        header.extend(mask)
        masked = bytearray(b ^ mask[i % 4] for i, b in enumerate(data))
        self.sock.sendall(header + masked)

    def _read_exact(self, n):
        b = bytearray()
        while len(b) < n:
            chunk = self.sock.recv(n - len(b))
            if not chunk:
                raise EOFError("Socket closed")
            b.extend(chunk)
        return bytes(b)

    def recv_msg(self, timeout=3.0):
        self.sock.settimeout(timeout)
        try:
            while True:
                b1, b2 = self._read_exact(2)
                opcode = b1 & 0x0F
                masked = (b2 & 0x80) != 0
                length = b2 & 0x7F
                if length == 126:
                    length = struct.unpack('>H', self._read_exact(2))[0]
                elif length == 127:
                    length = struct.unpack('>Q', self._read_exact(8))[0]
                mask = self._read_exact(4) if masked else None
                payload = self._read_exact(length)
                if masked:
                    payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
                
                if opcode == 0x8: # close
                    return None
                elif opcode == 0x9: # ping -> pong
                    pong = bytearray([0x8A, 0x80 | len(payload)])
                    m = os.urandom(4)
                    pong.extend(m)
                    pong.extend(b ^ m[i % 4] for i, b in enumerate(payload))
                    self.sock.sendall(pong)
                    continue
                elif opcode == 0x1: # text frame
                    return json.loads(payload.decode('utf-8'))
        except (socket.timeout, TimeoutError):
            return None

    def drain(self, duration=0.2):
        deadline = time.time() + duration
        msgs = []
        while time.time() < deadline:
            remaining = max(0.01, deadline - time.time())
            msg = self.recv_msg(timeout=remaining)
            if msg:
                msgs.append(msg)
            else:
                break
        return msgs

    def close(self):
        try:
            close_frame = bytearray([0x88, 0x80]) + os.urandom(4)
            self.sock.sendall(close_frame)
            self.sock.close()
        except Exception:
            pass

# Step A: Create Room A (pair mode) and Room B (open mode)
st, r_a = http_post('/v1/rooms', {"mode": "pair", "preset": "debug_together"})
if st != 201 or 'room_id' not in r_a:
    sys.exit(f"Failed to create room A: {st} {r_a}")
room_a = r_a['room_id']
owner_token_a = r_a.get('owner_token')

st, r_b = http_post('/v1/rooms', {"mode": "open", "language": "python"})
if st != 201 or 'room_id' not in r_b:
    sys.exit(f"Failed to create room B: {st} {r_b}")
room_b = r_b['room_id']
owner_token_b = r_b.get('owner_token')

host_session = "11111111-1111-4111-8111-111111111111"
part_session = "22222222-2222-4222-8222-222222222222"
other_session = "33333333-3333-4333-8333-333333333333"

# Step B: Connect Tab 1 and Tab 2 (same host session in Room A)
tab1 = WsClient(room_a, host_session)
tab1.send_json({"type": "join", "display_name": "HostUser", "owner_token": owner_token_a})
tab1.drain(0.3)

tab2 = WsClient(room_a, host_session)
tab2.send_json({"type": "join", "display_name": "HostUser", "owner_token": owner_token_a})
tab2.drain(0.3)

# Step C: Connect Tab 3 (participant session in Room A)
tab3 = WsClient(room_a, part_session)
tab3.send_json({"type": "join", "display_name": "PartUser"})
tab3.drain(0.3)

# Step D: Connect Tab 4 (session in Room B)
tab4 = WsClient(room_b, other_session)
tab4.send_json({"type": "join", "display_name": "RoomBUser", "owner_token": owner_token_b})
tab4.drain(0.3)

# Step E: Verify GET /internal/rooms/{roomId}/sessions/{sessionId}
st, host_info = http_get(f"/internal/rooms/{room_a}/sessions/{host_session}", {"X-Internal-Token": token})
if st != 200:
    sys.exit(f"Expected 200 for host session lookup, got {st}: {host_info}")
assert "member_id" in host_info and host_info["member_id"].startswith("m_"), f"member_id invalid: {host_info}"
assert host_info["role"].lower() == "host", f"role mismatch: {host_info}"
assert host_info["display_name"] == "HostUser", f"display_name mismatch: {host_info}"
assert host_info["mode"].lower() == "pair", f"mode mismatch: {host_info}"
assert host_info["can_run"] is True, f"can_run mismatch: {host_info}"
assert host_info["ai_enabled"] is True, f"ai_enabled mismatch: {host_info}"
assert host_info.get("run_denied_reason") is None, f"run_denied_reason mismatch: {host_info}"

st, part_info = http_get(f"/internal/rooms/{room_a}/sessions/{part_session}", {"X-Internal-Token": token})
if st != 200:
    sys.exit(f"Expected 200 for part session lookup, got {st}: {part_info}")
assert "member_id" in part_info and part_info["member_id"].startswith("m_"), f"member_id invalid: {part_info}"
assert part_info["role"].lower() == "participant", f"role mismatch: {part_info}"
assert part_info["display_name"] == "PartUser", f"display_name mismatch: {part_info}"
assert part_info["mode"].lower() == "pair", f"mode mismatch: {part_info}"
assert part_info["ai_enabled"] is True, f"ai_enabled mismatch: {part_info}"

# Unknown session in Room A -> 404 SESSION_NOT_FOUND
st, err_info = http_get(f"/internal/rooms/{room_a}/sessions/00000000-0000-4000-8000-000000000000", {"X-Internal-Token": token})
if st != 404 or err_info.get("error", {}).get("code") != "SESSION_NOT_FOUND":
    sys.exit(f"Expected 404 SESSION_NOT_FOUND, got {st}: {err_info}")

# Room B session queried in Room A -> 404 SESSION_NOT_FOUND
st, err_info = http_get(f"/internal/rooms/{room_a}/sessions/{other_session}", {"X-Internal-Token": token})
if st != 404 or err_info.get("error", {}).get("code") != "SESSION_NOT_FOUND":
    sys.exit(f"Expected 404 SESSION_NOT_FOUND for cross-room session query, got {st}: {err_info}")

# Step F: Targeted Message Delivery (to host_session)
tab1.drain(0.1)
tab2.drain(0.1)
tab3.drain(0.1)
tab4.drain(0.1)

target_payload = {
    "type": "run_result",
    "run_id": "run-ws-int-999",
    "status": "success",
    "stdout": "targeted test output\n",
    "audience": {
        "kind": "sessions",
        "session_ids": [host_session]
    }
}
st, del_info = http_post(f"/internal/rooms/{room_a}/messages", target_payload, {"X-Internal-Token": token})
if st != 200 or del_info.get("delivered") != 2:
    sys.exit(f"Expected 200 delivered:2 (two tabs for target host session), got {st}: {del_info}")

# Tab 1 should receive it; audience MUST be stripped
m1 = tab1.recv_msg(timeout=2.0)
if not m1 or m1.get("type") != "run_result" or m1.get("run_id") != "run-ws-int-999":
    sys.exit(f"Tab 1 did not receive targeted message: {m1}")
if "audience" in m1:
    sys.exit(f"Tab 1 message leaked audience field: {m1}")

# Tab 2 (second tab of host_session) MUST also receive it; audience stripped
m2 = tab2.recv_msg(timeout=2.0)
if not m2 or m2.get("type") != "run_result" or m2.get("run_id") != "run-ws-int-999":
    sys.exit(f"Tab 2 did not receive targeted message: {m2}")
if "audience" in m2:
    sys.exit(f"Tab 2 message leaked audience field: {m2}")

# Tab 3 (participant in same room) MUST NOT receive it
m3 = tab3.recv_msg(timeout=0.4)
if m3 is not None:
    sys.exit(f"Tab 3 received targeted message meant only for host: {m3}")

# Tab 4 (session in room B) MUST NOT receive it
m4 = tab4.recv_msg(timeout=0.4)
if m4 is not None:
    sys.exit(f"Tab 4 in Room B received targeted message from Room A: {m4}")

# Step G: Room-wide Broadcast (Room A)
room_payload = {
    "type": "announcement",
    "message": "room broadcast test",
    "audience": {
        "kind": "room"
    }
}
st, del_info = http_post(f"/internal/rooms/{room_a}/messages", room_payload, {"X-Internal-Token": token})
if st != 200:
    sys.exit(f"Expected 200 on room broadcast, got {st}: {del_info}")

r1 = tab1.recv_msg(timeout=2.0)
r2 = tab2.recv_msg(timeout=2.0)
r3 = tab3.recv_msg(timeout=2.0)
r4 = tab4.recv_msg(timeout=0.4)

if not r1 or r1.get("type") != "announcement" or "audience" in r1:
    sys.exit(f"Tab 1 room broadcast error: {r1}")
if not r2 or r2.get("type") != "announcement" or "audience" in r2:
    sys.exit(f"Tab 2 room broadcast error: {r2}")
if not r3 or r3.get("type") != "announcement" or "audience" in r3:
    sys.exit(f"Tab 3 room broadcast error: {r3}")
if r4 is not None:
    sys.exit(f"Tab 4 in Room B received Room A broadcast: {r4}")

# Clean up sockets
tab1.close()
tab2.close()
tab3.close()
tab4.close()
print("WebSocket targeted delivery, multi-tab delivery, audience stripping, and room isolation verified successfully.")
PY_EOF

pass sync-service "real WebSocket clients verified: multi-tab delivery, target isolation, audience stripping, and room isolation"
