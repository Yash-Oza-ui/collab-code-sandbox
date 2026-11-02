package com.collabcode.execution.runner;

import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Deterministic in-memory stub for CodeRunner.
 *
 * Updated in T-19 (Jay) to execute the run spec object (RunSpec).
 * It never starts a process or touches Docker (FR5.1, NFR3).
 *
 * Behaviour (agreed with Quincy so the interface is stable):
 *  1. If any file contains "syntax_error" (case-insensitive), writes a compiler
 *     error to stderr, exit 1, failure_kind "compile_error".
 *  2. If any file contains "stderr" (case-insensitive), writes "boom\n" to stderr
 *     and exits 2 — simulating a runtime failure ("non_zero_exit").
 *  3. If stdin is non-empty, echoes its UPPER-CASE version to stdout.
 *  4. Otherwise, returns "Hello from <language>!\n" in stdout, exit 0.
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

        // Case 1: simulate a compile error
        boolean hasSyntaxError = spec.getFiles() != null && spec.getFiles().stream()
                .map(RunFile::getContent)
                .filter(c -> c != null)
                .anyMatch(c -> c.toLowerCase().contains("syntax_error"));

        if (hasSyntaxError) {
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
                    /* message         */ null
            );
        }

        // Case 2: simulate a stderr / runtime failure
        boolean hasStderr = spec.getFiles() != null && spec.getFiles().stream()
                .map(RunFile::getContent)
                .filter(c -> c != null)
                .anyMatch(c -> c.toLowerCase().contains("stderr"));

        if (hasStderr) {
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
                    /* message         */ null
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
                    /* message         */ null
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
                /* message         */ null
        );
    }
}
