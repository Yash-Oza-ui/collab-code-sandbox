# sync-service

Spring Boot WebSocket relay for real-time collaborative editing using Yjs CRDTs (T-02, FR1, FR2, FR3).

---

## 1. Overview

`sync-service` is an ephemeral, low-latency WebSocket message relay:
- Clients connect to `ws://localhost:8081/ws/rooms/{room_id}?session_id={session_id}`.
- Every client in a room receives messages published by other clients in that room.
- Clients in other rooms receive nothing (strict room isolation).
- Binary frames (Yjs sync & update messages) are forwarded untouched without server-side parsing or buffering.
- Yjs awareness frames (first byte `0x01`) are echoed back to the sender so a lone client in a room maintains its heartbeat and does not disconnect.
- Internal HTTP broadcast endpoint (`POST /internal/rooms/{room_id}/messages`, protected by `X-Internal-Token`) broadcasts execution results and system events to all active WebSocket sessions in a room.

---

## 2. Prerequisites

- **Java**: 21 LTS (e.g. Eclipse Temurin 21)
- **Maven**: 3.9+ (or use IDE bundled Maven)
- **Node.js & wscat** (for manual verification): `npm install -g wscat`

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
Runs the full suite of integration tests in `RelayIntegrationTest.java`, covering:
- Actuator health check (`/actuator/health` -> 200)
- Binary frame relay and byte integrity
- Echo prevention for non-awareness frames
- Awareness frame echo to sender
- Room boundary isolation
- Session disconnect & cleanup
- Internal HTTP broadcast delivery
- Internal endpoint authentication (`X-Internal-Token`) and schema validation
- Malformed room ID and missing session ID rejection (status code 4400)

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

## 4. Manual Verification (Three-Terminal Scenario)

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

*(Note: `wscat` sends text frames for verification of routing; binary frame byte integrity is proven by the automated test suite).*

---

## 5. Known Limitations (Sprint 1)

> **No Server-Side Document State**:
> In Sprint 1, `sync-service` is strictly a relay and maintains no document state in memory or persistence storage. If all users disconnect from a room, the room document is cleared from the server. A late joiner receives the current document state solely from active peers connected in that room. Persistent document storage and server-side resync will be evaluated and decided under **FR11** in Sprint 2.
