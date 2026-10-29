package com.collabcode.execution.runner;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Deterministic in-memory stub for CodeRunner.
 *
 * Used in Sprint 1 while US-09 (DockerCodeRunner) is not yet merged.
 * It never starts a process or touches Docker (FR5.1, NFR3).
 *
 * Behaviour (agreed with Quincy so the interface is stable):
 *  1. If stdin is non-empty, echoes its UPPER-CASE version to stdout.
 *  2. If code contains "stderr" (case-insensitive), writes "boom" to stderr
 *     and exits 2 — simulating a runtime failure.
 *  3. If code contains "syntax_error" (case-insensitive), writes a compiler
 *     error to stderr, exit 1, failure_kind "compile_error".
 *  4. Otherwise, returns "Hello from <language>!\n" in stdout, exit 0.
 *
 * Quincy's PR (feature/US-08-run-code-runner-hookup) replaces this bean
 * with DockerCodeRunner. Spring will use DockerCodeRunner when it is on the
 * classpath; the @Primary annotation here is removed in that PR.
 *
 * PLACEHOLDER – replaced by US-09 (DockerCodeRunner).
 */
@Primary
@Component
public class FakeCodeRunner implements CodeRunner {

    @Override
    public ExecutionResult execute(String language, String code, String stdin) {

        // Case 3: simulate a compile error
        if (code != null && code.toLowerCase().contains("syntax_error")) {
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
        if (code != null && code.toLowerCase().contains("stderr")) {
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

        // Case 1: stdin echoing
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
