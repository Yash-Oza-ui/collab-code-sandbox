# Decision Record: Two room modes (T-23)

**Issue:** T-23 · #62
**Branch:** `docs/T-23-two-room-modes-adr`
**Author:** Femina Rathod (Category D)
**Date:** 31 October 2026
**Status:** Proposed. Becomes Accepted when the reviewers below approve the PR.
**Reviewers:** Yash Oza (D, wrote `platform-home.md`). FYI: one person each from Category A (client), B (execution), C (AI/ML) confirms their part is stated correctly.
**Sources:** Requirements v2, Backlog v2 (US-34 to US-38 Pair mode, US-39 to US-51 Open mode), [CHG-01](../CHG_01.md), [service contracts T-17](../api-contracts.md).

## 1. Context

Version 1 described a single kind of room: one shared file that everyone in the room edits together. The mid-evaluation review and [CHG-01](../CHG_01.md) found that one shape could not serve the two things the product is actually used for:

- A **teaching or interview** session, where one person drives and a guest (or candidate) is watched. Here a single shared file and a Host who decides who may type is exactly right, and modules would only get in the way.
- A **debugging-together** session on a small project, where several people own different files, save versions and run the project as a whole. Here one shared file is not enough; the room needs a workspace of files with per-file ownership and runs built from saved versions.

Forcing both into one shape meant either the teaching case carried unused machinery, or the project case could not express file ownership and saved-version runs at all. CHG-01 resolves this by fixing **two modes**, chosen once when the room is created (FR17, US-34), and never changed afterwards. The conflict log in Requirements v2 (section 6) records the discussion and the decision to make the mode immutable rather than a per-room toggle.

This record states what each mode is and what it means for the architecture, so every Pair mode and Open mode story builds on the same definition. The field and message names it refers to are defined in the T-17 contract and stay **Proposed** until that contract is merged and confirmed.

## 2. Decision

**A room has one of two modes, `pair` or `open`, chosen in the `POST /v1/rooms` call that creates it (T-17, decision 1). The mode cannot be changed after creation (FR17, US-34).**

### Pair mode

- One shared document, `main` (T-17, decision 6). No modules.
- A **preset** is chosen at creation: `teach_interview` or `debug_together` (Pair only).
- The Host controls who may edit the shared file; a guest can be made an **Observer**, who reads, sees cursors and shared results, but cannot edit or run (US-27, FR9.4). Edit permission is enforced on the server (T-20), not by hiding a button.
- Runs are sent inline: the run request carries the file content directly (T-17, decision 8, `kind: "inline"`).

### Open mode

- A **workspace of modules**. Each module has exactly one owner, a server-generated opaque `module_id` that survives rename and transfer, and a `name` that is the file name with the language extension, unique in the workspace (T-17, decision 7).
- One language is fixed for the whole workspace at creation (Open only).
- Each module is its own Yjs document; its `doc_id` is the `module_id` (T-17, decision 6).
- **Runs come from saved versions, never from live buffers.** execution-service reads the saved versions from sync-service and runs all modules of one run together as one snapshot; the result lists exactly that snapshot (T-17, decisions 8 and 9).
- Results are **private to the runner** by default and are shared with the room only when the runner shares them (T-17, decision 10).

### Roles (both modes)

Three roles exist on the server: `host`, `participant`, `observer`. The client takes labels and roles from the server's `roster` message, never from awareness, because awareness can be forged (T-17, decision 5; T-20).

## 3. Consequences

Written service by service, so each team knows what it owns.

- **sync-service** holds all room, role and module state: the room record (mode, preset, language, owner token), roles and roster, the modules and their owners, the saved versions, the audit log, the Host AI switch and the run records. It applies the edit-permission check (T-20). This state is recorded in the extension section of [`platform-home.md`](platform-home.md).
- **execution-service** reads saved versions from sync-service and keeps **no** permission state. On every run it asks sync-service for the caller's role, run permission and the room's AI switch (T-17, decisions 8 and 11). It never stores who may do what.
- **ai-ml-service** keeps only hash-keyed measurements; it never holds room, role or module state (T-18).
- **client** renders two different workspaces from the same server contract: Pair mode shows one file and Host controls; Open mode shows a module list with per-module ownership. Both read roles from `roster`.
- Because the mode is immutable, no story has to handle a mid-session mode switch, and each UI can assume its shape for the life of the room.

## 4. Open points

- Whether an empty Open-mode room keeps its modules when everyone leaves is tied to the storage decision for FR15 (T-17, decision 15, open item 3). Until a durable store is chosen (see [`platform-home.md`](platform-home.md) section 5), room and module state is held in memory behind an interface.
- Transfer of the Host role (US-54) and module ownership transfer are their own stories; this record only states that `module_id` survives transfer.
- All field and code names are **Proposed** until the T-17 contract is merged and confirmed.
