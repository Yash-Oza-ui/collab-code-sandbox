# Anomaly Detection Specification (T-13)

**Objective:** Define execution features, labelling rules for synthetic data, and user-facing warning categories for the anomaly detection model.

## 1. Execution Features
These features represent a single execution run. They rely on numeric metrics and categorical metadata (like language); no raw user code or text output is included (NFR8.2, NFR9).
* `execution_time_ms` (numeric): Wall-clock time taken by the run.
* `peak_memory_mb` (numeric): Maximum memory used during execution.
* `stdout_bytes` (integer): Size of standard output generated.
* `stderr_bytes` (integer): Size of standard error output generated.
* `exit_code` (integer): Process exit code.
* `timed_out` (boolean): Whether the container was killed for exceeding the configured wall-time limit (current default: 5s, NFR4.1, DR3).
* `oom_killed` (boolean): Whether the container was killed for exceeding the configured memory limit (current default: 128MB, NFR4.2, DR3).
* `language` (string): The language of the execution (`cpp`, `python`, `c`, `java`, `javascript`).

> **Note on Configured Limits (DR3):**
> 5 seconds wall-clock time (NFR4.1), 128 MB peak memory (NFR4.2), and 1 MB output cap (NFR4.4) represent the **current default configured limits**. Per DR3, these limits are loaded from configuration rather than hardcoded into the contract or models.

## 2. Reason Categories (User-Facing Warnings)
When flagged as anomalous, the model returns one or more `reason_category_id` values with user-facing message templates:
* `EXCESSIVE_EXECUTION_TIME`: "The execution took unusually long, nearing the configured time limit."
* `HIGH_MEMORY_USAGE`: "The code consumed an unexpectedly large amount of memory."
* `OUTPUT_FLOODING`: "The code generated an unusually massive amount of text output."
* `ABNORMAL_TERMINATION`: "The execution crashed or triggered a system resource kill."

## 3. Labelling Rules for Synthetic Data (T-12)
Based on current default NFR4 limits (5s time, 128MB memory, 64 processes, 1MB output).

### 3.1 Unit Conventions for Dataset Generation
For the synthetic dataset generator (T-12), **1 KB = 1024 bytes (KiB)** and **1 MB = 1024 KB = 1,048,576 bytes (MiB)**:
* `100 KB` = `102,400 bytes`
* `900 KB` = `921,600 bytes`
* `1 MB` = `1,048,576 bytes`

### 3.2 Boundary Handling & Exclusion Zone
* Any sample falling strictly on or between the normal and anomalous bounds (e.g., `2500 ms <= execution_time_ms <= 4500 ms`, `60 MB <= peak_memory_mb <= 115 MB`, or `102,400 bytes <= (stdout_bytes + stderr_bytes) <= 921,600 bytes`) falls into the **Exclusion Zone** and is **discarded from the synthetic dataset**.
* Samples that fit neither class definition (for example, a low-resource run with a non-standard exit code like `exit_code = 2` that does not meet the strict Normal requirement `exit_code ∈ {0, 1}` nor triggers any Anomalous rule) fall into the **Exclusion Zone** and are **discarded from synthetic training data** to ensure clean, unambiguous decision boundaries for the baseline classifier.

### 3.3 Normal Class (Label 0)
A sample is labelled **0 (Normal)** if and only if **all** of the following conditions hold:
* `execution_time_ms` < 2500 ms.
* `peak_memory_mb` < 60 MB.
* `(stdout_bytes + stderr_bytes)` < 102,400 bytes (100 KB).
* `timed_out` = `false` and `oom_killed` = `false`.
* `exit_code` ∈ {0, 1} (standard successful execution or standard application error).

### 3.4 Anomalous Class (Label 1)
A sample is labelled **1 (Anomalous)** if it triggers **at least one** of the following rules:
* **Rule 1 (Time Limit Stress):** `execution_time_ms` > 4500 ms OR `timed_out` = `true` -> `EXCESSIVE_EXECUTION_TIME`.
* **Rule 2 (Memory Limit Stress):** `peak_memory_mb` > 115 MB OR `oom_killed` = `true` -> `HIGH_MEMORY_USAGE`.
* **Rule 3 (Output Limit Stress):** `(stdout_bytes + stderr_bytes)` > 921,600 bytes (900 KB) -> `OUTPUT_FLOODING`.
* **Rule 4 (Severe Crashing):** `exit_code` >= 128 (e.g., exit code 139 for segmentation fault / SIGSEGV) combined with high resource spikes (`peak_memory_mb` > 100 MB or `execution_time_ms` > 4000 ms) -> `ABNORMAL_TERMINATION`.

---

## 4. Anomaly Threshold Note
The final continuous anomaly score threshold is NOT hardcoded here. It will be chosen during model validation using real execution metrics in Sprint 3 (FR8.1). The baseline stub in Sprint 1 returns `is_anomalous: false`.

---

## 5. Consistency Check Record
* **Evaluator 1:** Digvijay Parmar
* **Evaluator 2:** Prince Gadara
* **Agreement Rate:** 100% (10/10 manual labels matched perfectly).

### Consistency Check Samples (10 Cases)

| ID | Lang | Time (ms) | Memory (MB) | Total Output (Bytes) | Exit Code | Timeout | OOM | Evaluator 1 (Digvijay) | Evaluator 2 (Prince) | Consensus Label | Reason Category / Note |
|---|---|---|---|---|---|---|---|---|---|---|---|
| S01 | python | 120 | 14.2 | 512 | 0 | false | false | Label 0 | Label 0 | **Label 0 (Normal)** | Standard quick execution |
| S02 | cpp | 310 | 4.1 | 1,024 | 0 | false | false | Label 0 | Label 0 | **Label 0 (Normal)** | Typical compiled run |
| S03 | cpp | 4,920 | 22.0 | 64 | 137 | true | false | Label 1 | Label 1 | **Label 1 (Anomalous)** | `EXCESSIVE_EXECUTION_TIME` |
| S04 | python | 1,100 | 124.5 | 0 | 137 | false | true | Label 1 | Label 1 | **Label 1 (Anomalous)** | `HIGH_MEMORY_USAGE` |
| S05 | javascript | 850 | 28.0 | 960,000 | 0 | false | false | Label 1 | Label 1 | **Label 1 (Anomalous)** | `OUTPUT_FLOODING` |
| S06 | c | 4,200 | 108.0 | 128 | 139 | false | false | Label 1 | Label 1 | **Label 1 (Anomalous)** | `ABNORMAL_TERMINATION` (crash + high memory) |
| S07 | java | 190 | 38.0 | 450 | 1 | false | false | Label 0 | Label 0 | **Label 0 (Normal)** | User error / exit 1 |
| S08 | javascript | 420 | 32.5 | 2,048 | 0 | false | false | Label 0 | Label 0 | **Label 0 (Normal)** | Standard execution |
| S09 | python | 4,780 | 18.0 | 120 | 0 | false | false | Label 1 | Label 1 | **Label 1 (Anomalous)** | `EXCESSIVE_EXECUTION_TIME` (stress without timeout) |
| S10 | cpp | 2,300 | 118.5 | 300 | 0 | false | false | Label 1 | Label 1 | **Label 1 (Anomalous)** | `HIGH_MEMORY_USAGE` (memory stress > 115 MB) |