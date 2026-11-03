# sync-service

Spring Boot service for real-time collaborative editing using Yjs CRDTs (T-02), room creation and owner token identity that survives refresh (T-10, FR9.5), and room/role modeling (T-20).

---

## 1. Overview

`sync-service` provides real-time collaboration and platform state:
- **Room Creation & Owner Token Issuance** (`POST /v1/rooms`): Creates rooms and issues an unguessable 256-bit owner token to identify the room Host.
- **Owner Identity Verification** (`GET /v1/rooms/{room_id}/owner`): Validates bearer owner tokens so Host ownership survives page refreshes and reconnects without requiring login (FR9.5).
- **Room & Role Modeling** (T-20, V2.3, V2.4): Assigns roles (`host`, `participant`, `observer`), manages session memberships with unique display names (`NAME_TAKEN`), handles reconnects without role loss, and broadcasts membership rosters.
- **WebSocket Relay**:
  - Clients connect to `ws://localhost:8081/ws/rooms/{room_id}?session_id={session_id}`.
  - Every client in a room receives messages published by other clients in that room.
  - Clients in other rooms receive nothing (strict room isolation).
  - Binary frames (Yjs sync & update messages) are forwarded untouched without server-side parsing or buffering.
  - Yjs awareness frames (first byte `0x01`) are echoed back to the sender so a lone client in a room maintains its heartbeat and does not disconnect.
- **Internal Broadcast** (`POST /internal/rooms/{room_id}/messages`, protected by `X-Internal-Token`): Delivers execution results and system events to active WebSocket sessions in a room.

---

## 2. Prerequisites

- **Java**: 21 LTS (e.g. Eclipse Temurin 21)
- **Maven**: 3.9+ (or use IDE bundled Maven)
- **Node.js & wscat** (for manual WebSocket verification): `npm install -g wscat`
- **curl** (for REST verification)

---

## 3. How to Run & Test

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
Runs the full suite of automated unit and integration tests (33 tests total):
- `RelayIntegrationTest.java` (11 tests):
  - Actuator health check (`/actuator/health` -> 200)
  - Binary frame relay and byte integrity
  - Echo prevention for non-awareness frames
  - Awareness frame echo to sender
  - Room boundary isolation
  - Session disconnect & cleanup
  - Internal HTTP broadcast delivery
  - Internal endpoint authentication (`X-Internal-Token` -> 401)
  - Schema validation (`type` missing or malformed body -> 400)
  - Malformed room ID rejection (close code 4400)
  - Missing session ID rejection (close code 4400)
- `OwnerControllerTest.java` (4 tests, T-10): Owner validation with valid token (`200`), wrong token (`403 NOT_OWNER`), missing token (`403 NOT_OWNER`), and unknown room (`404 ROOM_NOT_FOUND`).
- `RoomControllerTest.java` (10 tests, T-20): Server-generated 22-char base64url room id, mode/preset/language validation, owner token hashing, and error envelopes.
- `RoomServiceTest.java` (8 tests, T-20): Join logic, Host vs Participant role assignment from owner token, duplicate name refusal (`NAME_TAKEN`), session reconnects, and roster/joined message shapes.

### Check Health Endpoint
```bash
curl -i http://localhost:8081/actuator/health
```
Expected response: `HTTP/1.1 200 OK` with `{"status":"UP"}`.

### Run via Docker
```bash
docker build -t sync-service .
docker run -p 8081:8081 sync-service
```

---

## 4. Room Creation & Owner Token Verification (T-10, FR9.5; T-20, V2.1)

### Security & Token Lifecycle
1. **Generation:** When a room is created (`POST /v1/rooms`), a cryptographically secure 256-bit random token is generated (`OwnerTokens.generate()`, base64url without padding) alongside a 128-bit server-generated room ID (22 characters).
2. **Client Storage:** The plaintext token is returned **once** in the creation response and saved in the browser (`localStorage` keyed by room id, Contract §8).
3. **Post-Creation Usage:** On connecting to the room over the control socket, the Host sends the token in the `join` message (never in the URL) to claim the Host role (Contract V2.3). The token is also sent via `X-Owner-Token` on `GET /v1/rooms/{room_id}/owner` to verify Host identity across page refreshes.
4. **Server Hashing:** The server stores **only the SHA-256 hash** of the owner token in `Room` state; the plaintext token is never stored in memory or persisted.
5. **Constant-Time Verification:** When validating ownership (`GET /v1/rooms/{room_id}/owner`), the presented `X-Owner-Token` is hashed and checked in constant time via `MessageDigest.isEqual` to prevent timing attacks.
6. **Confidentiality:** The token is never logged, never placed in URLs or shared links, and never broadcast in WebSocket messages.
7. **Error Envelopes:** Error responses adhere to the common §7 error envelope (`request_id: null`).

### REST Verification with `curl`

#### 1. Create a room
```bash
curl -s -X POST http://localhost:8081/v1/rooms \
  -H "Content-Type: application/json" \
  -d '{"mode":"open","language":"python"}'
```
Expected response (`201 Created`):
```json
{
  "room_id": "<server-generated-22-char-id>",
  "owner_token": "<server-generated-256-bit-token>",
  "mode": "open",
  "preset": null,
  "language": "python"
}
```

#### 2. Owner check with correct token
```bash
curl -i -H "X-Owner-Token: <token>" http://localhost:8081/v1/rooms/<room_id>/owner
```
Expected response (`200 OK`):
```json
{
  "room_id": "<room_id>",
  "is_owner": true
}
```

#### 3. Owner check with wrong token
```bash
curl -i -H "X-Owner-Token: wrong" http://localhost:8081/v1/rooms/<room_id>/owner
```
Expected response (`403 Forbidden`):
```json
{
  "request_id": null,
  "error": {
    "code": "NOT_OWNER",
    "message": "Missing or invalid owner token.",
    "retry_after_seconds": null
  }
}
```

#### 4. Owner check with missing token header
```bash
curl -i http://localhost:8081/v1/rooms/<room_id>/owner
```
Expected response (`403 Forbidden`):
```json
{
  "request_id": null,
  "error": {
    "code": "NOT_OWNER",
    "message": "Missing or invalid owner token.",
    "retry_after_seconds": null
  }
}
```

#### 5. Owner check on unknown room
```bash
curl -i -H "X-Owner-Token: <token>" http://localhost:8081/v1/rooms/NonExistentRoom123456/owner
```
Expected response (`404 Not Found`):
```json
{
  "request_id": null,
  "error": {
    "code": "ROOM_NOT_FOUND",
    "message": "Room not found.",
    "retry_after_seconds": null
  }
}
```

---

## 5. Manual Verification (Three-Terminal WebSocket Scenario)

Use `wscat` across three terminal windows to verify routing and room isolation:

1. **Terminal A** (Room 1, Client A):
   ```bash
   wscat -c "ws://localhost:8081/ws/rooms/room-demo-1?session_id=a0000000-0000-4000-8000-000000000001"
   ```

2. **Terminal B** (Room 1, Client B):
   ```bash
   wscat -c "ws://localhost:8081/ws/rooms/room-demo-1?session_id=b0000000-0000-4000-8000-000000000002"
   ```

3. **Terminal C** (Room 2, Client C):
   ```bash
   wscat -c "ws://localhost:8081/ws/rooms/room-demo-2?session_id=c0000000-0000-4000-8000-000000000003"
   ```

**Test steps**:
- Type `hello` in **Terminal A**:
  - **Terminal B** receives `hello`.
  - **Terminal C** receives nothing.
  - **Terminal A** does not echo the message back to itself.
- Close **Terminal B** (Ctrl+C).
- Type in **Terminal A** again: no server error occurs.
- Reconnect **Terminal B**: relay resumes normally.

---

## 6. Known Limitations (Sprint 1 / Sprint 2)

> **No Server-Side Document State**:
> In Sprint 1, `sync-service` is strictly a relay and maintains no document state in memory or persistence storage. If all users disconnect from a room, the room document is cleared from the server. A late joiner receives the current document state solely from active peers connected in that room. Persistent document storage and server-side resync will be evaluated and decided under **FR11** / **FR15** (Contract V2.15, item 3).

> **In-Memory Platform State**:
> Per architectural decision T-05 (`docs/decisions/platform-home.md`), room metadata and member states live in `sync-service` behind `RoomStore`. In Sprint 1 and 2, this store is in-memory. If `sync-service` restarts, in-memory room records are cleared. Durable persistence is planned for future evaluation.
