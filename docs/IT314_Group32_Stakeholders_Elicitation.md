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

In Lab 6, our stakeholder list included both people and external software systems such as the LLM API, Docker, and GitHub.

For this phase, we separated them into:

* **Human stakeholders** — people or groups who have an interest in the system.
* **External systems and reference sources** — systems and documentation that impose technical or design constraints.

The **course instructor and project mentor** were merged into one role. The **Group 32 development and operations team** was also added because the team will run and maintain the code-execution infrastructure.

### 1.1 Human Stakeholders

| ID     | Stakeholder                                | Type                      | Main Interest / Concern                                                                                     | Elicitation Technique            |
| ------ | ------------------------------------------ | ------------------------- | ----------------------------------------------------------------------------------------------------------- | -------------------------------- |
| **S1** | Student developer (individual user)        | Primary                   | Write and run code quickly in the languages used in coursework; see clear output and errors.                | Survey (n = 32), Interviews (3)  |
| **S2** | Collaborating teammate (pair / group work) | Primary                   | Edit the same file without conflicts or lag; know who is editing where.                                     | Survey (n = 32), Observation     |
| **S3** | Room owner / session host                  | Primary                   | Create and share rooms; control who stays in a session.                                                     | Brainstorming, Interview         |
| **S4** | Course instructor / project mentor         | Key — Sponsor / Evaluator | Project must have meaningful ML and GenAI components, not only a front-end/back-end application.            | Mentor Interview                 |
| **S5** | Development & Operations Team (Group 32)   | Secondary                 | Run untrusted code safely within free-tier / lab hardware; keep AI API costs and rate limits under control. | Brainstorming, Document Analysis |

---

### 1.2 External Systems

| External System             | Why It Matters                                                                         | Studied Through                                |
| --------------------------- | -------------------------------------------------------------------------------------- | ---------------------------------------------- |
| **LLM API**                 | Rate limits, 429 errors and cost constrain the AI review feature.                      | Official rate-limit documentation              |
| **Docker Engine**           | Provides isolation and resource limits for code execution.                             | Docker resource-constraints documentation      |
| **GitHub OAuth (optional)** | Supports login and repository import; scopes determine the amount of access requested. | GitHub OAuth scopes documentation              |
| **Yjs (CRDT library)**      | Determines how synchronization, cursors and reconnection can be implemented.           | Yjs, y-websocket and y-protocols documentation |

---

# 2. Elicitation Summary and Status

Multiple requirements-elicitation techniques were used to understand the needs of different stakeholders.

| Technique             | Stakeholder | Participants / Source                                                  | Key Output                                                                                                                                | Status      |
| --------------------- | ----------- | ---------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| **Questionnaire**     | S1, S2      | Google Form, 32 responses, 20–25 Sep 2026                              | Quantified pain points, latency tolerance, execution details, interest in AI features and preferred languages.                            | ✅ Completed |
| **Interviews**        | S1          | 3 classmates                                                           | Supported room links, simultaneous editing and live cursors (FR1–FR3).                                                                    | ✅ Completed |
| **Observation**       | S2          | Pair-programming session on Replit                                     | Users had difficulty knowing who was editing which part of the file.                                                                      | ✅ Completed |
| **Brainstorming**     | S3, S5      | Internal group session                                                 | Owner needs to remove disruptive users and view basic session activity.                                                                   | ✅ Completed |
| **Interview**         | S4          | Mentor meeting                                                         | ML and GenAI components are mandatory scope (FR7, FR8).                                                                                   | ✅ Completed |
| **Document Analysis** | S5          | Judge0, Piston, Docker, Claude API, GitHub OAuth and Yjs documentation | Concrete limits and design constraints.                                                                                                   | ✅ Completed |
| **Interview**         | S3          | Room owner / host                                                      | Host is notified when participants join, can remove participants, can require approval before guests join, and can make guests read-only. | ✅ Completed |

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

### Collaboration Experience

* **9** respondents collaborate daily.
* **7** collaborate a few times a week.
* **15** collaborate rarely.
* **29 / 32 (91%)** have previously used a real-time collaborative tool such as Replit, Live Share or Colab.

Therefore, most responses were based on actual experience with collaborative development tools.

---

## 3.3 Likert-Scale Results

**Scale:** 1 = Low, 5 = High

| Question                                                  |     Mean | Rated 4–5 | Distribution (1 / 2 / 3 / 4 / 5) |
| --------------------------------------------------------- | -------: | --------: | -------------------------------- |
| Importance of seeing teammates' live cursor and selection | **4.59** |  29 (91%) | 0 / 0 / 3 / 7 / 22               |
| Concern about running untrusted code safely               | **4.12** |  25 (78%) | 1 / 1 / 5 / 11 / 14              |
| Interest in AI-generated code review comments             | **3.91** |  23 (72%) | 3 / 0 / 6 / 11 / 12              |
| Interest in on-demand inline AI suggestions               | **3.97** |  23 (72%) | 3 / 0 / 6 / 9 / 14               |

---

## 3.4 Other Questionnaire Findings

### Maximum Acceptable Synchronization Delay

* **20 respondents (63%)** selected **instant / under 200 ms**.
* **10 respondents (31%)** accepted delays of up to **1 second**.
* **2 respondents** were unsure or did not notice small delays.

### Code Execution Frequency

* **18 respondents (56%)** run code constantly while collaborating.
* **12 respondents (38%)** run code occasionally.

### Risk Warning

For warnings when code execution appears unusual or risky:

* **25 respondents (78%)** said **"yes, definitely useful"**.
* **7 respondents (22%)** said **"maybe, depends on how it's shown"**.
* **0 respondents** said no.

---

# 4. How the Data Changed the Requirements

The collected data was used to refine and finalize the system requirements.

| Finding                                                                                          | Impact on Requirements                                                                                                    |
| ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------- |
| Conflicts (78%), lag (69%) and not knowing who edits what (66%) were the top three frustrations. | Confirms **FR2** (conflict-free CRDT editing) and **FR3** (live cursors). FR3 moves from Provisional to Final.            |
| 63% want changes visible in under 200 ms; 94% accept 1 second or less.                           | **NFR1** fixed at **≤ 200 ms (95th percentile)** instead of the previously unverified 150 ms from Lab 6.                  |
| 38% have lost work because of disconnects or refreshes.                                          | New requirement **FR11**: edits persist and the client automatically resynchronizes after reconnecting.                   |
| stdout (88%) and stderr (81%) matter most.                                                       | stdout/stderr are always shown in **FR4**. Time and memory remain Medium priority in a compact metrics bar under **FR6**. |
| 72% are interested in AI review, although 3 respondents rated it 1/5.                            | **FR7** is on-demand only, dismissible and can be disabled per user.                                                      |
| 78% want risk warnings.                                                                          | **FR8** flags must be non-blocking and display a short reason rather than only a red label.                               |
| C++ dominates usage at 97%.                                                                      | **DR1** language priority: C++ → Python → C → Java → JavaScript.                                                          |

---

# 5. Data Collection — Document Analysis

The project also analyzed technical documentation for the external systems and technologies that influence the system design.

## 5.1 Judge0 CE API

### Findings

Default limits on the public instance include:

* CPU time: **2 seconds**
* Wall time: **5 seconds**
* Memory: **128,000 KB**
* Maximum processes/threads: **60**
* Maximum output file: **1024 KB**
* Maximum queue size: **100**

### Requirement Derived

The project's **NFR4** values of:

* **5-second wall time**
* **128 MB memory**

match an established execution engine.

Additional process-count and output-size limits should also be applied.

---

## 5.2 Piston

### Findings

* Compilation and execution are separate stages.
* Default compile timeout: **10 seconds**
* Default run timeout: **3 seconds**
* Memory is unlimited unless configured.
* Maximum processes: **256**
* Maximum open files: **2048**
* Temporary space is cleaned after each run.
* Each job runs as a separate unprivileged user.

### Requirement Derived

Because C++ is the dominant language:

* A separate compile timeout is important.
* Memory limits must be configured explicitly.
* The workspace should be destroyed after every execution.
* Code must never run as root.

---

## 5.3 Docker Resource Constraints

Docker does not impose resource limits by default.

Important findings:

* Memory exhaustion can result in the kernel OOM-killing the process.
* `--memory-swap` only has the intended effect when used with `--memory`.
* `--cpus` provides a hard CPU cap.
* `--cpu-shares` provides only a relative weight.
* `--pids-limit` restricts the number of processes.

### Derived Execution Configuration

Containers should use:

```text
--network none
--memory = --memory-swap
--cpus <hard limit>
--pids-limit <process limit>
```

Memory-related process termination should be reported as:

```text
memory limit exceeded
```

---

## 5.4 Claude API Rate Limits

The Claude API uses usage-tier-based limits involving:

* Requests per minute
* Input tokens per minute
* Output tokens per minute

Limits are enforced using a token-bucket mechanism, meaning short bursts can still fail.

When a rate limit is exceeded:

* The API returns **HTTP 429**.
* A `retry-after` header is provided.
* Monthly spending limits also apply.

### Derived Requirements

AI calls should be:

* User-triggered.
* Cached by code hash.
* Rate-limited per room.
* Retried according to the `retry-after` value.

Most importantly, if an AI request fails because of rate limiting, **the editor should continue working normally**.

This contributes to **NFR5 and NFR6**.

---

## 5.5 GitHub OAuth

The documentation analysis found:

* A token with no scope can read public information.
* `read:user` and `user:email` cover profile and email information.
* The `repo` scope grants full read/write access to private repositories.
* Users may grant fewer scopes than requested.

### Derived Requirement

If **FR10** is retained:

* Request only `read:user` and `user:email`.
* Public-repository import requires no additional scope.
* Private-repository import remains **out of scope**.

---

## 5.6 Yjs and Collaborative Editing

Yjs and its associated protocols provide functionality required for real-time collaboration.

### Awareness Protocol

The awareness protocol can share:

* Cursor position
* User name
* User colour

A client that has not refreshed for **30 seconds** is dropped.

### Reconnection

`y-websocket` supports reconnection using **exponential backoff**.

### Offline Editing

`y-indexeddb` can store the document locally to support offline editing.

### Derived Requirements

The planned **Monaco + Yjs** stack supports:

* **FR3** — live cursors and presence.
* **FR11** — persistence and reconnection.

Idle collaborators disappear from the presence list after approximately **30 seconds**.

---

# 6. Limitations

The study has several limitations:

1. The questionnaire used a **convenience sample of DAU students**.
2. Most respondents primarily use **C++**, so the findings may not generalize to developers using other languages.
3. The survey results describe the project's target users well but **do not generalize to all developers**.
4. The open-ended question produced only **3 answers**, none of which contained substantive suggestions.
5. Therefore, qualitative depth comes primarily from the **interviews and observation**.

---

# 7. References

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

7. **Group 32 Survey Responses**
   Google Form, collected **20–25 September 2026**

---

## 📌 Project Summary

**Group 32** is developing a **Collaborative Code Editor with Sandboxed Execution**.

The requirements were finalized using:

* Questionnaires
* Stakeholder interviews
* User observation
* Brainstorming
* Mentor discussions
* Technical document analysis

The collected evidence emphasizes **real-time collaborative editing, low synchronization latency, safe sandboxed execution, persistent collaboration sessions, and controlled AI-assisted development**.

The resulting requirements and technical constraints provide the basis for the subsequent design and implementation of the system.