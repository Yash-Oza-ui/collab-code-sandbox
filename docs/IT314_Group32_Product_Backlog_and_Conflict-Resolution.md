# IT-314 Software Engineering --- Group 32

## Collaborative Code Editor with Sandboxed Execution

**Day-3 Deliverable:** Product Backlog and Conflict-Resolution Log\
**Group:** 32\
**Group 3 Members:** Chandan Luhar, Quincy Vadi, Bhavya Vekariya

------------------------------------------------------------------------

## 1. Product Backlog

This backlog is based on the Group 2 requirements and keeps the same six
EPICs, priorities, and four-sprint plan. It covers all 24 requirement
IDs: FR1--FR13, NFR1--NFR8, and DR1--DR3. NFR2 and DR3 still need real
load-test or deployment-host evidence, so they are included as
validation work instead of using guessed values.

### 1.1 EPIC 1 --- Real-Time Collaborative Editor

#### US-01 --- FR1 \| Sprint 1 \| Priority: High

**User Story:** As a student, I want to create a room and share a join
link so that my teammates can join the same coding session.

**Acceptance Criteria** - Creating a room generates a shareable join
link. - Opening the link loads the same Yjs-backed shared document for
all users.

#### US-02 --- FR2 \| Sprint 1 \| Priority: High

**User Story:** As a collaborator, I want simultaneous edits to merge
safely so that nobody loses work.

**Acceptance Criteria** - Concurrent edits merge through Yjs without
overwriting another user's changes. - Connected clients reach the same
document state.

#### US-03 --- FR3 \| Sprint 2 \| Priority: High

**User Story:** As a collaborator, I want to see live cursors and
presence so that I know who is editing.

**Acceptance Criteria** - Each active collaborator's cursor and
selection are visible with name/colour. - Idle collaborators are removed
from presence after about 30 seconds.

#### US-04 --- FR11 \| Sprint 2 \| Priority: High

**User Story:** As a collaborator, I want automatic reconnection and
resync so that I do not lose work after a disconnect or refresh.

**Acceptance Criteria** - The client automatically reconnects after a
temporary disconnect. - The document resynchronizes to the latest shared
state without a manual resync step.

#### US-05 --- NFR1 \| Sprint 2 \| Priority: High

**User Story:** As a collaborator, I want edits to appear quickly so
that the editor feels real-time.

**Acceptance Criteria** - 95th-percentile edit-sync latency is 200 ms or
less under the agreed normal test conditions.

### 1.2 EPIC 2 --- Sandboxed Execution Engine

#### US-06 --- FR4 \| Sprint 1 \| Priority: High

**User Story:** As a student, I want to run code and see the result so
that I can check whether my program works.

**Acceptance Criteria** - The execution request includes code, language,
and input when required. - stdout and stderr are shown after execution,
including failure cases.

#### US-07 --- FR5 \| Sprint 1 \| Priority: High

**User Story:** As a platform operator, I want every run isolated in a
temporary Docker container so that user code cannot affect the host or
other users.

**Acceptance Criteria** - Each run uses its own Docker container. - The
container is destroyed after execution and no run state is reused.

#### US-08 --- FR6 \| Sprint 2 \| Priority: Medium

**User Story:** As a student, I want execution time and memory usage so
that I can understand basic performance.

**Acceptance Criteria** - Execution time and memory usage are captured
for each run. - The metrics are shown separately from stdout/stderr in a
compact area.

#### US-09 --- NFR3 \| Sprint 2 \| Priority: High

**User Story:** As a platform operator, I want strong sandbox isolation
so that untrusted code stays contained.

**Acceptance Criteria** - Execution has no network access. - Code runs
as a non-root user and the code mount is read-only. - A run cannot
access host files or another user's data.

#### US-10 --- NFR4 \| Sprint 2 \| Priority: High

**User Story:** As a platform operator, I want strict resource limits so
that runaway code cannot exhaust the system.

**Acceptance Criteria** - Wall-clock execution is limited to 5
seconds. - Memory is limited to 128 MB. - Process/thread count and
output size are capped, and compiled languages use a separate compile
timeout.

#### US-11 --- DR1 \| Sprint 2 \| Priority: High

**User Story:** As a student, I want the main coursework languages
supported so that I can use the editor for normal lab work.

**Acceptance Criteria** - Supported languages are C++, Python, C, Java,
and JavaScript. - Testing priority follows C++ first, then Python, C,
Java, and JavaScript.

#### US-12 --- DR2 \| Sprint 2 \| Priority: High

**User Story:** As a platform operator, I want execution on self-managed
Docker containers so that the team controls the sandbox.

**Acceptance Criteria** - Code execution uses the team's Docker
runtime. - Judge0 and Piston are used only as configuration references,
not as the runtime.

#### US-13 --- DR3 \| Sprint 4 \| Priority: Medium

**User Story:** As a platform operator, I want resource limits
configurable per deployment host so that the sandbox can match the
available hardware.

**Acceptance Criteria** - NFR4 limits can be configured for the
deployment host. - Final safe host-specific settings are recorded only
after the deployment host is fixed and tested.

### 1.3 EPIC 3 --- AI Code Review & Assistance

#### US-14 --- FR7 \| Sprint 3 \| Priority: Medium

**User Story:** As a student, I want to request AI code review only when
I need it so that the feature stays optional.

**Acceptance Criteria** - AI review runs only after a user request. -
Review output is dismissible and the user can disable the feature.

#### US-15 --- FR12 \| Sprint 3 \| Priority: High

**User Story:** As a student, I want to give consent before code is sent
to an external AI service so that I control external use of my code.

**Acceptance Criteria** - External AI use requires explicit consent. -
Consent is available through a first-use notice and a per-session
control. - Opting out does not block normal editing or code execution.

#### US-16 --- FR13 \| Sprint 3 \| Priority: Medium

**User Story:** As a student, I want a short explanation when execution
fails so that I can understand the error more easily.

**Acceptance Criteria** - Compile errors, non-zero exits, and timeout
failures can produce a short plain-language explanation. - The
explanation is shown beside the normal stderr/output and is separate
from FR7 review.

#### US-17 --- NFR5 \| Sprint 3 \| Priority: Medium

**User Story:** As a platform operator, I want AI requests controlled so
that repeated calls do not waste quota or cost.

**Acceptance Criteria** - AI calls are user-triggered. - Results can be
cached by code hash and requests are rate-limited per room.

#### US-18 --- NFR6 \| Sprint 3 \| Priority: Medium

**User Story:** As a student, I want the editor and execution to keep
working when the AI service is rate-limited.

**Acceptance Criteria** - A 429 response follows the provider
retry-after behaviour where available. - Editor and execution remain
usable while AI is unavailable or waiting.

#### US-19 --- NFR7 \| Sprint 3 \| Priority: Medium

**User Story:** As a platform operator, I want a monthly external-AI
cost cap so that spending stays controlled.

**Acceptance Criteria** - Cumulative AI API spend is tracked. - Further
paid AI review/explanation calls are blocked after the configured cap is
reached. - The team lead is alerted when the cap is reached.

### 1.4 EPIC 4 --- ML Execution Anomaly Detection

#### US-20 --- FR8 \| Sprint 3 \| Priority: Medium

**User Story:** As a student, I want a warning when execution behaviour
looks unusual so that I can investigate it without losing my result.

**Acceptance Criteria** - An unusual execution pattern produces a short
reason/warning. - The warning is non-blocking and normal execution
results are still shown.

### 1.5 EPIC 5 --- Room & Session Management

#### US-21 --- FR9 \| Sprint 2 \| Priority: High

**User Story:** As a room owner, I want basic participant controls so
that I can manage the session.

**Acceptance Criteria** - The owner is notified when a user joins and
can remove a participant. - Join approval is optional and can be enabled
per room. - The owner can make a guest read-only.

### 1.6 EPIC 6 --- Platform: Auth, Data & Scale

#### US-22 --- FR10 \| Sprint 4 \| Priority: Low

**User Story:** As a student, I want optional GitHub login and
public-repository import so that I can bring existing code into the
editor.

**Acceptance Criteria** - GitHub OAuth is optional and requests only
`read:user` and `user:email`. - Public-repository import is in scope;
private-repository import is out of scope.

#### US-23 --- NFR2 \| Sprint 2 \| Priority: Medium

**User Story:** As a platform operator, I want the room to support at
least 10 concurrent users without breaking the sync target.

**Acceptance Criteria** - Run a controlled test with at least 10
concurrent users in one room. - Record whether NFR1 (200 ms or less at
the 95th percentile) is maintained at that load. - Do not mark the
target verified until measured evidence is available.

#### US-24 --- NFR8 \| Sprint 4 \| Priority: Medium

**User Story:** As a platform operator, I want AI/ML API calls logged so
that failures and usage can be audited.

**Acceptance Criteria** - Log request metadata, response status, and
latency server-side. - Do not store extra raw user code in logs beyond
what the call itself requires.

------------------------------------------------------------------------

## 2. Conflict-Resolution Log

  --------------------------------------------------------------------------
  ID             Requirements   Conflict       Resolution     Status
  -------------- -------------- -------------- -------------- --------------
  CR-01          FR1 vs FR9     FR1 supports   Instant join   Resolved
                                joining by     stays the      
                                link, while    default.       
                                FR9 can        Approval is an 
                                require owner  optional       
                                approval.      per-room       
                                               setting        
                                               controlled by  
                                               the owner.     

  CR-02          FR7 / NFR7 /   AI review is   Only the AI    Resolved
                 NFR6           on demand, but add-on is      
                                the monthly    blocked after  
                                cost cap can   the cap.       
                                stop further   Editor and     
                                paid AI calls. code execution 
                                               continue to    
                                               work under     
                                               NFR6.          

  CR-03          NFR2 vs DR3    NFR2 targets   Test 10 users  Validation
                                at least 10    on the actual  required
                                users, but DR3 host and       
                                says the real  record the     
                                safe limit     measured       
                                depends on     result. Do not 
                                deployment     claim the      
                                hardware.      target is      
                                               verified       
                                               before         
                                               testing.       
  --------------------------------------------------------------------------
