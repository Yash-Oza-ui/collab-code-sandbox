# IT-314 Software Engineering

## Requirements Specification, Version 2

**Group 32 | Collaborative Code Editor with Sandboxed Execution**

Repository: https://github.com/Yash-Oza-ui/collab-code-sandbox

| Field | Value |
|---|---|
| Document | Requirements Specification (FR, NFR, DR) and Conflict-Resolution Log (CR) |
| Version | 2 |
| Date | 28 October 2026 |
| Supersedes | Requirements Specification, EPICs and Sprint Plan, Day-2 deliverable (version 1, file `IT314_Group32_FR_NFR_DR_EPICs_Sprints_REV.pdf`, kept unchanged) |
| Change basis | CHG-01, file `CHG_01.md` (status Decided) |
| AI/ML requirements | Taken from `AI_ML_Requirements_v2.md` (27 October 2026), whose scope source is `ai-ml-service.md`. FR35 (Host AI switch), which that file removes, is kept. |
| Stakeholder elicitation | `IT314_Group32_Stakeholders_Elicitation_Final.pdf` (round 1) and `docs/elicitation-round-2.md` (round 2) |

This version keeps the existing requirement IDs. Requirements whose text changes are marked **Changed (v2)**, new requirements are appended with new IDs and marked **New (v2)**, and nothing is renumbered. A requirement that was removed keeps its ID unused, so there are gaps (section 7.5). The AI/ML requirements follow `AI_ML_Requirements_v2.md`. EPIC mapping and the sprint plan are issued separately, with Backlog v2 and the sprint re-plan, and refer to the IDs in this document.

---

## 1. Stakeholders

The stakeholders are restated here for the redesigned product. The IDs S1 to S5 are unchanged. The two room modes widen the interests of S1 to S4 but introduce no new stakeholder group.

| ID | Stakeholder | Type | Main interest in the redesigned product | Elicitation |
|---|---|---|---|---|
| S1 | Student developer (individual user) | Primary | Write and run code in coursework languages, see clear output and errors, and decide who sees a run result. | Survey (n = 32), interviews (3), round 2 |
| S2 | Collaborating teammate (pair or group work, including module owners and writers) | Primary | Edit without conflicts or lag, know who is editing what, and own a clearly bounded part of the program. | Survey (n = 32), observation, round 2 |
| S3 | Room owner / session host (Host): student, teacher, TA or interviewer | Primary | Create a room in the right mode, control who edits, who owns modules and who can run, and keep AI use under control. | Brainstorming, interviews, round 2 |
| S4 | Course instructor / project mentor / TA | Key (sponsor, evaluator) | A clear use case for shared coding, safe execution, errors traceable to a module, and meaningful ML and GenAI components. | Mentor meeting, mid-evaluation review and TA session (round 2) |
| S5 | Development and operations team (Group 32) | Secondary | Run untrusted code safely on free-tier or lab hardware, and keep AI cost and rate limits under control. | Brainstorming, document analysis |

External systems that constrain the design:

| External system | Why it matters for the redesign |
|---|---|
| LLM API | Rate limits, 429 errors and monthly cost caps constrain every AI feature. |
| Docker Engine | Provides isolation and resource limits, including for repeated runs and sanitizer builds. |
| GitHub OAuth (optional) | Login and repository import. Scopes decide how much access is requested. |
| Yjs (CRDT library) | Determines how per-module synchronisation, presence and reconnection can be built. |
| AddressSanitizer and UndefinedBehaviorSanitizer | Compiler tooling used for C and C++ crash explanation (FR39). |

### 1.1 Positioning and use scenarios

The product is a shared coding room with a shared, sandboxed run, for pairs and small groups of 2 to 5 people, with 10 as the supported maximum (NFR2). The room size is a design assumption, not a measured figure. This section answers CHG-01 feedback E1 (use case) and E5 (room size).

| Scenario | Mode | How it works in the room |
|---|---|---|
| Pair debugging between two students | Pair | One student is the driver with edit access and the other reads live as the navigator. Both see every run result. |
| Mock or technical interview | Pair (Teach/Interview preset) | The Host gives the candidate edit access (FR19.1), watches them type and run code, and AI is off by default (FR35.2). |
| Lab help | Pair | A TA joins a student's room, reads the code and the failing run, and can demonstrate a fix once given edit access (FR19.3). |

Open mode serves a small group that builds one program from modules. Each module has a named owner, so an error points to one module and one person (E4).

### 1.2 Out of scope and deferred

| Item | Status | Reason |
|---|---|---|
| Vote-to-run (other participants approve a run) | Out of scope | Rejected in CHG-01 (E3): slow on the most frequent action and open to misuse. It may return later as an optional Host run policy in Open mode. |
| Running a subset of modules without the entry file | Out of scope | FR25 allows one runnable module on its own, or a selection run through the entry file. |
| More than one programming language in a workspace | Out of scope | FR20.1. |
| Changing a room's mode after creation | Out of scope | FR17. |
| GitHub push of exported code | Deferred | Optional and later (CHG-01). FR33 covers zip export only. |
| Import from private GitHub repositories | Out of scope | FR10.2. |

### 1.3 AI/ML scope: the five tasks

The AI/ML requirements follow `AI_ML_Requirements_v2.md`. Its five tasks are the AI/ML scope. The main idea is to run the code and measure the results before making claims about it. AI can suggest a fix, but the sandbox must test the fix before it is shown as a verified improvement.

| Task | What it does | Requirements |
|---|---|---|
| Task 1 | Measure time complexity and verify optimizations | FR36.1 to FR36.4, NFR14 |
| Task 2 | Detect slowdowns in run history | FR37.1 to FR37.4 |
| Task 3 | Find failing inputs and make them smaller | FR38.1 to FR38.5, NFR14 |
| Task 4 | Explain C and C++ crashes | FR39.1 to FR39.3, DR5 |
| Task 5 | Reduce AI usage and track costs | FR40.1 to FR40.5, NFR5.1 to NFR5.3, NFR7.1, NFR7.2, NFR7.4, NFR7.5, NFR8.1, NFR8.2, DR3 |
| All tasks | Consent and non-blocking delivery | FR12.1 to FR12.4, FR35.1 to FR35.3, NFR9 |

| Design principle | Enforced by |
|---|---|
| Measure before making claims | FR36.1, FR36.2, FR37.1 |
| Use simple tools first | FR36.4, FR40.3 |
| Keep normal execution responsive | NFR9, FR40.5 |
| Respect user consent | FR12.1 to FR12.4, FR35.1 to FR35.3, NFR5.1 |
| Protect user data | NFR8.2, FR40.1, FR40.2 |
| Verify suggested fixes | FR36.3 |
| Keep costs under control | FR40.1 to FR40.4, NFR5.1 to NFR5.3, NFR7.1, NFR7.2, NFR7.4, NFR7.5 |

---

## 2. How to Read the Tables

- **Priority:** High is needed for the core demo, Medium is important but can follow, Low is optional.
- **Status:** Final unless the source data is still missing (Provisional) or the requirement is deliberately limited in scope (Final (scoped)).
- **Version:** Unchanged (identical to version 1), Changed (v2) (text revised), or New (v2) (added in this version).
- **Source labels:** Survey and Interview are round 1 data. Docs is a fact taken from documentation. Benchmark is a default from Judge0 or Piston, used only as a reference. Design decision is a team choice, not evidence. CHG-01 (E1 to E5) points to the stakeholder feedback recorded in the change request: E1 use case for shared coding, E2 half-typed code at run time, E3 slow and misusable run approval, E4 modularity and error attribution, E5 room size.
- **AI/ML labels:** ai-ml-service (Task N) points to Task 1 to 5 of `ai-ml-service.md`, the AI/ML scope source. CHG-01 (section 4.6) points to the change request for the AI/ML tasks.
- Unchanged requirements keep their original wording. Sprint references inside them follow the sprint plan in force.

### 2.1 Terms

| Term | Meaning |
|---|---|
| Pair mode | Room mode with one shared file and no modules. |
| Open mode | Room mode with a workspace of modules. |
| Host | The room owner. Version 1 requirements that say "owner" or "room owner" refer to the Host. |
| Participant | A person in the room who edits and runs as permitted. |
| Observer | A read-only guest. Reads live, cannot edit or run. |
| Module | A file in an Open mode workspace. |
| Module owner | The one person responsible for a module. |
| Module writer | A person who has been given write access to a module. |
| Entry file | The module from which a whole-workspace run starts. |
| Saved version | The last saved content of a module. |
| Runner | The person who presses Run. |
| Run restriction | A Host setting that stops one named participant, or everyone except the Host, from running code (FR41). |
| Analysis | One run of a Task 1 to Task 4 feature on a user's code. |
| Input hash | A hash of the input given to a run. Runs are comparable only when their input hashes match. |
| Baseline | The historical median runtime and Median Absolute Deviation (MAD) for runs with the same input hash. |
| Reference solution | A trusted brute-force solution that the user's program is compared against. |
| Minimised input | A small failing input that still shows the problem. |
| Normalised code | Code with comments removed and whitespace normalised, used only to build the cache key. |
| Paid model | An external LLM API that costs money per call. |
| Fallback message | The short message shown when no method can answer (FR40.5). |

### 2.2 Roles and permissions at a glance

This table summarises the requirements below. If it ever disagrees with a requirement row, the row is correct.

| Action | Host | Participant | Observer |
|---|---|---|---|
| Read the file or every module live | Yes | Yes | Yes |
| Edit the file (Pair mode) | Yes | Only with edit access (FR19.1) | No |
| Edit a module (Open mode) | Only as module owner or module writer (FR22.2) | Only as module owner or module writer (FR22.2) | No |
| Save a module (Open mode) | Only as module owner or module writer (FR24.1) | Only as module owner or module writer (FR24.1) | No |
| Run code | Yes | Yes, unless the Host has restricted Run (FR41) | No |
| Share own run result with the room (Open mode) | Yes | Yes | Not applicable |
| Request a module (FR21.1) or edit access (FR19.3) | Approves the request | Yes | No |
| Give or take back edit access (Pair mode) | Yes | No | No |
| Give or take back write access to a module | Yes (FR22.1) | Only as module owner (FR22.1) | No |
| Rename, delete or transfer a module | Only as module owner (FR21.4, FR21.5) | Only as module owner (FR21.4) | No |
| Remove users, switch join approval, make a guest an Observer, set the entry file, switch AI on or off, restrict Run, transfer the Host role | Yes | No | No |

---

## 3. Functional Requirements

| ID | Requirement | Priority | Status | Version | Source |
|---|---|---|---|---|---|
| | **FR1: Rooms** | | | | |
| FR1 | A user can create a room, choose its mode (FR17) and get a shareable link. Opening the link loads the shared, CRDT-synced document in that mode. | High | Final | Changed (v2) | Interview: S1 (room links); CHG-01 (E1) |
| FR2 | Simultaneous edits by several users merge through Yjs without overwriting each other. In Open mode this applies to each module. | High | Final | Changed (v2) | Survey: 78% report overwritten changes; CHG-01 (E4) |
| | **FR3: Live cursors and presence** | | | | |
| FR3.1 | Each active collaborator's cursor and selection are shown with a name, a colour and the collaborator's role. | High | Final | Changed (v2) | Survey: 91% rate it 4-5; 66% do not know who is editing what; docs: Yjs awareness; CHG-01 |
| FR3.2 | A collaborator with no activity for about 30 s is dropped from the presence list. | Medium | Final | Unchanged | Docs: Yjs awareness (30 s timeout) |
| | **FR4: Running code and showing results** | | | | |
| FR4.1 | A run request carries the selected language, optional stdin and the code to run. In Pair mode the code is the live text of the file. In Open mode it is the saved versions of the modules being run, and the runnable module or entry file that starts the run (FR25). | High | Final | Changed (v2) | Design decision (API contract); CHG-01 (E2, E4) |
| FR4.2 | stdout and stderr are always shown after a run, including failed runs. | High | Final | Unchanged | Survey: stdout 88%, stderr 81% |
| FR4.3 | Execution time and memory usage are shown in a compact metrics bar, separate from the output. | Medium | Final | Unchanged | Survey: time 59%, memory 41% |
| FR4.4 | The exit code is shown in the metrics bar. | Low | Final | Unchanged | Survey: 31% |
| FR4.5 | In Pair mode the result of a run is shown to every participant in the room. In Open mode the result is shown to the runner and is shared with the room only if the runner chooses (FR27). | High | Final | Changed (v2) | Design decision (shared session); CHG-01 (E2, E3) |
| | **FR5: Sandboxed execution** | | | | |
| FR5.1 | Every run executes in its own new Docker container. A run is one container execution, and a batch of generated tests (FR38.4) counts as one run. | High | Final | Changed (v2) | Course scope; design decision; CHG-01 (section 4.7); ai-ml-service (Task 3) |
| FR5.2 | The container and its workspace are destroyed after the run. No state is reused. | High | Final | Unchanged | Design decision; benchmark: Piston cleans temp space |
| FR6 | Execution time and peak memory are captured for every run. | Medium | Final | Unchanged | Survey: time 59%, memory 41% |
| | **FR9: Host controls** | | | | |
| FR9.1 | The room owner is notified each time someone joins. | Medium | Final | Unchanged | Interview: S3 |
| FR9.2 | The owner can remove any participant at any time. | High | Final | Unchanged | Interview: S3 |
| FR9.3 | The owner can switch on join approval for a room. Instant join stays the default. | Medium | Final | Unchanged | Interview: S3; FR1 |
| FR9.4 | The Host can make a guest an Observer. This is enforced on the server, not only hidden in the interface. An Observer can see the document or modules, cursors and run results shared with the room, but cannot edit or press Run. | Medium | Final | Changed (v2) | Interview: S3; team review; CHG-01 (section 4.2) |
| FR9.5 | The room owner keeps ownership after a page refresh or reconnect. The owner is identified by a token saved in the browser, or by GitHub login when it is used. | High | Final | Unchanged | Team review (login is optional, FR10) |
| FR9.6 | A removed user is blocked from that room, by session ID, until the owner re-admits them or regenerates the link. Automatic reconnect does not get around the block. | High | Final | Unchanged | Interview: S3; team review |
| | **FR10: GitHub login and import (optional)** | | | | |
| FR10.1 | Login with GitHub is optional and asks only for the read:user and user:email scopes. | Low | Final (scoped) | Unchanged | Docs: GitHub OAuth scopes; design decision |
| FR10.2 | A user can import code from a public GitHub repository. Private repositories are out of scope. | Low | Final (scoped) | Unchanged | Docs: GitHub OAuth scopes; design decision |
| | **FR11: Reconnection and resync** | | | | |
| FR11.1 | After a disconnect or page refresh, the client reconnects automatically with exponential backoff. | High | Final | Unchanged | Survey: 38% lost work; docs: y-websocket |
| FR11.2 | After reconnecting, the document resyncs to the latest shared state with no manual step. In Open mode every module resyncs. | High | Final | Changed (v2) | Survey: 38% lost work; docs: y-indexeddb; CHG-01 |
| | **FR12: AI privacy consent** | | | | |
| FR12.1 | Before the first AI use, the user sees a notice that their code will be sent to an external AI service. | High | Final | Unchanged | Team gap review (privacy); ai-ml-service (design principle: respect user consent) |
| FR12.2 | AI features stay blocked until the user consents. | High | Final | Unchanged | Team gap review (privacy); ai-ml-service (design principle: respect user consent) |
| FR12.3 | Declining consent does not affect editing or code execution. | High | Final | Unchanged | Team gap review (privacy) |
| FR12.4 | Consent can be given or withdrawn per session. | High | Final | Unchanged | Team gap review (privacy) |
| FR14 | A user who joins without GitHub login enters a display name. That name and a colour are used for the user's cursor. | Medium | Final | Unchanged | Design decision (FR3 needs a name, FR10 is optional) |
| FR15 | Execution history for a room is kept for a limited time and then deleted automatically, and in any case when the room is deleted (FR32). Proposed period: around 24 hours. | Low | Provisional | Changed (v2) | Team discussion; period not yet confirmed; CHG-01 |
| FR16 | The execution queue has a maximum length. A run request beyond it, including a repeated run, is rejected with a clear "busy, try again" message. | Medium | Final | Changed (v2) | Benchmark: Judge0 CE queue size 100; design decision; CHG-01 |
| | **FR17 to FR19: Room modes and edit control** | | | | |
| FR17 | A room has exactly one mode, Pair or Open, chosen when the room is created and fixed afterwards. The mode is shown to every participant. | High | Final | New (v2) | CHG-01 (E1, E2, E4) |
| FR18 | In Pair mode a room has one shared file and no modules. Only participants who have been given edit access can change it, and the relay enforces this on every edit. | High | Final | New (v2) | CHG-01 (E2) |
| FR19.1 | In Pair mode the Host can give edit access to one or several participants and take it back at any time. | High | Final | New (v2) | CHG-01 (E1, E2) |
| FR19.2 | A Pair mode room is created with a preset: Teach/Interview (only the Host edits by default) or Debug together (everyone edits by default). | Medium | Final | New (v2) | CHG-01 (E1) |
| FR19.3 | A participant without edit access can send a request for it, which the Host approves or declines. | Medium | Final | New (v2) | CHG-01 (E1) |
| | **FR20 to FR25: Open mode workspace** | | | | |
| FR20.1 | An Open mode workspace holds several modules and uses one programming language. | High | Final | New (v2) | CHG-01 (E4) |
| FR20.2 | The module owner can mark a module as runnable. | Medium | Final | New (v2) | CHG-01 (E4) |
| FR20.3 | The Host sets the entry file for whole-workspace runs. | Medium | Final | New (v2) | CHG-01 (E4) |
| FR21.1 | Any participant can request a module. | High | Final | New (v2) | CHG-01 (E4) |
| FR21.2 | The Host approves a module request. The requester becomes the owner unless the Host chooses someone else. | High | Final | New (v2) | CHG-01 (E4) |
| FR21.3 | Each module has exactly one owner. | High | Final | New (v2) | CHG-01 (E4) |
| FR21.4 | The module owner can rename, delete or transfer a module. | Low | Final | New (v2) | CHG-01 (E4) |
| FR21.5 | If a module's owner is removed from the room (FR9.2), the Host becomes the owner of that module until the Host transfers it, so every module always has exactly one owner. | Medium | Final | New (v2) | CHG-01 (E4); design decision (FR21.3) |
| FR22.1 | The module owner or the Host can give write access to a module to one or several people and take it back. The server enforces it. | High | Final | New (v2) | CHG-01 (E4) |
| FR22.2 | Only the module owner and people given write access can edit a module. Everyone else in the room, including the Host, can only read it. The server enforces it. | High | Final | New (v2) | CHG-01 (E4); decision in version 2 review (the Host has no special edit right in Open mode) |
| FR23 | Everyone in the room can read the live edits of every module. | High | Final | New (v2) | CHG-01 (E1, E4) |
| FR24.1 | Saving is manual. The module owner and module writers can save a module. | Medium | Final | New (v2) | CHG-01 (E2); design decision (who can save) |
| FR24.2 | A small dot beside a module shows that it has changed since the last save. | Medium | Final | New (v2) | CHG-01 (E2) |
| FR25.1 | A run in Open mode executes the saved versions of the modules, never the live text. | High | Final | New (v2) | CHG-01 (E2, E4); survey: 56% run code constantly while collaborating |
| FR25.2 | A module that its owner has marked runnable (FR20.2) can be run on its own. The run contains only that module. | High | Final | New (v2) | CHG-01 (E4); decision in version 2 review |
| FR25.3 | A selection of modules, or the whole workspace, can be run through the entry file (FR20.3). The run starts at the entry file and contains the entry file and the selected modules. A module that the entry file needs but that is not selected makes the run fail in the normal way, and FR28 names the failing module. If no entry file has been set, the run is refused with a message saying so. | High | Final | New (v2) | CHG-01 (E4); decision in version 2 review |
| | **FR26 to FR30: Run attribution and visibility** | | | | |
| FR26 | Every run result shown in a room is labelled with who ran it and when. In Open mode it also names the saved version that was run. | High | Final | New (v2) | CHG-01 (E2); survey: 56% run code constantly while collaborating |
| FR27 | In Open mode a run result is private to the runner by default. The runner can use a Share to room button to show it to everyone. Nobody else has to approve a run. | High | Final | New (v2) | CHG-01 (E3) |
| FR28 | In Open mode, when a run fails, the result names the failing module and shows its owner and the person who last saved it. | High | Final | New (v2) | CHG-01 (E4); survey: 66% do not know who is editing what |
| FR29 | Each run keeps a record of the runner, time, language, files and saved versions run, input hash, and measured time and memory. The record supports run history (FR37). | Medium | Final | New (v2) | CHG-01; design decision |
| FR30 | The Open mode workspace shows who wrote which line, where this is feasible. | Low | Provisional | New (v2) | CHG-01 (E4); open decision CR14 |
| | **FR31 to FR35: Room lifecycle and room-level controls** | | | | |
| FR31.1 | The Host can transfer the Host role to another participant at any time. | Medium | Final | New (v2) | CHG-01 (section 4.2) |
| FR31.2 | If the Host leaves without transferring the role, the room is closed after a timeout unless the Host returns. The timeout is read from configuration. | Medium | Final | New (v2) | CHG-01 (section 4.5) |
| FR32 | Code and run records are deleted when the room is deleted or closed. | Medium | Final | New (v2) | CHG-01 |
| FR33 | The room's file or modules can be exported as a zip download. | Low | Final | New (v2) | CHG-01 |
| FR34 | A visible audit log records role, edit-access, write-access and run-permission changes, with who made each change and when. | Low | Final | New (v2) | CHG-01 (section 4.5) |
| FR35.1 | The Host can switch AI on or off for the room at any time. | High | Final | New (v2) | CHG-01 (sections 4.2, 4.5); FR12 |
| FR35.2 | AI is off by default in a room created with the Teach/Interview preset (FR19.2) and on by default in other rooms. User consent (FR12) is still needed in every case. | Medium | Final | New (v2) | CHG-01 (section 4.5); design decision for rooms without a preset |
| FR35.3 | While AI is off, no step that sends code to an external AI service (FR36.4, FR38.3, FR39.3) runs. Editing, execution and the AI-free parts of the analyses (measurement, statistics, rules and sanitizer parsing) are not affected. | High | Final | New (v2) | CHG-01 (section 4.5); FR12 |
| | **FR36: Time complexity and verified optimizations (Task 1)** | | | | |
| FR36.1 | The system runs the code at input sizes n, 2n and 4n, runs each size several times and takes the median runtime at each size. The repeats count toward the run cap in NFR14. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 1) |
| FR36.2 | The system fits a line to log(input size) against log(runtime) on the medians. It reports the estimated growth rate, the R² score and an estimate of the input size at which a run would reach the run time limit (NFR4.1). | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 1) |
| FR36.3 | A suggested fix is run in the sandbox. It is shown as a verified improvement only if its measured runtime is lower than the original's under comparable conditions. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 1, design principle: verify suggested fixes) |
| FR36.4 | Common slow patterns, such as nested loops and repeated string concatenation, are first looked for with simple AST or regex rules. An AI call is made only when the rules find no likely issue, and only subject to FR12 and FR35. | Medium | Final | New (v2) | ai-ml-service (Task 1) |
| | **FR37: Slowdown detection in run history (Task 2)** | | | | |
| FR37.1 | The system compares comparable runs (same input hash) against a median and MAD baseline. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 2) |
| FR37.2 | A run that is statistically unusual and at least 8 times slower than the baseline is flagged. The factor is configurable. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 2) |
| FR37.3 | A flagged run highlights the lines that changed since the last normal run for the same input. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 2) |
| FR37.4 | Runtime and memory measurements are kept in short-lived room memory, together with a hash of the input. | Medium | Final | New (v2) | ai-ml-service (Task 2); open decision CR11 |
| | **FR38: Failing inputs and input reduction (Task 3)** | | | | |
| FR38.1 | The system searches for an input on which the code gives wrong output, crashes or times out. Wrong output is detected by comparing with a trusted brute-force reference solution when one is available. When there is none, the search looks for crashes and timeouts only. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 3) |
| FR38.2 | The minimised input is displayed, and a Save as Test Case action stores it as a test case. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 3) |
| FR38.3 | Test inputs start with simple edge cases: empty input, n = 1, maximum bounds, duplicates and, where valid, negative values. A cached AI-generated script can add random inputs, subject to FR12, FR35 and FR40.3. | Medium | Final | New (v2) | ai-ml-service (Task 3) |
| FR38.4 | A batch of generated tests runs as one run in one new container (FR5.1). The tests run one after another, each as a separate process within NFR4.1 and NFR4.2, the whole batch stays within the aggregate time budget (NFR14), and the container is destroyed afterwards. | Medium | Final | New (v2) | ai-ml-service (Task 3); CR15 |
| FR38.5 | When a test fails, the system repeatedly removes or simplifies parts of the input while the failure remains, to reach a small input that still shows the problem. | Medium | Final | New (v2) | ai-ml-service (Task 3) |
| | **FR39: C and C++ crash explanation (Task 4)** | | | | |
| FR39.1 | The user can start an optional AddressSanitizer and UndefinedBehaviorSanitizer run for C or C++ code. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 4) |
| FR39.2 | The sanitizer report is parsed deterministically into an error summary that gives the error type and, where possible, the reported line number. Examples of error types are out-of-bounds access, use-after-free, double free and undefined behaviour. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 4) |
| FR39.3 | A short AI explanation and a possible fix are produced from the error summary and the relevant code section only, subject to FR12 and FR35. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (Task 4) |
| | **FR40: AI usage and cost control (Task 5)** | | | | |
| FR40.1 | The system keeps counts of model calls, token usage, cache hits and fallback activations, for each room and in total. The counters use SQLite or in-memory storage and never hold raw code or user text. | Medium | Final | New (v2) | CHG-01 (section 4.6); NFR7; ai-ml-service (Task 5) |
| FR40.2 | An AI call sends only the code section relevant to the request, such as the target function or the small section around a reported error, and not the whole file. | Medium | Final | New (v2) | CHG-01 (section 4.6); FR12; ai-ml-service (Task 5) |
| FR40.3 | AI features use the cheapest method that can answer, in this order: cache, rules and statistics, a local model if one is available, then a paid model. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (overview) |
| FR40.4 | A call to a paid model has a limited prompt size and token budget, read from configuration. | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (overview) |
| FR40.5 | When no method can answer (for example consent is off, the cap is reached or the service is down), the user sees a short fallback message and the normal result is unaffected (NFR9). | Medium | Final | New (v2) | CHG-01 (section 4.6); ai-ml-service (overview) |
| | **FR41 to FR42: Run permission and identity** | | | | |
| FR41.1 | In both modes any Participant can run code, unless the Host has restricted Run (FR41.2, FR41.3). An Observer cannot run (FR9.4). | High | Final | New (v2) | CHG-01 (E3, section 4.2); decision in version 2 review |
| FR41.2 | The Host can take away the right to run from a named participant and give it back. | Medium | Final | New (v2) | CHG-01 (section 4.2); decision in version 2 review |
| FR41.3 | The Host can switch a room to Host-only running and back. While it is on, only the Host can run code. | Medium | Final | New (v2) | CHG-01 (section 4.2); decision in version 2 review |
| FR41.4 | Run permission is checked on the server, not only hidden in the interface. A refused run tells the user why. | High | Final | New (v2) | Design decision; FR41.1 |
| FR42 | Display names are unique within a room, and a person is identified by session ID (or GitHub login), so run labels and error attribution (FR26, FR28) name the right person. A name that is already taken is refused. | Medium | Final | New (v2) | CHG-01 (E4, section 4.4); design decision |

*Functional requirements: 29 rows from version 1 (10 changed, 19 unchanged) and 60 new rows (89 in total). 7 version 1 rows were removed (section 7.5).*

---

## 4. Non-Functional Requirements

| ID | Requirement | Priority | Status | Version | Source |
|---|---|---|---|---|---|
| NFR1 | Edits reach other users within 200 ms at the 95th percentile, measured under test set-up T1 (below the table). | High | Final | Unchanged | Survey: 63% want under 200 ms; replaces 150 ms from Lab 6 |
| NFR2 | A room supports 2 to 5 users typically and up to 10 users as its maximum, without breaking NFR1, measured under test set-up T1 (variants T1-P, T1-A and T1-O). Measured first on development machines, then again on the final host. | Medium | Provisional | Changed (v2) | Design decision; CHG-01 (E5; section 4.1: a design assumption, not a measured figure); no load-test data yet |
| | **NFR3: Sandbox isolation** | | | | |
| NFR3.1 | Code runs with no network access. | High | Final | Unchanged | Design decision; docs: Docker --network none |
| NFR3.2 | Code runs as a non-root user. | High | Final | Unchanged | Design decision; benchmark: Piston unprivileged user |
| NFR3.3 | The code directory is mounted read-only. | High | Final | Unchanged | Design decision (sandbox threat model) |
| NFR3.4 | A run cannot reach host files or another user's data. | High | Final | Unchanged | Survey: 78% worried about untrusted code; design decision |
| | **NFR4: Execution limits** | | | | |
| NFR4.1 | Wall-clock time is limited to 5 s for each individual run and for each test inside a generated-test batch (FR38.4). It is killed after that. Features that need several runs (FR36) use separate runs, each within this limit (NFR14), and a test batch also stays within the NFR14 aggregate budget. | High | Final | Changed (v2) | Benchmark: Judge0 CE (5 s wall time); CHG-01 |
| NFR4.2 | Memory is limited to 128 MB with swap disabled (memory-swap equal to memory). An out-of-memory kill is reported as "memory limit exceeded". | High | Final | Unchanged | Benchmark: Judge0 CE (128 MB); docs: Docker memory-swap |
| NFR4.3 | The number of processes and threads is capped (initial value 64) to stop fork bombs. | High | Final | Unchanged | Benchmark: Judge0 CE (60), Piston (256); initial value is a design decision |
| NFR4.4 | Output size is capped (initial value 1 MB). | Medium | Final | Unchanged | Benchmark: Judge0 CE (1024 KB); initial value is a design decision |
| NFR4.5 | Compiled languages get a separate compile-stage timeout (initial value 10 s). | High | Final | Unchanged | Benchmark: Piston compile stage; survey: 97% use C++ |
| | **NFR5: Control of AI requests** | | | | |
| NFR5.1 | An AI call is made only as a step of an analysis (Task 1 to Task 4) that the user has started, only after consent (FR12) and only while AI is switched on (FR35). Nothing calls an external AI service in the background. | Medium | Final | Changed (v2) | Design decision, in response to API rate limits (API docs); privacy and cost control (FR12, NFR7); ai-ml-service (Task 5) |
| NFR5.2 | AI responses are cached by a hash of the normalised code, the language, the request type and the error output where it matters. Normalisation removes comments and normalises whitespace, so formatting-only changes reuse the cached result, and it must preserve program meaning. Only the hash is stored, not the code. | Medium | Final | Changed (v2) | Design decision; team review; CHG-01; ai-ml-service (Task 5) |
| NFR5.3 | AI requests are rate-limited per room, with an hourly limit per room. The limit value is read from configuration. | Medium | Final | Changed (v2) | Design decision, in response to API rate limits (API docs); CHG-01; ai-ml-service (Task 5) |
| | **NFR7: AI cost cap** | | | | |
| NFR7.1 | Total AI API spend is tracked. | Medium | Final | Unchanged | Design decision; docs: API tiers have monthly spend limits; ai-ml-service (Task 5) |
| NFR7.2 | When the monthly cap is reached, only AI calls are blocked, and the blocked call returns the error code COST_CAP_REACHED. Editing and execution continue. | Medium | Final | Changed (v2) | Design decision; docs: API tiers have monthly spend limits; CHG-01; ai-ml-service (Task 5) |
| NFR7.4 | The team lead is alerted when spend reaches 80 percent of the monthly cap. | Low | Final | New (v2) | CHG-01; design decision; ai-ml-service (Task 5) |
| NFR7.5 | Blocked AI calls do not stop the AI-free parts of the analyses (measurement, statistics, rules and sanitizer parsing). | Medium | Final | New (v2) | CHG-01; NFR7.2; ai-ml-service (overview) |
| | **NFR8: AI service logging** | | | | |
| NFR8.1 | Every AI/ML call is logged on the server with request metadata, response status and latency. | Medium | Final | Unchanged | Team gap review (audit, debugging) |
| NFR8.2 | Logs never contain source code or other user-written text. They keep metadata only. | Medium | Final | Changed (v2) | Team gap review; FR12; CHG-01 (section 4.6); ai-ml-service (overview) |
| NFR9 | AI/ML processing never delays the execution result. The result is delivered first. Slowdown flags, analysis results and explanations arrive afterwards as separate messages and are shown to the same people as the run result they belong to (FR4.5, FR27). | High | Final | Changed (v2) | Design decision (fork and join); team review; ai-ml-service (design principle: keep normal execution responsive) |
| NFR10 | No API keys or credentials are committed to the repository. Every commit is scanned for secrets with gitleaks. | High | Final | Unchanged | Team decision (gitleaks); LLM API keys in use |
| NFR11 | Every change reaches main through a pull request with one review and a passing automated build. | Medium | Final | Unchanged | Course GitHub rules; team workflow |
| NFR12 | The client works in the current versions of Chrome, Edge and Firefox. | Low | Final | Unchanged | Design decision (Monaco and Yjs support) |
| NFR13 | The server checks write permission for every change to a document, in both modes. A change from a participant without permission, including from a modified client, is rejected and not forwarded. | High | Final | New (v2) | CHG-01 (E2, E4) |
| NFR14 | Repeated-run features keep every run within NFR4.1, stay within a configurable aggregate time budget, and are capped at a configurable number of runs per request. | Medium | Final | New (v2) | CHG-01; design decision; ai-ml-service (Task 1, Task 3) |
| NFR15 | Limits apply to the number of modules, the size of a module and the number of people in a room. The values are set from testing and read from configuration. | Medium | Final | New (v2) | CHG-01 (E5); design decision |
| NFR16 | Room links contain a long random token that cannot be guessed (at least 128 bits from a secure random generator). | High | Final | New (v2) | CHG-01 (section 4.5); design decision |

**Test set-up T1** (used by NFR1 and NFR2): 10 simulated users in one room, in a document of about 1,000 lines, all on the same campus network. Each user who types enters about 5 characters per second. Three variants are used.

- **T1-P (Pair mode, one editor):** one user types and the other nine only read.
- **T1-A (Pair mode, everyone edits):** all ten users type in the one file, as in the Debug together preset. This is the version 1 set-up.
- **T1-O (Open mode):** a workspace of five modules of about 200 lines each. Five owners type in their own modules and the other five users read all modules.

Each variant is run on a development machine first and on the final host later.

*Non-functional requirements: 22 rows from version 1 (8 changed, 14 unchanged) and 6 new rows (28 in total). 2 version 1 rows were removed (section 7.5).*

---

## 5. Domain Requirements

| ID | Requirement | Priority | Status | Version | Source |
|---|---|---|---|---|---|
| DR1 | Sandboxed execution supports C++, Python, C, Java and JavaScript. Testing priority follows this order. | High | Final | Unchanged | Survey: C++ 97%, Python 8, C 5, Java 3, JS 2 (n = 32) |
| DR2 | Code execution uses the team's own Docker containers. Judge0 and Piston are used only as a reference for limits, never as the runtime. | High | Final | Unchanged | Course scope; Judge0 and Piston used as benchmarks only |
| DR3 | Resource limits (NFR4), sanitizer limits (DR5), the aggregate time budget and run cap (NFR14), the room limits (NFR15), the Host-leaves timeout (FR31.2), the slowdown factor (FR37.2), the AI prompt and token budget (FR40.4), the hourly limit per room (NFR5.3) and the monthly cost cap (NFR7.2) are read from configuration, not hard-coded, and can be set per deployment host, because the safe number of concurrent containers and the safe budgets depend on the hardware. | Medium | Provisional | Changed (v2) | Design decision; docs: Docker resource constraints; CHG-01 |
| DR4 | Files in a workspace keep language-appropriate names and extensions, so compile and run output can be mapped to the module that caused an error. | Medium | Final | New (v2) | CHG-01 (E4); design decision |
| DR5 | Sanitizer-based crash explanation (FR39) applies to C and C++ only. Sanitizer runs use their own configured resource limits (DR3), because sanitizer builds need more memory than NFR4.2 allows, and keep every NFR3 sandbox rule. | Medium | Final | New (v2) | CHG-01; docs: compiler sanitizers; design decision; ai-ml-service (Task 4) |

*Domain requirements: 3 rows from version 1 (1 changed, 2 unchanged) and 2 new rows (5 in total).*

Four items are Provisional: NFR2 (needs load-test results), FR15 (retention period not yet confirmed), DR3 (until the deployment host is fixed) and FR30 (until the decision on keeping deleted content, CR14). They are marked open on purpose.

---

## 6. Conflict-Resolution Log

CR01 to CR07 are carried over, except that CR02 and CR06 are reworded because FR7 and FR13 were removed. CR08 onward are new in version 2.

| ID | Requirements | Conflict | Resolution | Status |
|---|---|---|---|---|
| CR01 | FR1 vs FR9.3 | FR1 supports joining by link; FR9.3 lets the owner require approval first. | Instant join stays the default. Approval is an optional per-room owner setting. | Resolved |
| CR02 | FR36.4, FR38.3, FR39.3 vs NFR7 | The AI steps of the analyses are paid calls, but the monthly cost cap can stop further paid AI calls. | Only the AI steps are blocked once the cap is reached, and the call returns COST_CAP_REACHED (NFR7.2). Measurement, statistics, rules and sanitizer parsing continue (NFR7.5). Editing and execution continue under NFR9. | Resolved |
| CR03 | NFR2 vs DR3 | NFR2 targets up to 10 users; DR3 says the real safe limit depends on deployment hardware. | Test under set-up T1 on development machines first, then again on the actual host. Do not claim the target verified before both measurements exist. | Validation required |
| CR04 | FR11 vs FR9.6 | Automatic reconnection could let a removed user silently rejoin the room. | The server keeps a per-room block list by session ID; a removed user's reconnect attempts are rejected regardless of FR11. | Resolved |
| CR05 | NFR3 vs FR10 | The sandbox has no network access, but GitHub import (FR10.2) needs one to fetch a repository. | Import runs in the platform service, outside the execution sandbox. Sandbox containers never receive network access. | Resolved |
| CR06 | FR12 vs FR36.4, FR38.3, FR39.3 | The AI steps depend on code leaving the sandbox, but a user can decline or withdraw consent. | Declining or withdrawing consent (FR12.3, FR12.4) disables only the AI steps. The AI-free parts of the analyses, editing and execution are unaffected. | Resolved |
| CR07 | NFR11 vs Day-0 setup | NFR11 requires every change through a pull request, but the initial repository skeleton was pushed directly to main before branch protection's bypass setting was locked. | NFR11 is scoped to apply from Sprint 1 onward. The Day-0 skeleton (folders, .gitignore) is a documented, one-time exception made by agreement before enforcement began. | Resolved |
| CR08 | NFR9 vs FR27 | Analysis messages go to the people who see the run result, but in Open mode the runner chooses who sees output. | Visibility is a property of the run. Slowdown flags, analysis results and explanations have the same audience as the run result they belong to (NFR9). The runner always sees their own. | Resolved |
| CR09 | FR9.4 vs FR23 | Read-only guests versus everyone reading every module live. | Reading is never restricted inside a room. FR9.4 restricts editing and running only. A removed user is blocked entirely (FR9.6). | Resolved |
| CR10 | NFR4.1 vs FR36 | Repeated runs at input sizes n, 2n and 4n cannot fit inside one 5 s limit as a whole. | The 5 s limit stays per run. A separate configurable aggregate budget and a cap on runs per request apply (NFR14). Each run uses its own container, and the queue limit applies to every run (FR16). | Resolved |
| CR11 | FR15 vs FR29, FR32 and FR37.4 | A retention period of about 24 hours (Provisional) versus run history needed for slowdown detection (FR37) and deletion at room close. | Until FR15's period is confirmed, run history is kept for the life of the room and deleted with it (FR32). The period is chosen so that a normal session keeps enough history for the FR37 baseline, and is confirmed before FR15 is marked Final. | Validation required |
| CR12 | FR12 vs FR35 | Per-user consent versus the room-level Host AI switch. | An AI step runs only when the Host switch is on and the user has given consent. Either one being off blocks it. Editing and execution are unaffected (FR12.3). | Resolved |
| CR13 | NFR13 vs NFR1 | Checking write permission on every change could add edit latency. | Checks use a cached permission lookup on the message header and do not parse the document content. The effect is measured under T1-P, T1-A and T1-O. NFR13 is not marked verified until NFR1 is recorded as met with the checks on. | Validation required |
| CR14 | FR30 vs NFR1 and NFR2 | Showing who wrote which line needs the shared document to keep deleted content, which raises memory use and could affect edit sync at the maximum room size. | The decision on keeping deleted content is made before FR30 is built and tested with long sessions under T1-O. FR30 is Low priority and can be dropped without affecting any other requirement. | Validation required |
| CR15 | FR38.4 vs FR5.1 and NFR4.1 | Task 3 runs many generated tests in one sandbox container, but FR5.1 requires every run to use its own new container and NFR4.1 limits each run to 5 s. | A whole test batch counts as one run, in one new container (FR5.1, FR38.4). Each test runs as a separate process within NFR4.1 and NFR4.2, the whole batch stays within the NFR14 budget, and the container is destroyed afterwards. FR5.1 has no exception, and every NFR3 isolation rule applies. | Resolved |

---

## 7. Revision History

### 7.1 Versions

| Version | Date | Change |
|---|---|---|
| 1 | Day-2 deliverable | Requirement set with FR1 to FR16, NFR1 to NFR12 and DR1 to DR3, EPICs and sprint plan. Conflict log CR01 to CR07 in the backlog document. |
| 2 | 28 October 2026 | Redesign for two room modes under CHG-01, with the AI/ML requirements aligned to `AI_ML_Requirements_v2.md`. Existing IDs kept, 19 requirements changed, 9 version 1 requirements removed (section 7.5), 68 new rows added (FR17 to FR42 with sub-IDs, NFR7.4, NFR7.5, NFR13 to NFR16, DR4, DR5), conflicts CR08 to CR15 added, CR02 and CR06 reworded, stakeholders restated, positioning, roles summary and out-of-scope list added. |

### 7.2 Requirements changed in version 2

| ID | Change |
|---|---|
| FR1 | Room creation includes the mode choice. |
| FR2 | Merging applies per module in Open mode. |
| FR3.1 | Presence shows the role. |
| FR4.1 | Run request carries live text (Pair) or saved modules with an entry file or runnable module (Open). |
| FR4.5 | Result visibility depends on the mode. |
| FR5.1 | A run is one container execution, and a generated-test batch counts as one run. |
| FR9.4 | Read-only guest becomes the Observer role. |
| FR11.2 | Resync covers every module. |
| FR15 | History is also deleted when the room is deleted. |
| FR16 | Queue limit applies to repeated runs. |
| NFR2 | Room size stated, T1 variants added. |
| NFR4.1 | The 5 s limit applies per run and per test inside a test batch. |
| NFR5.1 | AI calls happen only as a step of an analysis the user has started. |
| NFR5.2 | Cache key uses normalised code, and normalisation is defined. |
| NFR5.3 | Hourly per-room limit. |
| NFR7.2 | Blocked calls return COST_CAP_REACHED. |
| NFR8.2 | Logs hold no source code or user-written text. |
| NFR9 | Analysis messages have the same audience as the run result. |
| DR3 | Configuration covers the new budgets and limits. |

### 7.3 Summary

| Class | Rows | Share of 122 |
|---|---|---|
| Unchanged | 35 | 28.7% |
| Changed (v2) | 19 | 15.6% |
| New (v2) | 68 | 55.7% |

Of the 63 requirements in version 1, 35 (55.6%) are unchanged, 19 (30.2%) are changed and 9 (14.3%) were removed (section 7.5). Version 1's own text says it has 65 rows, but its tables hold 63 (36 + 24 + 3), and this document uses 63.

### 7.4 Decisions recorded in version 2

| Topic | Decision | Where |
|---|---|---|
| Selective run | Two allowed ways: run a module that is marked runnable on its own, or run a selection of modules (or the whole workspace) through the entry file. Subsets without the entry file are out of scope. | FR25.2, FR25.3 |
| Who can run | Any Participant can run, an Observer cannot, and the Host can restrict Run per person and for the whole room. Vote-to-run is out of scope. | FR41, section 1.2 |
| Items Proposed in CHG-01 section 4.5 | FR31 to FR35 and NFR15 have been approved and are Final. FR35 (Host AI switch) is kept, although `AI_ML_Requirements_v2.md` removes it. | FR31 to FR35, NFR15 |
| AI/ML requirements | They follow `AI_ML_Requirements_v2.md`. AI/ML requirements that are not in its five tasks were removed, except FR35 and NFR5.1. | Sections 1.3 and 7.5 |
| AI calls | AI calls happen only as a step of an analysis the user has started, after consent and while AI is on. | NFR5.1 |
| Generated-test batches (CR15) | A batch of generated tests is one run in one new container, so FR5.1 has no exception. Each test keeps the NFR4.1 limit. | FR5.1, FR38.4, NFR4.1, CR15 |
| Host in Open mode | The Host can edit a module only if given write access, like anyone else. | FR22.2 |
| Design decisions made in this review | Who can save a module (FR24.1), module ownership after the owner is removed (FR21.5), AI default outside the Teach/Interview preset (FR35.2), unique display names (FR42), unguessable room links (NFR16). Each is marked in the Source column. | Source column |

### 7.5 AI/ML requirements removed in version 2

These requirements were in earlier drafts but are not described in `ai-ml-service.md`, so they are not part of the AI/ML scope in `AI_ML_Requirements_v2.md`. Restore a row here if the team wants it back.

| ID | Requirement (short) | Reason for removal |
|---|---|---|
| FR7.1, FR7.2, FR7.3 | On-demand AI code review, dismissing a review, turning review off | Code review is not one of the five tasks. |
| FR8.1, FR8.2 | Anomaly warning from an anomaly score above a threshold | Not in the five tasks. The closest task is slowdown detection (FR37). |
| FR13.1, FR13.2 | AI explanation of failed runs in general | Only C and C++ crash explanation (FR39) is in scope. |
| NFR6 | Wait for retry-after on HTTP 429 | Not stated in `ai-ml-service.md`. The fallback message (FR40.5) covers an unavailable service. |
| NFR7.3 | Alert when the cap is reached | `ai-ml-service.md` alerts at 80 percent (NFR7.4) and returns COST_CAP_REACHED at the cap (NFR7.2). |

FR35 (Host AI switch) is also absent from `AI_ML_Requirements_v2.md` but is kept in this document, because CHG-01 (sections 4.2 and 4.5) requires it. NFR5.1 is also absent from that file and is restored with new wording, because the AI steps start inside user-started analyses and need a rule that stops background calls.
