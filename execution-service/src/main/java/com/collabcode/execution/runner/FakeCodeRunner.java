package com.collabcode.execution.runner;

import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Deterministic in-memory stub for CodeRunner.
 *
 * Updated in T-19 (Decision 8 & 9, Jay and Yash) to execute RunSpec and produce Decision 9 fields:
 *  1. If any file contains "oom", simulates an out-of-memory kill:
 *     oom_killed: true, failure_kind: "non_zero_exit", exit 137.
 *  2. If any file contains "syntax_error" (case-insensitive), writes a compiler
 *     error to stderr, exit 1, failure_kind "compile_error".
 *  3. If any file contains "stderr" (case-insensitive), writes "boom\n" to stderr
 *     and exits 2 — simulating a runtime failure ("non_zero_exit").
 *  4. If stdin is non-empty, echoes its UPPER-CASE version to stdout.
 *  5. Otherwise, returns "Hello from <language>!\n" in stdout, exit 0.
 *
 * Quincy's PR replaces this bean with DockerCodeRunner once merged.
 */
@Primary
@Component
public class FakeCodeRunner implements CodeRunner {

    @Override
    public ExecutionResult execute(RunSpec spec) {
        String language = spec.getLanguage();
        String stdin = spec.getStdin();

        Double compileTime = isCompiled(language) ? 12.0 : null;

        // Case 0: simulate an out-of-memory kill (Decision 9)
        boolean hasOom = spec.getFiles() != null && spec.getFiles().stream()
                .map(RunFile::getContent)
                .filter(c -> c != null)
                .anyMatch(FakeCodeRunner::matchesOom);

        if (hasOom) {
            String failingModule = findFailingFile(spec, "oom");
            return new ExecutionResult(
                    /* stdout          */ "",
                    /* stderr          */ "Killed: Out of memory\n",
                    /* exitCode        */ 137,
                    /* executionTimeMs */ 25.0,
                    /* peakMemoryMb    */ 64.0,
                    /* timedOut        */ false,
                    /* oomKilled       */ true,
                    /* failureKind     */ "non_zero_exit",
                    /* outputTruncated */ false,
                    /* message         */ "Memory limit exceeded",
                    /* compileTimeMs   */ compileTime,
                    /* failingModule   */ failingModule
            );
        }

        // Case 1: simulate a compile error
        boolean hasSyntaxError = spec.getFiles() != null && spec.getFiles().stream()
                .map(RunFile::getContent)
                .filter(c -> c != null)
                .anyMatch(c -> c.toLowerCase().contains("syntax_error"));

        if (hasSyntaxError) {
            String failingModule = findFailingFile(spec, "syntax_error");
            return new ExecutionResult(
                    /* stdout          */ "",
                    /* stderr          */ language + ": SyntaxError: unexpected token near line 1\n",
                    /* exitCode        */ 1,
                    /* executionTimeMs */ 0.0,
                    /* peakMemoryMb    */ 0.0,
                    /* timedOut        */ false,
                    /* oomKilled       */ false,
                    /* failureKind     */ "compile_error",
                    /* outputTruncated */ false,
                    /* message         */ null,
                    /* compileTimeMs   */ isCompiled(language) ? 0.0 : null,
                    /* failingModule   */ failingModule
            );
        }

        // Case 2: simulate a stderr / runtime failure
        boolean hasStderr = spec.getFiles() != null && spec.getFiles().stream()
                .map(RunFile::getContent)
                .filter(c -> c != null)
                .anyMatch(c -> c.toLowerCase().contains("stderr"));

        if (hasStderr) {
            String failingModule = findFailingFile(spec, "stderr");
            return new ExecutionResult(
                    /* stdout          */ "",
                    /* stderr          */ "boom\n",
                    /* exitCode        */ 2,
                    /* executionTimeMs */ 12.0,
                    /* peakMemoryMb    */ 4.0,
                    /* timedOut        */ false,
                    /* oomKilled       */ false,
                    /* failureKind     */ "non_zero_exit",
                    /* outputTruncated */ false,
                    /* message         */ null,
                    /* compileTimeMs   */ compileTime,
                    /* failingModule   */ failingModule
            );
        }

        // Case 3: stdin echoing
        if (stdin != null && !stdin.isEmpty()) {
            return new ExecutionResult(
                    /* stdout          */ stdin.toUpperCase() + "\n",
                    /* stderr          */ "",
                    /* exitCode        */ 0,
                    /* executionTimeMs */ 10.0,
                    /* peakMemoryMb    */ 4.0,
                    /* timedOut        */ false,
                    /* oomKilled       */ false,
                    /* failureKind     */ null,
                    /* outputTruncated */ false,
                    /* message         */ null,
                    /* compileTimeMs   */ compileTime,
                    /* failingModule   */ null
            );
        }

        // Case 4: normal success
        return new ExecutionResult(
                /* stdout          */ "Hello from " + language + "!\n",
                /* stderr          */ "",
                /* exitCode        */ 0,
                /* executionTimeMs */ 15.0,
                /* peakMemoryMb    */ 4.2,
                /* timedOut        */ false,
                /* oomKilled       */ false,
                /* failureKind     */ null,
                /* outputTruncated */ false,
                /* message         */ null,
                /* compileTimeMs   */ compileTime,
                /* failingModule   */ null
        );
    }

    private static boolean isCompiled(String language) {
        return "cpp".equalsIgnoreCase(language)
                || "c".equalsIgnoreCase(language)
                || "java".equalsIgnoreCase(language);
    }

    private static boolean matchesOom(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        return (lower.contains("oom") && !lower.contains("boom")) || lower.contains("out_of_memory");
    }

    private static String findFailingFile(RunSpec spec, String token) {
        if (spec.getFiles() == null) return null;
        return spec.getFiles().stream()
                .filter(f -> {
                    if (f.getContent() == null) return false;
                    if ("oom".equalsIgnoreCase(token)) {
                        return matchesOom(f.getContent());
                    }
                    return f.getContent().toLowerCase().contains(token);
                })
                .map(RunFile::getName)
                .findFirst()
                .orElse(null);
    }
}
