# Decision Record: Two room modes (T-23)

**Issue:** T-23 · #62
**Branch:** `docs/T-23-two-room-modes-adr`
**Author:** Femina Rathod (Category D)
**Date:** 31 October 2026
**Status:** Proposed. Becomes Accepted when the reviewers below approve the PR.
**Reviewers:** Yash Oza (D, wrote `platform-home.md`). FYI: one person each from Category A (client), B (execution), C (AI/ML) confirms their part is stated correctly.
**Sources:** Requirements v2, Backlog v2 (US-34 to US-38 Pair mode, US-39 to US-51 Open mode), [CHG-01](../CHG_01.md), [service contracts T-17](../api-contracts.md).

## 1. Context

Version 1 described a single kind of room: one shared file that everyone in the room edits and runs together, with every result shown to everyone. At the mid-evaluation elicitation ([CHG-01](../CHG_01.md), [elicitation round 2](../elicitation-round-2.md)) stakeholders raised five concerns:

- **E1** — there is no clear use case for many people editing one file at once.
- **E2** — if someone presses Run while a teammate has a half-typed line, the run is guaranteed to break, and the broken result goes to everyone.
- **E3** — requiring everyone to approve each run is slow and open to misuse, so a vote-to-run rule is not the answer to E2.
- **E4** — co-editing one file undermines modularity: different functions belong in separate modules, so a failure can be pinned to one module and one person.
- **E5** — the expected room size must be stated.

CHG-01 weighed four options (section 3) and chose **Option D: two room modes**, fixed when the room is created:

- **Pair mode** keeps the controlled single file, for the teaching, interview and pair-debugging sessions where one person drives and others watch — the clear use case E1 was missing. A preset, Teach/Interview or Debug together, sets who may edit by default (FR19.2); both presets are Pair mode, not a separate mode.
- **Open mode** adds a workspace of modules with per-module owners and saved-version runs, for modular programs — what E4 asks for. A run executes the last saved version so a half-typed line cannot break it (E2), and the runner chooses whether to share the output.

The mode is **immutable**: it is chosen at room creation and cannot change afterwards (FR17; CHG-01 section 1). This record states what each mode is and what it means for the architecture, so every Pair mode and Open mode story builds on the same definition. The field and message names it refers to are defined in the T-17 contract and stay **Proposed** until that contract is merged and confirmed.

## 2. Options considered

From CHG-01 section 3. The criteria were: does it answer the concerns (E1–E5), how much existing work survives, can it be delivered in the remaining time.

| Option | What it means | Verdict |
|---|---|---|
| **A. Pivot** | Change the product to a different idea. | Rejected — most sync, execution and AI/ML work would be redesigned. |
| **B. Vote-to-run** | A run happens only after the other participants approve it. | Rejected — this is exactly concern E3: slow on the most frequent action and abusable. |
| **C. "Monolith" defence** | Change nothing and argue the monolithic-architecture angle. | Rejected — the system already has four separate services; monolith vs microservice is about deployment, not code modularity (E4). |
| **D. Two modes** | Pair mode for a controlled single file; Open mode for modules with ownership, saved-version runs, run-output choice and attributed errors. | **Accepted** — answers E1–E5 and keeps the sync relay, Docker runner, AI/ML service, CI and contracts; changes are additions and tightening. |

## 3. Decision

**A room has one of two modes, `pair` or `open`, chosen in the `POST /v1/rooms` call that creates it (T-17, decision 1). The mode cannot be changed after creation (FR17, US-34).**

### Pair mode

- One shared document, `main`, and no modules (FR18; T-17, decision 6).
- A **preset** is chosen at creation: `teach_interview` or `debug_together` (Pair only).
- The Host gives and takes back edit access to the shared file (FR19.1); a guest can be made an **Observer**, who reads, sees cursors and shared results, but cannot edit or run (US-27, FR9.4). Edit permission is enforced on the server — the relay checks every edit (FR18), not a hidden button (T-20).
- Runs are sent inline: the run request carries the file content directly (T-17, decision 8, `kind: "inline"`).

### Open mode

- A **workspace of modules**, using one language fixed for the whole workspace at creation (FR20.1). Each module has exactly one owner (FR21.3), a server-generated opaque `module_id` that survives rename and transfer, and a `name` that is the file name with the language extension, unique in the workspace (T-17, decision 7).
- Each module is its own Yjs document; its `doc_id` is the `module_id` (T-17, decision 6).
- **Runs come from saved versions, never from live buffers** (FR25.1). execution-service reads the saved versions from sync-service and runs all modules of one run together as one snapshot; the result lists exactly that snapshot (T-17, decisions 8 and 9).
- Results are **private to the runner** by default and are shared with the room only when the runner shares them (FR27; T-17, decision 10).

### Roles (both modes)

Three roles exist on the server: `host`, `participant`, `observer`. The client takes labels and roles from the server's `roster` message, never from awareness, because awareness can be forged (T-17, decision 5; T-20).

## 4. Consequences

Written service by service, so each team knows what it owns.

- **sync-service** holds all room, role and module state: the room record (mode, preset, language, owner token), roles and roster, the modules and their owners, the saved versions, the audit log, the Host AI switch and the run records. It applies the edit-permission check (T-20). This state is recorded in the extension section of [`platform-home.md`](platform-home.md).
- **execution-service** reads saved versions from sync-service and keeps **no** permission state. On every run it asks sync-service for the caller's role, run permission and the room's AI switch (T-17, decisions 8 and 11). It never stores who may do what.
- **ai-ml-service** keeps only hash-keyed measurements and its own AI consent and cost-cap tracking (platform-home section 3), and never holds room, role or module state (T-18).
- **client** renders two different workspaces from the same server contract: Pair mode shows one file and Host controls; Open mode shows a module list with per-module ownership. Both read roles from `roster`.
- Because the mode is immutable, no story has to handle a mid-session mode switch, and each UI can assume its shape for the life of the room.

## 5. Open points

- Whether an empty Open-mode room keeps its modules when everyone leaves is tied to the storage decision for FR15 (T-17, decision 15, open item 3). Until a durable store is chosen (see [`platform-home.md`](platform-home.md) section 5), room and module state is held in memory behind an interface.
- Transfer of the Host role (US-54) and module ownership transfer are their own stories; this record only states that `module_id` survives transfer.
- All field and code names are **Proposed** until the T-17 contract is merged and confirmed.

## 6. Confirmations

Reviewed by Yash Oza (D). One person each from Category A, B and C confirms that their part is stated correctly:

- [ ] **Het (A, client):** the two workspaces (Pair: one file + Host controls; Open: module list with per-module ownership) and that roles/labels come from `roster`.
- [ ] **Jay (B, execution):** Open-mode runs use saved versions read as one snapshot, and execution-service keeps no permission state.
- [ ] **Prince (C, AI/ML):** ai-ml-service keeps only hash-keyed measurements and its own AI consent and cost-cap tracking (platform-home section 3), and never holds room, role or module state.
