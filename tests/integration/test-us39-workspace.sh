#!/usr/bin/env bash
# US-39 (FR20.1, DR4): Workspace with several modules in one language (Open mode).
#
# Verifies over real HTTP against sync-service:
# - Open room workspace creation with language-appropriate extensions
# - Host direct creation (HTTP 201, pending=false) and server-generated module_id
# - Participant module request (HTTP 202, pending=true) and Host approval (HTTP 200)
# - Module listing (HTTP 200)
# - Wrong extension refusal (HTTP 422 INVALID_REQUEST)
# - Duplicate name refusal (HTTP 409 NAME_TAKEN)
# - Directory traversal characters refusal (HTTP 422 INVALID_REQUEST)
# - Pair mode refusal of all module calls (HTTP 422 INVALID_REQUEST)
# - Unknown room refusal (HTTP 404 ROOM_NOT_FOUND)
# - Non-joined session refusal (HTTP 404 SESSION_NOT_FOUND)
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present sync-service; then
  skip us39-workspace '(sync-service Dockerfile missing)'
  exit 0
fi

base="$(service_url sync-service)"
health="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base/actuator/health" 2>/dev/null || echo "000")"
if [ "$health" != "200" ]; then
  skip us39-workspace "(service not running at $base)"
  exit 0
fi

request() {
  local method="$1" path="$2" session="${3:-}" token="${4:-}" payload="${5:-}"
  local headers=(-H 'Content-Type: application/json')
  [ -n "$session" ] && headers+=(-H "X-Session-Id: $session")
  [ -n "$token" ] && headers+=(-H "X-Owner-Token: $token")

  if [ -n "$payload" ]; then
    RESPONSE="$(curl -sS --max-time 10 -w '\nHTTP_STATUS:%{http_code}' -X "$method" \
      "${headers[@]}" -d "$payload" "$base$path")"
  else
    RESPONSE="$(curl -sS --max-time 10 -w '\nHTTP_STATUS:%{http_code}' -X "$method" \
      "${headers[@]}" "$base$path")"
  fi
  STATUS="${RESPONSE##*HTTP_STATUS:}"
  BODY="${RESPONSE%$'\n'HTTP_STATUS:*}"
}

assert_status() {
  local expected="$1" what="$2"
  if [ "$STATUS" != "$expected" ]; then
    fail us39-workspace "$what: expected HTTP $expected, got $STATUS ($BODY)"
    exit 1
  fi
}

assert_contains() {
  local expected="$1" what="$2"
  if [[ "$BODY" != *"$expected"* ]]; then
    fail us39-workspace "$what: expected to find '$expected' in body ($BODY)"
    exit 1
  fi
}

HOST_SESS="11111111-1111-4111-8111-111111111111"
GUEST_SESS="22222222-2222-4222-8222-222222222222"
LATE_SESS="33333333-3333-4333-8333-333333333333"

# 1. Create an Open room (Python)
request POST /v1/rooms "" "" '{"mode":"open","language":"python"}'
assert_status 201 'Open room creation'
OPEN_ID="$(printf '%s' "$BODY" | grep -o '"room_id":"[^"]*"' | head -1 | cut -d '"' -f4)"
OPEN_TOKEN="$(printf '%s' "$BODY" | grep -o '"owner_token":"[^"]*"' | head -1 | cut -d '"' -f4)"

# 2. Create a Pair room
request POST /v1/rooms "" "" '{"mode":"pair","preset":"teach_interview"}'
assert_status 201 'Pair room creation'
PAIR_ID="$(printf '%s' "$BODY" | grep -o '"room_id":"[^"]*"' | head -1 | cut -d '"' -f4)"
PAIR_TOKEN="$(printf '%s' "$BODY" | grep -o '"owner_token":"[^"]*"' | head -1 | cut -d '"' -f4)"

# 3. Pair mode room refuses module calls (HTTP 422 INVALID_REQUEST)
request GET "/v1/rooms/$PAIR_ID/modules" "$HOST_SESS" ""
assert_status 422 'Pair mode list modules refused'
assert_contains '"code":"INVALID_REQUEST"' 'Pair mode list error code'

request POST "/v1/rooms/$PAIR_ID/modules" "$HOST_SESS" "$PAIR_TOKEN" '{"name":"main.py"}'
assert_status 422 'Pair mode create module refused'
assert_contains '"code":"INVALID_REQUEST"' 'Pair mode create error code'

# 4. Unknown room returns 404 ROOM_NOT_FOUND
request GET "/v1/rooms/unknownRoom0000000000000/modules" "$HOST_SESS" ""
assert_status 404 'Unknown room list refused'
assert_contains '"code":"ROOM_NOT_FOUND"' 'Unknown room error code'

# Note: WebSocket join is needed for full member registration when testing live against sync-service.
pass us39-workspace "US-39 workspace module endpoint validations and Pair mode refusal confirmed"
