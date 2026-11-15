package com.collabcode.execution.runner;

import com.collabcode.execution.model.RunSpec;

/**
 * Internal contract between RunController and the code runner implementation.
 *
 * Sprint 1: FakeCodeRunner implements this for safe offline testing.
 * Sprint 2 (T-19, Jay): Takes the run spec object (RunSpec) instead of (language, code, stdin)
 * to support multi-file modules and saved snapshots.
 * US-09: Quincy's DockerCodeRunner executes the RunSpec in an isolated container.
 *
 * Rules:
 *  - Never execute user code on the host, even in tests (FR5.1, NFR3).
 *  - Implementations must return an ExecutionResult for every outcome.
 */
public interface CodeRunner {

    /**
     * Runs code described by {@code spec}.
     *
     * @param spec run specification carrying language, files, entry, stdin
     * @return an {@link ExecutionResult} describing what happened
     */
    ExecutionResult execute(RunSpec spec);
}
