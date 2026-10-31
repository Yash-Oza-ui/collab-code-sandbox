# AI and Data Science Subsystem (`ai-ml-service`)

## 1. Overview

The `ai-ml-service` adds code analysis and testing features to the Collaborative Code Sandbox. It helps users understand how their code performs, find possible bugs, and investigate crashes.

The main idea is simple: **run the code and measure the results before making claims about it.** AI can suggest a fix, but the sandbox must test that fix before it is shown as a verified improvement.

To keep the service affordable and responsive, it uses this order whenever possible:

1. Check the cache.
2. Try simple rules and statistical methods.
3. Use a local model if one is available.
4. Use a paid AI model with a limited prompt and token budget.
5. If none of these work, return a useful fallback message.

Normal code output should be returned first. External AI calls require the user's consent. Logs must not contain source code or the text written by users.

## 2. Main Tasks and Implementation

### Task 1: Measure Time Complexity and Verify Optimizations

Instead of estimating performance from code alone, the service runs the program with inputs of increasing size and compares the results.

- **Run with larger inputs:** Test input sizes such as `n`, `2n`, and `4n`. Run each size several times and use the median runtime to reduce the effect of unusual measurements.
- **Estimate growth rate:** Use NumPy to fit a line to `log(input size)` versus `log(runtime)`. Report the estimated growth rate, the \(R^2\) score, and an estimate of the input size at which execution may reach the 5-second timeout.
- **Find common slow patterns:** Start with simple AST or regex rules for patterns such as nested loops and repeated string concatenation. Use an AI call only when the rules do not find a likely issue.
- **Test suggested fixes:** Run any proposed patch in the sandbox. Show it as a verified improvement only if the measured runtime is lower under comparable conditions.

### Task 2: Detect Slowdowns in Run History

This feature checks whether a new run is much slower than earlier runs of the same code input.

- **Identify comparable runs:** Store runtime and memory measurements in short-lived room memory, together with a hash of the input. Compare runs only when their input fingerprints match.
- **Build a baseline:** Use the historical median and Median Absolute Deviation (MAD) to describe normal performance.
- **Flag possible regressions:** Report a slowdown when the run is statistically unusual and also much slower than the baseline—for example, at least 8 times slower. The threshold should be configurable and tested.
- **Show the code changes:** Compare the current submission with the last normal run for the same input and highlight the changed lines that may be related to the slowdown.

### Task 3: Find Failing Inputs and Make Them Smaller

This feature helps find edge cases that cause incorrect output, crashes, or timeouts.

- **Generate test inputs:** Start with simple edge cases such as empty input, `n = 1`, maximum bounds, duplicates, and negative values where valid. A cached AI-generated script can create additional random inputs.
- **Compare program outputs:** Run many generated tests in one sandbox container, within a fixed time budget. Compare the user's program with a trusted brute-force solution when one is available. If there is no reference solution, look for crashes and timeouts instead.
- **Reduce failing cases:** When a test fails, repeatedly remove or simplify parts of the input while keeping the failure. The goal is to find a small example that still shows the problem.
- **Save the result:** Display the reduced input and provide a **Save as Test Case** action.

### Task 4: Explain C/C++ Crashes

This feature helps users understand common memory errors and crashes.

- **Run with sanitizers:** Provide an optional debug run using AddressSanitizer and UndefinedBehaviorSanitizer, with separate memory and time limits.
- **Parse the report:** Use deterministic parsing to extract the error type and reported line number where possible. Examples include out-of-bounds access, use-after-free, double free, and undefined behavior.
- **Explain the issue:** Send only the short error summary and the relevant code section to the AI model. Ask it for a brief explanation and a possible fix.

### Task 5: Reduce AI Usage and Track Costs

This feature keeps AI use within the project's budget.

- **Normalize code for caching:** Remove comments and normalize whitespace before hashing code, so formatting-only changes can reuse cached results. The normalization must preserve program meaning.
- **Send only relevant code:** Include the target function or the small section around a reported error instead of the entire file.
- **Track usage:** Keep counts for model calls, token usage, cache hits, and fallback activations using SQLite or in-memory counters. Do not store raw code or user text in telemetry.
- **Apply limits:** Enforce hourly limits for each room and a monthly spending cap. Send an alert when usage reaches 80% of the configured budget. Return `COST_CAP_REACHED` when the monthly cap blocks further paid calls.

## 6. Design Principles

- **Measure before making claims:** Use sandbox results to support performance reports.
- **Use simple tools first:** Prefer caching, rules, and statistics before paid AI calls.
- **Keep normal execution responsive:** Return standard execution output without waiting for optional AI analysis.
- **Respect user consent:** Do not send code to an external AI service without permission.
- **Protect user data:** Do not put source code or user text in telemetry logs.
- **Verify suggested fixes:** A suggested patch is not a proven improvement until it has been tested.
- **Keep costs under control:** Use cache hits, smaller prompts, token limits, and budget alerts.
