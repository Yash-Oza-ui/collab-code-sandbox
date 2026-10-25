# IT-314 Software Engineering --- Group 32

## Product Backlog and Conflict-Resolution Log

**Project:** Collaborative Code Editor with Sandboxed Execution\
**Deliverable:** Day 3\
**Group members:** Chandan Luhar, Quincy Vadi, Bhavya Vekariya\
**Repository:** https://github.com/Yash-Oza-ui/collab-code-sandbox

This backlog is rebuilt against the finalized Group 2 requirements
document (Requirements Specification, EPICs and Sprint Plan). It keeps
the same six EPICs, priorities and four-sprint plan, and follows Group
2's requirement order rather than the earlier draft's story order, so
the two documents can be read side by side.

It covers all requirement IDs in that document: FR1--FR16, NFR1--NFR12
and DR1--DR3, either as a dedicated story or, where a requirement is
purely a delivery constraint on other stories (NFR9), as a shared
acceptance criterion. One story is written per requirement, except where
a requirement bundles two behaviours that are built and tested
separately; those get one story each, noted below. NFR2 and DR3 are
included as validation work, since they still need load-test or
deployment-host evidence rather than guessed values.

# 1. Product Backlog

## 1.1 EPIC 1 --- Real-Time Collaborative Editor (Category A)

### US-01 --- FR1 \| Sprint 1 \| High

**User story:** As a student, I want to create a room and share a join
link so that my teammates can join the same coding session.

**Acceptance criteria**

- - Creating a room generates a shareable join link.
- Opening the link loads the same Yjs-backed shared document for all users.

### US-02 --- FR2 \| Sprint 1 \| High

**User story:** As a collaborator, I want simultaneous edits to merge
safely so that nobody loses work.

**Acceptance criteria**

- - Concurrent edits merge through Yjs without overwriting another user's changes.
- Connected clients reach the same document state.

### US-03 --- FR3 \| Sprint 2 \| High

**User story:** As a collaborator, I want to see live cursors and
presence so that I know who is editing.

**Acceptance criteria**

- - Each active collaborator's cursor and selection are visible with name and colour (FR3.1).
- A collaborator idle for about 30 seconds is removed from the presence list (FR3.2).

### US-04 --- FR11 \| Sprint 2 \| High

**User story:** As a collaborator, I want automatic reconnection and
resync so that I do not lose work after a disconnect or refresh.

**Acceptance criteria**

- - The client reconnects automatically with exponential backoff after a disconnect (FR11.1).
- The document resynchronizes to the latest shared state with no manual step (FR11.2).

**Note:** Reconnection does not override a removal --- see US-28
(FR9.6).

### US-05 --- FR14 \| Sprint 1 \| Medium

**User story:** As a student without GitHub login, I want to enter a
display name on joining so that teammates can identify my cursor and
edits.

**Acceptance criteria**

- - A user who skips GitHub login is prompted for a display name before joining.
- That name and an assigned colour label the user's cursor and presence entry.

### US-06 --- NFR1 \| Sprint 2 \| High

**User story:** As a collaborator, I want edits to appear quickly so
that the editor feels real-time.

**Acceptance criteria**

- - 95th-percentile edit-sync latency is 200 ms or less under test set-up T1.

### US-07 --- NFR12 \| Sprint 2 \| Low

**User story:** As a student, I want the editor to work in my regular
browser so that I don't need a specific setup to collaborate.

**Acceptance criteria**

- - The client is verified in the current versions of Chrome, Edge and Firefox.

## 1.2 EPIC 2 --- Sandboxed Execution Engine (Category B)

### US-08 --- FR4 \| Sprint 1 \| High

**User story:** As a student, I want to run code and see the result so
that I can check whether my program works.

**Acceptance criteria**

- - The execution request includes code, language and stdin when required (FR4.1).
- stdout and stderr are shown after execution, including failure cases (FR4.2).
- The result is shown to every participant in the room (FR4.5).

**Note:** The exit code (FR4.4) and the metrics bar (FR4.3, FR6) ship
together in Sprint 2 --- see US-10.

### US-09 --- FR5 \| Sprint 1 \| High

**User story:** As a platform operator, I want every run isolated in a
temporary Docker container so that user code cannot affect the host or
other users.

**Acceptance criteria**

- - Each run uses its own new Docker container (FR5.1).
- The container and its workspace are destroyed after execution; no run state is reused (FR5.2).

### US-10 --- FR6 \| Sprint 2 \| Medium

**User story:** As a student, I want execution time and memory usage so
that I can understand basic performance.

**Acceptance criteria**

- - Execution time and peak memory are captured for each run.
- Time, memory and the exit code are shown separately from stdout/stderr in a compact metrics area (FR4.3, FR4.4).

### US-11 --- FR16 \| Sprint 2 \| Medium

**User story:** As a student, I want a clear message when the system is
busy so that I know to try again instead of assuming it's broken.

**Acceptance criteria**

- - A run request beyond the execution queue's maximum length is rejected.
- The rejection message clearly says the system is busy and to try again.

### US-12 --- NFR3 \| Sprint 2 \| High

**User story:** As a platform operator, I want strong sandbox isolation
so that untrusted code stays contained.

**Acceptance criteria**

- - Execution has no network access (NFR3.1).
- Code runs as a non-root user and the code mount is read-only (NFR3.2, NFR3.3).
- A run cannot access host files or another user's data (NFR3.4).

### US-13 --- NFR4 \| Sprint 2 \| High

**User story:** As a platform operator, I want strict resource limits so
that runaway code cannot exhaust the system.

**Acceptance criteria**

- - Wall-clock execution is limited to 5 seconds (NFR4.1).
- Memory is limited to 128 MB with swap disabled (NFR4.2).
- Process/thread count (initial 64) and output size (initial 1 MB) are capped (NFR4.3, NFR4.4).
- Compiled languages use a separate compile-stage timeout, initially 10 seconds (NFR4.5).

### US-14 --- DR1 \| Sprint 2 \| High

**User story:** As a student, I want the main coursework languages
supported so that I can use the editor for normal lab work.

**Acceptance criteria**

- - Supported languages are C++, Python, C, Java and JavaScript.
- Testing priority follows C++ first, then Python, C, Java and JavaScript.

### US-15 --- DR2 \| Sprint 2 \| High

**User story:** As a platform operator, I want execution on self-managed
Docker containers so that the team controls the sandbox.

**Acceptance criteria**

- - Code execution uses the team's Docker runtime.
- Judge0 and Piston are used only as configuration references, not as the runtime.

### US-16 --- DR3 \| Sprint 4 \| Medium

**User story:** As a platform operator, I want resource limits
configurable per deployment host so that the sandbox can match the
available hardware.

**Acceptance criteria**

- - NFR4 limits are read from configuration, not hard-coded.
- Final host-specific values are recorded only after the deployment host is fixed and tested.

**Note:** The configuration mechanism itself is built in Sprint 2
alongside NFR4, per Group 2's document; only host-specific tuning
remains for Sprint 4.

## 1.3 EPIC 3 --- AI Code Review and Assistance (Category C)

### US-17 --- FR12 \| Sprint 3 \| High

**User story:** As a student, I want to give consent before code is sent
to an external AI service so that I control external use of my code.

**Acceptance criteria**

- - A first-use notice explains that code will be sent to an external AI service (FR12.1).
- AI features stay blocked until the user consents (FR12.2).
- Declining consent does not affect editing or code execution (FR12.3).

### US-18 --- FR7 \| Sprint 3 \| Medium

**User story:** As a student, I want to request AI code review only when
I need it, and dismiss a result so that the feature stays optional and
out of my way.

**Acceptance criteria**

- - AI review runs only after a user request, and only once FR12 consent is given (FR7.1).
- A review result can be dismissed by the user (FR7.2).

### US-19 --- FR7.3 \| Sprint 3 \| Medium

**User story:** As a student, I want to turn AI review off entirely so
that I don't have to dismiss it every time if I never want it.

**Acceptance criteria**

- - A setting exists to disable AI review for the user, separate from dismissing a single result.
- While disabled, no review is generated even if otherwise triggerable.

**Note:** New story split from FR7 --- dismissing one result (US-18) and
disabling the feature are different, independently testable behaviours.

### US-20 --- FR13 \| Sprint 3 \| Medium

**User story:** As a student, I want a short explanation when execution
fails so that I can understand the error more easily.

**Acceptance criteria**

- - Compile errors, non-zero exits and timeout failures can produce a plain-language explanation, subject to FR12 consent, of at most three sentences (FR13.1).
- The explanation is shown beside stderr and kept separate from the FR7 review, arriving after the result as its own message (FR13.2, NFR9).

### US-21 --- FR12.4 \| Sprint 3 \| High

**User story:** As a student, I want to withdraw AI consent later, not
just at first use so that I can change my mind without losing access to
editing or execution.

**Acceptance criteria**

- - Consent can be withdrawn from an active session, not only granted once at first use.
- Withdrawing consent immediately blocks further AI calls, but does not affect editing or execution (FR12.3).

**Note:** New story split from FR12 --- the initial consent gate (US-17)
and ongoing per-session control are different, independently testable
behaviours.

### US-22 --- NFR5 \| Sprint 3 \| Medium

**User story:** As a platform operator, I want AI requests controlled so
that repeated calls do not waste quota or cost.

**Acceptance criteria**

- - AI calls are user-triggered (NFR5.1).
- Results are cached by a hash of code, language, request type and stderr where relevant, storing only the hash (NFR5.2).
- Requests are rate-limited per room (NFR5.3).

### US-23 --- NFR6 \| Sprint 3 \| Medium

**User story:** As a student, I want the editor and execution to keep
working when the AI service is rate-limited so that a slow AI provider
never blocks my work.

**Acceptance criteria**

- - A 429 response is followed by waiting for the provided retry-after time.
- Editor and execution remain usable while AI is unavailable or waiting.

### US-24 --- NFR7 \| Sprint 3 \| Medium

**User story:** As a platform operator, I want a monthly external-AI
cost cap so that spending stays controlled.

**Acceptance criteria**

- - Cumulative AI API spend is tracked (NFR7.1).
- Further AI review/explanation calls are blocked once the configured cap is reached; editing and execution continue (NFR7.2).
- The team lead is alerted when the cap is reached (NFR7.3).

### US-25 --- NFR8 \| Sprint 3 \| Medium

**User story:** As a platform operator, I want AI/ML API calls logged so
that failures and usage can be audited.

**Acceptance criteria**

- - Request metadata, response status and latency are logged server-side (NFR8.1).
- Logs do not store extra raw user code beyond what the call itself requires (NFR8.2).

**Note:** Kept in Sprint 3, not Sprint 4 --- NFR7's cost cap needs call
records from the same sprint the AI features ship in.

## 1.4 EPIC 4 --- ML Execution Anomaly Detection (Category C)

NFR9 (result never delayed by AI/ML processing) is not a separate story;
it is a shared acceptance criterion, already stated in US-20 (FR13.2)
and below in US-26 (FR8.2). A dedicated story would have no code path of
its own beyond what those two already build.

### US-26 --- FR8 \| Sprint 3 \| Medium

**User story:** As a student, I want a warning when execution behaviour
looks unusual so that I can investigate it without losing my result.

**Acceptance criteria**

- - An anomaly score above the Sprint-3-validated threshold produces a short reason/warning (FR8.1).
- The warning is non-blocking, arrives after the result as a separate message, and is shown to everyone in the room like the result itself (FR8.2, NFR9).

## 1.5 EPIC 5 --- Room and Session Management (Category A / D)

### US-27 --- FR9 \| Sprint 2 \| High

**User story:** As a room owner, I want basic participant and ownership
controls so that I can manage the session.

**Acceptance criteria**

- - The owner is notified when a user joins (FR9.1) and can remove a participant (FR9.2).
- Join approval is optional and can be enabled per room; instant join stays the default (FR9.3).
- The owner can make a guest read-only, enforced on the server; a read-only guest cannot edit or press Run (FR9.4).
- Ownership survives a page refresh or reconnect, via a browser-saved token or GitHub login (FR9.5).

### US-28 --- FR9.6 \| Sprint 2 \| High

**User story:** As a room owner, I want a removed user to stay out until
I re-admit them so that removal is actually effective, not just
cosmetic.

**Acceptance criteria**

- - A removed user is blocked from that room by session ID.
- The block holds until the owner re-admits them or regenerates the room link.
- Automatic reconnection (FR11) does not let a removed user bypass the block.

**Note:** New story split from FR9 --- removing a participant (US-27)
and enforcing that the removal sticks are different, independently
testable behaviours.

## 1.6 EPIC 6 --- Platform: Auth, Data and Quality (Category D)

### US-29 --- FR10 \| Sprint 4 \| Low

**User story:** As a student, I want optional GitHub login and
public-repository import so that I can bring existing code into the
editor.

**Acceptance criteria**

- - GitHub OAuth is optional and requests only `read:user` and `user:email` (FR10.1).
- Public-repository import is in scope; private-repository import is out of scope (FR10.2).

### US-30 --- FR15 \| Sprint 4 \| Low

**User story:** As a platform operator, I want execution history to
auto-delete after a limited time so that old runs don't accumulate as a
storage or privacy liability.

**Acceptance criteria**

- - Execution history for a room is deleted automatically after a configured period.
- The exact period (currently proposed at around 24 hours) is confirmed by the team before this is marked Final.

**Status:** Provisional until the team confirms the retention period.

### US-31 --- NFR2 \| Sprint 2 / Sprint 4 \| Medium

**User story:** As a platform operator, I want the room to support at
least 10 concurrent users without breaking the sync target so that the
editor holds up under a full-size study group.

**Acceptance criteria**

- - A controlled test under test set-up T1 runs at least 10 concurrent users in one room.
- Sprint 2: baseline run on development machines. Sprint 4: re-run on the final deployment host.
- NFR1 (200 ms or less at the 95th percentile) is recorded as met or not met at that load; the target is not marked verified until both measurements exist.

### US-32 --- NFR10 \| Sprint 1 \| High

**User story:** As a platform operator, I want every commit scanned for
secrets so that an accidental API key never reaches the repository.

**Acceptance criteria**

- - The gitleaks pre-commit hook is installed repo-wide before any service scaffolding lands.
- A commit containing a detectable secret pattern is blocked locally.

### US-33 --- NFR11 \| Sprint 1 \| Medium

**User story:** As a platform operator, I want every change reviewed and
build-checked before merging so that broken or unreviewed code never
reaches `main`.

**Acceptance criteria**

- - From Sprint 1 onward, `main` is protected: direct pushes are rejected.
- A pull request needs one approval and a passing automated build before it can merge.

**Note:** Scoped to "from Sprint 1 onward" --- the Day-0 repository
skeleton (folders, `.gitignore`, initial scaffolding) predates this rule
and was pushed directly by agreement; see CR-07.

# 2. Conflict-Resolution Log

Seven conflicts were identified across the finalized requirements. Three
carry over from the original log; four are new, surfaced by this
reorganization and by review.

| ID | Requirements | Conflict | Resolution | Status |
|---|---|---|---|---|
| CR-01 | FR1 vs FR9.3 | FR1 supports joining by link; FR9.3 lets the owner require approval first. | Instant join stays the default. Approval is an optional per-room owner setting. | Resolved |
| CR-02 | FR7 / FR13 vs NFR7 / NFR6 | AI review and failure explanations are on demand, but the monthly cost cap can stop further paid AI calls. | Only the AI add-ons (review and explanation) are blocked once the cap is reached. Editor and execution continue under NFR6/NFR9. | Resolved |
| CR-03 | NFR2 vs DR3 | NFR2 targets at least 10 users; DR3 says the real safe limit depends on deployment hardware. | Test under set-up T1 on development machines in Sprint 2, then again on the actual host in Sprint 4. Do not claim the target verified before both measurements exist. | Validation required |
| CR-04 | FR11 vs FR9.6 | Automatic reconnection could let a removed user silently rejoin the room. | The server keeps a per-room block list by session ID; a removed user's reconnect attempts are rejected regardless of FR11. | Resolved |
| CR-05 | NFR3 vs FR10 | The sandbox has no network access, but GitHub import (FR10.2) needs one to fetch a repository. | Import runs in the platform service, outside the execution sandbox. Sandbox containers never receive network access. | Resolved |
| CR-06 | FR12 vs FR7 / FR13 | AI features depend on code leaving the sandbox, but a user can decline or withdraw consent. | Declining or withdrawing consent (FR12.3, FR12.4) disables only the AI add-ons. Editing and execution are unaffected. | Resolved |
| CR-07 | NFR11 vs Day-0 setup | NFR11 requires every change through a pull request, but the initial repository skeleton was pushed directly to `main` before branch protection's bypass setting was locked. | NFR11 is scoped to apply from Sprint 1 onward. The Day-0 skeleton (folders, `.gitignore`) is a documented, one-time exception made by agreement before enforcement began. | Resolved |
