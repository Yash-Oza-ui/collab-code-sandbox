# sync-service

Spring Boot WebSocket relay for real-time collaborative editing using Yjs CRDTs (T-02, T-20, FR1, FR3, FR9.4, FR17, FR41.4, FR42, CR13).

---

## 1. Overview

`sync-service` provides the server-side room model and WebSocket relay for collaborative editing sessions.

### 1.1 Sockets (T-20)

| Socket | Path | Frame type | Purpose |
|--------|------|------------|---------|
| **Control socket** | `/ws/rooms/{room_id}?session_id=` | Text (JSON) | Join, roles, roster, edit-rejected notifications |
| **Document socket** | `/ws/rooms/{room_id}/docs/{doc_id}?session_id=` | Binary (Yjs) | Yjs sync frames, with awareness echo and edit-permission check |

Both sockets require `session_id` to be a valid UUID v4 query parameter.

### 1.2 Close Codes

| Code | Meaning | Defined in |
|------|---------|------------|
| `4400` | Malformed room id or missing/invalid `session_id` | v0 §3.6 |
| `4404` | Room not found (create-on-connect removed in T-20) | T-17 V2.2 |
| `4405` | Unknown `doc_id` / `module_id` | T-17 V2.5 |
| `4406` | Document socket opened before `join` | T-17 V2.5 |
| `4410` | Room closed / destroyed | T-17 V2.5 |
| `1009` | Binary frame exceeds 1 MiB (document socket) | v0 §3.6 |

### 1.3 REST Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v1/rooms` | Create a room — returns `room_id`, `owner_token`, `mode`, `preset`, `language` |
| `GET` | `/v1/rooms/{id}/owner` | Validate the owner token sent in `X-Owner-Token` (T-10, FR9.5) |
| `POST` | `/internal/rooms/{id}/messages` | Broadcast a JSON message to all control-socket sessions in a room (requires `X-Internal-Token`) |

### 1.4 Error Codes (Internal Endpoint)

| HTTP | Code | Meaning |
|------|------|---------|
| 401 | `UNAUTHORIZED` | Missing or invalid `X-Internal-Token` |
| 400 | `PAYLOAD_TOO_LARGE` | Body exceeds 4 MiB |
| 400 | `INVALID_BODY` | Body has no `type` field |
| 400 | `INVALID_JSON` | Body is not valid JSON |

---

## 2. Control Socket Protocol

### Connect
```
ws://localhost:8081/ws/rooms/{room_id}?session_id={uuid-v4}
```
Closes with `4404` if the room does not exist.

### Join (first text frame, client → server)
```json
{ "type": "join", "display_name": "Alice" }
```
Host includes `"owner_token"`:
```json
{ "type": "join", "display_name": "Alice", "owner_token": "<token>" }
```

### Server responses

**`joined`** — sent to the joining session on success:
```json
{ "type": "joined", "member_id": "m_...", "role": "host", "mode": "pair", "preset": "teach_interview", "permissions": { "can_edit": true, "can_run": true } }
```

**`join-refused`** — e.g. NAME_TAKEN:
```json
{ "type": "join-refused", "reason": "NAME_TAKEN", "message": "The display name 'Alice' is already taken." }
```

**`roster`** — broadcast to all sessions in the room after every join:
```json
{ "type": "roster", "members": [ { "member_id": "m_...", "display_name": "Alice", "role": "host", "permissions": { "can_edit": true, "can_run": true } } ] }
```

**`edit-rejected`** — sent to a session that attempted a document change without write permission:
```json
{ "type": "edit-rejected", "doc_id": "main", "reason": "no_edit_access" }
```
Contract reasons (T-17 Section V2.12):
- `"no_edit_access"`: Pair mode — sender has no edit access (teach_interview non-host).
- `"not_module_writer"`: Open mode — sender is not a module writer.
- `"observer"`: Observer in either mode.

---

## 3. Document Socket Protocol

```
ws://localhost:8081/ws/rooms/{room_id}/docs/{doc_id}?session_id={uuid-v4}
```

- Carries Yjs binary frames only.
- Closes with `4406` if opened before completing `join` on the control socket.
- Frames are isolated to the specific document (`doc_id`): in Open mode, edits on one module never leak to another.
- Awareness frames (`byte[0] == 0x01`) are echoed back to the sender.
- Frames > 1 MiB close the socket with code `1009`.
- **Edit-permission check (CR13)**: inspects bytes 0–1 only without parsing the document:
  - `0 0`: Sync step 1 (request for state) — forwarded (read-only).
  - `0 1`: Sync step 2 (missing updates) — carries document changes; dropped if sender cannot edit.
  - `0 2`: Sync update (incremental update) — carries document changes; dropped if sender cannot edit.
  - `1 ...`: Awareness — forwarded.
  - `3`: Query awareness — forwarded.
  - If dropped, an `edit-rejected` text message is sent to the sender's control socket.
- In Pair mode: `doc_id` is `"main"`. In Open mode: `doc_id` is the module id.

---

## 4. Room Creation & Owner Token Verification (T-10, FR9.5)

### Security and token lifecycle
1. **Generation:** `POST /v1/rooms` generates a 256-bit random owner token (`OwnerTokens.generate()`, base64url, no padding) next to the server-generated room id.
2. **Client storage:** the plaintext token is returned **once** and kept in the browser (`localStorage`, keyed by room id; contract section 8).
3. **Usage:** the Host sends the token in the control-socket `join` message (never in the URL) and in `X-Owner-Token` on `GET /v1/rooms/{room_id}/owner`, so ownership survives a refresh or reconnect.
4. **Hashing:** only the SHA-256 hash is stored in `Room` state. The plaintext token is never stored.
5. **Constant-time check:** the presented token is hashed and compared with `MessageDigest.isEqual`.
6. **Confidentiality:** the token is never logged, never put in URLs or shared links, and never broadcast.
7. **Errors:** the common error envelope with `request_id: null`.

### Check it with curl
```bash
# 1. Create a room -> 201 {"room_id","owner_token","mode","preset","language"}
curl -s -X POST http://localhost:8081/v1/rooms \
  -H "Content-Type: application/json" \
  -d '{"mode":"open","language":"python"}'

# 2. Right token -> 200 {"room_id":"<id>","is_owner":true}
curl -i -H "X-Owner-Token: <token>" http://localhost:8081/v1/rooms/<room_id>/owner

# 3. Wrong token -> 403 NOT_OWNER
curl -i -H "X-Owner-Token: wrong" http://localhost:8081/v1/rooms/<room_id>/owner

# 4. No header -> 403 NOT_OWNER
curl -i http://localhost:8081/v1/rooms/<room_id>/owner

# 5. Unknown room -> 404 ROOM_NOT_FOUND
curl -i -H "X-Owner-Token: <token>" http://localhost:8081/v1/rooms/NonExistentRoom123456/owner
```
Error body (403): `{"request_id":null,"error":{"code":"NOT_OWNER","message":"Missing or invalid owner token.","retry_after_seconds":null}}`

The same five checks run in `tests/integration/test-owner-token.sh` against the live stack.

---

## 5. Prerequisites

- **Java**: 21 LTS (e.g. Eclipse Temurin 21)
- **Maven**: 3.9+ (or use IDE bundled Maven)
- **Node.js & wscat** (optional, for manual verification): `npm install -g wscat`

---

## 6. How to Run & Test

### Run the Service Locally
```bash
cd sync-service
mvn spring-boot:run
```
By default, the server starts on port `8081`.

### Run Automated Tests
```bash
cd sync-service
mvn test
```
Test classes:
- `RelayIntegrationTest` — health, binary relay, room isolation, awareness echo, disconnect, internal broadcast, 4400/4404.
- `SocketsAndEditCheckIntegrationTest` — join/roster flow, NAME_TAKEN, CR13 edit check, contract reasons, 4406 not-joined, doc_id isolation.
- `DocumentHandlerUnitTest` — first-two-bytes edit-check logic (`0 0`, `0 1`, `0 2`, `1`, `3`) and close-code constants.
- `RoomServiceTest` — join/roster logic, roles, NAME_TAKEN (Femina's part, used by control handler).
- `RoomControllerTest` — POST /v1/rooms validation.
- `OwnerControllerTest` — GET /v1/rooms/{id}/owner.

### Check Health Endpoint
```bash
curl -i http://localhost:8081/actuator/health
```
Expected: `HTTP/1.1 200 OK` with `{"status":"UP"}`.

### Create a Room and Connect
```bash
# 1. Create a room
curl -s -X POST http://localhost:8081/v1/rooms \
  -H "Content-Type: application/json" \
  -d '{"mode":"pair","preset":"debug_together"}'
# Response: {"room_id":"<id>","owner_token":"<token>","mode":"pair","preset":"debug_together","language":null}

# 2. Connect to the control socket (wscat)
wscat -c "ws://localhost:8081/ws/rooms/<id>?session_id=a0000000-0000-4000-8000-000000000001"

# 3. Join as Host (send as text)
{"type":"join","display_name":"Alice","owner_token":"<token>"}

# 4. Connect to the document socket (second terminal)
wscat -b -c "ws://localhost:8081/ws/rooms/<id>/docs/main?session_id=a0000000-0000-4000-8000-000000000001"
```

### Run via Docker
```bash
docker build -t sync-service .
docker run -p 8081:8081 sync-service
```

---

## 7. Known Limitations

- **Pair mode**: one document (`doc_id = "main"`). Open mode per-module documents tracked by `doc_id = module_id`.
- **No Server-Side Document State**: `sync-service` is a relay. If all users disconnect, the document is cleared. Persistent storage is a later decision (FR11).
- **Edit mode rules** (US-35, US-43): the `canEdit` flag at join is set by the Pair preset. Per-module write access in Open mode is applied in US-43 on top of the check already implemented here.
- **ROOM_FULL / USER_BLOCKED** defined in US-63 / US-28 respectively.
- **In-memory platform state** (T-05, `docs/decisions/platform-home.md`): room metadata and members live behind `RoomStore` in memory. A `sync-service` restart clears all rooms, so owner tokens issued before it stop working. Durable storage is a later decision.
