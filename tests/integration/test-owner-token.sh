#!/usr/bin/env bash
# Integration test for T-10 / Issue #16: Room owner token issuing and validation (FR9.5).
# Verifies that:
# 1. POST /v1/rooms creates a room and returns room_id and owner_token (HTTP 201).
# 2. GET /v1/rooms/{id}/owner with valid X-Owner-Token returns HTTP 200 {is_owner: true}.
# 3. GET /v1/rooms/{id}/owner with invalid X-Owner-Token returns HTTP 403 NOT_OWNER.
# 4. GET /v1/rooms/{id}/owner without X-Owner-Token returns HTTP 403 NOT_OWNER.
# 5. GET /v1/rooms/{id}/owner on unknown room returns HTTP 404 ROOM_NOT_FOUND.
set -euo pipefail
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$dir/lib.sh"

if ! service_present "sync-service"; then
  skip "sync-service" "(no Dockerfile)"
  exit 0
fi

base_url="$(service_url sync-service)"

# Check if sync-service is accessible; skip cleanly if offline
health_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$base_url/actuator/health" 2>/dev/null || echo "000")"
if [ "$health_code" != "200" ]; then
  skip "sync-service" "(service not running at $base_url; start with tests/integration/up.sh)"
  exit 0
fi

# 1. Create a room (POST /v1/rooms)
resp_create="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$base_url/v1/rooms" \
  -H "Content-Type: application/json" \
  -d '{"mode":"open","language":"python"}')"

status_create="$(echo "$resp_create" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_create="$(echo "$resp_create" | grep -v 'HTTP_STATUS:')"

if [ "$status_create" != "201" ]; then
  fail "sync-service" "room creation expected 201, got $status_create: $body_create"
  exit 1
fi

room_id="$(echo "$body_create" | grep -o '"room_id":"[^"]*' | cut -d'"' -f4)"
owner_token="$(echo "$body_create" | grep -o '"owner_token":"[^"]*' | cut -d'"' -f4)"

if [ -z "$room_id" ] || [ -z "$owner_token" ]; then
  fail "sync-service" "room creation response missing room_id or owner_token: $body_create"
  exit 1
fi
pass "sync-service" "POST /v1/rooms issued room_id and owner_token (HTTP 201)"

# 2. Check ownership with correct token (GET /v1/rooms/{id}/owner -> 200)
resp_valid="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET "$base_url/v1/rooms/$room_id/owner" \
  -H "X-Owner-Token: $owner_token")"

status_valid="$(echo "$resp_valid" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_valid="$(echo "$resp_valid" | grep -v 'HTTP_STATUS:')"

if [ "$status_valid" = "200" ] && echo "$body_valid" | grep -q '"is_owner":true'; then
  pass "sync-service" "valid owner token verified -> HTTP 200 is_owner: true"
else
  fail "sync-service" "expected 200 with is_owner:true, got $status_valid: $body_valid"
  exit 1
fi

# 3. Check ownership with wrong token (GET /v1/rooms/{id}/owner -> 403 NOT_OWNER)
resp_wrong="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET "$base_url/v1/rooms/$room_id/owner" \
  -H "X-Owner-Token: wrong")"

status_wrong="$(echo "$resp_wrong" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_wrong="$(echo "$resp_wrong" | grep -v 'HTTP_STATUS:')"

if [ "$status_wrong" = "403" ] && echo "$body_wrong" | grep -q "NOT_OWNER"; then
  pass "sync-service" "wrong owner token refused -> HTTP 403 NOT_OWNER"
else
  fail "sync-service" "expected 403 NOT_OWNER, got $status_wrong: $body_wrong"
  exit 1
fi

# 4. Check ownership with missing token (GET /v1/rooms/{id}/owner -> 403 NOT_OWNER)
resp_missing="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET "$base_url/v1/rooms/$room_id/owner")"

status_missing="$(echo "$resp_missing" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_missing="$(echo "$resp_missing" | grep -v 'HTTP_STATUS:')"

if [ "$status_missing" = "403" ] && echo "$body_missing" | grep -q "NOT_OWNER"; then
  pass "sync-service" "missing owner token refused -> HTTP 403 NOT_OWNER"
else
  fail "sync-service" "expected 403 NOT_OWNER, got $status_missing: $body_missing"
  exit 1
fi

# 5. Check ownership on unknown room (GET /v1/rooms/unknown/owner -> 404 ROOM_NOT_FOUND)
resp_unknown="$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X GET "$base_url/v1/rooms/NonExistentRoom123456/owner" \
  -H "X-Owner-Token: $owner_token")"

status_unknown="$(echo "$resp_unknown" | grep 'HTTP_STATUS:' | cut -d':' -f2)"
body_unknown="$(echo "$resp_unknown" | grep -v 'HTTP_STATUS:')"

if [ "$status_unknown" = "404" ] && echo "$body_unknown" | grep -q "ROOM_NOT_FOUND"; then
  pass "sync-service" "unknown room refused -> HTTP 404 ROOM_NOT_FOUND"
else
  fail "sync-service" "expected 404 ROOM_NOT_FOUND, got $status_unknown: $body_unknown"
  exit 1
fi

exit 0
