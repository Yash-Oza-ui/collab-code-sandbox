package com.collabcode.execution.budget;

import com.collabcode.execution.runner.ExecutionResult;

import java.util.Collections;
import java.util.List;

/**
 * Thrown by {@link RunBudget} when the next run in a repeated-run request would exceed
 * either the runs-per-request cap or the aggregate execution time budget (NFR14, CR10, US-64).
 *
 * Mapped to HTTP 429 {@code RUN_BUDGET_EXCEEDED} by {@code RunBudgetExceededExceptionHandler}
 * matching {@code docs/examples/v2-error-run-budget-exceeded.json}.
 *
 * Runs already completed before the budget was hit are preserved on {@link #getCompletedResults()}
 * so callers never lose work already done.
 */
public class RunBudgetExceededException extends RuntimeException {

    public static final String ERROR_CODE = "RUN_BUDGET_EXCEEDED";

    private final String requestId;
    private final BudgetLimitKind limitKind;
    private final int runsCompleted;
    private final int maxRuns;
    private final long elapsedTimeMs;
    private final long aggregateTimeBudgetMs;
    private final int retryAfterSeconds;
    private final List<ExecutionResult> completedResults;

    public RunBudgetExceededException(
            String requestId,
            BudgetLimitKind limitKind,
            int runsCompleted,
            int maxRuns,
            long elapsedTimeMs,
            long aggregateTimeBudgetMs,
            int retryAfterSeconds,
            List<ExecutionResult> completedResults) {
        super(formatMessage(limitKind, maxRuns, aggregateTimeBudgetMs));
        this.requestId = requestId;
        this.limitKind = limitKind;
        this.runsCompleted = runsCompleted;
        this.maxRuns = maxRuns;
        this.elapsedTimeMs = elapsedTimeMs;
        this.aggregateTimeBudgetMs = aggregateTimeBudgetMs;
        this.retryAfterSeconds = retryAfterSeconds;
        this.completedResults = completedResults != null
                ? List.copyOf(completedResults)
                : Collections.emptyList();
    }

    private static String formatMessage(
            BudgetLimitKind limitKind,
            int maxRuns,
            long aggregateTimeBudgetMs) {
        if (limitKind == BudgetLimitKind.MAX_RUNS_PER_REQUEST) {
            return "Repeated-run cap of " + maxRuns + " runs per request exceeded (NFR14).";
        }
        return "Aggregate repeated-run execution budget of " + aggregateTimeBudgetMs + "ms exceeded (NFR14).";
    }

    public String getRequestId()                    { return requestId; }
    public BudgetLimitKind getLimitKind()           { return limitKind; }
    public String getReason()                       { return limitKind.getReason(); }
    public int getRunsCompleted()                   { return runsCompleted; }
    public int getMaxRuns()                         { return maxRuns; }
    public long getElapsedTimeMs()                  { return elapsedTimeMs; }
    public long getAggregateTimeBudgetMs()          { return aggregateTimeBudgetMs; }
    public int getRetryAfterSeconds()               { return retryAfterSeconds; }
    public List<ExecutionResult> getCompletedResults() { return completedResults; }
}
