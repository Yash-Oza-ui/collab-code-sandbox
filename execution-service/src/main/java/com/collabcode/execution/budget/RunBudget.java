package com.collabcode.execution.budget;

import com.collabcode.execution.queue.RunQueueGate;
import com.collabcode.execution.runner.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Per-request execution budget for repeated-run features (NFR14, CR10, US-64).
 *
 * Each repeated-run request (e.g. complexity measurement, generated-test batch, sanitizer)
 * receives its own {@code RunBudget} instance created by {@link RunBudgetFactory}. Budget
 * accounting is strictly per-request (never global) so one analysis cannot starve another.
 *
 * Before starting the next run, this object verifies both configured limits (DR3):
 *   1. Runs-per-request cap ({@code maxRuns}).
 *   2. Aggregate execution time budget ({@code aggregateTimeBudgetMs}).
 *
 * Every accepted run is executed through the single {@link RunQueueGate} (US-11, FR16),
 * ensuring repeated runs count toward the same queue capacity and receive the same
 * {@code QUEUE_FULL} refusal when the queue is saturated.
 *
 * If a limit would be exceeded, the next run is refused with {@link RunBudgetExceededException}
 * before acquiring a queue slot or starting a container, and all previously completed runs
 * remain intact in {@link #getCompletedResults()}.
 *
 * Never log submitted user code here (NFR8.2).
 */
public class RunBudget {

    private static final Logger log = LoggerFactory.getLogger(RunBudget.class);

    private final String requestId;
    private final int maxRuns;
    private final long aggregateTimeBudgetMs;
    private final int retryAfterSeconds;
    private final RunQueueGate runQueueGate;
    private final LongSupplier nanoClock;

    private int runsCompleted = 0;
    private double totalRunTimeMs = 0.0;
    private final List<ExecutionResult> completedResults = new ArrayList<>();

    public RunBudget(
            String requestId,
            int maxRuns,
            long aggregateTimeBudgetMs,
            int retryAfterSeconds,
            RunQueueGate runQueueGate) {
        this(requestId, maxRuns, aggregateTimeBudgetMs, retryAfterSeconds, runQueueGate, System::nanoTime);
    }

    public RunBudget(
            String requestId,
            int maxRuns,
            long aggregateTimeBudgetMs,
            int retryAfterSeconds,
            RunQueueGate runQueueGate,
            LongSupplier nanoClock) {
        if (maxRuns < 1) {
            throw new IllegalArgumentException("maxRuns must be at least 1 (NFR14)");
        }
        if (aggregateTimeBudgetMs < 1) {
            throw new IllegalArgumentException("aggregateTimeBudgetMs must be at least 1 (NFR14)");
        }
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("retryAfterSeconds must be at least 1 (NFR14)");
        }
        this.requestId = requestId;
        this.maxRuns = maxRuns;
        this.aggregateTimeBudgetMs = aggregateTimeBudgetMs;
        this.retryAfterSeconds = retryAfterSeconds;
        this.runQueueGate = Objects.requireNonNull(runQueueGate, "runQueueGate must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
    }

    /**
     * Runs one budgeted execution through the single {@link RunQueueGate}.
     *
     * Checks both the runs-per-request cap and the aggregate time budget BEFORE
     * acquiring a queue slot or starting a container. If either limit has been
     * reached, throws {@link RunBudgetExceededException} without losing any
     * previously completed runs.
     *
     * @param runWork supplier that executes one sandboxed run (e.g. {@code () -> codeRunner.execute(spec)})
     * @return the {@link ExecutionResult} returned by {@code runWork}
     * @throws RunBudgetExceededException if the run cap or aggregate time budget has been reached
     * @throws com.collabcode.execution.queue.QueueFullException if the shared queue gate is full
     */
    public synchronized ExecutionResult execute(Supplier<ExecutionResult> runWork) {
        Objects.requireNonNull(runWork, "runWork must not be null");

        checkBudgetBeforeNextRun();

        long startNanos = nanoClock.getAsLong();
        ExecutionResult result = runQueueGate.run(requestId, runWork);
        double wallElapsedMs = Math.max(0.0, (nanoClock.getAsLong() - startNanos) / 1_000_000.0);

        double reportedMs = 0.0;
        if (result != null) {
            reportedMs = Math.max(0.0, result.getExecutionTimeMs());
            if (result.getCompileTimeMs() != null && result.getCompileTimeMs() > 0.0) {
                reportedMs += result.getCompileTimeMs();
            }
        }
        double recordedMs = Math.max(wallElapsedMs, reportedMs);

        runsCompleted++;
        totalRunTimeMs += recordedMs;
        if (result != null) {
            completedResults.add(result);
        }

        return result;
    }

    private void checkBudgetBeforeNextRun() {
        if (runsCompleted >= maxRuns) {
            log.warn("run refused: request_id={} reason={} runs_completed={} max_runs={} elapsed_ms={}",
                    requestId, BudgetLimitKind.MAX_RUNS_PER_REQUEST.getReason(),
                    runsCompleted, maxRuns, Math.round(totalRunTimeMs));
            throw new RunBudgetExceededException(
                    requestId,
                    BudgetLimitKind.MAX_RUNS_PER_REQUEST,
                    runsCompleted,
                    maxRuns,
                    Math.round(totalRunTimeMs),
                    aggregateTimeBudgetMs,
                    retryAfterSeconds,
                    completedResults
            );
        }

        if (totalRunTimeMs >= aggregateTimeBudgetMs) {
            log.warn("run refused: request_id={} reason={} runs_completed={} elapsed_ms={} budget_ms={}",
                    requestId, BudgetLimitKind.AGGREGATE_TIME_BUDGET.getReason(),
                    runsCompleted, Math.round(totalRunTimeMs), aggregateTimeBudgetMs);
            throw new RunBudgetExceededException(
                    requestId,
                    BudgetLimitKind.AGGREGATE_TIME_BUDGET,
                    runsCompleted,
                    maxRuns,
                    Math.round(totalRunTimeMs),
                    aggregateTimeBudgetMs,
                    retryAfterSeconds,
                    completedResults
            );
        }
    }

    public String getRequestId()                    { return requestId; }
    public int getMaxRuns()                         { return maxRuns; }
    public long getAggregateTimeBudgetMs()          { return aggregateTimeBudgetMs; }
    public int getRetryAfterSeconds()               { return retryAfterSeconds; }

    public synchronized int getRunsCompleted()      { return runsCompleted; }
    public synchronized double getTotalRunTimeMs()  { return totalRunTimeMs; }
    public synchronized int getRemainingRuns()      { return Math.max(0, maxRuns - runsCompleted); }

    public synchronized double getRemainingTimeMs() {
        return Math.max(0.0, aggregateTimeBudgetMs - totalRunTimeMs);
    }

    public synchronized boolean isBudgetExhausted() {
        return runsCompleted >= maxRuns || totalRunTimeMs >= aggregateTimeBudgetMs;
    }

    /** Returns an immutable snapshot of all runs completed so far within this request. */
    public synchronized List<ExecutionResult> getCompletedResults() {
        return Collections.unmodifiableList(new ArrayList<>(completedResults));
    }
}
