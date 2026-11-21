"""US-56 live test. Needs the three services running with a shared internal token."""
import datetime
import hashlib
import json
import os
import time
import uuid
import urllib.error
import urllib.request
import websocket

sync = os.environ["SYNC_URL"].rstrip("/")
execution = os.environ["EXEC_URL"].rstrip("/")
token = os.environ["INTERNAL_API_TOKEN"]
assert token, "The stack must have a shared INTERNAL_API_TOKEN"


def request(url, method="GET", body=None, headers=None):
    content = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=content, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as exc:
        try:
            return exc.code, json.load(exc)
        except Exception:
            return exc.code, {}


status, room_data = request(sync + "/v1/rooms", "POST", {"mode": "open", "language": "python"})
assert status == 201, f"Create room: HTTP {status}"
room = room_data["room_id"]
owner = room_data["owner_token"]
sid = str(uuid.uuid4())
ws = websocket.create_connection(sync.replace("http://", "ws://") +
                                 f"/ws/rooms/{room}?session_id={sid}", timeout=5,
                                 origin="http://localhost:3000")
try:
    ws.send(json.dumps({"type": "join", "display_name": "Host", "owner_token": owner}))
    message = json.loads(ws.recv())
    assert message["type"] == "joined", message.get("type")
    status, module = request(sync + f"/v1/rooms/{room}/modules", "POST", {"name": "main.py"},
                             {"X-Session-Id": sid, "X-Owner-Token": owner})
    assert status == 201, f"Create module: HTTP {status}"
    now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    run = {"type": "run-result", "room_id": room, "request_id": str(uuid.uuid4()),
           "language": "python", "runner": {"member_id": "m_test", "display_name": "Host"},
           "ran_at": now, "files": ["main.py"], "saved_versions": [],
           "input_hash": hashlib.sha256(b"").hexdigest(), "execution_time_ms": 10,
           "peak_memory_mb": 5, "stdout": "ok", "stderr": "", "exit_code": 0}
    status, _ = request(sync + f"/internal/rooms/{room}/messages", "POST", run,
                        {"X-Internal-Token": token})
    assert status == 200, f"Record run: HTTP {status}"
    status, data = request(sync + f"/internal/rooms/{room}/runs", headers={"X-Internal-Token": token})
    assert status == 200 and len(data["runs"]) == 1, "Run was not stored"
finally:
    ws.close()

# Default grace period 30 s; polling has bounded wall-clock time.
deadline = time.monotonic() + 40
while time.monotonic() < deadline:
    status, _ = request(sync + f"/internal/rooms/{room}/runs", headers={"X-Internal-Token": token})
    if status == 404:
        break
    time.sleep(0.5)
else:
    raise AssertionError("Host departure did not close room within 40 seconds")

# A closed tombstone accepts no new Host token, module or room membership.
status, _ = request(sync + f"/v1/rooms/{room}/modules", headers={"X-Session-Id": sid})
assert status != 200, "Closed room still exposes modules"

# Verify the execution -> AI forwarding contract, including idempotence.
for _ in range(2):
    status, data = request(execution + f"/internal/rooms/{room}/ended", "POST",
                           {"room_id": room, "reason": "closed"}, {"X-Internal-Token": token})
    assert status == 200 and data.get("status") == "cleared", f"Remote cleanup: HTTP {status}"
