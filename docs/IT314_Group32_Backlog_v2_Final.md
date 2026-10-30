# IT-314 Software Engineering

## Product Backlog, Version 2

**Group 32 | Collaborative Code Editor with Sandboxed Execution**

| Field | Value |
|---|---|
| Document | Product Backlog (user stories, enabler tasks, traceability) |
| Version | 2 |
| Date | 28 October 2026 |
| Supersedes | Product Backlog, Day-3 deliverable (version 1, file `IT314_Group32_Backlog_ConflictLog_REV.pdf`, kept unchanged) |
| Built against | Requirements Specification version 2 (`IT314_Group32_Requirements_v2_Final.md`) |
| Change basis | CHG-01 (`CHG_01.md`) |


US-01 to US-33 keep their IDs. Each is marked **Unchanged**, **Modified** or **Removed** (with the story that replaces it, where one exists). New stories are appended from US-34. Enabler tasks continue from T-16 as T-17 onward. Nothing is renumbered or deleted.

The Conflict-Resolution Log (CR01 to CR15) is kept in Requirements v2, section 6, and is not repeated here. The sprint plan is in section 9. It uses sprint numbers only, with no dates.

---

## 1. How to Read This Document

- **Cat** is the lead category: A Frontend/Editor, B Execution and Sandbox, C AI/GenAI and ML, D Platform/Auth/Infra/QA. A story may need support from another category.
- **Priority** is taken from the highest-priority requirement the story covers.
- **Depends on** lists stories or tasks that must exist first. It is a build order, not a schedule.
- Requirement IDs in brackets point to Requirements v2.
- **Shared acceptance criteria** (section 7) apply to every story they name. They are not repeated inside each story.

---

## 2. EPICs

The six EPICs from version 1 keep their IDs. Two are retitled because their requirements were removed or replaced, and one is added.

| EPIC | Title | Cat | Change | Stories |
|---|---|---|---|---|
| EPIC 1 | Real-Time Collaborative Editor | A | Unchanged | US-01 to US-07 |
| EPIC 2 | Sandboxed Execution Engine | B | Unchanged | US-08 to US-16, US-64 |
| EPIC 3 | AI Analysis and Cost Control | C | Retitled (was AI Code Review and Assistance) | US-17, US-21, US-22, US-24, US-25, US-65 to US-67, US-70 to US-78. Removed: US-18, US-19, US-20, US-23 |
| EPIC 4 | Run History and Slowdown Detection | C | Retitled (was ML Execution Anomaly Detection) | US-52, US-68, US-69. Removed: US-26 |
| EPIC 5 | Room and Session Management | A / D | Extended | US-27, US-28, US-34 to US-38, US-54 to US-63 |
| EPIC 6 | Platform: Auth, Data and Quality | D | Unchanged | US-29 to US-33 |
| EPIC 7 | Open Mode Workspace | A / B | New | US-39 to US-51, US-53 |

---

## 3. Status of the 33 Version 1 Stories

| Story | Requirement | Status in v2 | Reason |
|---|---|---|---|
| US-01 | FR1 | **Modified** | Room creation includes the mode choice (FR1). |
| US-02 | FR2 | **Modified** | Merging applies per module in Open mode (FR2). |
| US-03 | FR3 | **Modified** | Presence shows the role (FR3.1). |
| US-04 | FR11 | **Modified** | Resync covers every module (FR11.2). |
| US-05 | FR14 | Unchanged | |
| US-06 | NFR1 | Unchanged | |
| US-07 | NFR12 | Unchanged | |
| US-08 | FR4 | **Modified** | New run request and result visibility (FR4.1, FR4.5). |
| US-09 | FR5 | **Modified** | A generated-test batch counts as one run (FR5.1). |
| US-10 | FR6 | Unchanged | |
| US-11 | FR16 | **Modified** | The queue limit counts repeated runs (FR16). |
| US-12 | NFR3 | Unchanged | |
| US-13 | NFR4 | **Modified** | The 5 s limit applies per run and per test in a batch (NFR4.1). |
| US-14 | DR1 | Unchanged | |
| US-15 | DR2 | Unchanged | |
| US-16 | DR3 | **Modified** | Configuration covers the new budgets and limits (DR3). |
| US-17 | FR12 | Unchanged | |
| US-18 | FR7 | **Removed** | FR7.1 and FR7.2 were removed (Requirements v2, section 7.5). |
| US-19 | FR7.3 | **Removed** | FR7.3 was removed. |
| US-20 | FR13 | **Removed**, narrower replacement US-74 | FR13 was removed. Only C and C++ crash explanation (FR39.3) remains. |
| US-21 | FR12.4 | Unchanged | |
| US-22 | NFR5 | **Modified** | NFR5.1, NFR5.2 and NFR5.3 changed. |
| US-23 | NFR6 | **Removed** | NFR6 was removed. US-78 covers an unavailable service. |
| US-24 | NFR7 | **Modified** | NFR7.2 changed, NFR7.3 removed, NFR7.4 and NFR7.5 added. |
| US-25 | NFR8 | **Modified** | NFR8.2 changed. |
| US-26 | FR8 | **Removed**, closest replacement US-68 | FR8 was removed. Slowdown detection (FR37) is the closest scope. |
| US-27 | FR9 | **Modified** | The read-only guest becomes the Observer role (FR9.4). |
| US-28 | FR9.6 | Unchanged | |
| US-29 | FR10 | Unchanged | |
| US-30 | FR15 | **Modified** | History is also deleted when the room is deleted (FR15). |
| US-31 | NFR2 | **Modified** | Room size and the three T1 variants (NFR2). |
| US-32 | NFR10 | Unchanged | |
| US-33 | NFR11 | Unchanged | |

| Class | Stories | Share of 33 |
|---|---|---|
| Unchanged | 13 | 39.4% |
| Modified | 15 | 45.5% |
| Removed | 5 | 15.2% |

After this version the backlog has 73 active stories: 28 from version 1 (13 unchanged and 15 modified) and 45 new (61.6% of the active stories). US-32 is already done, so 72 stories remain to be planned (section 9).

---

## 4. Version 1 Stories That Changed

Only the changed or added acceptance criteria are listed. Every other criterion of the version 1 story stays as it was. User stories keep their original wording unless a change is shown.

### US-01: Create a room and share a join link (FR1) | Modified | High | A
- Creating a room generates a shareable join link. The mode choice is added by US-34 (FR17).
- Opening the link loads the shared, Yjs-backed document in that mode.
- Depends on T-17, which decides who generates the room id.
- US-34 builds on this story and adds the mode choice.

### US-02: Merge simultaneous edits safely (FR2) | Modified | High | A
- In Open mode edits merge per module without overwriting another user's changes.
- In Pair mode merging applies whenever the Host has given edit access to more than one person.

### US-03: See live cursors and presence (FR3) | Modified | High | A
- A collaborator's cursor and selection show name, colour and role (FR3.1).
- The 30 s idle rule (FR3.2) is unchanged.

### US-04: Reconnect and resync (FR11) | Modified | High | A
- After reconnecting in Open mode every module resyncs without a manual step (FR11.2).
- Reconnection still does not let a removed user back in (US-28).

### US-08: Run code and see the result (FR4) | Modified | High | B
- The run request carries the language, optional stdin and the code to run (FR4.1). In Pair mode the code is the live text of the file.
- In Pair mode the result goes to every participant (FR4.5). Open mode visibility is US-49.
- The Open mode request shape (saved modules, runnable module or entry file) is added by US-47 and US-48.
- It is accepted only after a two-browser check with the client run panel: one person runs and both browsers show the result (T-11).
- Depends on T-19, T-11.

### US-09: Isolate every run in a temporary Docker container (FR5) | Modified | High | B
- A run is one container execution. A batch of generated tests counts as one run and uses one new container (FR5.1, US-71).
- The container and its workspace are still destroyed afterwards (FR5.2).

### US-11: Clear message when the system is busy (FR16) | Modified | Medium | B
- Repeated runs, including those made by a repeated-run feature, count toward the queue limit and are refused with the same "busy, try again" message.

### US-13: Strict resource limits (NFR4) | Modified | High | B
- The 5 s wall-clock limit applies to each run and to each test inside a generated-test batch (NFR4.1).
- NFR4.2 to NFR4.5 are unchanged.
- Depends on US-09.

### US-16: Resource limits configurable per host (DR3) | Modified | Medium | B
- Every limit listed in DR3 is read from configuration: resource limits, sanitizer limits, aggregate budget and run cap, room limits, Host-leaves timeout, slowdown factor, AI prompt and token budget, hourly per-room limit and monthly cost cap.
- Final host-specific values are recorded only after the deployment host is fixed and tested.
- Depends on US-13.

### US-22: AI requests controlled (NFR5) | Modified | Medium | C
- An AI call is made only as a step of an analysis the user has started, after consent and while AI is on (NFR5.1).
- The cache key uses a hash of the normalised code, the language, the request type and the error output where it matters. Only the hash is stored (NFR5.2).
- Normalisation removes comments and normalises whitespace without changing what the program means. A formatting-only change reuses the cached result, and a change in meaning does not. Whitespace that carries meaning, such as Python indentation, is kept.
- Requests are rate-limited per room with an hourly limit read from configuration (NFR5.3).
- Depends on US-17, T-18.

### US-24: Monthly AI cost cap (NFR7) | Modified | Medium | C
- Spend is tracked (NFR7.1).
- When the cap is reached only AI calls are blocked and the call returns `COST_CAP_REACHED`. Editing and execution continue (NFR7.2).
- The team lead is alerted when spend reaches 80 percent of the monthly cap (NFR7.4).
- Blocked AI calls do not stop the AI-free parts of the analyses (NFR7.5).
- The cap-reached alert (NFR7.3) is removed.
- Depends on US-22.

### US-25: AI/ML calls logged (NFR8) | Modified | Medium | C
- Every AI/ML call is logged with request metadata, response status and latency (NFR8.1). The log line carries `request_id`, `room_id`, `request_type`, `language`, `status`, `error_code` and `latency_ms` (`docs/ai-ml-contract.md` section 9).
- The room id is taken from the validated request body and is never `unknown`. The logging middleware in `app/main.py` must not rely on `request.state.room_id`, which no route sets.
- Logs never contain source code, stdin, stderr text, prompts, model output or other user-written text (NFR8.2) on any path: request logging, validation errors, exception handlers, the LLM client, the cache and the usage counters.
- Tests: one sends a unique marker in every user-text field of every live endpoint, including a request that fails, and checks that no log line contains it; one checks that latency and room id are present. PR #44 (merged) tests the marker on `/v1/review` only, and T-18 retires that endpoint, so the test must move to a live endpoint.

### US-27: Basic participant and Host controls (FR9) | Modified | High | A / D
- The Host can make a guest an Observer, enforced on the server. An Observer can read the file or modules, cursors and shared run results, but cannot edit or press Run (FR9.4).
- FR9.1, FR9.2, FR9.3 and FR9.5 are unchanged.
- The Observer role is enforced only on the server's role model (T-20), and only the Host, identified by the owner token (T-10), can assign it.
- Depends on T-10, T-20.

### US-30: Execution history deleted after a limited time (FR15) | Modified | Low | D
- History is deleted after the configured period and in any case when the room is deleted (FR15, US-56).
- The period (proposed at about 24 hours) is confirmed before FR15 is marked Final, and is chosen so a normal session keeps enough history for the slowdown baseline (CR11).
- Depends on US-52.

### US-31: Room size and load test (NFR2) | Modified | Medium | D
- A controlled test runs 10 simulated users under three variants of set-up T1: T1-P (one editor), T1-A (everyone edits) and T1-O (Open mode, five modules).
- Each variant is run on development machines first and on the final host later.
- NFR1 is recorded as met or not met at each load. The target is not marked verified until every variant has both measurements.
- The effect of write-permission checks (NFR13, CR13) is measured in the same runs.
- The runs use the room limits of US-63 (NFR15) with the people limit set to the supported maximum of 10, and one extra join is refused. The recorded results state which limit values were in force.
- Depends on T-22, US-43, US-63.

---

## 5. New Stories

Every story below is New (v2) with status Final unless it says Provisional.

### 5.1 Room modes and Pair mode (EPIC 5)

**US-34: Choose a room mode when creating a room** | FR17 | High | A / D
As a Host, I want to choose Pair or Open mode when I create a room so that the room behaves the way my session needs.
- The room mode is chosen at creation and cannot be changed afterwards.
- Every participant sees the mode.
- Depends on T-20, US-01.

**US-35: Only people with edit access can edit in Pair mode** | FR18, NFR13 | High | B
As a Host, I want the server to stop anyone without edit access from changing the file so that a modified client cannot bypass the rule.
- A Pair mode room has one shared file and no modules.
- A change from a participant without edit access, including from a modified client, is rejected and not forwarded to others.
- The check does not parse document content (CR13).
- Depends on T-20, US-34.

**US-36: Give and take back edit access** | FR19.1 | High | A / D
As a Host, I want to give edit access to one or several participants and take it back at any time so that I control who types.
- Giving or taking back access takes effect immediately for the person concerned.
- Several participants can have edit access at once.
- Depends on US-35.

**US-37: Pair mode presets** | FR19.2 | Medium | A / D
As a Host, I want to choose a Teach/Interview or Debug together preset so that a new room starts with sensible permissions.
- Teach/Interview: only the Host can edit by default, and AI is off (US-59).
- Debug together: everyone can edit by default.
- Depends on US-36.

**US-38: Request edit access** | FR19.3 | Medium | A
As a participant without edit access, I want to ask for it so that I can take over typing when the Host agrees.
- The Host sees the request and approves or declines it.
- Approval gives edit access as in US-36.
- Depends on US-36.

### 5.2 Open mode workspace (EPIC 7)

**US-39: Workspace with several modules** | FR20.1, DR4 | High | A / B
As a group member, I want a workspace with several modules in one language so that each part of the program is separate.
- A workspace holds several modules and uses one programming language.
- Module files keep language-appropriate names and extensions.
- Depends on US-34.

**US-40: Request a module and get one owner** | FR21.1, FR21.2, FR21.3 | High | A / D
As a participant, I want to request a module so that I have a part of the program that is mine.
- Any participant can request a module.
- The Host approves the request. The requester becomes the owner unless the Host chooses someone else.
- Each module has exactly one owner at all times.
- Depends on US-39.

**US-41: Owner manages a module** | FR21.4 | Low | A
As a module owner, I want to rename, delete or transfer my module so that I can keep the workspace tidy.
- Only the owner can rename, delete or transfer the module.
- Transfer makes the new person the single owner.
- Depends on US-40.

**US-42: A module never loses its owner** | FR21.5 | Medium | D
As a Host, I want to take over a module when its owner is removed so that every module always has exactly one owner.
- When the owner is removed from the room (FR9.2), the Host becomes the owner.
- The Host keeps the module until the Host transfers it.
- Depends on US-40, US-27.

**US-43: Control write access to a module** | FR22.1, FR22.2, NFR13 | High | B / D
As a module owner, I want to give and take back write access so that only the people I choose edit my module.
- The owner or the Host can give write access to one or several people and take it back.
- Only the owner and people with write access can edit the module. Everyone else, including the Host, can only read it.
- The server enforces this for every change, including from a modified client (NFR13).
- Depends on US-40, T-20.

**US-44: Read every module live** | FR23 | High | A
As a participant, I want to read every module as it changes so that I know what the others are doing.
- Everyone in the room, including Observers, sees live edits in every module.
- Depends on US-39.

**US-45: Save a module manually** | FR24.1, FR24.2 | Medium | A
As a module owner or writer, I want to save my module myself so that a run only uses code I chose to save.
- Saving is manual and available to the owner and writers only.
- A small dot beside a module shows that it has changed since the last save.
- Depends on US-43.

**US-46: Mark a module runnable and set the entry file** | FR20.2, FR20.3 | Medium | A
As a group, I want to mark runnable modules and set an entry file so that the workspace knows what can start a run.
- The module owner can mark a module as runnable.
- The Host sets the entry file.
- Depends on US-40.

**US-47: Run one runnable module** | FR25.1, FR25.2 | High | B
As a module owner, I want to run my module on its own so that I can test it without the rest of the program.
- A run in Open mode uses the saved versions of the modules, never the live text.
- A module marked runnable can be run on its own, and the run contains only that module.
- Depends on US-45, US-46, T-19.

**US-48: Run a selection of modules or the whole workspace** | FR25.3 | High | B
As a group member, I want to run the program through the entry file so that the modules are tested together.
- The run starts at the entry file and contains the entry file and the selected modules.
- A module that the entry file needs but that is not selected makes the run fail in the normal way, and the failure names that module (US-51).
- With no entry file set, the run is refused with a message saying so.
- All modules in the run come from one consistent snapshot of saved versions (section 6.1, decision 8).
- Depends on US-47.

**US-49: Keep a run result private or share it** | FR27, FR4.5, NFR9 | High | A / B
As a runner, I want my result to be private until I share it so that I can fix mistakes before anyone sees them.
- In Open mode the result is private to the runner by default.
- A Share to room button shows it to everyone. Nobody else has to approve a run.
- Analysis messages follow the same audience (NFR9, CR08).
- Depends on US-08, US-47.

**US-50: Label every result with who ran it** | FR26 | High | A / B
As a participant, I want each shown result to say who ran it and when so that I know which run I am reading.
- Every run result shown in a room names the runner and the time.
- In Open mode it also names the saved version that was run.
- Depends on US-08, T-19.

**US-51: Name the failing module and its people** | FR28, DR4 | High | B
As a group member, I want a failed run to name the failing module so that I know whose part to look at.
- In Open mode a failed run names the failing module, its owner and the person who last saved it.
- The module is found from file names in compile and run output (DR4).
- Depends on US-47, US-45.

**US-53: Show who wrote which line** | FR30 | Low, Provisional | A
As a group member, I want to see who wrote each line where this is feasible so that changes can be traced.
- The workspace shows the author of each line.
- The story is built only after the decision on keeping deleted content (T-21, CR14) and can be dropped without affecting any other story.
- Depends on T-21.

### 5.3 Run record and history (EPIC 4)

**US-52: Keep a record of every run** | FR29 | Medium | B
As a platform operator, I want a record of each run so that run history can be used later.
- The record holds the runner, time, language, files and saved versions run, input hash, and measured time and memory.
- The record contains no secrets and is deleted with the room (US-56).
- Depends on US-08.

### 5.4 Room lifecycle and room-level controls (EPIC 5)

**US-54: Transfer the Host role** | FR31.1 | Medium | D
As a Host, I want to hand the Host role to another participant so that the session can continue without me.
- The Host can transfer the role to any participant at any time.
- Depends on US-27.

**US-55: Close the room when the Host leaves** | FR31.2 | Medium | D
As a participant, I want a room whose Host has left without a handover to close after a timeout so that no room stays open without an owner.
- If the Host leaves without transferring the role, the room closes after the timeout unless the Host returns.
- The timeout is read from configuration (DR3).
- Depends on US-54.

**US-56: Delete room data with the room** | FR32 | Medium | D
As a platform operator, I want code and run records deleted when a room is deleted or closed so that no old data is kept.
- Deleting or closing a room deletes its code and run records, and the room-scoped measurements kept by ai-ml-service (FR37.4, section 6.2, decision 9).
- Depends on US-52, T-18, US-68.

**US-57: Export the room's files** | FR33 | Low | A
As a participant, I want to download the file or modules as a zip so that I can keep my work.
- The room's file or modules can be exported as a zip download.
- GitHub push is not part of this story.
- Depends on US-39 for the Open mode export.

**US-58: Audit log of permission changes** | FR34 | Low | D
As a participant, I want to see who changed roles and permissions so that I can understand why something changed.
- A visible log records role, edit-access, write-access and run-permission changes, with who made each change and when.
- Depends on US-36, US-43, US-60.

**US-59: Host AI switch** | FR35.1, FR35.2, FR35.3 | High | C / D
As a Host, I want to switch AI on or off for my room so that I can keep it off in an interview.
- The Host can switch AI on or off at any time.
- AI is off by default in a room created with the Teach/Interview preset and on by default in other rooms. User consent (US-17) is still needed.
- While AI is off no step that sends code to an external AI service runs (US-67, US-70, US-74). Editing, execution and the AI-free parts of the analyses continue.
- Depends on US-37, US-17.

**US-60: Control who can run** | FR41.1, FR41.2, FR41.3, FR41.4 | High | B / D
As a Host, I want to restrict who can run code so that one person cannot misuse the sandbox for everyone.
- Any Participant can run unless the Host restricts Run. An Observer cannot run.
- The Host can take away the right to run from a named participant and give it back.
- The Host can switch a room to Host-only running and back.
- The server checks run permission, and a refused run tells the user why.
- Depends on US-27, T-20.

**US-61: Unique display names** | FR42 | Medium | A / D
As a participant, I want display names to be unique in the room so that run labels and error messages name the right person.
- A name already taken in the room is refused.
- A person is identified by session ID or GitHub login.
- The relay enforces it from the join message (section 6.1, decision 4).
- Depends on US-05, T-20.

**US-62: Unguessable room links** | NFR16 | High | D
As a Host, I want room links that cannot be guessed so that only people I share the link with can find my room.
- A room link contains a random token of at least 128 bits from a secure random generator.
- Depends on US-01.

**US-63: Limits on modules and room size** | NFR15 | Medium | D
As a platform operator, I want limits on the number of modules, module size and people in a room so that one room cannot exhaust the service.
- The limits apply to modules per workspace, size of a module and people in a room.
- The values come from testing and are read from configuration (DR3).
- Depends on T-22.

### 5.5 Execution budget (EPIC 2)

**US-64: Budget for repeated runs** | NFR14 | Medium | B
As a platform operator, I want repeated-run features limited so that they cannot use all the sandbox time.
- Every run inside a repeated-run feature stays within the 5 s limit.
- The feature stays within a configurable aggregate time budget and a configurable number of runs per request (CR10).
- Depends on US-13, US-11.

### 5.6 AI analysis: Task 1, time complexity (EPIC 3)

**US-65: Measure time complexity** | FR36.1, FR36.2 | Medium | C / B
As a student, I want an estimate of how my program's run time grows so that I can see whether it will be too slow.
- The code runs at input sizes n, 2n and 4n, several times each, and the median runtime is taken at each size. The repeats count toward the run cap (US-64).
- A line is fitted to log(input size) against log(runtime). The result shows the estimated growth rate, the R² score and the input size at which a run would reach the time limit.
- Depends on US-64.

**US-66: Verify a suggested fix** | FR36.3 | Medium | C
As a student, I want a suggested fix tested before it is called an improvement so that I can trust it.
- A suggested fix is run in the sandbox.
- It is shown as a verified improvement only if its measured runtime is lower than the original's under comparable conditions.
- Depends on US-65.

**US-67: Look for slow patterns with rules first** | FR36.4 | Medium | C
As a platform operator, I want simple rules to look for slow patterns before any AI call so that AI cost stays low.
- Nested loops and repeated string concatenation are looked for with simple AST or regex rules.
- An AI call is made only when the rules find no likely issue, and only if consent is given and AI is on (US-17, US-59). Until US-59 exists the room AI setting is on by default.
- Depends on T-18, US-22.

### 5.7 AI analysis: Task 2, slowdown detection (EPIC 4)

**US-68: Flag a run that is much slower than usual** | FR37.1, FR37.2, FR37.4 | Medium | C
As a student, I want a warning when a run is far slower than my earlier runs so that I notice a regression.
- A run is compared with a median and MAD baseline built from runs with the same input hash.
- A run that is statistically unusual and at least 8 times slower is flagged. The factor is configurable and tested with seeded slowdowns.
- Runtime and memory measurements are kept in short-lived room memory together with a hash of the input.
- The flag arrives after the result as a separate message with the same audience as the result (NFR9).
- Depends on US-52.

**US-69: Highlight what changed in a slow run** | FR37.3 | Medium | A / C
As a student, I want the changed lines highlighted on a flagged run so that I can see what might have caused it.
- A flagged run highlights the lines changed since the last normal run for the same input.
- Depends on US-68.

### 5.8 AI analysis: Task 3, failing inputs (EPIC 3)

**US-70: Search for a failing input** | FR38.1, FR38.3 | Medium | C
As a student, I want the system to look for an input that breaks my program so that I find bugs I did not test.
- The search looks for an input that gives wrong output, crashes or times out. Wrong output is detected against a trusted brute-force reference solution when one is available. Without one, the search looks for crashes and timeouts only.
- Tests start with simple edge cases: empty input, n = 1, maximum bounds, duplicates and, where valid, negative values.
- A cached AI-generated script can add random inputs, only with consent and while AI is on. The script runs only inside the sandbox.
- Depends on US-71.

**US-71: Run a batch of generated tests in one container** | FR38.4, FR5.1, NFR4.1, NFR4.2 | High | B
As a platform operator, I want a test batch to use one container so that FR5.1 stays without exceptions.
- A batch counts as one run in one new container.
- Tests run one after another, each as a separate process within the time and memory limits (NFR4.1, NFR4.2). The whole batch stays within the aggregate budget (US-64).
- The container is destroyed afterwards and every sandbox isolation rule applies (CR15).
- Depends on US-09, US-64.

**US-72: Minimise a failing input and save it as a test** | FR38.5, FR38.2 | Medium | C
As a student, I want a small failing input so that I can see the problem and keep it as a test.
- When a test fails the system repeatedly removes or simplifies parts of the input while the failure remains.
- The minimised input is displayed, and a Save as Test Case action stores it.
- Depends on US-70.

### 5.9 AI analysis: Task 4, crash explanation (EPIC 3)

**US-73: Run C or C++ with sanitizers and read the report** | FR39.1, FR39.2, DR5 | Medium | B / C
As a student, I want an optional sanitizer run so that a crash gets a clear error type and line.
- The user can start an optional AddressSanitizer and UndefinedBehaviorSanitizer run for C or C++ code only.
- The report is parsed deterministically into an error type and, where possible, the line number.
- Sanitizer runs use their own configured resource limits and keep every sandbox isolation rule (NFR3).
- Depends on US-12, US-14, US-16.

**US-74: Explain a crash in plain language** | FR39.3 | Medium | C
As a student, I want a short explanation and a possible fix for a crash so that I understand it.
- The explanation and fix are produced from the error summary and the relevant code section only, with consent and while AI is on.
- Replaces US-20. The code of the existing explanation prototype (T-16) can be reused, but it must send only the relevant section and not the whole file (US-76).
- Depends on US-73, US-76.

### 5.10 AI cost control: Task 5 (EPIC 3)

**US-75: Count AI usage** | FR40.1 | Medium | C
As a platform operator, I want usage counters so that I can see AI use and cost.
- The system keeps counts of model calls, token usage, cache hits and fallback activations, per room and in total.
- Counters use SQLite or in-memory storage and never hold raw code or user text.

**US-76: Send only the relevant code** | FR40.2 | Medium | C
As a student, I want only the relevant part of my code sent to an AI service so that less of my code leaves the sandbox.
- A call sends only the code section relevant to the request, such as the target function or the small section around a reported error, and not the whole file.

**US-77: Use the cheapest method first** | FR40.3, FR40.4 | Medium | C
As a platform operator, I want the cheapest method tried first so that paid calls are the last choice.
- The order is cache, rules and statistics, a local model if available, then a paid model.
- A paid-model call has a limited prompt size and token budget read from configuration.
- Depends on US-22.

**US-78: Show a fallback message** | FR40.5 | Medium | C
As a student, I want a short message when no AI answer is available so that I am not left waiting.
- When no method can answer (consent is off, the cap is reached or the service is down), the user sees a short fallback message and the normal result is unaffected.
- Depends on US-24.

---

## 6. Enabler Tasks

These are technical tasks that several stories rely on. They continue the T-xx series. T-24 to T-26 are new in this revision.

| ID | Task | Needed by | Depends on | Cat |
|---|---|---|---|---|
| T-17 | Service contracts version 2 (`docs/api-contracts.md`): room creation, join and roles, module addressing, the run specification, run visibility and targeted delivery, permission errors, error-code reconciliation and legacy message types. Continues T-06 and carries its unconfirmed items, which are to be closed after T-17 is merged (see Sprint 2 in section 9.3).. The decisions are in section 6.1; the JSON examples are written into the contract and confirmed by every category. | US-01, US-34 to US-51, T-10, T-18, T-19, T-20 | None | D |
| T-18 | AI/ML contract version 2 (`docs/ai-ml-contract.md`): analysis endpoints for Tasks 1 to 4 and the usage counters, consent and Host switch fields, error codes including `COST_CAP_REACHED`, who starts the repeated and batch runs, where run history lives, and what happens to the review, explain and anomaly endpoints. The decisions are in section 6.2. | US-22, US-65 to US-78 | T-17 | C |
| T-19 | Change `CodeRunner` and the run request to the spec object of section 6.1 (decision 8), with the run result carrying runner, time, saved versions, visibility and failing module (decision 9). `CodeRunner` is already in `main` (PR #48) with the signature `execute(language, code, stdin)`, and `RunRequest.code` is a single string. A run of several modules uses one consistent snapshot of saved versions: the saved versions are read together in one call, and the result names exactly those versions (FR25.1, FR26). T-19 must land before the Docker runner (PR #40) merges. | US-08, US-09, US-47, US-48, US-50 | T-17 | B |
| T-20 | Room, role and permission model in sync-service: room creation, the document sockets and close codes of section 6.1 (decisions 3 and 6), the join message with unique names (FR42), roles, the permission check on edits, the session lookup that execution-service uses for run permission and the AI switch, and targeted delivery of internal messages. | US-34, US-35, US-43, US-60, US-61 | T-17 | D / B |
| T-21 | Decision: keep deleted content in the shared document for line attribution (Yjs garbage collection), tested with long sessions (CR14). | US-53 | US-44 | A |
| T-22 | Load-test harness for T1-P, T1-A and T1-O, also used to measure the cost of permission checks (CR03, CR13). | US-06, US-31, US-63 | None | D |
| T-23 | Architecture decision record "two room modes" under `docs/decisions/`. It also extends `docs/decisions/platform-home.md` with the state sync-service now holds: room mode, modules, saved versions, the audit log, the Host AI switch and run records. | All mode stories | None | D |
| T-24 | AI/ML evaluation report, so claims are measured before they are made. Seeded programs with known growth classes (Task 1), seeded slowdowns and threshold tests (Task 2), seeded buggy programs (Task 3), sample crashes for each sanitizer error type (Task 4), and counter, cap and fallback checks (Task 5): consent off, cap reached and service down each give the short message and leave the normal result unaffected. Results are written to `ai-ml-service/docs/`. | Acceptance of US-65 to US-78 | US-65, US-66, US-68, US-70, US-72, US-74, US-75, US-24, US-78 | C |
| T-25 | Put the requirement and backlog documents in `docs/`. (1) Requirements v2 and Backlog v2; keep the version 1 files. (2) The source documents that Requirements v2 names: `CHG_01.md`, `docs/elicitation-round-2.md`, `AI_ML_Requirements_v2.md`, `ai-ml-service.md` and `IT314_Group32_Stakeholders_Elicitation_Final.pdf`. Each one is either committed under `docs/` and linked from the README, or listed in the README as intentionally external with the place it is kept. None of them is in `docs/` today. (3) Reconcile `docs/decisions/platform-home.md`: its status says Proposed, but PR #39 is merged and issue #8 is closed, so set it to Accepted. (4) Update the README links. (5) Add the labels `epic-7` and `sprint-2` to `sprint-6`. | All | None | D |
| T-26 | Final documentation and demo script: README, service READMEs, and demo scenarios for Pair mode, Open mode and the AI analyses. It ends with a check that every document status in `docs/` (decision records, contracts) matches the state of the repository. | Final submission | T-25 | D |

### 6.1 Contract decisions for T-17

**Decided** means the requirements or an accepted decision record force the behaviour. **Proposed** means a design choice that each category confirms in the T-17 pull request (the T-06 rule). The field names are Proposed in every row until the contract is merged and confirmed.

| # | Topic | Decision | Basis | Status |
|---|---|---|---|---|
| 1 | Room creation | A room is created only by `POST /v1/rooms` on sync-service. The body carries `mode` (`pair` or `open`), `preset` (`teach_interview` or `debug_together`, Pair mode only) and `language` (one of the five, Open mode only). The response is 201 with `room_id`, `owner_token`, `mode`, `preset` and `language`. | FR1, FR17, FR19.2, FR20.1; `platform-home.md` section 4 | Decided |
| 2 | Who generates the room id | The server. The id is at least 128 bits from a secure random generator, written in base64url, so it fits the existing 1 to 64 character rule. The client cannot choose it. | NFR16, US-62 | Decided |
| 3 | Unknown room | Connecting to a room id that does not exist closes the socket with `4404`. The v0 rule that the relay creates a room on first connect is withdrawn. | FR17 (the mode is fixed at creation); `platform-home.md` section 4 | Decided |
| 4 | Join and unique names | After connecting, the client sends one text message `join` with `display_name` and, for the Host only, `owner_token`. The server answers `joined` (role, mode, preset, permissions) or `join-refused` with a code (`NAME_TAKEN`, `ROOM_FULL`, `USER_BLOCKED`, `APPROVAL_PENDING`). The owner token is never in the URL. This replaces the v0 rule that the client sends no text messages. The server needs the name because names today live only in Yjs awareness, which the relay never reads. | FR9.3, FR9.5, FR9.6, FR14, FR42, NFR15 | Proposed |
| 5 | Roles | Three roles: `host`, `participant`, `observer`. The server sends a `roster` message (session id, display name, role, permissions). The client takes labels and roles from it and not from awareness, because awareness can be forged. | FR3.1, FR9.4, FR42 | Proposed |
| 6 | Sockets | Two kinds. The control socket `/ws/rooms/{room_id}?session_id=` carries text messages only. A document socket `/ws/rooms/{room_id}/docs/{doc_id}?session_id=` carries Yjs frames only, with the v0 relay rules (echo of awareness, query-awareness frame, 1 MiB limit). Pair mode has one document, `main`. Open mode has one document per module, and its `doc_id` is the `module_id`. The client does not yet use the v0 binary URL, so the change is expected to be limited. | FR2, FR11.2, FR23 | Proposed |
| 7 | Module addressing | `module_id` is a server-generated opaque id (at least 16 URL-safe characters), never reused, and it survives rename and transfer. `name` is the file name with the language extension, unique in the workspace, and the owner can change it. Module operations are REST calls on sync-service (list, request, approve, rename, delete, transfer, set runnable, set entry file, grant and revoke write access, save). Each result is also pushed as a `module-updated` message. | FR20 to FR25, DR4 | Proposed |
| 8 | Run specification | `POST /v1/runs` keeps its path. The body carries `request_id`, `room_id`, `session_id`, `language`, `stdin` and one `source` object. Pair mode: `{"kind":"inline","files":[{"name":"main.py","content":"..."}],"entry":"main.py"}`. Open mode, one runnable module: `{"kind":"saved","target":"module","module_id":"..."}`. Open mode, through the entry file: `{"kind":"saved","target":"entry","module_ids":["..."]}` or `"module_ids":"all"`. In Open mode the client sends no code. execution-service reads the saved versions from sync-service, so a run cannot contain text that was never saved. The saved versions of all modules in one run are read together as one snapshot, so a save made while the run starts cannot mix versions, and `saved_versions` in the result lists exactly that snapshot. `code` is accepted for Pair mode only until T-19 lands and then rejected as `INVALID_REQUEST`. The T-14 convention `solution.py` is replaced by the file names in the request. | FR4.1, FR25.1 to FR25.3, DR4 | Proposed |
| 9 | Run result | Adds `runner` (`session_id`, `display_name`), `ran_at`, `saved_versions` (module, version, saved by, saved at; Open mode), `visibility` (`room` or `private`), `failure` (failing module, its owner and last saver, or `null`) and `compile_time_ms` (or `null`). `execution_time_ms` is the run stage only. Output cap is combined stdout and stderr, as in the v1 proposal. An out-of-memory kill uses `failure_kind: "non_zero_exit"` with `oom_killed: true`, never `"oom"`. | FR26, FR28, FR29, NFR4.4; contract open items 5 and 6 | Proposed |
| 10 | Result visibility | Pair mode: `room`. Open mode: `private` and delivered only to the runner's connections (every tab with that session id). `POST /v1/runs/{request_id}/share`, allowed to the runner only, sends the result and the analysis messages already produced for that run to the whole room, and sets `visibility` to `room`. Observers receive shared results only. The internal endpoint `POST /internal/rooms/{room_id}/messages` gains an optional `audience`: `{"kind":"room"}` (default) or `{"kind":"sessions","session_ids":[...]}`. | FR4.5, FR27, NFR9, CR08, FR9.4 | Decided (behaviour), Proposed (fields) |
| 11 | Permission lookup | On every run or analysis request, execution-service asks sync-service for the caller's role, run permission and the room's AI switch through `GET /internal/rooms/{room_id}/sessions/{session_id}` with `X-Internal-Token`. execution-service keeps no permission state. | FR41.4, FR9.4, FR35.1, NFR13; `platform-home.md` (room state lives in sync-service) | Proposed |
| 12 | Permission errors | `RUN_NOT_ALLOWED` (403) with an added optional `error.reason` of `observer`, `run_revoked` or `host_only`. `NOT_OWNER` (403) for an action only the Host may do (name kept from the v1 proposal). `NOT_MODULE_OWNER` (403), `NOT_RUNNER` (403, share), `MODULE_NOT_FOUND` (404), `ROOM_NOT_FOUND` (404), `MODULE_NOT_RUNNABLE` (422), `NO_ENTRY_FILE` (422), `NAME_TAKEN` (409) and `LIMIT_REACHED` (409, NFR15). A refused edit is never an HTTP error: the relay drops the frame and sends the control message `edit-rejected` with `doc_id` and `reason` (`no_edit_access`, `not_module_writer` or `observer`). The relay decides from the first two bytes of the frame and does not parse the document (CR13). New close codes: `4404` room not found and `4410` room closed (FR31.2). | FR9.4, FR25.2, FR25.3, FR41.4, NFR13, NFR15, CR13 | Decided (behaviour), Proposed (code names) |
| 13 | Legacy message types | `anomaly-warning` and `ai-explanation` are removed from the contract (FR8 and FR13 were removed, Requirements v2 section 7.5). One generic `analysis-result` message replaces them (fields in section 6.2, decision 6). `run-result` stays. | Requirements v2 section 7.5 | Decided (removal), Proposed (new name) |
| 14 | Error codes of the internal endpoint | sync-service uses `INVALID_MESSAGE` (400) for a body that is not JSON, has no `type` or is over 4 MiB. Its current `INVALID_BODY`, `INVALID_JSON` and `PAYLOAD_TOO_LARGE` are changed to match the contract, because clients and execution-service read the contract. The change is made in T-20. | Contract section 7.2 | Proposed |
| 15 | Carried from T-06 | Open item 3 (an empty room keeps no document): unchanged, revisited with the storage decision for FR15. Items 5 and 6: closed by decision 9. Item 9 (page route `/room/{room_id}`): confirmed in the real client during T-17 review. Items 4 and 7: closed by decisions 1 to 3 and 12. Item 8: closed by decision 13. | `api-contracts.md` section 9 | Proposed |

### 6.2 Contract decisions for T-18

| # | Topic | Decision | Basis | Status |
|---|---|---|---|---|
| 1 | `POST /v1/review` | Retired. The route, its tests and its section in the contract are removed (the git history keeps them). | FR7 removed (Requirements v2 section 7.5) | Decided |
| 2 | `POST /v1/explain` | Retired as a general explainer of failed runs. The prototype code (T-16, PR #45) is reused for crash explanation (US-74) after US-76 changes what it sends. | FR13 removed; FR39.3 | Decided |
| 3 | `POST /v1/anomaly` and the scorer | The endpoint is retired. It is a placeholder that always answers "not anomalous", so it is not counted as a delivered feature. The scorer (`app/ml/scorer.py`), the dataset generator and their tests stay in the repository, wired to no route and marked as outside the v2 scope. The team checks with the mentor (S4) whether a model is wanted. The scorer is reused or deleted before US-68 starts. Slowdown detection (FR37) is median and MAD statistics and needs no model. | FR8 removed; FR37 | Decided (endpoint), Open (scorer, mentor check) |
| 4 | Entry point for an analysis | The client calls `POST /v1/analyses` on execution-service with the run or code reference and the `task`. execution-service checks run permission and the room's AI switch (T-17, decision 11), then returns 202 with `analysis_id`. The result arrives later as an `analysis-result` message, so the run result is never delayed. | NFR9, NFR5.1, FR12, FR35 | Proposed |
| 5 | Endpoints of ai-ml-service | Called only by execution-service: `POST /v1/analyses/complexity` (Task 1), `/slowdown` (Task 2), `/failing-input` (Task 3), `/crash-explanation` (Task 4), `GET /v1/usage` (Task 5, counts only) and `DELETE /v1/rooms/{room_id}/state`. Every call carries `request_id`, `room_id`, `analysis_id`, `language`, `consent` and `ai_enabled`. `request_type` is `complexity`, `slowdown`, `failing_input` or `crash_explanation`. A call sends only the relevant code section (FR40.2). | FR36 to FR40, DR5 | Proposed |
| 6 | Result message | `analysis-result` with `request_id` (of the run), `analysis_id`, `task`, `status` (`ok`, `fallback` or `error`), `ai_step`, `result` (task payload) and `message`. It goes to the same audience as the run it belongs to. | NFR9, CR08 | Proposed |
| 7 | Who starts repeated and batch runs | execution-service starts every container. ai-ml-service asks for runs through `POST /internal/runs` on execution-service (`X-Internal-Token`, purpose `measurement`, `test_batch` or `sanitizer`). These runs use the same queue (FR16), budget (NFR14) and isolation rules, and are not broadcast as run results. | FR5.1, FR16, NFR14, CR10, CR15 | Proposed |
| 8 | Consent and the Host switch | The client sends `consent` on each request (`platform-home.md`: the caller supplies it). execution-service reads `ai_enabled` from sync-service and passes it on. ai-ml-service runs an AI step only when both are true. | FR12, FR35, CR12 | Decided (rule), Proposed (fields) |
| 9 | Run history | The run record (FR29, US-52) is held by sync-service, which already sees every result, and is written with the delivery call. ai-ml-service keeps only hash-keyed measurements in room-scoped memory (FR37.4) and deletes them on `DELETE /v1/rooms/{room_id}/state`, which execution-service calls when a room is deleted or closed (US-56). | FR29, FR32, FR37.4, `platform-home.md` | Proposed |
| 10 | Blocked AI steps | AI-free parts always return (NFR7.5, FR35.3). A blocked AI step is reported in the 200 body as `ai_step` with `status: "skipped"` and an `error_code`: `CONSENT_MISSING`, `AI_DISABLED`, `COST_CAP_REACHED`, `ROOM_RATE_LIMITED`, `RATE_LIMITED_PROVIDER`, `PROVIDER_ERROR` or `PROVIDER_TIMEOUT`. execution-service then shows the short fallback message (FR40.5). The envelope is used only for calls that cannot be answered: `INVALID_REQUEST` (422), `UNSUPPORTED_LANGUAGE` (422, sanitizer run on a language other than C or C++) and `RUN_BUDGET_EXCEEDED` (429, NFR14). `AI_DISABLED` and `ROOM_RATE_LIMITED` are new. | NFR7.2, NFR7.5, FR35.3, FR40.5, NFR5.3, DR5 | Decided (codes and behaviour), Proposed (shape) |
| 11 | Cache key | SHA-256 of the normalised code (comments removed, whitespace normalised, meaning preserved), the language, the request type and the error output where it matters. Only the hash is stored. This replaces the raw-code key in section 8 of the v0 contract. | NFR5.2 | Decided |
| 12 | Logging | The log fields stay as in section 9 of the v0 contract: `request_id`, `room_id`, `request_type`, `language`, `status`, `error_code` and `latency_ms`. The room id comes from the validated request. This is the acceptance rule of US-25. | NFR8.1, NFR8.2 | Decided |

---

## 7. Shared Acceptance Criteria

| Applies to | Criterion |
|---|---|
| Every story that produces an analysis result or message (US-65, US-66, US-68, US-69, US-70, US-72, US-73, US-74, US-78) | AI/ML processing never delays the execution result. Slowdown flags, analysis results and explanations arrive afterwards as separate messages and are shown to the same people as the run result they belong to (NFR9, CR08). |
| Every story that sends code to an external AI service (US-66, US-67, US-70, US-74) | The step runs only after consent (FR12) and while AI is on (FR35). Declining consent or switching AI off does not affect editing, execution or the AI-free parts of the analyses (FR12.3, CR06, CR12). |
| Every story that logs or counts AI use (US-25, US-75) | Logs and counters hold metadata only and never source code or other user-written text (NFR8.2). |
| Every story that makes a run (US-08, US-47, US-48, US-65, US-66, US-70, US-71, US-73) | The 5 s limit, the memory limit and every sandbox isolation rule apply (NFR3, NFR4). |
| Every story that checks a permission (US-35, US-43, US-60) | The check is made on the server, not only hidden in the interface (NFR13 for document changes, FR41.4 for running). |

---

## 8. Traceability Matrix

Every requirement in Requirements v2 is covered by at least one story. Version 1 requirements that were removed (FR7, FR8, FR13, NFR6, NFR7.3) have no story and appear in section 3 as removed.

| Requirement | Story |
|---|---|
| FR1 | US-01 |
| FR2 | US-02 |
| FR3.1, FR3.2 | US-03 |
| FR4.1, FR4.2, FR4.5 (Pair mode) | US-08 |
| FR4.3, FR4.4, FR6 | US-10 |
| FR4.5 (Open mode) | US-49 |
| FR5.1 | US-09, US-71 |
| FR5.2 | US-09 |
| FR9.1 to FR9.5 | US-27 |
| FR9.6 | US-28 |
| FR10.1, FR10.2 | US-29 |
| FR11.1, FR11.2 | US-04 |
| FR12.1, FR12.2, FR12.3 | US-17 |
| FR12.4 | US-21 |
| FR14 | US-05 |
| FR15 | US-30 |
| FR16 | US-11 |
| FR17 | US-34 |
| FR18 | US-35 |
| FR19.1 | US-36 |
| FR19.2 | US-37 |
| FR19.3 | US-38 |
| FR20.1 | US-39 |
| FR20.2, FR20.3 | US-46 |
| FR21.1, FR21.2, FR21.3 | US-40 |
| FR21.4 | US-41 |
| FR21.5 | US-42 |
| FR22.1, FR22.2 | US-43 |
| FR23 | US-44 |
| FR24.1, FR24.2 | US-45 |
| FR25.1, FR25.2 | US-47 |
| FR25.3 | US-48 |
| FR26 | US-50 |
| FR27 | US-49 |
| FR28 | US-51 |
| FR29 | US-52 |
| FR30 | US-53 |
| FR31.1 | US-54 |
| FR31.2 | US-55 |
| FR32 | US-56 |
| FR33 | US-57 |
| FR34 | US-58 |
| FR35.1, FR35.2, FR35.3 | US-59 |
| FR36.1, FR36.2 | US-65 |
| FR36.3 | US-66 |
| FR36.4 | US-67 |
| FR37.1, FR37.2, FR37.4 | US-68 |
| FR37.3 | US-69 |
| FR38.1, FR38.3 | US-70 |
| FR38.4 | US-71 |
| FR38.2, FR38.5 | US-72 |
| FR39.1, FR39.2 | US-73 |
| FR39.3 | US-74 |
| FR40.1 | US-75 |
| FR40.2 | US-76 |
| FR40.3, FR40.4 | US-77 |
| FR40.5 | US-78 |
| FR41.1 to FR41.4 | US-60 |
| FR42 | US-61 |
| NFR1 | US-06 |
| NFR2 | US-31 |
| NFR3.1 to NFR3.4 | US-12 |
| NFR4.1, NFR4.2 | US-13, US-71 (per test in a batch) |
| NFR4.3, NFR4.4, NFR4.5 | US-13 |
| NFR5.1, NFR5.2, NFR5.3 | US-22 |
| NFR7.1, NFR7.2, NFR7.4, NFR7.5 | US-24 |
| NFR8.1, NFR8.2 | US-25 |
| NFR9 | Shared criteria (section 7), US-49 |
| NFR10 | US-32 |
| NFR11 | US-33 |
| NFR12 | US-07 |
| NFR13 | US-35, US-43 |
| NFR14 | US-64 |
| NFR15 | US-63 |
| NFR16 | US-62 |
| DR1 | US-14 |
| DR2 | US-15 |
| DR3 | US-16 |
| DR4 | US-39, US-51 |
| DR5 | US-73 |

A range such as `FR9.1 to FR9.5` covers every sub-requirement in it. Check of 30 October 2026: all 122 rows of Requirements v2 (89 FR, 28 NFR, 5 DR) appear in this matrix, and all 73 active stories appear in it. The stories below also enforce a requirement that is mapped above to one primary story. Their own criteria say so.

| Requirement | Also enforced by |
|---|---|
| FR12.2, FR35.3, NFR5.1 | US-67, US-70, US-74 (each AI step checks consent and the Host switch) |
| NFR3.1 to NFR3.4 | US-71 (test batch, CR15), US-73 (sanitizer run) |
| NFR14 | US-65 (repeats count toward the run cap), US-71 (batch budget) |
| FR16 | US-64 (a repeated run counts toward the queue limit, CR10) |


---

## 9. Sprint Plan

This plan uses sprint numbers only. Sprint 1 is closed and its unfinished items carry over. Sprint 2 is the re-plan sprint. Sprints 3 to 6 are added so that every active story and task fits, with Sprint 6 kept light to absorb anything that slips. All 72 stories still to do (the 73 active stories minus US-32, which is done) and all 13 open or new tasks appear exactly once.

### 9.1 Rules

- **Order is build order.** Inside a sprint, items are listed in the order they can be built. Every dependency is in an earlier sprint, or earlier in the same sprint.
- **Early start.** The AI/ML items marked early start (US-65 and US-70) may be started against a stub or mock runner in the sprint before their dependency is ready. They are not closed until the real dependency is merged.
- **Named owners** are given only for the AI/ML work. Every other item is assigned inside its category at sprint planning. Members who sit in two categories (Chandan in A and C, Yash in B and D) are counted once per sprint at planning.
- **Definition of done.** The acceptance criteria and the shared criteria in section 7 are met, the pull request is reviewed, the build is green, and an integration test for the new behaviour is added to `tests/integration/`.
- **Issues are created sprint by sprint** at sprint planning, not all at once. Carry-over issues get the new sprint label. The `sprint-1` label stays for history.
- **If a sprint overruns,** unfinished items move to the next sprint. If time runs out, cut in this order: US-53 (Provisional, no other story depends on it); US-29 (Low, optional login and import); US-57 (Low); US-41 (Low); US-58 (Low); US-07 (Low). No other story depends on these.

### 9.2 Sprint overview

| Sprint | Name | Items |
|---|---|---|
| Sprint 2 | Re-plan sprint: carry-over and foundations | 24 |
| Sprint 3 | Execution core and Pair mode enforcement | 23 |
| Sprint 4 | Pair mode complete, workspace begins, analyses core | 18 |
| Sprint 5 | Open mode runs and room lifecycle | 15 |
| Sprint 6 | Stabilisation | 5 |


### 9.3 Sprints

#### Sprint 2: Re-plan sprint: carry-over and foundations

**Goal.** Clear the carry-over from the old Sprint 1, publish the version 2 contracts, land the one-spec runner and the Docker isolation, put the room, role and permission model in place, and let the AI/ML team build the parts that need no sandbox (contract, consent, logging, caching, rules, counters).

**Sequencing.** T-17 is the gate. No contract-dependent work (US-01, T-10, T-11, T-19, T-20, T-18, US-08, US-09, US-27) is accepted before the T-17 decisions are merged and confirmed. T-19 and T-20 follow T-17, and T-18 follows T-17. The AI/ML work that needs no contract change can start at once.

**Exit criteria.** T-17, T-18, T-19 and T-20 merged and confirmed by every category. PR #40 merged (PRs #44 and #48 were merged on 29 October). A room can be created and joined and two browsers merge text. A Pair mode run reaches the participants. The consent gate and the AI request control work.

| Item | Title | Cat | Depends on | Existing issue or PR | AI/ML owner |
|---|---|---|---|---|---|
| T-17 | Service contracts version 2 | D | None | #9 (T-06) |  |
| T-23 | Architecture decision record: two room modes | D | None |  |  |
| T-25 | Requirements, Backlog and source documents into docs/, labels | D | None |  |  |
| T-19 | One spec object for the runner | B | T-17 |  |  |
| T-20 | Room, role and permission model | D / B | T-17 |  |  |
| T-18 | AI/ML contract version 2 | C | T-17 |  | Prince, Chandan, Digvijay |
| US-33 | Reviewed and build-checked merges | D | None |  |  |
| US-01 | Create a room and share a join link | A | T-17 | #11 |  |
| US-02 | Merge simultaneous edits safely | A | None | #12 |  |
| US-05 | Display name on joining | A | None | #13 |  |
| T-11 | Client run panel | A | T-19 | #17 |  |
| T-10 | Host identity (owner token) | D | T-17 | #16 |  |
| US-27 | Basic participant and Host controls | A / D | T-10, T-20 |  |  |
| T-08 | Integration skeleton wired into CI | D | None | #18 |  |
| US-08 | Run code and see the result | B | T-19, T-11 | #14, PR #48 (merged) |  |
| US-09 | Isolate every run in a temporary Docker container | B | T-19 | #15, PR #40 |  |
| US-11 | Clear message when the system is busy | B | None |  |  |
| US-15 | Self-managed Docker execution | B | None |  |  |
| US-17 | AI consent before code leaves the sandbox | C | None |  | Prince |
| US-25 | AI/ML calls logged | C | None | PR #44 (merged, part of US-25) | Prince |
| US-22 | AI requests controlled | C | US-17, T-18 |  | Digvijay |
| US-67 | Look for slow patterns with rules first | C | T-18, US-22 |  | Prince |
| US-75 | Count AI usage | C | None |  | Digvijay |
| US-76 | Send only the relevant code | C | None |  | Digvijay |

Already merged: PR #48 (the US-08 run endpoint, `CodeRunner` interface, stub runner and room broadcast) and PR #44 (request logging without bodies). Neither finishes its story: US-08 still needs T-19 and the runner and time fields (US-50), and US-25 still needs latency logging and a real room id.

Housekeeping in this sprint: close issue #6 (T-14) once its three Definition of Done items are confirmed, and close issue #22 (T-16) as superseded by US-74. Close issue #9 (T-06) after T-17 is merged and the v1 confirmations are ticked. US-33 had no issue in the Sprint 1 pack, so check that `main` is protected (one approval and a passing build) and create the issue only if it is not.

#### Sprint 3: Execution core and Pair mode enforcement

**Goal.** Execution limits, metrics, run record and the repeated-run budget. Pair mode rights enforced on the server. Run permission. The sanitizer run. Cost cap. The slowdown flag against the run record.

**Exit criteria.** The 5 s, memory, process and output limits are enforced. The run budget (US-64) exists. A modified client cannot edit in Pair mode (US-35). The Host can restrict Run (US-60). A C or C++ sanitizer run gives an error type and line. The cost cap blocks paid calls.

| Item | Title | Cat | Depends on | Existing issue or PR | AI/ML owner |
|---|---|---|---|---|---|
| US-13 | Strict resource limits | B | US-09 |  |  |
| US-16 | Resource limits configurable per host | B | US-13 |  |  |
| US-12 | Strong sandbox isolation | B | US-09 |  |  |
| US-14 | Main coursework languages | B | US-09 |  |  |
| US-10 | Execution time and memory metrics | B | US-08 |  |  |
| US-52 | Keep a record of every run | B | US-08 |  |  |
| US-50 | Label every result with who ran it | A / B | US-08, T-19 |  |  |
| US-64 | Budget for repeated runs | B | US-13, US-11 |  |  |
| US-73 | Run C or C++ with sanitizers and read the report | B / C | US-12, US-14, US-16 |  | Digvijay |
| US-60 | Control who can run | B / D | US-27, T-20 |  |  |
| US-34 | Choose a room mode when creating a room | A / D | T-20, US-01 |  |  |
| US-35 | Only people with edit access can edit in Pair mode | B | T-20, US-34 |  |  |
| US-61 | Unique display names | A / D | US-05, T-20 |  |  |
| US-04 | Reconnect and resync | A | None |  |  |
| US-03 | See live cursors and presence | A | None |  |  |
| US-62 | Unguessable room links | D | US-01 |  |  |
| US-54 | Transfer the Host role | D | US-27 |  |  |
| US-28 | A removed user stays out | D | US-27 |  |  |
| T-22 | Load-test harness | D | None |  |  |
| US-21 | Withdraw AI consent | C | US-17 |  | Prince |
| US-68 | Flag a run that is much slower than usual | C | US-52 |  | Prince |
| US-24 | Monthly AI cost cap | C | US-22 |  | Digvijay |
| US-77 | Use the cheapest method first | C | US-22 |  | Digvijay |

Early starts: Prince starts the US-65 measurement harness against a stub runner. Chandan starts the US-70 edge-case input generator against a stub runner.

#### Sprint 4: Pair mode complete, workspace begins, analyses core

**Goal.** Edit-access controls and presets for Pair mode. The Open mode workspace with modules, owners and write access. The first AI analyses on real runs: growth estimate, verified fix, failing-input search, crash explanation, fallback message.

**Exit criteria.** Pair mode presets and edit requests work. Modules can be created, owned and shared for writing, and everyone reads them live. A test batch runs in one container (US-71). Task 1, Task 3 search and Task 4 explanation work end to end.

| Item | Title | Cat | Depends on | Existing issue or PR | AI/ML owner |
|---|---|---|---|---|---|
| US-71 | Run a batch of generated tests in one container | B | US-09, US-64 |  |  |
| US-65 | Measure time complexity | C / B | US-64 |  | Prince |
| US-66 | Verify a suggested fix | C | US-65 |  | Prince |
| US-70 | Search for a failing input | C | US-71 |  | Chandan |
| US-74 | Explain a crash in plain language | C | US-73, US-76 |  | Digvijay |
| US-78 | Show a fallback message | C | US-24 |  | Digvijay |
| US-36 | Give and take back edit access | A / D | US-35 |  |  |
| US-37 | Pair mode presets | A / D | US-36 |  |  |
| US-38 | Request edit access | A | US-36 |  |  |
| US-39 | Workspace with several modules | A / B | US-34 |  |  |
| US-40 | Request a module and get one owner | A / D | US-39 |  |  |
| US-44 | Read every module live | A | US-39 |  |  |
| US-43 | Control write access to a module | B / D | US-40, T-20 |  |  |
| US-46 | Mark a module runnable and set the entry file | A | US-40 |  |  |
| US-30 | Execution history deleted after a limited time | D | US-52 |  |  |
| US-55 | Close the room when the Host leaves | D | US-54 |  |  |
| US-56 | Delete room data with the room | D | US-52, T-18, US-68 |  |  |
| US-06 | Edit sync latency | A | T-22 |  |  |

#### Sprint 5: Open mode runs and room lifecycle

**Goal.** Save, run one module, run a selection through the entry file, private and shared results, failure attribution, the audit log, the Host AI switch, room limits and the remaining analyses (minimiser, changed lines). The AI/ML evaluation report.

**Exit criteria.** An Open mode room can save, run a module, run a selection, keep a result private or share it, and name the failing module and its people. The audit log, Host AI switch and room limits work. T-24 is written.

| Item | Title | Cat | Depends on | Existing issue or PR | AI/ML owner |
|---|---|---|---|---|---|
| US-45 | Save a module manually | A | US-43 |  |  |
| US-47 | Run one runnable module | B | US-45, US-46, T-19 |  |  |
| US-48 | Run a selection of modules or the whole workspace | B | US-47 |  |  |
| US-49 | Keep a run result private or share it | A / B | US-08, US-47 |  |  |
| US-51 | Name the failing module and its people | B | US-47, US-45 |  |  |
| US-42 | A module never loses its owner | D | US-40, US-27 |  |  |
| US-41 | Owner manages a module | A | US-40 |  |  |
| US-57 | Export the room's files | A | US-39 |  |  |
| US-58 | Audit log of permission changes | D | US-36, US-43, US-60 |  |  |
| US-59 | Host AI switch | C / D | US-37, US-17 |  | Chandan |
| US-63 | Limits on modules and room size | D | T-22 |  |  |
| US-69 | Highlight what changed in a slow run | A / C | US-68 |  | Prince |
| US-72 | Minimise a failing input and save it as a test | C | US-70 |  | Chandan |
| T-21 | Yjs garbage-collection decision | A | US-44 |  |  |
| T-24 | AI/ML evaluation report | C | US-65, US-66, US-68, US-70, US-72, US-74, US-75, US-24, US-78 |  | Prince, Chandan, Digvijay |

#### Sprint 6: Stabilisation

**Goal.** Final-host load tests, host values, browser check, the optional items, final documentation and the demo. This sprint is light on purpose. Anything that slipped from earlier sprints lands here.

**Exit criteria.** T1-P, T1-A and T1-O recorded on the final host with NFR1 met or not met. FR15 period and DR3 host values confirmed. FR30 built or dropped. Every integration test green. Documentation and demo ready.

| Item | Title | Cat | Depends on | Existing issue or PR | AI/ML owner |
|---|---|---|---|---|---|
| US-53 | Show who wrote which line | A | T-21 |  |  |
| US-29 | GitHub login and public import | D | None |  |  |
| US-31 | Room size and load test | D | T-22, US-43, US-63 |  |  |
| US-07 | Browser support | A | None |  |  |
| T-26 | Final documentation and demo script | D | T-25 |  |  |

Decisions to close in this sprint: FR15 retention period, DR3 host values, the NFR2 final-host run, whether FR30 (US-53) is built or dropped, and the mentor check on the anomaly scorer (section 6.2, decision 3).

### 9.4 AI/ML work by person and sprint

Tasks: Prince has Tasks 1 and 2, Chandan has Task 3, Digvijay has Tasks 4 and 5. Consent, logging, the AI request control and the Host switch are shared work, split as shown. Early starts and support work keep everyone busy in every sprint.

| Sprint | Prince (Tasks 1 and 2) | Chandan (Task 3) | Digvijay (Tasks 4 and 5) |
|---|---|---|---|
| 2 | T-18 (Tasks 1 and 2 sections), US-17, US-25, US-67 | T-18 (Task 3, consent and Host switch fields), Cat A support (for example US-03) | T-18 (assembles the contract, error codes, review/explain/anomaly decision), US-22, US-75, US-76 |
| 3 | US-21, US-68, early start of US-65 | Early start of US-70, Cat A support (for example US-04) | US-73, US-24, US-77 |
| 4 | US-65, US-66 | US-70, start of US-72 | US-74, US-78, support for the C and C++ images with Execution (US-14) |
| 5 | US-69, T-24 (Tasks 1 and 2 evaluation) | US-72, US-59, T-24 (Task 3 evaluation) | T-24 (Tasks 4 and 5 evaluation), cost-cap and counter checks under load |
| 6 | AI integration tests with Execution, demo cases for Tasks 1 and 2 | AI integration tests for Task 3, demo case, US-53 if built | AI integration tests for the cost cap and Task 4, demo cases |

---

## 10. Revision History

| Version | Date | Change |
|---|---|---|
| 1 | Day-3 deliverable | US-01 to US-33 and Conflict-Resolution Log CR01 to CR07. |
| 2 | 28 October 2026 | Rebuilt against Requirements v2. US-01 to US-33 kept: 13 unchanged, 15 modified, 5 removed. 45 new stories US-34 to US-78. Enabler tasks T-17 to T-26. EPIC 3 and EPIC 4 retitled, EPIC 7 added. Traceability matrix and treatment of existing issues and pull requests added. The conflict log moved to Requirements v2. |