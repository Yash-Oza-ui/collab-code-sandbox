# 💻 Collab Code Sandbox — Client (`client/`)

**IT-314 Software Engineering | Group 32**  
**Task:** T-01 · Frontend Client Scaffold  
**Traceability:** FR1, FR2, FR3, FR14 · EPIC 1 · Sprint 1  

---

## 1. Overview & Technology Choices

The `client/` package is the web frontend for **Collab Code Sandbox**, providing the collaborative Monaco editor interface and room management. It serves as the baseline on which **US-01** (create room & share link), **US-02** (merge simultaneous edits), **US-05** (display name), and **T-11** (run panel) are built.

### Stack Selection
- **Framework & Bundler:** **Vite** + **React 19** + **TypeScript**
  - *Rationale:* Instant hot module replacement (HMR), lightweight build times, native TypeScript support, and zero configuration friction for team collaboration.
- **Code Editor:** **Monaco Editor** (`@monaco-editor/react` & `monaco-editor`)
  - *Rationale:* Industry-standard browser editor powering VS Code, supporting syntax highlighting, keybindings, multi-cursor, and high-performance editing for C++, Python, C, Java, and JavaScript.
  - *Note on CDN Dependency:* `@monaco-editor/react` currently fetches the Monaco Editor bundle from CDN (`cdn.jsdelivr.net`) at runtime in the browser. An active internet connection is required when loading the editor.
- **Collaborative State (CRDT):** **Yjs** (`yjs`)
  - *Rationale:* High-performance Conflict-free Replicated Data Type (CRDT) document model providing conflict-free simultaneous edit merging without central coordinator locking.
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
- `src/roomDoc.test.tsx`:
  - `createRoomDoc` unit tests (instantiation, shared text mutations, clean destruction).
  - `useRoomDoc` StrictMode regression test (verifies that during React StrictMode effect replay, each replaced document is destroyed, the document after effect replay is live, and the document is destroyed on final unmount).
  - Room ID change regression test (verifies old document is destroyed when switching rooms).
  - `RoomPage` component regression test under `<StrictMode>`.

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
- Per T-01 specification, the client does **not** connect to the sync relay yet. The WebSocket provider connection and Monaco editor binding are implemented in **US-01** and **US-02**.

---

## 9. Fresh-Clone Verification Procedure (For Reviewers / Teammates)

The following steps are provided for another team member or reviewer to verify the client scaffold on a fresh clone:

```bash
# 1. From repository root, navigate into the client folder
cd client

# 2. Clean install dependencies
npm ci

# 3. Run the automated test suite (all tests must pass)
npm test

# 4. Compile and verify production build
npm run build

# 5. Run linter (must pass with 0 errors and 0 warnings)
npm run lint

# 6. Start the local dev server
npm run dev
# -> Open http://localhost:3000/room/test-room in a browser with internet access
# -> Confirm the header displays "Room: test-room" and the Monaco editor renders
```
