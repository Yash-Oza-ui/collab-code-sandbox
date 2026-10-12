# Anomaly Detection Specification (T-13)

**Objective:** Define execution features, labelling rules for synthetic data, and user-facing warning categories for the anomaly detection model.

## 1. Execution Features
These features represent a single execution run. They rely strictly on numeric metrics; no raw user code or text output is included (NFR8.2, NFR9).
* `execution_time_ms` (numeric): Wall-clock time taken by the run.
* `peak_memory_mb` (numeric): Maximum memory used during execution.
* `stdout_bytes` (integer): Size of standard output generated.
* `stderr_bytes` (integer): Size of standard error output generated.
* `exit_code` (integer): Process exit code.
* `timed_out` (boolean): Whether the container was killed for exceeding the 5s wall time limit.
* `oom_killed` (boolean): Whether the container was killed for exceeding the 128MB memory limit.
* `language` (string): The language of the execution (`cpp`, `python`, `c`, `java`, `javascript`).

## 2. Reason Categories (User-Facing Warnings)
When flagged as anomalous, the model returns one or more `reason_category_id` values:
* `EXCESSIVE_EXECUTION_TIME`: "The execution took unusually long, nearing the 5-second system limit."
* `HIGH_MEMORY_USAGE`: "The code consumed an unexpectedly large amount of memory."
* `OUTPUT_FLOODING`: "The code generated an unusually massive amount of text output."
* `ABNORMAL_TERMINATION`: "The execution crashed or triggered a system resource kill."

## 3. Labelling Rules for Synthetic Data (T-12)
Based on NFR4 limits (5s time, 128MB memory, 64 processes, 1MB output).

**Normal Class (Label 0)**
* `execution_time_ms` < 2500 ms.
* `peak_memory_mb` < 60 MB.
* `stdout_bytes` + `stderr_bytes` < 100 KB.
* `timed_out` and `oom_killed` are `false`.
* `exit_code` is standard (e.g., 0 or 1).

**Anomalous Class (Label 1)**
Triggers at least one of the following:
* **Rule 1 (Time Limit Stress):** `execution_time_ms` > 4500 ms OR `timed_out` = `true` -> `EXCESSIVE_EXECUTION_TIME`.
* **Rule 2 (Memory Limit Stress):** `peak_memory_mb` > 115 MB OR `oom_killed` = `true` -> `HIGH_MEMORY_USAGE`.
* **Rule 3 (Output Limit Stress):** `stdout_bytes` > 900 KB -> `OUTPUT_FLOODING`.
* **Rule 4 (Severe Crashing):** Non-standard exit codes combined with high resource spikes -> `ABNORMAL_TERMINATION`.

## 4. Anomaly Threshold Note
The final anomaly score threshold is NOT defined here. It will be chosen during model validation using real metrics in Sprint 3 (FR8.1).

## 5. Consistency Check Record
* Evaluator 1: Digvijay Parmar
* Evaluator 2: Prince Gadara 
* Agreement Rate: 100% (10/10 manual labels matched perfectly).