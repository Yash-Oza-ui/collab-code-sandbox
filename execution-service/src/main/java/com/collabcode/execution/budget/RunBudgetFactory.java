package com.collabcode.execution.budget;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.queue.RunQueueGate;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * Factory for per-request {@link RunBudget} instances (NFR14, CR10, DR3, US-64).
 *
 * Reads the configured aggregate time budget, runs-per-request cap, and retry-after
 * duration once from {@link ExecutionLimitsProperties} (DR3) and fails fast at startup
 * if any value is invalid.
 *
 * Every repeated-run path creates a fresh {@link RunBudget} per request via
 * {@link #create(String)} so accounting stays strictly per-request and every budgeted
 * run passes through the single {@link RunQueueGate} (US-11).
 */
@Component
public class RunBudgetFactory {

    private final long aggregateTimeBudgetMs;
    private final int maxRunsPerRequest;
    private final int budgetRetryAfterSeconds;
    private final RunQueueGate runQueueGate;

    public RunBudgetFactory(ExecutionLimitsProperties limits, RunQueueGate runQueueGate) {
        this.aggregateTimeBudgetMs = limits.getAggregateTimeBudgetMs();
        this.maxRunsPerRequest = limits.getMaxRunsPerRequest();
        this.budgetRetryAfterSeconds = limits.getBudgetRetryAfterSeconds();
        this.runQueueGate = runQueueGate;

        if (aggregateTimeBudgetMs < 1) {
            throw new IllegalStateException(
                    "app.execution.limits.aggregate-time-budget-ms must be at least 1 (NFR14)");
        }
        if (maxRunsPerRequest < 1) {
            throw new IllegalStateException(
                    "app.execution.limits.max-runs-per-request must be at least 1 (NFR14)");
        }
        if (budgetRetryAfterSeconds < 1) {
            throw new IllegalStateException(
                    "app.execution.limits.budget-retry-after-seconds must be at least 1 (NFR14)");
        }
    }

    /** Creates a new per-request {@link RunBudget} for one repeated-run request. */
    public RunBudget create(String requestId) {
        return new RunBudget(
                requestId,
                maxRunsPerRequest,
                aggregateTimeBudgetMs,
                budgetRetryAfterSeconds,
                runQueueGate
        );
    }

    /** Creates a new per-request {@link RunBudget} with an explicit monotonic clock (for tests). */
    public RunBudget create(String requestId, LongSupplier nanoClock) {
        return new RunBudget(
                requestId,
                maxRunsPerRequest,
                aggregateTimeBudgetMs,
                budgetRetryAfterSeconds,
                runQueueGate,
                nanoClock
        );
    }

    public long getAggregateTimeBudgetMs()   { return aggregateTimeBudgetMs; }
    public int getMaxRunsPerRequest()        { return maxRunsPerRequest; }
    public int getBudgetRetryAfterSeconds()  { return budgetRetryAfterSeconds; }
}
