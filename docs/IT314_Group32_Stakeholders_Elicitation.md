# IT-314 Software Engineering

## Stakeholder Finalization, Requirements Elicitation & Data Collection

**Group 32 — Collaborative Code Editor with Sandboxed Execution**

---

## 👥 Team Members

| Member ID |
| --------- |
| 202401493 |
| 202401431 |
| 202401457 |
| 202401419 |
| 202401427 |
| 202401430 |
| 202401441 |
| 202401444 |
| 202401488 |
| 202401491 |

**Repository:**
https://github.com/Yash-Oza-ui/collab-code-sandbox

---

# 1. Finalized Stakeholders

In Lab 6, the stakeholder list included both people and external software systems such as the LLM API, Docker, and GitHub.

For this phase, they were separated into:

* **Human stakeholders** — people or groups who have an interest in the system.
* **External systems and reference sources** — systems and documentation that impose technical or design constraints.

The course instructor and project mentor were merged into one role. The Group 32 development and operations team was also added because the team will run and maintain the code-execution infrastructure.

## 1.1 Human Stakeholders

| ID     | Stakeholder                                | Type                      | Main Interest / Concern                                                                                     | Elicitation Technique                         |
| ------ | ------------------------------------------ | ------------------------- | ----------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| **S1** | Student developer (individual user)        | Primary                   | Write and run code quickly in the languages used in coursework; see clear output and errors.                | Survey (n = 32), Interviews (3)               |
| **S2** | Collaborating teammate (pair / group work) | Primary                   | Edit the same file without conflicts or lag; know who is editing where.                                     | Survey (n = 32), Observation                  |
| **S3** | Room owner / session host                  | Primary                   | Create and share rooms; control who stays in a session.                                                     | Competitor analysis (VS Code Live Share docs) |
| **S4** | Course instructor / project mentor         | Key — Sponsor / Evaluator | Project must have meaningful ML and GenAI components, not only a front-end/back-end application.            | Interview (mentor meeting)                    |
| **S5** | Development & Operations Team (Group 32)   | Secondary                 | Run untrusted code safely within free-tier / lab hardware; keep AI API costs and rate limits under control. | Brainstorming, Document Analysis              |

---

## 1.2 External Systems and Reference Sources

| External System / Source    | Why It Matters                                                                                                       | Studied Through                                |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| **Judge0 CE API**           | Provides established execution limits and constraints for code execution.                                            | Official Judge0 CE API documentation           |
| **Piston**                  | Provides separate compilation/execution stages and additional process and workspace constraints.                     | Piston README and configuration documentation  |
| **Docker Engine**           | Provides isolation and resource limits for code execution.                                                           | Docker resource-constraints documentation      |
| **LLM / Claude API**        | Rate limits, HTTP 429 errors and cost constrain the AI review feature.                                               | Official API rate-limit documentation          |
| **GitHub OAuth (optional)** | Supports login and repository import; OAuth scopes determine the amount of requested access.                         | GitHub OAuth scopes documentation              |
| **Yjs (CRDT library)**      | Determines how synchronization, cursors and reconnection can be implemented.                                         | Yjs, y-websocket and y-protocols documentation |
| **VS Code Live Share**      | Provides a competitor/reference point for collaborative session controls, participant presence and host permissions. | VS Code Live Share documentation               |

---

# 2. Elicitation Summary and Status

| Technique               | Stakeholder | Participants / Source                                                  | Key Output                                                                                                                                | Status      |
| ----------------------- | ----------- | ---------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| **Questionnaire**       | S1, S2      | Google Form, 32 responses, 20–25 Sep 2026                              | Quantified pain points, latency tolerance, execution details, interest in AI features and preferred languages.                            | ✅ Completed |
| **Interviews**          | S1          | 3 classmates                                                           | Supported room links, simultaneous editing and live cursors (FR1–FR3).                                                                    | ✅ Completed |
| **Observation**         | S2          | Pair-programming session on Replit                                     | Users had difficulty knowing who was editing which part of the file.                                                                      | ✅ Completed |
| **Brainstorming**       | S3, S5      | Internal group session                                                 | Owner needs to remove disruptive users and view basic session activity.                                                                   | ✅ Completed |
| **Interview**           | S4          | Mentor meeting                                                         | ML and GenAI components are mandatory scope (FR7, FR8).                                                                                   | ✅ Completed |
| **Document Analysis**   | S5          | Judge0, Piston, Docker, Claude API, GitHub OAuth and Yjs documentation | Concrete technical limits and design constraints.                                                                                         | ✅ Completed |
| **Competitor Analysis** | S3          | VS Code Live Share documentation                                       | Host is notified when participants join, can remove participants, can require approval before guests join, and can make guests read-only. | ✅ Completed |

---

# 3. Data Collection — Questionnaire Results

## 3.1 Method

A Google Form containing **13 questions** using multiple-choice, multi-select and 1–5 Likert-scale questions was circulated among DAU students.

* **Total responses:** 32
* **Collection period:** 20–25 September 2026
* **Group-member responses:** 4
* **Responses excluding group members:** 28

The figures were re-run without the four group members, and no conclusion changed, so all 32 responses are reported.

---

## 3.2 Respondent Profile

### Programming Languages

| Language       |   Respondents |
| -------------- | ------------: |
| **C++**        |            31 |
| **Python**     |             8 |
| **C**          |             5 |
| **Java**       |             3 |
| **JavaScript** |             2 |

### Collaboration Frequency

| Frequency          | Respondents |
| ------------------ | ----------: |
| Daily              |           9 |
| A few times a week |           7 |
| Rarely             |          15 |
| Never              |           1 |
| **Total**          |      **32** |

Additionally, **29 / 32 respondents (91%)** had previously used a real-time collaborative tool such as Replit, Live Share or Colab.

---

## 3.3 Collaboration Frustrations and Synchronization

The questionnaire identified five important frustrations with collaborative coding:

| Frustration                         |  Respondents |
| ----------------------------------- | -----------: |
| Conflicts while collaborating       |      **78%** |
| Lag / synchronization delay         |      **69%** |
| Not knowing who is editing what     |      **66%** |
| Losing work on disconnect / refresh |      **38%** |
| Can't see teammates' cursors        |      **25%** |

These findings support the requirements for conflict-free collaborative editing, live collaborator awareness, low synchronization latency, and persistence after disconnection.

### Acceptable Synchronization Delay

| Acceptable Delay            |  Respondents |
| --------------------------- | -----------: |
| **Instant / under 200 ms**  | **20 (63%)** |
| **Up to 1 second**          | **10 (31%)** |
| **Not sure / don't notice** |   **2 (6%)** |

These results support **NFR1**, which specifies a synchronization target of **≤ 200 ms at the 95th percentile**.

---

## 3.4 Execution Details

| Execution Detail            | Respondents |
| --------------------------- | ----------: |
| stdout is important         |     **88%** |
| stderr is important         |     **81%** |
| Execution time is important |     **59%** |
| Memory usage is important   |     **41%** |
| Exit code is important      |     **31%** |

stdout and stderr are therefore always displayed under **FR4**, while execution time and memory remain Medium-priority information in a compact metrics bar under **FR6**.

### Code Execution Frequency

* **18 respondents (56%)** run code constantly while collaborating.
* **12 respondents (38%)** run code occasionally.
* **1 respondent** runs code rarely.
* **1 respondent** never runs code while collaborating.

### Risk Warnings

For warnings when execution appears unusual or risky:

* **25 respondents (78%)** said **"yes, definitely useful"**.
* **7 respondents (22%)** said **"maybe, depends on how it's shown"**.
* **0 respondents** said no.

This led to the requirement that **FR8** risk warnings should be non-blocking and provide a short explanation rather than simply displaying a red warning label.

---

## 3.5 Likert-Scale Results

**Scale:** 1 = Low, 5 = High

| Question                                                  |     Mean | Rated 4–5 | Distribution (1 / 2 / 3 / 4 / 5) |
| --------------------------------------------------------- | -------: | --------: | -------------------------------- |
| Importance of seeing teammates' live cursor and selection | **4.59** |  29 (91%) | 0 / 0 / 3 / 7 / 22               |
| Concern about running untrusted code safely               | **4.12** |  25 (78%) | 1 / 1 / 5 / 11 / 14              |
| Interest in AI-generated code review comments             | **3.91** |  23 (72%) | 3 / 0 / 6 / 11 / 12              |
| Interest in on-demand inline AI suggestions               | **3.97** |  23 (72%) | 3 / 0 / 6 / 9 / 14               |

---

# 4. How the Data Changed the Requirements

| Finding                                                                                          | Impact on Requirements                                                                           |
| ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| Conflicts (78%), lag (69%) and not knowing who edits what (66%) were the top three frustrations. | Confirms **FR2** and **FR3**. FR3 moves from Provisional to Final.                               |
| 63% want changes visible in under 200 ms; 31% accept delays of up to 1 second.                   | **NFR1** fixed at **≤ 200 ms at the 95th percentile**.                                           |
| 38% (12 respondents) have lost work because of a disconnect or refresh.                          | New **FR11**: edits persist and the client automatically resynchronizes after reconnecting.      |
| stdout (88%) and stderr (81%) matter most.                                                       | stdout/stderr are always shown in **FR4**. Time and memory remain Medium priority under **FR6**. |
| 72% are interested in AI review, although 3 respondents rated it 1/5.                            | **FR7** is on-demand, dismissible and can be disabled per user.                                  |
| 78% want risk warnings.                                                                          | **FR8** flags must be non-blocking and include a short reason.                                   |
| C++ dominates usage at 97%.                                                                      | **DR1** language priority: C++ → Python → C → Java → JavaScript.                                 |

---

# 5. Data Collection — Document Analysis

The document analysis examined the technical constraints of the systems and technologies used by the proposed architecture.

## 5.1 Judge0 CE API

The public Judge0 CE instance provides established execution limits including:

* CPU time: **2 seconds**
* Wall time: **5 seconds**
* Memory: **128,000 KB**
* Maximum processes/threads: **60**
* Maximum output file: **1024 KB**
* Maximum queue size: **100**

These findings support the project's execution constraints and motivate explicit process and output-size limits.

---

## 5.2 Piston

Piston separates compilation and execution.

Relevant findings include:

* Default compile timeout: **10 seconds**
* Default run timeout: **3 seconds**
* Memory is unlimited unless explicitly configured.
* Maximum processes: **256**
* Maximum open files: **2048**
* Temporary workspace is cleaned after each run.
* Jobs run as separate unprivileged users.

Because C++ is the dominant language, the design uses a separate compile timeout and explicitly configures memory limits. Code is not executed as root.

---

## 5.3 Docker Resource Constraints

Docker does not impose resource limits by default.

Important findings:

* Memory exhaustion can result in an OOM kill.
* `--memory-swap` has meaning only when used with `--memory`.
* `--cpus` provides a hard CPU limit.
* `--cpu-shares` provides a relative scheduling weight rather than a hard limit.
* `--pids-limit` restricts process count.

### Planned Container Configuration

```bash
docker run \
  --network none \
  --memory=128m \
  --memory-swap=128m \
  --cpus=<hard CPU limit> \
  --pids-limit=<process limit>
```

Using equal `--memory` and `--memory-swap` limits disables additional swap for the container.

A memory-related OOM termination should be reported as:

```text
memory limit exceeded
```

These constraints are particularly important because the system executes untrusted user code.

---

## 5.4 Claude API Rate Limits

The Claude API uses usage-tier-based limits involving:

* Requests per minute
* Input tokens per minute
* Output tokens per minute

The limits use a token-bucket mechanism, so short bursts may also trigger rate limiting.

When a rate limit is exceeded:

* The API returns **HTTP 429**.
* A `retry-after` header is provided.
* Monthly spending limits also apply.

### Derived Design

AI calls should be:

* User-triggered.
* Cached by code hash.
* Rate-limited per room.
* Retried according to `retry-after`.

A rate-limited AI request should **not prevent the collaborative editor from continuing to work**. These constraints contribute to **NFR5 and NFR6**.

---

## 5.5 GitHub OAuth

The documentation analysis found:

* A token with no scope can access public information.
* `read:user` and `user:email` cover profile and email information.
* The `repo` scope grants full read/write access to private repositories.
* Users may grant fewer scopes than requested.

### Derived Design

If **FR10** is retained:

* Request only `read:user` and `user:email`.
* Public-repository import requires no additional repository scope.
* Private-repository import remains out of scope.

The final FR10 scope decision remains a next-step item to be explicitly finalized.

---

## 5.6 Yjs and Collaborative Editing

Yjs and its associated protocols provide functionality required for real-time collaboration.

### Awareness

The awareness protocol can share:

* Cursor position
* User name
* User colour

A client that has not refreshed for approximately **30 seconds** is dropped.

### Reconnection

`y-websocket` supports reconnection using **exponential backoff**.

### Offline Persistence

`y-indexeddb` can store the document locally, supporting offline editing.

The planned **Monaco + Yjs** stack therefore supports:

* **FR3** — live cursors and collaborator awareness.
* **FR11** — persistence and automatic resynchronization.

Idle collaborators disappear from the presence list after approximately 30 seconds.

---

# 6. Competitor Analysis — VS Code Live Share

VS Code Live Share was analyzed as a reference system for collaborative-session management.

The analysis informs **FR9**, particularly:

* Participant presence.
* Host awareness when users join.
* Removing participants.
* Optional approval before guests join.
* Read-only guest access.

This analysis is documented as a **competitor analysis** rather than a direct stakeholder interview.

---

# 7. Limitations and Next Steps

## 7.1 Survey Limitations

The questionnaire used a **convenience sample of DAU students**. Most respondents primarily use C++, so the results describe the project's target users well but do not generalize to all developers.

The open-ended question produced only **3 responses**, none of which contained substantive suggestions. Therefore, qualitative depth comes primarily from interviews and observation.

## 7.2 Next Steps

1. **NFR2 load testing** — perform load testing for concurrent users per room and validate the required performance target.
2. **FR10 scope decision** — finalize the scope of GitHub integration, particularly regarding public versus private repository access.

---

# 8. References

1. **Judge0 CE API Documentation**
   https://ce.judge0.com

2. **Piston README and Configuration Documentation**
   https://github.com/engineer-man/piston

3. **Docker Documentation — Resource Constraints**
   https://docs.docker.com/engine/containers/resource_constraints/

4. **Claude API Documentation — Rate Limits**
   https://platform.claude.com/docs/en/api/rate-limits

5. **GitHub Documentation — OAuth Scopes**
   https://docs.github.com

6. **Yjs Documentation — y-websocket, Awareness and y-indexeddb**
   https://docs.yjs.dev

7. **VS Code Live Share Documentation — Security**
   https://learn.microsoft.com/en-us/visualstudio/liveshare/reference/security

8. **Group 32 Survey Responses**
   Google Form, collected **20–25 September 2026**

---

# 9. Project Summary

**Group 32** is developing a **Collaborative Code Editor with Sandboxed Execution**.

The requirements were elicited and refined using:

* Questionnaires
* Stakeholder interviews
* User observation
* Brainstorming
* Mentor discussions
* Competitor analysis
* Technical document analysis

The collected evidence emphasizes:

* Real-time collaborative editing
* Low synchronization latency
* Live collaborator awareness
* Safe sandboxed execution of untrusted code
* Persistent collaboration sessions
* AI-assisted code review
* Execution-risk warnings
* Controlled API usage
* Secure and limited external-service integration

The resulting requirements and technical constraints provide the basis for the system's subsequent architecture, design and implementation.