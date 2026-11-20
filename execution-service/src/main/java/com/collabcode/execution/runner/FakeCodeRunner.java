package com.collabcode.execution.runner;

import com.collabcode.execution.budget.RunBudget;
import com.collabcode.execution.model.BatchRunSpec;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic in-memory stub for CodeRunner.
 *
 * This bean is available only when the "stub-runner" Spring profile is active.
 * The normal application uses DockerRunner; this stub remains available for
 * controlled offline development and tests that explicitly select the profile.
 *
 * Simulated outcomes:
 *  1. Content containing an OOM marker returns exit 137,
 *     oom_killed=true, failure_kind="non_zero_exit".
 *  2. Content containing "syntax_error" returns a simulated compile error.
 *  3. Content containing "stderr" returns a simulated runtime failure.
 *  4. Non-empty stdin is echoed in uppercase.
 *  5. Otherwise, a deterministic success result is returned.
 */
@Component
@Profile("stub-runner")
public class FakeCodeRunner implements CodeRunner {

    @Override
    public ExecutionResult execute(RunSpec spec) {
        if (spec == null) {
            return invalidRequest("Run specification must not be null.");
        }

        String language = spec.getLanguage();
        String stdin = spec.getStdin();

        Double compileTime = isCompiled(language) ? 12.0 : null;

        // Case 0: simulate an out-of-memory kill (Decision 9).
        boolean hasOom = (spec.getFiles() != null && spec.getFiles().stream()
                .filter(file -> file != null)
                .map(RunFile::getContent)
                .filter(content -> content != null)
                .anyMatch(FakeCodeRunner::matchesOom))
                || matchesOom(stdin);

        if (hasOom) {
            String failingModule = findFailingFile(spec, "oom");
            return new ExecutionResult(
                    "",
                    "Killed: Out of memory\n",
                    137,
                    25.0,
                    64.0,
                    false,
                    true,
                    "non_zero_exit",
                    false,
                    "Memory limit exceeded",
                    compileTime,
                    failingModule
            );
        }

        // Case 1: simulate a compile error.
        boolean hasSyntaxError = spec.getFiles() != null && spec.getFiles().stream()
                .filter(file -> file != null)
                .map(RunFile::getContent)
                .filter(content -> content != null)
                .anyMatch(content ->
                        content.toLowerCase(Locale.ROOT).contains("syntax_error"));

        if (hasSyntaxError) {
            String failingModule = findFailingFile(spec, "syntax_error");
            return new ExecutionResult(
                    "",
                    language + ": SyntaxError: unexpected token near line 1\n",
                    1,
                    0.0,
                    0.0,
                    false,
                    false,
                    "compile_error",
                    false,
                    null,
                    isCompiled(language) ? 0.0 : null,
                    failingModule
            );
        }

        // Case 2: simulate a stderr / runtime failure.
        boolean hasStderr = spec.getFiles() != null && spec.getFiles().stream()
                .filter(file -> file != null)
                .map(RunFile::getContent)
                .filter(content -> content != null)
                .anyMatch(content ->
                        content.toLowerCase(Locale.ROOT).contains("stderr"));

        if (hasStderr) {
            String failingModule = findFailingFile(spec, "stderr");
            return new ExecutionResult(
                    "",
                    "boom\n",
                    2,
                    12.0,
                    4.0,
                    false,
                    false,
                    "non_zero_exit",
                    false,
                    null,
                    compileTime,
                    failingModule
            );
        }

        // Combine file content for deterministic marker checks.
        String code = spec.getFiles() == null ? "" : spec.getFiles().stream()
                .filter(file -> file != null)
                .map(RunFile::getContent)
                .filter(c -> c != null)
                .collect(java.util.stream.Collectors.joining("\n"));

        // Case 2.5: Sanitizer run simulation (US-73, FR39.1, FR39.2)
        if (spec.isSanitizer()) {
            if (code.contains("clean_sanitizer") || code.contains("SANITIZER_CLEAN")) {
                return new ExecutionResult(
                        "SANITIZER_CLEAN\n",
                        "",
                        0,
                        15.0,
                        4.2,
                        false,
                        false,
                        null,
                        false,
                        null,
                        compileTime,
                        null
                );
            }
            if (code.contains("non_sanitizer_crash")) {
                return new ExecutionResult(
                        "",
                        "Segmentation fault (core dumped)\n",
                        139,
                        15.0,
                        4.2,
                        false,
                        false,
                        "non_zero_exit",
                        false,
                        "Program exited with a non-zero status.",
                        compileTime,
                        null
                );
            }
            if (code.contains("heap-buffer-overflow")) {
                String asanReport = "==4210==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000014\n" +
                        "READ of size 4 at 0x602000000014 thread T0\n" +
                        "    #0 0x401233 in process_buffer /workspace/solution.cpp:14\n";
                return new ExecutionResult(
                        "",
                        asanReport,
                        1,
                        15.0,
                        4.2,
                        false,
                        false,
                        "non_zero_exit",
                        false,
                        null,
                        compileTime,
                        null
                );
            }
            if (code.contains("stack-buffer-overflow")) {
                String asanReport = "==5120==ERROR: AddressSanitizer: stack-buffer-overflow on address 0x7ffe12345678\n" +
                        "WRITE of size 4 at 0x7ffe12345678 thread T0\n" +
                        "    #0 0x4011aa in vulnerable_copy /workspace/solution.c:20\n";
                return new ExecutionResult(
                        "",
                        asanReport,
                        1,
                        15.0,
                        4.2,
                        false,
                        false,
                        "non_zero_exit",
                        false,
                        null,
                        compileTime,
                        null
                );
            }
            if (code.contains("use-after-free")) {
                String asanReport = "==7032==ERROR: AddressSanitizer: heap-use-after-free on address 0x602000000010\n" +
                        "READ of size 4 at 0x602000000010 thread T0\n" +
                        "    #0 0x401222 in read_node /workspace/solution.cpp:32\n";
                return new ExecutionResult(
                        "",
                        asanReport,
                        1,
                        15.0,
                        4.2,
                        false,
                        false,
                        "non_zero_exit",
                        false,
                        null,
                        compileTime,
                        null
                );
            }
            if (code.contains("double-free")) {
                String asanReport = "==8192==ERROR: AddressSanitizer: attempting double-free on 0x602000000010 in thread T0:\n" +
                        "    #0 0x7f8812 in free (/usr/lib/x86_64-linux-gnu/libasan.so.6+0xb2123)\n" +
                        "    #1 0x401290 in clean_memory /workspace/solution.c:25\n";
                return new ExecutionResult(
                        "",
                        asanReport,
                        1,
                        15.0,
                        4.2,
                        false,
                        false,
                        "non_zero_exit",
                        false,
                        null,
                        compileTime,
                        null
                );
            }
            if (code.contains("signed-integer-overflow")) {
                String ubsanReport = "/sandbox/code/solution.c:14:5: runtime error: signed integer overflow: 2147483647 + 1 cannot be represented in type 'int'\n";
                return new ExecutionResult(
                        "",
                        ubsanReport,
                        1,
                        15.0,
                        4.2,
                        false,
                        false,
                        "non_zero_exit",
                        false,
                        null,
                        compileTime,
                        null
                );
            }
        }

        // Case 3: timeout / infinite loop simulation (US-13, NFR4.1)
        if (code.contains("infinite_loop") || code.contains("while True: pass") || code.contains("while true")
                || (stdin != null && (stdin.contains("infinite_loop") || stdin.contains("timeout")))) {
            return new ExecutionResult(
                    "",
                    "",
                    124,
                    5000.0,
                    0.0,
                    true,
                    false,
                    "timeout",
                    false,
                    "Time limit exceeded",
                    compileTime,
                    null
            );
        }

        // Case 3.5: output flood simulation (US-13)
        if (code.contains("output_flood")) {
            return new ExecutionResult(
                    "A".repeat(1024),
                    "",
                    0,
                    15.0,
                    4.2,
                    false,
                    false,
                    null,
                    true,
                    null,
                    compileTime,
                    null
            );
        }

        // Case 4: simulate bounded in-flight execution for testing (FR16 / US-11 / US-64)
        double simulatedTimeMs = 15.0;
        if (code.toLowerCase(Locale.ROOT).contains("sleep") || code.toLowerCase(Locale.ROOT).contains("delay")) {
            long delayMs = 3000;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:sleep|delay)[\\s_\\(]+(\\d+)")
                    .matcher(code.toLowerCase(Locale.ROOT));
            if (m.find()) {
                try {
                    long requested = Long.parseLong(m.group(1));
                    delayMs = requested <= 10 ? requested * 1000 : requested;
                    delayMs = Math.min(Math.max(delayMs, 50), 5000);
                } catch (NumberFormatException ignored) {}
            }
            long startNanos = System.nanoTime();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            simulatedTimeMs = Math.max(delayMs, elapsedMs);
        }

        // Case 4.5: stdin echoing.
        if (stdin != null && !stdin.isEmpty()) {
            double timeMs = (simulatedTimeMs > 15.0) ? simulatedTimeMs : 10.0;
            return new ExecutionResult(
                    stdin.toUpperCase(Locale.ROOT) + "\n",
                    "",
                    0,
                    timeMs,
                    4.0,
                    false,
                    false,
                    null,
                    false,
                    null,
                    compileTime,
                    null
            );
        }

        // Case 5: normal success
        return new ExecutionResult(
                "Hello from " + language + "!\n",
                "",
                0,
                simulatedTimeMs,
                4.2,
                false,
                false,
                null,
                false,
                null,
                compileTime,
                null
        );
    }

    @Override
    public List<ExecutionResult> executeBatch(BatchRunSpec spec, RunBudget budget) {
        if (spec == null) {
            throw new IllegalArgumentException("BatchRunSpec must not be null");
        }
        List<String> inputs = spec.getInputs();
        List<ExecutionResult> results = new ArrayList<>(inputs.size());

        for (String input : inputs) {
            budget.checkBudgetBeforeNextRun();

            RunSpec singleSpec = new RunSpec(
                    spec.getLanguage(),
                    spec.getFiles(),
                    spec.getEntry(),
                    input
            );
            long startNanos = System.nanoTime();
            ExecutionResult res = this.execute(singleSpec);
            double elapsedMs = Math.max(0.0, (System.nanoTime() - startNanos) / 1_000_000.0);

            budget.recordRun(res, Math.max(elapsedMs, res.getExecutionTimeMs()));
            results.add(res);
        }

        return results;
    }

    private ExecutionResult invalidRequest(String message) {
        return new ExecutionResult(
                "",
                message + "\n",
                2,
                0.0,
                0.0,
                false,
                false,
                "non_zero_exit",
                false,
                message,
                null,
                null
        );
    }

    private static boolean isCompiled(String language) {
        if (language == null) {
            return false;
        }
        return "cpp".equalsIgnoreCase(language)
                || "c".equalsIgnoreCase(language)
                || "java".equalsIgnoreCase(language);
    }

    private static boolean matchesOom(String content) {
        if (content == null) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        return (lower.contains("oom") && !lower.contains("boom"))
                || lower.contains("out_of_memory");
    }

    private static String findFailingFile(RunSpec spec, String token) {
        if (spec.getFiles() == null) {
            return null;
        }

        return spec.getFiles().stream()
                .filter(file -> file != null && file.getContent() != null)
                .filter(file -> {
                    String content = file.getContent();
                    if ("oom".equalsIgnoreCase(token)) {
                        return matchesOom(content);
                    }
                    return content.toLowerCase(Locale.ROOT)
                            .contains(token.toLowerCase(Locale.ROOT));
                })
                .map(RunFile::getName)
                .findFirst()
                .orElse(null);
    }
}