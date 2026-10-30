# CHG-01: Role-Based Rooms with Pair and Open Modes

| Field | Value |
|---|---|
| Project | Collaborative Code Editor with Sandboxed Execution (IT-314, Group 32) |
| Change request ID | CHG-01 |
| Status | Decided |
| Related documents | `docs/elicitation-round-2.md` (stakeholder elicitation) |

---

## 1. Summary

The original requirements describe one room model: a single shared file that every participant can edit and run, with every result shown to everyone. Stakeholder feedback at the mid-evaluation showed that this model does not give a clear use case for shared editing, does not prevent errors caused by half-typed code, and does not support modular programs.

This change request replaces it with two room modes, chosen when the room is created and fixed afterwards:

- **Pair mode:** one file, no modules. The Host grants and takes back edit (driver) access, enforced by the relay on every edit. Run executes the live text and the result, labelled with who ran it and when, goes to everyone.
- **Open mode:** a workspace of modules. Each module has exactly one owner, and can be shared for writing. Everyone in the room can read every module live. Run executes the last saved version. The runner chooses whether to share the output, which is always labelled with who ran it. Failures name the failing module and its owner.

The technology stack, the four-service architecture, the sandbox rules and the non-blocking AI/ML rule (NFR9) do not change.

---

## 2. Trigger

### 2.1 The event

On 2nd stage review Elicitations, after 13 October 2026, with the TA, and other stakeholders. The review is recorded as an elicitation event in `docs/elicitation-round-2.md`.

### 2.2 Feedback received

| Ref | Feedback from Stakeholders | Gap in the current requirements |
|---|---|---|
| E1 | There is no clear use case for many people coding together. | Simultaneous editing is specified without a scenario that needs it. |
| E2 | If someone presses Run while a teammate has half a line typed, an error is guaranteed. | A run uses the live shared document, and the result goes to everyone. |
| E3 | Requiring everyone in the room to approve each run is slow and open to misuse. | Approval-based run control is not a suitable answer to E2. |
| E4 | Co-editing one file undermines modularity. Different functions belong in separate modules, so an error points to one module. | There is no module concept, so a failure cannot be attributed to a part of the program or to a person. |
| E5 | The expected room size must be stated. | No requirement states it. |

The selective run treats a symptom of E2 only. The monolith suggestion was analysed afterwards and not adopted (section 3, Option C).

## 3. Options considered

Criteria: does it answer the concerns, how much existing work survives, can it be delivered in the remaining time, does it help novelty.

| Option | What it means | Answers | Reuse of existing work | Verdict |
|---|---|---|---|---|
| **A. Pivot** | Change the product to a different idea. | Possibly all, by replacing the idea. | Low. Most of the sync, execution and AI/ML work would be redesigned. | Rejected. The team decided not to pivot. |
| **B. Vote-to-run** | A run happens only after the other participants approve it. | Partly E2 (people check before running). Does not answer E1 or E4. | High | Rejected. It is exactly the concern in E3: slow on the most frequent action and abusable by a mischievous participant. |
| **C. "Monolith" defence** | Change nothing and argue the examiner's monolithic-architecture angle. | Avoids E4 rather than answering it. | Total | Rejected. The system already has four separate services, so the defence contradicts the architecture. Monolith versus microservice is about deployment, not about code modularity inside a program. |
| **D. Two modes (chosen)** | Pair mode for a controlled single file. Open mode for modules with ownership, saved-version runs, run-output choice and attributed errors. | E1 (named scenarios), E2 (one writer; saved-version runs), E3 (the runner controls sharing; nobody approves runs), E4 (modules, owners, attribution), E5 (stated size). | High. Sync relay, Docker runner, AI/ML service, CI and contracts stay. Changes are additions and tightening. | **Accepted** |

Notes:

- Option D keeps the old behaviour available. Everything built so far still works in Pair mode, with one extra rule (edit rights controlled by the Host). If the Host grants edit rights to several people, concurrent editing works as before.
- The selective run from the room is kept as a possible refinement. Its exact form is Open and is decided in Requirements v2.
- Vote-to-run may return later as an optional Host run policy (Open). It is not part of this change.

---

## 4. Decision

**Adopt Option D, two room modes** (Decided). Formal team and TA approval is recorded further.

### 4.1 Positioning and room size (Decided)

The product is a shared coding room with a shared, sandboxed run, for pairs and small groups of 2 to 5, with 10 as the supported maximum. Typical sessions:

- pair debugging between two students (driver and navigator);
- mock or technical interviews (a host watches a candidate type and run code);
- lab help (a TA joins a student's room, reads the code and the failing run, and can demonstrate a fix).

Room size 2 to 5 typical and 10 maximum is a **design assumption, not a measured figure**.

### 4.2 Roles

| Role | Mode | Powers |
|---|---|---|
| Host (room owner) | Both | Creates the room, removes users, grants and revokes edit rights, sets run policy, switches AI on or off, approves joins (optional) and module requests, sets the entry file, transfers the Host role |
| Participant | Both | Edits and runs only as permitted |
| Observer (read-only guest) | Both | Reads live; cannot edit or run |
| Module owner | Open | Exactly one per module. Grants or revokes write access, renames, deletes, transfers |
| Module writer | Open | Edits a module they were granted |

### 4.3 Pair mode

Decided:

- One shared file inside the room, no modules. All pre-redesign single-file functionality stays.
- The Host can grant and take back edit (driver) access at any time. The relay enforces this on every edit, so a modified client cannot bypass it.
- If the Host grants edit rights to several people, concurrent editing works as before.
- Run executes the live text at the click. The result shows who ran it and when, and goes to everyone in the room. With one editor there is no edit overlap. With several editors a half-typed line can still cause an error, which the timestamp makes explainable.
- Presets at creation: Teach/Interview (only the Host edits by default) and Debug together (everyone edits by default).
- A Request edit action that the Host approves.

### 4.4 Open mode

Decided:

- Anyone can request a module. The Host approves. The requester becomes the owner unless the Host picks someone else. Exactly one owner per module. The owner (or Host) can give write access to many people.
- Everyone in the room can read the live edits of any module.
- Run executes the last saved version in a Docker sandbox. A runnable module can be run alone, or the whole workspace can be run from the entry file.
- Run output is shown to everyone only if the runner chooses, and is always labelled with who ran it.
- When an error occurs, the failing module is pointed out with its owner's name.
- Line-level attribution (who wrote which line) is welcome if feasible.
- Saving is manual. A small dot on a module means it was edited after the last save.
- The Host sets the entry file.
- Run output is private by default, with a Share to room button.
- The error message also shows who last saved the module, because a shared module could otherwise blame the wrong person.
- One language per workspace. The owner marks a module as runnable.

Open:

- The Yjs garbage-collection decision. Line-level attribution needs the shared document to keep deleted content (garbage collection off), which raises memory use and must be tested with long sessions.

### 4.5 Common to both modes (Proposed)

- Long random room links, display names, optional Host approval to join (instant join stays the default, per CR01).
- Host leaves: transfer the role, or close the room after a timeout.
- Room lifetime: code and run records are deleted with the room.
- Export as a zip download (GitHub push is optional and later).
- A visible audit log of role and permission changes.
- Limits on module count, module size and room size (values from testing).
- AI policy: a Host switch for AI, off by default for interview rooms.

### 4.6 AI/ML tasks

Principles (Decided): measure before making claims; use the cheapest method first (cache, then rules and statistics, then a local model if available, then a paid model with a limited prompt and token budget, then a fallback message); return normal output first; no external AI call without the user's consent; no source code or user text in logs; a suggested patch is not a proven improvement until the sandbox has tested it.

The five tasks are the agreed work list (Decided):

1. Time-complexity measurement and verification of suggested fixes in the sandbox (runs input sizes n, 2n, 4n).
2. Slowdown detection on run history (median and MAD baseline, flag runs at least 8 times slower, configurable).
3. Failing-input generation and minimisation against a brute-force reference, with a Save as Test Case action.
4. C/C++ crash explanation using optional AddressSanitizer and UndefinedBehaviorSanitizer runs.
5. AI usage and cost control: normalised-code caching, usage counters, hourly per-room limits, a monthly cap with an alert at 80 percent, error code `COST_CAP_REACHED`.


### 4.7 What does not change

- All-Java Spring Boot backend. This was debated, settled and confirmed, and is not reopened.
- The four service folders (client, sync-service, execution-service, ai-ml-service) and the T-05 decision on where platform features live. Ports and paths in `docs/api-contracts.md` are the currently documented placeholder values, not final.
- Sandbox rules: new container per run, no network, non-root, read-only code mount, limits from configuration (FR5, NFR3, NFR4, DR2, DR3).
- AI/ML as a separate Python service, consent first, cost cap, never in the critical path of a result (FR12, NFR7, NFR9).
- Branch protection, secret scanning and CI (NFR10, NFR11).
- Languages and testing priority (DR1). Sprint 1 execution is Python only.

---

## 5. Revision history

| Version | Date | Author | Change |
|---|---|---|---|
| 1 | 29 Sept 2026 | Team | First draft |
| 2 | 26 Oct 2026 | Team | Redesign aligned with newly gathered stakeholder elicitation findings.: five examiner concerns, corrected reasons for rejecting vote-to-run and the monolith defence, Decided/Open labels, per-mode run visibility, roles, Host AI switch, missing requirement areas, corrected AI task priority, corrected PR and issue handling |
