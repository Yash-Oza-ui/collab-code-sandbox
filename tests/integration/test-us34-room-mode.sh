#!/usr/bin/env bash
# US-34 / FR17: room mode creation, strict payloads, and immutable room mode.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present sync-service; then
  skip us34-room-mode '(sync-service Dockerfile missing)'
  exit 0
fi
base="$(service_url sync-service)"
health="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base/actuator/health" || true)"
if [ "$health" != 200 ]; then
  fail us34-room-mode "sync-service unavailable at $base (health HTTP $health)"
  exit 1
fi

# curl response is kept in shell variables and never printed: owner tokens are secrets.
request() {
  local method="$1" path="$2" payload="${3:-}"
  if [ -n "$payload" ]; then
    RESPONSE="$(curl -sS --max-time 10 -w '\nHTTP_STATUS:%{http_code}' -X "$method" \
      -H 'Content-Type: application/json' -d "$payload" "$base$path")"
  else
    RESPONSE="$(curl -sS --max-time 10 -w '\nHTTP_STATUS:%{http_code}' -X "$method" "$base$path")"
  fi
  STATUS="${RESPONSE##*HTTP_STATUS:}"
  BODY="${RESPONSE%$'\n'HTTP_STATUS:*}"
}
assert_status() {
  local expected="$1" what="$2"
  if [ "$STATUS" != "$expected" ]; then
    fail us34-room-mode "$what: expected HTTP $expected, got $STATUS"
    exit 1
  fi
}
assert_contains() {
  local expected="$1" what="$2"
  if [[ "$BODY" != *"$expected"* ]]; then
    fail us34-room-mode "$what: response did not contain expected field"
    exit 1
  fi
}

request POST /v1/rooms '{"mode":"pair","preset":"teach_interview"}'
assert_status 201 'Pair creation'
assert_contains '"mode":"pair"' 'Pair response'
assert_contains '"preset":"teach_interview"' 'Pair response'
assert_contains '"language":null' 'Pair response'
PAIR_ID="$(printf '%s' "$BODY" | grep -o '"room_id":"[^"]*"' | head -1 | cut -d '"' -f4)"
[ -n "$PAIR_ID" ] || { fail us34-room-mode 'Pair response missing room_id'; exit 1; }
pass us34-room-mode 'Pair room created with preset, no Open language'

request POST /v1/rooms '{"mode":"open","language":"python"}'
assert_status 201 'Open creation'
assert_contains '"mode":"open"' 'Open response'
assert_contains '"language":"python"' 'Open response'
assert_contains '"preset":null' 'Open response'
OPEN_ID="$(printf '%s' "$BODY" | grep -o '"room_id":"[^"]*"' | head -1 | cut -d '"' -f4)"
[ -n "$OPEN_ID" ] || { fail us34-room-mode 'Open response missing room_id'; exit 1; }
pass us34-room-mode 'Open room created with language, no Pair preset'

request POST /v1/rooms '{"mode":"pair","preset":"teach_interview","language":"python"}'
assert_status 422 'Pair mixed-mode rejection'
assert_contains '"code":"INVALID_REQUEST"' 'Pair mixed-mode rejection'
request POST /v1/rooms '{"mode":"open","language":"python","preset":"debug_together"}'
assert_status 422 'Open mixed-mode rejection'
assert_contains '"code":"INVALID_REQUEST"' 'Open mixed-mode rejection'
pass us34-room-mode 'Server rejects mixed-mode create payloads'

# No room-mode update API is defined. Attempt a mode change on the existing
# room resource; the server must reject it rather than mutate the room.
request PATCH "/v1/rooms/$PAIR_ID" '{"mode":"open","language":"python"}'
if [ "$STATUS" != 404 ] && [ "$STATUS" != 405 ]; then
  fail us34-room-mode "PATCH existing room must be rejected (404/405), got $STATUS"
  exit 1
fi
request PUT "/v1/rooms/$OPEN_ID" '{"mode":"pair","preset":"debug_together"}'
if [ "$STATUS" != 404 ] && [ "$STATUS" != 405 ]; then
  fail us34-room-mode "PUT existing room must be rejected (404/405), got $STATUS"
  exit 1
fi
pass us34-room-mode 'Server rejects room-mode update requests'
