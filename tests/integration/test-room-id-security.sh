#!/usr/bin/env bash
# Integration test for US-62 / Issue #131: unguessable room links (NFR16).
# Verifies, over real HTTP, that:
# 1. POST /v1/rooms ignores any client-supplied room_id and returns a
#    server-generated 22-char base64url id.
# 2. Two rooms created in a row get different ids (no reuse).
# 3. A made-up / unknown room id is not a room: GET /v1/rooms/{id}/owner -> 404
#    ROOM_NOT_FOUND (an unknown id never opens or creates a room).
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "sync-service" "(no Dockerfile)"
  exit 0
fi

base_url="$(service_url sync-service)"

# Skip cleanly if sync-service is offline (same convention as the other tests).
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base_url/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  skip "sync-service" "(service not running at $base_url; start with tests/integration/up.sh)"
  exit 0
fi

id_charset='^[A-Za-z0-9_-]{22}$'

# 1. A client-supplied room_id must be ignored; the server generates its own.
resp1="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base_url/v1/rooms" \
  -H "Content-Type: application/json" \
  -d '{"mode":"open","language":"python","room_id":"client-chosen-id-xyz"}')"
status1="$(echo "$resp1" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body1="$(echo "$resp1" | grep -v 'HTTP_STATUS:')"
room1="$(echo "$body1" | grep -o '"room_id":"[^"]*' | cut -d'"' -f4)"

if [ "$status1" != "201" ]; then
  fail "sync-service" "room creation expected 201, got $status1: $body1"
  exit 1
fi
if [ "$room1" = "client-chosen-id-xyz" ]; then
  fail "sync-service" "server used the client-supplied room_id (must be ignored)"
  exit 1
fi
if ! echo "$room1" | grep -qE "$id_charset"; then
  fail "sync-service" "room_id '$room1' is not a 22-char base64url id (NFR16)"
  exit 1
fi
pass "sync-service" "client-supplied room_id ignored; server id '$room1' is 22-char base64url"

# 2. A second room must get a different id.
room2="$(curl -s -X POST "$base_url/v1/rooms" -H "Content-Type: application/json" \
  -d '{"mode":"open","language":"python"}' | grep -o '"room_id":"[^"]*' | cut -d'"' -f4)"
if [ -z "$room2" ] || [ "$room2" = "$room1" ]; then
  fail "sync-service" "second room id repeated the first ('$room1')"
  exit 1
fi
pass "sync-service" "distinct room ids across creations ('$room1' != '$room2')"

# 3. A made-up id is not a room: the owner endpoint answers 404 ROOM_NOT_FOUND.
resp3="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET \
  "$base_url/v1/rooms/NonExistentGuess1234/owner" -H "X-Owner-Token: whatever")"
status3="$(echo "$resp3" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body3="$(echo "$resp3" | grep -v 'HTTP_STATUS:')"
if [ "$status3" = "404" ] && echo "$body3" | grep -q 'ROOM_NOT_FOUND'; then
  pass "sync-service" "unknown room id -> 404 ROOM_NOT_FOUND (opens/creates nothing)"
else
  fail "sync-service" "expected 404 ROOM_NOT_FOUND for unknown id, got $status3: $body3"
  exit 1
fi
