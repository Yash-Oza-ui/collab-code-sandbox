package com.collabcode.execution.runner;

import com.collabcode.execution.budget.RunBudget;
import com.collabcode.execution.model.BatchRunSpec;

import java.util.List;

/**
 * Internal contract for running a batch of test inputs in one container (FR38.4, FR5.1, US-71).
 *
 * Each test runs sequentially as an isolated process under the 5 s wall-clock limit
 * (NFR4.1) and memory limit (NFR4.2), bounded by the aggregate request budget (NFR14, US-64).
 * The container and workspace are destroyed in a finally path after the batch.
 */
public interface BatchRunner {

    /**
     * Executes a batch of test inputs described by {@code spec} within {@code budget}.
     *
     * @param spec batch run specification carrying language, files, entry, and inputs
     * @param budget request budget enforcing runs-per-request cap and aggregate time budget
     * @return a list of {@link ExecutionResult} entries matching the input order
     */
    List<ExecutionResult> executeBatch(BatchRunSpec spec, RunBudget budget);
}
