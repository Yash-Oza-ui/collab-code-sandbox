## IT-314 Software Engineering

## Requirements Specification, EPICs and Sprint Plan

Group 32 | Collaborative Code Editor with Sandboxed Execution

Day-2 Deliverable | Group 2 members: Niranjan Panchal, Jay Limbasiya, Prince Gadara, Yash Oza

Repository: https://github.com/Yash-Oza-ui/collab-code-sandbox

This document takes the stakeholders, survey results and document analysis from the Group 1 report and turns them into a requirement set the team can build and test against. Each requirement is one statement with one source, so a story or task can point at it directly.

## How to read the tables

- Priority: High is needed for the core demo, Medium is important but can follow, Low is optional. Status is Final unless the source data is still missing.

- Source labels: Survey and Interview are Group 1 data. Docs means a fact taken from documentation. Benchmark is a default from Judge0 or Piston, used only as a reference. Design decision is a team choice, not evidence.

## 1. Functional Requirements

| ID | Requirement | Priority Status |   | Source |
| --- | --- | --- | --- | --- |
| FR1 | A user can create a room and get a shareable link. Opening the link loads the shared, CRDT-synced document. | High | Final | Interview: S1 (room links) |
| FR2 | Simultaneous edits by several users merge through Yjs without overwriting each other. | High | Final | Survey: 78% report overwritten changes |
|   | FR3: Live cursors and presence |   |   |   |
| FR3.1 | Each active collaborator's cursor and selection are shown with a name and a colour. | High | Final | Survey: 91% rate it 4-5; docs: Yjs awareness |
| FR3.2 | A collaborator with no activity for about 30 s is dropped from the presence list. | Medium Final |   | Docs: Yjs awareness (30 s timeout) |
|   | FR4: Running code and showing results |   |   |   |
| FR4.1 | A run request carries the code, the selected language and optional stdin. | High | Final | Design decision (API contract) |
| FR4.2 | stdout and stderr are always shown after a run, including failed runs. | High | Final | Survey: stdout 88%, stderr 81% |
| FR4.3 | Execution time and memory usage are shown in a compact metrics bar, separate from the output. | Medium Final |   | Survey: time 59%, memory 41% |
| FR4.4 | The exit code is shown in the metrics bar. | Low | Final | Survey: 31% |
| FR4.5 | The result of a run is shown to every participant in the room. | High | Final | Design decision (shared session) |


| ID | Requirement | Priority Status |   | Source |
| --- | --- | --- | --- | --- |
|   | FR5: Sandboxed execution |   |   |   |
| FR5.1 | Every run executes in its own new Docker container. High |   | Final | Course scope; design decision |
| FR5.2 | The container and its workspace are destroyed after the run. No state is reused. | High | Final | Design decision; benchmark: Piston cleans temp space |
| FR6 | Execution time and peak memory are captured for every run. | Medium Final |   | Survey: time 59%, memory 41% |
|   | FR7: On-demand AI code review |   |   |   |
| FR7.1 | AI review runs only when the user asks for it, and only after consent (FR12). | Medium Final |   | Interview: S4; survey: 72% interested |
| FR7.2 | A review can be dismissed by the user. | Medium Final |   | Survey: 3 respondents rated interest 1/5 |
| FR7.3 | Each user can turn AI review off. | Medium Final |   | Survey: 3 respondents rated interest 1/5 |
|   | FR8: Anomaly flagging |   |   |   |
| FR8.1 | Unusual execution behaviour produces a warning with a short reason. "Unusual" means an anomaly score above a threshold chosen during model validation in Sprint 3. | Medium Final |   | Interview: S4; survey: 78% want warnings |
| FR8.2 | The warning arrives after the result as a separate message (NFR9) and never blocks it. It is shown to everyone in the room, like the result itself (FR4.5). | Medium Final |   | Survey: 22% said it depends on how it is shown |
|   | FR9: Room owner controls |   |   |   |
| FR9.1 | The room owner is notified each time someone joins. Medium Final |   |   | Interview: S3 |
| FR9.2 | The owner can remove any participant at any time. | High | Final | Interview: S3 |
| FR9.3 | The owner can switch on join approval for a room. Instant join stays the default. | Medium Final |   | Interview: S3; FR1 |
| FR9.4 | The owner can make a guest read-only. This is enforced on the server, not only hidden in the interface. A read-only guest can see the document, cursors and run results, but cannot edit or press Run. | Medium Final |   | Interview: S3; team review |
| FR9.5 | The room owner keeps ownership after a page refresh or reconnect. The owner is identified by a token saved in the browser, or by GitHub login when it is used. | High | Final | Team review (login is optional, FR10) |


| ID | Requirement | Priority Status |   | Source |
| --- | --- | --- | --- | --- |
| FR9.6 | A removed user is blocked from that room, by session ID, until the owner re-admits them or regenerates the link. Automatic reconnect does not get around the block. | High | Final | Interview: S3; team review |
|   | FR10: GitHub login and import (optional) |   |   |   |
| FR10.1 | Login with GitHub is optional and asks only for the read:user and user:email scopes. | Low | Final (scoped) | Docs: GitHub OAuth scopes; design decision |
| FR10.2 | A user can import code from a public GitHub repository. Private repositories are out of scope. | Low | Final (scoped) | Docs: GitHub OAuth scopes; design decision |
|   | FR11: Reconnection and resync |   |   |   |
| FR11.1 | After a disconnect or page refresh, the client reconnects automatically with exponential backoff. | High | Final | Survey: 38% lost work; docs: y- websocket |
| FR11.2 | After reconnecting, the document resyncs to the latest shared state with no manual step. | High | Final | Survey: 38% lost work; docs: y- indexeddb |
|   | FR12: AI privacy consent |   |   |   |
| FR12.1 | Before the first AI use, the user sees a notice that their code will be sent to an external AI service. | High | Final | Team gap review (privacy) |
| FR12.2 | AI features stay blocked until the user consents. | High | Final | Team gap review (privacy) |
| FR12.3 | Declining consent does not affect editing or code execution. | High | Final | Team gap review (privacy) |
| FR12.4 | Consent can be given or withdrawn per session. | High | Final | Team gap review (privacy) |
|   | FR13: AI explanation of failed runs |   |   |   |
| FR13.1 | When a run fails (compile error, non-zero exit or timeout kill), the user can get a short plain-language explanation of the likely cause, subject to FR12. The explanation is at most three sentences. | Medium Final |   | Team gap review; execution flow diagram |
| FR13.2 | The explanation arrives after the result as a separate message (NFR9), is shown next to stderr and is kept separate from the FR7 review. | Medium Final |   | Team gap review |
| FR14 | A user who joins without GitHub login enters a display name. That name and a colour are used for the user's cursor. | Medium Final |   | Design decision (FR3 needs a name, FR10 is optional) |
| FR15 | Execution history for a room is kept for a limited time and then deleted automatically. Proposed period: around 24 hours. | Low | Provisional | Team discussion; period not yet confirmed |


| ID | Requirement | Priority Status |   | Source |
| --- | --- | --- | --- | --- |
| FR16 | The execution queue has a maximum length. A run request beyond it is rejected with a clear "busy, try again" message. | Medium Final |   | Benchmark: Judge0 CE queue size 100; design decision |

*Table 1. Functional requirements (36 rows).*

## 2. Non-Functional Requirements

| ID | Requirement | Priority Status |   | Source |
| --- | --- | --- | --- | --- |
| NFR1 | Edits reach other users within 200 ms at the 95th percentile, measured under test set-up T1 (below the table). | High | Final | Survey: 63% want under 200 ms; replaces 150 ms from Lab 6 |
| NFR2 | A room supports at least 10 concurrent users without breaking NFR1, measured under test set-up T1. Measured first on development machines in Sprint 2, then again on the final host in Sprint 4. |   | Medium Provisional | No load-test data yet |
|   | NFR3: Sandbox isolation |   |   |   |
| NFR3.1 | Code runs with no network access. | High | Final | Design decision; docs: Docker -- network none |
| NFR3.2 | Code runs as a non-root user. | High | Final | Design decision; benchmark: Piston unprivileged user |
| NFR3.3 | The code directory is mounted read-only. | High | Final | Design decision (sandbox threat model) |
| NFR3.4 | A run cannot reach host files or another user's data. | High | Final | Survey: 78% worried about untrusted code; design decision |
|   | NFR4: Execution limits |   |   |   |
| NFR4.1 | Wall-clock time per run is limited to 5 s. The run is killed after that. | High | Final | Benchmark: Judge0 CE (5 s wall time) |
| NFR4.2 | Memory is limited to 128 MB with swap disabled (memory-swap equal to memory). An out-of- memory kill is reported as "memory limit exceeded". | High | Final | Benchmark: Judge0 CE (128 MB); docs: Docker memory-swap |
| NFR4.3 | The number of processes and threads is capped (initial value 64) to stop fork bombs. | High | Final | Benchmark: Judge0 CE (60), Piston (256); initial value is a design decision |


| ID | Requirement | Priority Status |   | Source |
| --- | --- | --- | --- | --- |
| NFR4.4 | Output size is capped (initial value 1 MB). | Medium Final |   | Benchmark: Judge0 CE (1024 KB); initial value is a design decision |
| NFR4.5 | Compiled languages get a separate compile-stage timeout (initial value 10 s). | High | Final | Benchmark: Piston compile stage; survey: 97% use C++ |
|   | NFR5: Control of AI requests |   |   |   |
| NFR5.1 | AI calls happen only when the user triggers them. | Medium Final |   | Design decision, in response to API rate limits (API docs) |
| NFR5.2 | AI responses are cached by a hash of the code, the language, the request type (review or explanation) and the stderr where it matters. Only the hash is stored, not the code. | Medium Final |   | Design decision; team review |
| NFR5.3 | AI requests are rate-limited per room. | Medium Final |   | Design decision, in response to API rate limits (API docs) |
| NFR6 | On an HTTP 429 response the client waits for the retry-after time. Editing and execution keep working. | Medium Final |   | Docs: API rate limits (429, retry- after) |
|   | NFR7: AI cost cap |   |   |   |
| NFR7.1 | Total AI API spend is tracked. | Medium Final |   | Design decision; docs: API tiers have monthly spend limits |
| NFR7.2 | When the monthly cap is reached, only AI calls (review and explanation) are blocked. Editing and execution continue. | Medium Final |   | Design decision; docs: API tiers have monthly spend limits |
| NFR7.3 | The team lead is alerted when the cap is reached. | Low | Final | Design decision |
|   | NFR8: AI service logging |   |   |   |
| NFR8.1 | Every AI/ML call is logged on the server with request metadata, response status and latency. | Medium Final |   | Team gap review (audit, debugging) |
| NFR8.2 | Logs do not keep raw user code beyond what the call itself needs. | Medium Final |   | Team gap review; FR12 |
| NFR9 | AI/ML processing never delays the execution result. The result is delivered first. Anomaly warnings and explanations arrive afterwards as separate messages. | High | Final | Design decision (fork and join); team review |


| ID | Requirement | Priority Status |   | Source |
| --- | --- | --- | --- | --- |
| NFR10 | No API keys or credentials are committed to the repository. Every commit is scanned for secrets with gitleaks. | High | Final | Team decision (gitleaks); LLM API keys in use |
| NFR11 | Every change reaches main through a pull request with one review and a passing automated build. | Medium Final |   | Course GitHub rules; team workflow |
| NFR12 | The client works in the current versions of Chrome, Edge and Firefox. | Low | Final | Design decision (Monaco and Yjs support) |

*Table 2. Non-functional requirements (24 rows).*

Test set-up T1 (used by NFR1 and NFR2): 10 simulated users in one room, each typing about 5 characters per second, in a document of about 1,000 lines, all on the same campus network. Run on a development machine in Sprint 2 and on the final host in Sprint 4.

## 3. Domain Requirements

| ID | Requirement | Priority Status |   | Source |
| --- | --- | --- | --- | --- |
| DR1 | Sandboxed execution supports C++, Python, C, Java and JavaScript. Testing priority follows this order. | High | Final | Survey: C++ 97%, Python 8, C 5, Java 3, JS 2 (n = 32) |
| DR2 | Code execution uses the team's own Docker containers. Judge0 and Piston are used only as a reference for limits, never as the runtime. | High | Final | Course scope; Judge0 and Piston used as benchmarks only |
| DR3 | Resource limits (NFR4) are read from configuration, not hard-coded, and can be set per deployment host, because the safe number of concurrent containers depends on the hardware. |   | Medium Provisional | Design decision; docs: Docker resource constraints |

*Table 3. Domain requirements (3 rows).*

Three items are still Provisional: NFR2 (needs load-test results), DR3 (needs the deployment host to be fixed) and FR15 (retention period not yet decided). They are marked as open on purpose, and each has a sprint where it will be closed.

## 4. EPICs

The requirements are grouped into six EPICs, each owned by one of the four team categories (A: Real-Time Editor, B: Execution and Sandbox, C: AI/GenAI and ML, D: Platform).

| EPIC | Title | Owning category | Requirements covered |
| --- | --- | --- | --- |
| EPIC 1 | Real-Time Collaborative Editor | Category A | FR1, FR2, FR3, FR11, FR14, NFR1, NFR12 |
| EPIC 2 | Sandboxed Execution Engine Category B |   | FR4, FR5, FR6, FR16, NFR3, NFR4, DR1, DR2, DR3 |


| EPIC | Title | Owning category | Requirements covered |
| --- | --- | --- | --- |
| EPIC 3 | AI Code Review and Assistance | Category C | FR7, FR12, FR13, NFR5, NFR6, NFR7, NFR8, NFR9, |
| EPIC 4 | ML Execution Anomaly Detection | Category C | FR8 |
| EPIC 5 | Room and Session Management | Category A / D FR9 |   |
| EPIC 6 | Platform: Auth, Data and Quality | Category D | FR10, FR15, NFR2, NFR10, NFR11 |

*Table 4. EPICs mapped to requirements and owning category.*

## 5. Sprint Plan

The project runs in four sprints of about two weeks each. Requirements, backlog and repository set-up are done before Sprint 1 starts on Oct 1.

| Sprint | Scope | Categories |
| --- | --- | --- |
| Sprint 1 | Service scaffolding for all four folders. EPIC 1 basics (FR1, FR2, FR14). Owner identity that survives refresh (FR9.5). EPIC 2 basics (FR4.1, FR4.2, FR4.5, FR5). CI, secret scanning and review rules (NFR10, NFR11). Integration-test skeleton and API contracts, including the contract between the execution service and the AI/ML service (covering all five DR1 languages). Category C prep: synthetic dataset for the anomaly model. | All categories |
| Sprint 2 | EPIC 1 complete (FR3, FR11, NFR1, NFR12). EPIC 2 complete (FR4.3, FR4.4, FR6, FR16, NFR3, NFR4, DR1, DR2), with limits read from configuration (DR3 mechanism). EPIC 5 (FR9). Baseline load test for NFR2 on development machines. Category C prep: baseline anomaly model on synthetic data and an internal LLM prototype behind the contract, not user-facing until FR12 is in place. Integration testing Editor and Execution. | A, B, C (prep), (D support) |
| Sprint 3 | EPIC 3: consent first (FR12), then FR7, FR13, NFR5, NFR6, NFR7, NFR8, NFR9. EPIC 4: anomaly detection delivered to users (FR8). Integration testing AI and Execution. | C, (B support) |
| Sprint 4 | EPIC 6: FR10, FR15 (once the period is confirmed), DR3 host tuning, NFR2 re- run on the final host. Integration testing across all services. Documentation and demo polish. | D, all |

*Table 5. Sprint plan mapped to requirements and categories.*

Cross-category support: EPIC 5 (room owner controls) and the NFR2 baseline load test bring a Category D member into Sprint 2, ahead of EPIC 6's main work in Sprint 4. This is planned early support, not a change of ownership.


## 6. Traceability and Changes

## 6.1 From the Lab 6 draft (FR1-FR10, NFR1-NFR5, DR1-DR3, all Provisional):

- FR3 moved from Provisional to Final (survey: 91% rate live cursors 4-5; Yjs awareness docs).

- NFR1 was revised from 150 ms to 200 ms at the 95th percentile, based on the survey.

- FR11 and NFR6 are new, from survey and document-analysis findings that were not available at Lab 6.

- DR1 is revised, not new. It was already Final at Lab 6. What is new is the survey-based priority order and the addition of C to the supported list.

- NFR5 was redefined. At Lab 6 it covered graceful AI degradation, which NFR6 now carries. NFR5 now covers AI call triggering, caching and per-room rate limiting.

- FR9 was broadened (join notification, optional approval, read-only guests) and its priority raised from Medium to High, based on the S3 follow-up.

- FR10 is kept but limited in scope by the OAuth-scopes document analysis.

## 6.2 In this version, after team review:

- Bundled requirements were split into single, testable statements. The 24 parent requirements became 56 rows, and 9 new requirements were added, for 65 rows in total. Parent IDs are unchanged, and sub-IDs added during review (FR7.3, FR9.6, FR12.4) were appended so no existing sub-ID changed.

- New requirements, each with a source: FR14, FR16, NFR9 to NFR12. FR15 (retention) is included as Provisional.

- NFR9 was reworded. AI/ML work cannot run in parallel with execution, because FR13 needs the failed run's stderr and FR8 needs its metrics. The result is delivered first, and warnings and explanations follow as separate messages.

- Blocking a removed user (now FR9.6) no longer relies on stopping automatic reconnect, which a removed user could bypass by opening the link again. The user is blocked by session ID until re-admitted or the link is regenerated.

- FR9.5 is new, because with optional login the owner is only a browser session and a refresh could lose ownership. FR9.4 now requires server-side enforcement, and read-only guests cannot press Run.

- NFR5.2 now caches on code, language, request type and stderr, and stores only the hash, which keeps it consistent with NFR8.2.

- NFR1 and NFR2 use one shared test set-up (T1), which removes the circular reference between them. NFR2 is measured twice, on development machines in Sprint 2 and on the final host in Sprint 4.

- DR3's configuration mechanism is built with NFR4 in Sprint 2, so that only host tuning remains for Sprint 4.

- AI/ML preparation (contract, synthetic dataset, baseline model, internal prototype) starts in Sprint 1 and 2, so Category C has visible progress at the Oct 13 mid-evaluation. Delivery of FR8 to users stays in Sprint 3.


- NFR8 (AI logging) moved from Sprint 4 to Sprint 3, and FR12 (consent) is scheduled first in Sprint 3 because FR7 and FR13 depend on it.

- Behaviours that would become separate stories or issues were split: dismissing a review versus turning AI review off (FR7.2, FR7.3), removing a participant versus blocking them afterwards (FR9.2, FR9.6), and blocking AI until consent versus giving or withdrawing consent (FR12.2, FR12.4).

- Sources are labelled by type. Where a requirement is a team choice (per-room rate limiting, hash caching, the team-lead alert, sandbox rules), it is marked as a design decision and not credited to documentation. Documentation is cited only for the facts it contains, such as 429 responses with retry-after, Yjs awareness timeouts and the Judge0 default limits.

- FR8 warnings are shown to everyone in the room, like run results (FR4.5). Sprint dates were added.
