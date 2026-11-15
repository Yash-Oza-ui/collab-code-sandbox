#!/usr/bin/env bash
# test-sync-internal-endpoints.sh: verify session lookup, internal targeted message delivery, and contract error codes (T-20, Issue #65, T-17 V2.10, V2.11, V2.14).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

base="$(service_url sync-service)"

# Skip cleanly if the sync-service folder has no Dockerfile yet.
service_present sync-service || { skip sync-service "not built yet"; exit 0; }

# Check service liveness; skip if container is not running locally.
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  skip sync-service "service not running on $base (start with tests/integration/up.sh)"
  exit 0
fi

token="${INTERNAL_API_TOKEN:-test-internal-token}"

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
if [ "$status" != "401" ] || ! echo "$body" | grep -q "UNAUTHORIZED"; then
  fail sync-service "expected 401 UNAUTHORIZED for session lookup without token, got $status: $body"
  exit 1
fi
pass sync-service "session lookup rejects missing token with 401 UNAUTHORIZED"

# 2. Auth check: session lookup with invalid token -> 401 UNAUTHORIZED
resp="$(do_request GET "$base/internal/rooms/test-room/sessions/a0000000-0000-4000-8000-000000000001" "X-Internal-Token: wrong-token" "" "")"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "401" ] || ! echo "$body" | grep -q "UNAUTHORIZED"; then
  fail sync-service "expected 401 UNAUTHORIZED for session lookup with invalid token, got $status: $body"
  exit 1
fi
pass sync-service "session lookup rejects invalid token with 401 UNAUTHORIZED"

# 3. Auth check: internal broadcast without token -> 401 UNAUTHORIZED
resp="$(do_request POST "$base/internal/rooms/test-room/messages" "" "application/json" '{"type":"run_result"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "401" ] || ! echo "$body" | grep -q "UNAUTHORIZED"; then
  fail sync-service "expected 401 UNAUTHORIZED for broadcast without token, got $status: $body"
  exit 1
fi
pass sync-service "internal broadcast rejects missing token with 401 UNAUTHORIZED"

# 4. Auth check: internal broadcast with invalid token -> 401 UNAUTHORIZED
resp="$(do_request POST "$base/internal/rooms/test-room/messages" "X-Internal-Token: wrong-token" "application/json" '{"type":"run_result"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "401" ] || ! echo "$body" | grep -q "UNAUTHORIZED"; then
  fail sync-service "expected 401 UNAUTHORIZED for broadcast with invalid token, got $status: $body"
  exit 1
fi
pass sync-service "internal broadcast rejects invalid token with 401 UNAUTHORIZED"

# 5. Create a room for lifecycle tests (POST /v1/rooms)
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
if [ "$status" != "404" ] || ! echo "$body" | grep -q "ROOM_NOT_FOUND"; then
  fail sync-service "expected 404 ROOM_NOT_FOUND for nonexistent room, got $status: $body"
  exit 1
fi
pass sync-service "session lookup returns 404 ROOM_NOT_FOUND on unknown room"

# 7. Session lookup: session not joined -> 404 SESSION_NOT_FOUND
resp="$(do_request GET "$base/internal/rooms/$room_id/sessions/a0000000-0000-4000-8000-000000000001" "X-Internal-Token: $token" "" "")"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "404" ] || ! echo "$body" | grep -q "SESSION_NOT_FOUND"; then
  fail sync-service "expected 404 SESSION_NOT_FOUND for unjoined session, got $status: $body"
  exit 1
fi
pass sync-service "session lookup returns 404 SESSION_NOT_FOUND on unjoined session"

# 8. Internal broadcast: nonexistent room -> 404 ROOM_NOT_FOUND
resp="$(do_request POST "$base/internal/rooms/nonexistent-room-99999/messages" "X-Internal-Token: $token" "application/json" '{"type":"run_result"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "404" ] || ! echo "$body" | grep -q "ROOM_NOT_FOUND"; then
  fail sync-service "expected 404 ROOM_NOT_FOUND for broadcast to nonexistent room, got $status: $body"
  exit 1
fi
pass sync-service "internal broadcast returns 404 ROOM_NOT_FOUND on unknown room"

# 9. Internal broadcast: unsupported Content-Type -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "text/plain" '{"type":"run_result"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ] || ! echo "$body" | grep -q "INVALID_MESSAGE"; then
  fail sync-service "expected 400 INVALID_MESSAGE on non-JSON Content-Type, got $status: $body"
  exit 1
fi
pass sync-service "internal broadcast rejects non-JSON Content-Type with 400 INVALID_MESSAGE"

# 10. Internal broadcast: empty body -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" "")"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ] || ! echo "$body" | grep -q "INVALID_MESSAGE"; then
  fail sync-service "expected 400 INVALID_MESSAGE on empty body, got $status: $body"
  exit 1
fi
pass sync-service "internal broadcast rejects empty body with 400 INVALID_MESSAGE"

# 11. Internal broadcast: malformed JSON -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"type": unquoted}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ] || ! echo "$body" | grep -q "INVALID_MESSAGE"; then
  fail sync-service "expected 400 INVALID_MESSAGE on malformed JSON, got $status: $body"
  exit 1
fi
pass sync-service "internal broadcast rejects malformed JSON with 400 INVALID_MESSAGE"

# 12. Internal broadcast: non-object JSON root -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '["not","an","object"]')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ] || ! echo "$body" | grep -q "INVALID_MESSAGE"; then
  fail sync-service "expected 400 INVALID_MESSAGE on non-object JSON root, got $status: $body"
  exit 1
fi
pass sync-service "internal broadcast rejects non-object root with 400 INVALID_MESSAGE"

# 13. Internal broadcast: missing type field -> 400 INVALID_MESSAGE with preserved request_id
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"request_id":"int-req-99","payload":"no-type"}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ] || ! echo "$body" | grep -q "INVALID_MESSAGE" || ! echo "$body" | grep -q "int-req-99"; then
  fail sync-service "expected 400 INVALID_MESSAGE preserving request_id, got $status: $body"
  exit 1
fi
pass sync-service "internal broadcast rejects missing type with 400 INVALID_MESSAGE preserving request_id"

# 14. Internal broadcast: blank type field -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"type":"   "}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ] || ! echo "$body" | grep -q "INVALID_MESSAGE"; then
  fail sync-service "expected 400 INVALID_MESSAGE on blank type, got $status: $body"
  exit 1
fi
pass sync-service "internal broadcast rejects blank type with 400 INVALID_MESSAGE"

# 15. Internal broadcast: invalid audience kind -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"type":"run_result","audience":{"kind":"bogus"}}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ] || ! echo "$body" | grep -q "INVALID_MESSAGE"; then
  fail sync-service "expected 400 INVALID_MESSAGE on bogus audience kind, got $status: $body"
  exit 1
fi
pass sync-service "internal broadcast rejects unknown audience kind with 400 INVALID_MESSAGE"

# 16. Internal broadcast: audience sessions with empty session_ids -> 400 INVALID_MESSAGE
resp="$(do_request POST "$base/internal/rooms/$room_id/messages" "X-Internal-Token: $token" "application/json" '{"type":"run_result","audience":{"kind":"sessions","session_ids":[]}}')"
status="$(echo "$resp" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body="$(echo "$resp" | grep -v 'HTTP_STATUS:')"
if [ "$status" != "400" ] || ! echo "$body" | grep -q "INVALID_MESSAGE"; then
  fail sync-service "expected 400 INVALID_MESSAGE on empty session_ids array, got $status: $body"
  exit 1
fi
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
