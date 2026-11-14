# 💻 Collab Code Sandbox — Client (`client/`)

**IT-314 Software Engineering | Group 32**  
**Task:** T-01 · Frontend Client Scaffold  
**Traceability:** FR1, FR2, FR3, FR14 · EPIC 1 · Sprint 1 / Sprint 2  

---

## 1. Overview & Technology Choices

The `client/` package is the web frontend for **Collab Code Sandbox**, providing the collaborative Monaco editor interface and room management. It serves as the baseline on which **US-01** (create room & share link), **US-02** (merge simultaneous edits), **US-05** (display name & colour presence), and **T-11** (run panel) are built.

### Stack Selection
- **Framework & Bundler:** **Vite** + **React 19** + **TypeScript**
  - *Rationale:* Instant hot module replacement (HMR), lightweight build times, native TypeScript support, and zero configuration friction for team collaboration.
- **Code Editor:** **Monaco Editor** (`@monaco-editor/react` & `monaco-editor`)
  - *Rationale:* Industry-standard browser editor powering VS Code, supporting syntax highlighting, keybindings, multi-cursor, and high-performance editing for C++, Python, C, Java, and JavaScript.
  - *Note on CDN Dependency:* `@monaco-editor/react` currently fetches the Monaco Editor bundle from CDN (`cdn.jsdelivr.net`) at runtime in the browser. An active internet connection is required when loading the editor.
- **Collaborative State (CRDT) & Awareness:** **Yjs** (`yjs`) & **y-protocols** (`y-protocols/awareness`)
  - *Rationale:* High-performance Conflict-free Replicated Data Type (CRDT) document model providing conflict-free simultaneous edit merging and decentralised participant awareness presence without central coordinator locking.
- **Collaboration Integration:** `y-websocket` and `y-monaco`
  - *Rationale:* Connects Yjs documents to the WebSocket relay and binds shared document changes to Monaco.
- **Testing & Tooling:** **Vitest** with `happy-dom` for DOM-aware tests, and **ESLint** for code quality.
- **Intended Browser Compatibility Target (NFR12):** Current desktop versions of **Chrome**, **Edge**, and **Firefox**. Standard web APIs are used, avoiding browser-specific extensions. Formal multi-browser test verification is scheduled for **Sprint 2**.

---

## 2. Prerequisites & Supported Engines

Based on `package-lock.json` engine requirements (e.g., Vite, esbuild, rollup):
- **Node.js:**
  - If running **Node 20**: requires at least **v20.19.0** (`^20.19.0`).
  - If running **Node 22**: requires at least **v22.12.0** (`>=22.12.0`).
  - Compatible with Node 20 CI runtime (`ubuntu-latest`, setup-node 20).
- **npm:** v10.x or higher

---

## 3. Installation

From the repository root:

```bash
cd client
npm ci
```

*(If you are developing without a `package-lock.json` sync requirement, `npm install` also works).*

---

## 4. Configuration & Environment Variables

Service addresses are configurable and never hard-coded (T-06 contract compliance). A template `.env.example` is provided:

```bash
# Copy example template to .env for local overrides
cp .env.example .env
```

*(On Windows PowerShell: `Copy-Item .env.example .env`)*

### Environment Variables (T-06)

| Variable | Default Value | Purpose |
|---|---|---|
| `VITE_SYNC_WS_URL` | `ws://localhost:8081/ws/rooms` | Base URL of the `sync-service` WebSocket relay (T-06 §3.1). |
| `VITE_EXECUTION_API_URL` | `http://localhost:8082` | Base URL of the `execution-service` REST API (T-06 §4.1). |
| `VITE_PLATFORM_API_URL` | `http://localhost:8081` | Base URL of the `sync-service` REST API, used to create rooms (`POST /v1/rooms`). Name from the v1 text in `docs/api-contracts.md` section 8. |

All configuration is parsed via `src/config.ts` with safe defaults matching `docs/api-contracts.md`.  
**Security note (NFR10):** Real `.env` files are ignored by `.gitignore`. Never commit API keys, tokens, or credentials to client configuration.

---

## 5. Development & Running Locally

Start the Vite development server:

```bash
npm run dev
```

The dev server is configured with:
- **Port:** `3000` (fixed with `strictPort: true` per T-06 section 1 to align with backend CORS origin `CLIENT_ORIGIN=http://localhost:3000` and T-08 smoke testing).
- **URL:** Open [http://localhost:3000](http://localhost:3000) or test a specific room at [http://localhost:3000/room/demo-room-01](http://localhost:3000/room/demo-room-01).

### Room URL Route (T-06 §3.2)
- Room routes follow `/room/{room_id}`, where `{room_id}` is 1 to 64 alphanumeric characters (`A-Z`, `a-z`, `0-9`, `_`, `-`).
- The editor page reads this ID from the URL path.
- Typing in the Monaco editor works upon loading the page (requires internet access to fetch Monaco from CDN).

### Create and join a room locally (US-01)

1. Start `sync-service` on port 8081 (see `sync-service/README.md`; it needs Java 21 and Maven).
2. Start the client with `npm run dev` and open http://localhost:3000/ (use `localhost`, not `127.0.0.1`: the server only allows the origin `http://localhost:3000` by default).
3. Click **Create room**. A link of the form `/room/{room_id}` appears. Use **Copy link**, or select it by hand if copying is blocked.
4. Open the link in a second browser profile or a private window, enter a display name, and the shared editor loads.

Notes:
- **Placeholder:** "Create room" currently sends a fixed Pair-mode body (`preset: debug_together`). The real mode/preset choice comes from US-34.
- The owner token returned at creation is stored in `localStorage` under `collab_owner_token:{room_id}`. It is never shown, logged or put in a URL. It is not yet sent when joining.
- Per `docs/api-contracts.md`, room and owner state lives in memory in Sprint 1, so restarting `sync-service` loses existing rooms.
- **Collaboration:** The room page connects to the sync-service through `y-websocket` after the participant enters a display name. `y-monaco` binds the shared Yjs document to the Monaco editor.
- **Document persistence limitation:** The current relay does not permanently persist document content. If all participants disconnect, the document content is not guaranteed to survive. A reconnecting participant can catch up on changes while another participant remains connected.
---

## 6. Building for Production

Compile TypeScript and bundle assets for production:

```bash
npm run build
```

This executes `tsc -b` (strict type-checking) and `vite build`, generating optimized static assets in `dist/`.

To preview the production build locally:

```bash
npm run preview
```

---

## 7. Testing & Linting

### Unit and Regression Tests
Run the Vitest test suite:

```bash
npm test
```

Test suites cover:
- `src/roomId.test.ts`: URL room ID validation and parsing rules (T-06 §3.2).
- `src/config.test.ts`: Environment configuration parsing and fallback to T-06 service contract defaults.
- `src/displayName.test.ts`: Display name validation (whitespace trimming, non-empty, max 50 chars).
- `src/userColor.test.ts`: User colour palette modulo indexing, non-colliding participant colour selection, and fallback logic (US-05).
- `src/awareness.test.ts`: Yjs awareness presence initialization and peer colour aggregation (US-05).
- `src/awarenessWs.test.ts`: Stable session ID management (T-06 §3.3), WebSocket URL construction (T-06 §3.1), message framing, and multi-client relay awareness synchronization (US-05).
- `src/DisplayNamePrompt.test.tsx`: Display name input modal and error message handling.
- `src/RoomPage.displayName.test.tsx`: Display name gating, room header colour badge rendering, awareness state exposure, and multi-client room sync over relay.
- `src/roomDoc.test.tsx`:
  - `createRoomDoc` unit tests (instantiation, shared text mutations, clean destruction).
  - `useRoomDoc` StrictMode regression test (verifies that during React StrictMode effect replay, each replaced document is destroyed, the document after effect replay is live, and the document is destroyed on final unmount).
  - Room ID change regression test (verifies old document is destroyed when switching rooms).
  - `RoomPage` component regression test under `<StrictMode>`.
- `src/RoomPage.displayName.test.tsx`: Verifies the display-name prompt and editor access after submitting a name.
- `src/App.test.tsx`: Tests application routing for the home page, room routes, and invalid paths.
- `src/CreateRoomScreen.test.tsx`: Tests room creation UI, loading state, error handling, share-link display, and clipboard behavior.
- `src/createRoom.test.ts`: Tests room creation API requests, response validation, owner-token storage, and error handling.
- `src/roomLink.test.ts`: Tests shareable room URL construction and URL parsing.
- `src/sessionId.test.ts`: Tests collaboration session ID generation and reuse.

### Code Quality (Linting)
Run ESLint:

```bash
npm run lint
```

---

## 8. Yjs Collaborative Editing & StrictMode Lifecycle (T-01 Boundary)

In T-01:
- `yjs` is installed as a primary dependency.
- **StrictMode-Safe Lifecycle:** `useRoomDoc` (`src/roomDoc.ts`) creates each `Y.Doc` inside effect setup and destroys that exact instance in effect cleanup. No disposable documents are instantiated during the render phase. In development with React StrictMode enabled, effect replay cleanly destroys the first instance and recreates a live document without leaving stale or destroyed documents.
- **Ref Availability:** The active document is exposed via `docRef` for subsequent **US-01** and **US-02** integrations.
- **US-01 / US-02 Integration:** The room page connects to the sync-service using `y-websocket` and binds the shared Yjs document to Monaco using `y-monaco`. The connection is established after the participant enters a display name and the editor is mounted. The provider and binding are cleaned up when the room page unmounts or the room state changes.
- **Participant Awareness & Presence (US-05):** Yjs Awareness (`y-protocols/awareness`) is used to publish and synchronize the participant's display name and assigned presence colour across control and document sockets.

---

## 9. Display Name, Colour Assignment & Presence Awareness (US-05 / FR14)

**US-05** allows guests joining a room without GitHub authentication to enter a display name and automatically receive a distinct presence colour:
1. **Name Prompt & Validation (`src/displayName.ts`, `src/DisplayNamePrompt.tsx`):**
   - Prompted before entering the room editor.
   - Validated: trimmed, non-empty, and limited to 50 characters (`MAX_DISPLAY_NAME_LENGTH = 50`).
2. **Colour Picker (`src/userColor.ts`):**
   - High-contrast, accessible 10-colour palette sized for room capacity (NFR2).
   - Pure function `pickUserColor(existingColors, fallbackIndex)` finds an unused colour among active participants in the room, falling back deterministically when all palette colours are taken.
3. **Yjs Awareness Presence (`src/awareness.ts`):**
   - Stores user presence in Yjs awareness state under `awareness.setLocalStateField('user', { name, color })`.
   - Ready for live cursors (US-03) without rework.
4. **WebSocket Awareness Synchronization & T-20 Protocol (`src/awarenessWs.ts`):**
   - Implements the T-20 two-socket architecture:
     - **Control Socket:** `ws://localhost:8081/ws/rooms/{room_id}?session_id={session_id}`
       - Text-based JSON control frames.
       - Client sends `{"type": "join", "display_name": "..."}` and receives `{"type": "joined", ...}` along with room `roster` broadcasts.
     - **Document Socket:** `ws://localhost:8081/ws/rooms/{room_id}/docs/{doc_id}?session_id={session_id}`
       - Dedicated binary socket for Yjs document updates and awareness frames (default `doc_id = 'main'`).
       - Enforces T-20 lifecycle: document sockets opened before `join` is completed on the control socket are rejected by the server with close code `4406`.
       - Binary awareness frames (`MESSAGE_AWARENESS = 1`) and query awareness frames (`MESSAGE_QUERY_AWARENESS = 3`) synchronize participant presence states in real time across peers without echo loops.
   - Formatted in accordance with T-06 §3.1 / §3.3 and T-20 using stable UUID v4 stored in `localStorage` (`collab_session_id`).
5. **Browser Console Inspection (DoD #5):**
   - The active awareness instance is exposed at `window.awareness`.
   - Reviewers and developers can inspect the active participants and local presence in DevTools console:
     ```javascript
     window.awareness.getStates()
     // Map(2) {
     //   [clientAId] => { user: { name: 'Alice', color: '#e11d48' } },
     //   [clientBId] => { user: { name: 'Bob', color: '#2563eb' } }
     // }
     ```

---

## 10. Multi-Client Verification Procedure (Reviewers / Teammates)

### Automated Test Verification
Run the multi-client relay integration suites:
```bash
npm test src/awarenessWs.test.ts src/RoomPage.displayName.test.tsx
```
Both test suites simulate the sync-service T-20 WebSocket relay (Control socket at `/ws/rooms/{room_id}` and Document socket at `/ws/rooms/{room_id}/docs/{doc_id}`) and verify that:
- Control join handshake (`join` -> `joined`) succeeds before document socket is established.
- Document sockets opened before completing `join` are rejected with close code `4406`.
- Client A joins as Alice.
- Client B joins as Bob.
- Both clients receive and observe the other's name and colour in their Awareness states over the document socket.
- Their assigned colours are distinct.

### Manual Two-Client Verification (Browser)
1. Start the services:
   - Ensure `sync-service` is running on `ws://localhost:8081` (via Docker Compose or `./gradlew bootRun`).
   - Start the client: `npm run dev` (opens on `http://localhost:3000`).
2. **Window A (First Participant):**
   - Navigate to `http://localhost:3000/room/demo-room-01`.
   - In the name prompt, enter `Alice` and click **Join room**.
   - The editor opens with header badge: `Room: demo-room-01 · You: Alice` with a colour dot (e.g. Rose `#e11d48`).
   - In DevTools console, run:
     ```javascript
     Array.from(window.awareness.getStates().values())
     // -> [{ user: { name: 'Alice', color: '#e11d48' } }]
     ```
3. **Window B (Second Participant in Incognito or second browser):**
   - Navigate to `http://localhost:3000/room/demo-room-01`.
   - In the name prompt, enter `Bob` and click **Join room**.
   - The editor opens with header badge: `Room: demo-room-01 · You: Bob` with a distinct colour dot (e.g. Blue `#2563eb`).
   - In **Window B** console, run:
     ```javascript
     Array.from(window.awareness.getStates().values())
     // -> [{ user: { name: 'Alice', color: '#e11d48' } }, { user: { name: 'Bob', color: '#2563eb' } }]
     ```
   - In **Window A** console, re-run:
     ```javascript
     Array.from(window.awareness.getStates().values())
     // -> Both Alice and Bob are present, verified synchronized over the WebSocket relay.
     ```
