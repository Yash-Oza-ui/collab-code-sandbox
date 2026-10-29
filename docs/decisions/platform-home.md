# Decision Record: Where the platform features live (T-05)

**Issue:** T-05 · #8
**Branch:** `feature/T-05-platform-decision`
**Author:** Yash Oza (Category D)
**Date:** 12 October 2026
**Status:** Proposed. Becomes Accepted when the reviewers below approve the PR.
**Reviewers:** Femina Rathod (D), Het Kapadiya (A), Jay Limbasiya (B). FYI: Prince Gadara (C), because section 3 touches ai-ml-service.

## 1. Context

The Sprint 1 plan scaffolds four folders (`client/`, `sync-service/`, `execution-service/`, `ai-ml-service/`), but conflict CR05 says GitHub import runs in "the platform service". The platform features (room and owner state, the block list, GitHub OAuth and import, AI consent and cost cap, history retention) need exactly one home each, so later stories (FR9, FR10, FR12, NFR7, FR15) and T-10 know where to build.

Constraints that apply to every option:

- Java / Spring Boot (final architecture).
- CR05 and NFR3.1: GitHub import runs **outside** the execution sandbox, and sandbox containers never get network access.
- FR10: only the `read:user` and `user:email` scopes, public repositories only.
- NFR10: OAuth client secrets come from environment variables only.
- No database or queue has been chosen yet. This record does not choose one (see section 5).

## 2. Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. Platform features live in `sync-service/`** | No new folder, CI job or compose entry. It already accepts the browser's WebSocket connections, which is where the owner, read-only and block-list checks must happen (FR9.4, FR9.6, CR04). It is T-10's default. | Mixes relay and platform code in one service. Platform work must not slow edit relay (NFR1). |
| **B. New `platform-service/` folder** | Cleanest separation of concerns. | A fifth folder needs a skeleton, health endpoint, README, CI job and compose entry, and the Sprint 1 scope says four folders. Adds a network hop to every connection check. |
| **C. Split across existing services** (for example import in `execution-service/`) | No new folder. | `execution-service/` has access to Docker, so adding outbound network calls and OAuth secrets there widens the blast radius of a sandbox escape. |

## 3. Decision

**Option A, with AI consent and cost cap staying in `ai-ml-service/`. No new folder is created in Sprint 1.**

| Responsibility | Requirements | Owning service | Why |
|---|---|---|---|
| Room and owner state (room settings, join approval, read-only guests, owner token) | FR9.1 to FR9.5 | `sync-service` | Connections are accepted here; T-10 default. |
| Removed-user block list, kept per room by session id | FR9.6, CR04 | `sync-service` | Checked when a WebSocket connects, so a reconnect cannot bypass it (FR11). |
| GitHub OAuth and public-repository import | FR10, CR05 | `sync-service` | Runs in the service process, never inside a sandbox container, and never in `execution-service`. Secrets from environment variables. |
| AI consent state and AI cost-cap tracking | FR12, NFR7 | `ai-ml-service` | Matches `docs/ai-ml-contract.md` (T-07): the consent gate and the cost-cap enforcement are Category C work in Sprint 3. The caller supplies the consent value on each request. |
| Execution history retention | FR15 | `sync-service` | Every run result already passes through `sync-service` on its way to the room (T-06 section 5). FR15 is still Provisional, so this is provisional too. |

"Which service stores this?" has exactly one answer in the table above.

**Confidence.** Room and owner state, the block list and AI consent and cost cap are firm, because the first two must be enforced where connections are accepted and the third already matches T-07. GitHub import and FR15 retention are provisional: both are Sprint 4 and low priority, no code exists yet, and no storage is chosen, so they are cheap to revisit.

CR05 is satisfied: import never runs in the execution sandbox, and sandbox containers still receive no network access (NFR3.1).

## 4. Consequences

- **T-10** builds owner identity inside `sync-service`. T-02 (the `sync-service` scaffold, PR #35) is still in review, so T-10's owner coordinates with the T-02 owners before touching that folder.
- **T-06 v1** adds a room-creation endpoint on `sync-service` that returns the room id and the owner token. This replaces the v0 rule that a room is created when someone first connects. The owner token is never sent in the WebSocket URL.
- Platform code goes in its own package and does not run on the relay's message-forwarding path, so it cannot slow edit sync (NFR1).
- **T-06 section 3.5, rule 2** says "the relay stores nothing: no document, no history". T-06 v1 narrows it: the message-forwarding path stores no document, while platform state (rooms, owners, the block list and, later, history) is held by `sync-service` in a separate package.
- The root README's Platform row and its sentence about T-05 are updated in this PR to point at this record.

## 5. Open points

- **Storage.** No database or queue choice exists yet. For Sprint 1, room and owner state can be held in memory behind an interface. A durable store must be chosen before state has to survive a restart or before FR15 is built (Sprint 4), in its own decision record.
- **Revisit trigger.** If `sync-service` gains FR10 and FR15 and becomes too large, or platform work starts affecting NFR1 at the Sprint 2 load test, create a `platform-service/` in Sprint 4 through a new decision record.

## 6. Reviews

- [ ] Femina (D): the table above
- [ ] Het (A): room and owner state as the client will use it
- [ ] Jay (B): nothing platform-related lands in `execution-service`
- [ ] Decision posted in Slack #dev and linked in issue #8
