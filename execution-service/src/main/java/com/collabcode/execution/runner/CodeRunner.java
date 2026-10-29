package com.collabcode.execution.runner;

/**
 * Internal contract between RunController (US-08, Jay) and the code runner
 * implementation.
 *
 * Sprint 1: FakeCodeRunner implements this for safe offline testing.
 * Sprint 1 / US-09: Quincy's DockerCodeRunner replaces it without any
 * changes to RunController or the rest of Jay's code.
 *
 * Rules:
 *  - Never execute user code on the host, even in tests (FR5.1, NFR3).
 *  - Implementations must return an ExecutionResult for every outcome
 *    (success, compile error, non-zero exit, timeout, OOM) so the
 *    endpoint always has a result to return (FR4.2).
 */
public interface CodeRunner {

    /**
     * Runs {@code code} in the given {@code language} with optional stdin.
     *
     * @param language one of: cpp, python, c, java, javascript (DR1)
     * @param code     source code to run
     * @param stdin    standard input; empty string if not required
     * @return an {@link ExecutionResult} describing what happened
     */
    ExecutionResult execute(String language, String code, String stdin);
}
