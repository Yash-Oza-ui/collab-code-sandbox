# Elicitation Round 2: Review of the Baseline Design and the Resulting Change (CHG-01)

Collaborative Code Sandbox | Group 32 | IT-314

## 1. Summary

Round 2 tested the baseline design (Requirements v1) with stakeholders and found that a single shared file, edited and run by everyone, needed role-based control and a clear meaning for Run. Their input led to CHG-01, which replaces the single room model with two modes: Pair mode (one file, Host-controlled editing) and Open mode (modules with owners, runs on the last saved version).

Interviewers explained the baseline design topic by topic and asked questions as they went. Twenty questions across seven topics were put to five interviewees. Seven points raised by stakeholders are not settled by CHG-01; section 6 lists them with their handling.

## 2. Stakeholders and method

| Source | Who | What it gave |
| --- | --- | --- |
| Review after the mid-evaluation (after 13 October 2026) | The TA and other stakeholders, as recorded in CHG-01 | Five concerns, E1 to E5 (section 4) |
| Round Two interviews | Interviewees: Kevin Rank, Rudra Chheta, Tanishq Shah, Shubh Patel, Aaditya Dave. Interviewers: Jay Limbasiya, Bhavya Vekariya, Niranjan Panchal, Het Kapadiya | Responses to twenty questions (section 5) |

- **Technique:** oral, semi-structured interviews. For each topic the interviewers explained the baseline design and then put questions to the interviewees.
- **Records:** the interviews were spoken, so the responses are consolidated summaries of what the interviewees said, not word-for-word transcripts. They are not attributed to individual interviewees.

## 3. Baseline design shown to stakeholders (Requirements v1)

| Area | Baseline design |
| --- | --- |
| Room model | One shared file that every participant can edit and run (FR1, FR2). |
| Presence | Cursors and selections with a name and colour. A collaborator idle for about 30 s is dropped (FR3). |
| Run | Run executes the shared live document, and the result goes to every participant (FR4, FR4.5). |
| Room control | The owner is notified of joins, can remove users, can switch on join approval and can make a guest read-only. Ownership survives a refresh (FR9). |
| Sandbox | A new Docker container per run, no network, non-root, read-only code mount, resource limits (FR5, NFR3, NFR4). |
| AI and ML | On-demand review and failure explanations after consent, and anomaly warnings that never delay the result (FR7, FR8, FR12, FR13, NFR9). |
| History | Execution history kept for a limited time, proposed at about 24 hours, still provisional (FR15). |

## 4. Feedback from the mid-evaluation review

CHG-01 records five concerns from this review and the options weighed (pivot, vote-to-run, monolith defence, two modes).

| Ref | Feedback | Gap in the baseline |
| --- | --- | --- |
| E1 | There is no clear use case for many people coding together. | Simultaneous editing is specified without a scenario that needs it. |
| E2 | If someone presses Run while a teammate has half a line typed, an error is guaranteed. | A run uses the live shared document, and the result goes to everyone. |
| E3 | Requiring everyone in the room to approve each run is slow and open to misuse. | Approval-based run control is not a suitable answer to E2. |
| E4 | Co-editing one file undermines modularity. Different functions belong in separate modules, so an error points to one module. | There is no module concept, so a failure cannot be attributed to a part of the program or to a person. |
| E5 | The expected room size must be stated. | No requirement states it. |

## 5. Round Two findings by topic

Each topic starts from the baseline design, then gives the questions put to the interviewees with their responses, and ends with what CHG-01 took from them.

### 5.1 Who can edit, join and leave

**Baseline:** every participant edits. The owner can remove users, switch on join approval and make a guest read-only (FR9).

**Q2. Editing access.** Think about a teaching or interview session you have taken part in. How was editing handled during the session? Was there a moment when access needed to change?

Response: In a teaching or interview session, I think the instructor or interviewer should control who can edit. They might keep editing access for themselves while explaining, then grant it to the student when it is their turn. This would make it clear whose turn it is to work on the code and help prevent accidental changes while someone is explaining. The instructor or interviewer should also be able to take editing access back when they need to demonstrate something or guide the session.

**Q14. Joining a room.** Tell me about a time you joined or invited someone to a shared coding workspace. How did people get access, and what worked or did not work?

Response: A room link would be convenient, but it should be difficult to guess. For a private class or interview, I would want the Host to approve people before they join. For a trusted group project, a link might be enough if access can be removed later.

**Q15. Observer access.** Have you been in a coding session where someone watched without editing? What did they need to see, and what were they able to do?

Response: A read-only role could help a teacher or reviewer watch the session without changing the code. I would expect observers to see the shared workspace and any output shared with the room, but not to edit, run code, or change permissions.

**Q16. Host leaves the room.** Have you experienced a session where the person leading it left or lost connection? What happened next?

Response: The Host should be able to transfer the role to someone else before leaving. If they leave unexpectedly, the room should offer a clear recovery option, such as transferring control to a named participant or closing the room after a warning.

**Led to (CHG-01):** the role table (Host, Participant, Observer). In Pair mode the Host grants and takes back edit access, enforced by the relay on every edit. Optional Host approval to join. The Host can transfer the role, or the room closes after a timeout (proposed).

### 5.2 What Run means and which version runs

**Baseline:** Run executes the shared live document, and the result goes to every participant (FR4, FR4.5).

**Q3. Concurrent editing and failed runs.** Think of a time a program failed while more than one person was working on it. What was happening at the time, and how did you investigate the failure?

Response: When several people edit the same file, it can be hard to tell whose change caused a problem, especially if someone runs the code while another person is still editing. If a run fails, I check the error message and line number, review the recent changes, and try running the code again after identifying what changed.

**Q5. Saving and unsaved changes.** Tell me about a time you were unsure whether your latest code changes had been saved or were included in a run. What happened?

Response: I would expect changes to save automatically, or at least have a clear Save button. An unsaved file should be easy to spot, for example with a dot or an 'Unsaved' label beside its name. Before running the project, I would want a clear indication of whether it will use unsaved changes or the last saved version.

**Q12. Priorities and missing topic.** Looking back at a recent group coding session, what caused the most difficulty or took the most time? What, if anything, would you have handled differently?

Response: I would prioritize preventing conflicting edits and making it clear which code version was run. We should also discuss how people recover their work if someone loses access or leaves the room.

**Led to (CHG-01):** Pair mode runs the live text and labels the result with who ran it and when. Open mode runs the last saved version, saving is manual, and a small dot marks a module edited after its last save.

### 5.3 Who sees run output

**Baseline:** the result goes to every participant (FR4.5).

**Q4. Run output audience.** Think of a time you ran code while other people were present. Who could see the output, and how did that affect the session?

Response: It depends on the situation. In pair debugging or group work, I would usually share the output with everyone so they can discuss it together. In a teaching session, the instructor and student should both see it. In an interview, or when the code contains private information, I would want the Host or the person running it to control whether it is shared.

**Led to (CHG-01):** in Open mode the runner chooses whether to share output, which is private by default with a Share to room button and always labelled with who ran it. In Pair mode the result goes to everyone (see section 6 for interview rooms).

### 5.4 Modules and project structure

**Baseline:** no modules. One shared file.

**Q1. Recent collaborative coding.** Tell me about the last time you worked on code with other people. Who was involved, what were you each doing, and what tools did you use?

Response: Recently, I worked with four people on a class project. We divided the work by sections, and each person was responsible for completing their assigned part. We used Google Docs to share our work and keep track of everyone's sections. It helped us work on the project together, but coordinating the different parts and combining them into one complete submission still required communication. For a coding project, I would also want a shared workspace where everyone can see updates and understand how the separate parts fit together.

**Q6. Module requests and ownership.** Tell me about a project where work was divided into separate parts or files. How were the parts assigned, and what happened when someone needed help or was unavailable?

Response: A participant should be able to request a module by giving it a name and briefly explaining its purpose. The Host could approve the request and assign an owner. The owner should be able to grant edit access to teammates, while the Host can resolve access issues. If the owner leaves, ownership should transfer to the Host or to someone the Host names, so the module does not become blocked.

**Q13. Choosing a room mode.** Think of a recent coding task you did with others. How did you organize the code, and what led the group to use one file or several?

Response: For a short exercise or teaching session, I would choose Pair mode because everyone can focus on the same file. For a larger project where people work on different parts, I would choose Open mode. I would want the difference explained clearly before creating the room.

**Q18. Project language and runnable modules.** Tell me about a project with multiple files or programming languages. How did you work out which file to run and how the files fit together?

Response: For a class exercise, using one language across the workspace would make setup simpler. I would want the entry file to be clearly marked, and I would expect the interface to show which modules can run on their own and which are only used by other files.

**Led to (CHG-01):** two modes chosen at room creation. In Open mode anyone requests a module, the Host approves, each module has one owner who (or the Host) can share write access, the Host sets the entry file, and a workspace uses one language with runnable modules marked by the owner. Room size of 2 to 5 stays a design assumption; Q1 gives one example of four people.

### 5.5 Errors and limits

**Baseline:** stdout and stderr are always shown, and a memory-limit kill is reported as 'memory limit exceeded' (FR4.2, NFR4.2).

**Q7. Error context and attribution.** Recall a time part of a shared program failed. What did you check first, and what helped you identify where the problem came from?

Response: To find the cause, I would want to see the error message, the module where it occurred, and which version was run. Knowing who owns the module or last saved it might help me ask the right person about the change, but it should not suggest that they are responsible for the error. The interface should label those details neutrally.

**Q20. Sandbox limits and run failures.** Have you had a program stop because it ran too long or used too much memory? What did you observe, and what did you do next?

Response: The result should say whether the program stopped because of a time limit, memory limit, or another error. I would want to see the measured usage and the limit, so I can tell whether my code needs changes or the run reached a system restriction.

**Led to (CHG-01):** a failure names the failing module with its owner and who last saved it. The usability test checks the wording for blame. Sandbox limits are unchanged (CHG-01 section 4.7).

### 5.6 AI and ML

**Baseline:** on-demand review and failure explanations after consent, and anomaly warnings that never delay the result (FR7, FR8, FR12, FR13).

**Q8. AI crash explanation and consent.** Tell me about a time you sought help for an error you could not easily understand. What resources did you use, and were there any details you chose not to share?

Response: An AI explanation could help when a crash message is difficult to understand, especially for a learner who is unsure where to start debugging. Before using it, I would want to know exactly what information is sent, such as code, error messages, or run details, who can access it, and whether it is stored. I would want to give clear consent first and be able to turn the feature off.

**Q9. AI in live interviews.** Think of a coding interview or assessment you took or observed. What tools or assistance were allowed, and how were those expectations communicated?

Response: AI should be available only if the interviewer allows it, and the candidate should be told clearly before the interview starts. I would make it off by default so there is no confusion about whether using it is permitted. The interviewer can turn it on if AI assistance is part of the exercise.

**Q10. Run-time comparison.** Tell me about a time you noticed a program's performance change after a code change. How did you notice, and how did you decide what to do?

Response: Comparing run times across saved versions could help me notice when a change makes a program slower. But a warning could be misleading if the runs use different inputs or conditions, or if timing varies between runs. I would want the system to compare similar runs, show the measurements behind the warning, and avoid interrupting me for a small or uncertain difference. I would also want to know how long run history is kept.

**Led to (CHG-01):** a Host switch for AI, off by default for interview rooms. Consent comes first, and no source code goes in logs. Slowdown detection on run history is AI task 2.

### 5.7 Security, retention and records

**Baseline:** the owner can remove users and switch on join approval (FR9). Execution history is kept for a limited time (FR15, provisional).

**Q11. Security and retention.** Tell me about a time you shared code or a coding workspace with others. How was access handled, and were there any concerns about privacy or keeping the work?

Response: I would want room links to be hard to guess, access to be controlled, and code and run history to be deleted when the room ends. I would also want output visibility to be clear. These safeguards should be verified technically.

**Q17. Saving and exporting work.** Think about the last time you finished a group coding session. What happened to the code afterward, and how did you keep or share anything you needed?

Response: I would want to download the project files so I can continue working later. Before the room closes, I would want a clear choice about whether the code and run history are kept or deleted, and I would want to know who can access anything that is retained.

**Q19. Permission and activity log.** Have you ever been unsure who changed a file, permission, or setting in a shared workspace? How did you find out what happened?

Response: A short activity log would help me understand why someone can or cannot edit. I would want it to show what changed, who made the change, and when. It should be easy to review without interrupting the coding session.

**Led to (CHG-01):** long random room links, optional Host approval to join, code and run records deleted with the room, a zip export, and a visible audit log of role and permission changes (all proposed).

## 6. Raised by stakeholders but not settled by CHG-01

Seven points came up in the interviews that CHG-01 does not adopt or does not fully cover. Each has a handling: out of scope or future innovation (not part of this change), or carried into Requirements v2 as detail on an existing feature.

| Point (source) | What stakeholders said | Handling |
| --- | --- | --- |
| Autosave (Q5) | One interviewee accepted autosave or a clear Save button. CHG-01 fixes manual save with an unsaved dot. | Out of scope for CHG-01. Autosave is a possible later option. |
| Recovering work (Q12) | Asked how people recover their work if someone loses access or leaves the room. | Future innovation. The zip export in CHG-01 is only a manual copy. |
| Keep or delete choice (Q17, Q10) | Wanted a choice before the room closes to keep or delete code and run history, and to know who can access what is kept and for how long. CHG-01 deletes records with the room. | Future innovation. |
| Output control in interviews (Q4) | Wanted the Host or runner to control sharing in interviews or for private code. In Pair mode, CHG-01 sends results to everyone. | Open for Requirements v2. |
| Data and AI notices (Q8, Q9) | Wanted to know what is sent, who can access it and whether it is stored, and wanted candidates told in advance whether AI is allowed. | Carried to Requirements v2 (consent notice content and the Host AI switch). |
| Limit failures (Q20) | Wanted the cause, the measured usage and the limit shown. | Carried to Requirements v2 (extends the existing limit messages). |
| Slowdown warnings (Q10) | Wanted comparisons of similar runs only, with the measurements shown and no interruption for small differences. | Carried to Requirements v2 (AI task 2). |

## 7. Limitations

- The interviews were oral. The responses are consolidated summaries, not transcripts, and are not attributed to individual interviewees. Still there are recorderd videos of these interviews.
- Five interviewees is a small sample.

## 8. Literature note: pair programming

Cockburn and Williams (2001) report, from interviews and controlled experiments, that pair programming can support continuous review, problem solving, learning and communication. In a 1999 controlled experiment at the University of Utah, pairs spent about 15% more time than individuals on the program. The authors present this as a modest cost for benefits such as better design quality and fewer defects.

For this project, this is background for exploring Pair mode, where two people work on the same code and discuss changes as they happen. The study examined pair programming practice at one computer, not an online tool, Host-controlled editing permissions or larger groups. Those design choices need to be evaluated with our own users.

**Reference:** Cockburn, A., & Williams, L. (2001). The costs and benefits of pair programming. In *Extreme Programming Examined*. Addison-Wesley.
