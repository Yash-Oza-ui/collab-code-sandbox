import json
import os
import urllib.error
import urllib.request
import uuid

import websocket


BASE_URL = os.environ.get("SYNC_SERVICE_URL", "http://localhost:8081").rstrip("/")
WS_URL = BASE_URL.replace("https://", "wss://").replace("http://", "ws://")


def http_request(method, path, payload=None, headers=None):
    request_headers = {"Content-Type": "application/json"}
    if headers:
        request_headers.update(headers)

    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        BASE_URL + path,
        data=data,
        headers=request_headers,
        method=method,
    )

    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body = response.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise AssertionError(
            f"{method} {path} failed: HTTP {error.code}: {body}"
        ) from error


def receive_type(sock, expected_type):
    while True:
        message = json.loads(sock.recv())
        if message.get("type") == expected_type:
            return message


def connect_member(room_id, display_name, owner_token=None):
    session_id = str(uuid.uuid4())
    url = f"{WS_URL}/ws/rooms/{room_id}?session_id={session_id}"
    sock = websocket.create_connection(url, timeout=10)

    try:
        join_message = {
            "type": "join",
            "display_name": display_name,
        }
        if owner_token:
            join_message["owner_token"] = owner_token

        sock.send(json.dumps(join_message))
        joined = receive_type(sock, "joined")

        return sock, session_id, joined
    except Exception:
        sock.close()
        raise


def assert_roster_member(roster, member_id, display_name, role):
    members = roster.get("members", [])
    member = next(
        (item for item in members if item.get("member_id") == member_id),
        None,
    )

    assert member is not None, (
        f"{display_name} was missing from roster: {members}"
    )
    assert member.get("display_name") == display_name
    assert member.get("role") == role


def main():
    host_socket = None
    guest_socket = None

    try:
        room = http_request(
            "POST",
            "/v1/rooms",
            {"mode": "open", "language": "python"},
        )

        room_id = room["room_id"]
        owner_token = room["owner_token"]

        host_socket, host_session, host_joined = connect_member(
            room_id, "US03 Host", owner_token
        )
        host_id = host_joined["member_id"]

        host_initial_roster = receive_type(host_socket, "roster")
        assert_roster_member(
            host_initial_roster, host_id, "US03 Host", "host"
        )
        print("PASS: host receives initial presence roster")

        guest_socket, guest_session, guest_joined = connect_member(
            room_id, "US03 Guest"
        )
        guest_id = guest_joined["member_id"]

        guest_roster = receive_type(guest_socket, "roster")
        host_roster = receive_type(host_socket, "roster")

        assert_roster_member(
            guest_roster, host_id, "US03 Host", "host"
        )
        assert_roster_member(
            guest_roster, guest_id, "US03 Guest", "participant"
        )
        assert_roster_member(
            host_roster, guest_id, "US03 Guest", "participant"
        )
        print("PASS: both clients receive the guest's presence")

        http_request(
            "PATCH",
            f"/v1/rooms/{room_id}/members/{guest_id}",
            {"role": "observer"},
            {
                "X-Session-Id": host_session,
                "X-Owner-Token": owner_token,
            },
        )

        updated_guest_roster = receive_type(guest_socket, "roster")
        updated_host_roster = receive_type(host_socket, "roster")

        assert_roster_member(
            updated_guest_roster, guest_id, "US03 Guest", "observer"
        )
        assert_roster_member(
            updated_host_roster, guest_id, "US03 Guest", "observer"
        )
        print("PASS: role changes are broadcast to both clients")
        print("US-03 live presence integration test passed")

    finally:
        if guest_socket is not None:
            guest_socket.close()
        if host_socket is not None:
            host_socket.close()


if __name__ == "__main__":
    main()
