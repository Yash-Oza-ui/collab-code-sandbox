package com.collabcode.execution.budget;

/**
 * Identifies which per-request repeated-run budget limit was reached (NFR14, US-64, CR10).
 *
 * Emitted in {@code error.reason} of the HTTP 429 {@code RUN_BUDGET_EXCEEDED} envelope
 * so the caller knows whether the runs-per-request cap or the aggregate time budget was hit.
 */
public enum BudgetLimitKind {

    /** The configured maximum number of runs per request was reached. */
    MAX_RUNS_PER_REQUEST("max_runs_per_request"),

    /** The configured aggregate wall-clock execution time budget was reached. */
    AGGREGATE_TIME_BUDGET("aggregate_time_budget");

    private final String reason;

    BudgetLimitKind(String reason) {
        this.reason = reason;
    }

    /** Machine-readable reason identifier for error.reason. */
    public String getReason() {
        return reason;
    }
}
