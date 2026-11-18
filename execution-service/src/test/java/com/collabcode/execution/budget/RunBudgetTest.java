package com.collabcode.execution.budget;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import com.collabcode.execution.queue.QueueFullException;
import com.collabcode.execution.queue.RunQueueGate;
import com.collabcode.execution.runner.ExecutionResult;
import com.collabcode.execution.runner.FakeCodeRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for per-request repeated-run budget accounting (NFR14, CR10, US-64).
 * Pure unit tests: no Spring context, no Docker (uses FakeCodeRunner / stubs).
 */
class RunBudgetTest {

    private static ExecutionLimitsProperties limits(
            int maxQueue,
            int queueRetry,
            long aggregateBudgetMs,
            int maxRuns,
            int budgetRetry) {
        ExecutionLimitsProperties props = new ExecutionLimitsProperties();
        props.setMaxQueueLength(maxQueue);
        props.setQueueRetryAfterSeconds(queueRetry);
        props.setAggregateTimeBudgetMs(aggregateBudgetMs);
        props.setMaxRunsPerRequest(maxRuns);
        props.setBudgetRetryAfterSeconds(budgetRetry);
        return props;
    }

    private static ExecutionResult okResult(String stdout, double timeMs) {
        return new ExecutionResult(stdout, "", 0, timeMs, 4.0,
                false, false, null, false, null);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("Runs within cap and time budget all succeed and are tracked accurately")
    void execute_withinLimits_completesAllRuns() {
        ExecutionLimitsProperties props = limits(5, 5, 20000, 3, 30);
        RunQueueGate gate = new RunQueueGate(props);
        RunBudgetFactory factory = new RunBudgetFactory(props, gate);
        RunBudget budget = factory.create("req-1");

        ExecutionResult r1 = budget.execute(() -> okResult("1\n", 100.0));
        ExecutionResult r2 = budget.execute(() -> okResult("2\n", 250.0));
        ExecutionResult r3 = budget.execute(() -> okResult("3\n", 150.0));

        assertThat(r1.getStdout()).isEqualTo("1\n");
        assertThat(r2.getStdout()).isEqualTo("2\n");
        assertThat(r3.getStdout()).isEqualTo("3\n");
        assertThat(budget.getRunsCompleted()).isEqualTo(3);
        assertThat(budget.getRemainingRuns()).isZero();
        assertThat(budget.getTotalRunTimeMs()).isGreaterThanOrEqualTo(500.0);
        assertThat(budget.getCompletedResults()).hasSize(3);
        assertThat(gate.inFlight()).isZero();
    }

    @Test
    @DisplayName("Runs-per-request cap refuses next run with MAX_RUNS_PER_REQUEST and preserves completed runs")
    void execute_whenRunCapReached_refusesNextRunAndPreservesPriorRuns() {
        ExecutionLimitsProperties props = limits(5, 5, 20000, 2, 30);
        RunQueueGate gate = new RunQueueGate(props);
        RunBudgetFactory factory = new RunBudgetFactory(props, gate);
        RunBudget budget = factory.create("req-cap");

        AtomicInteger runnerCalls = new AtomicInteger();
        budget.execute(() -> {
            runnerCalls.incrementAndGet();
            return okResult("first\n", 15.0);
        });
        budget.execute(() -> {
            runnerCalls.incrementAndGet();
            return okResult("second\n", 20.0);
        });

        assertThatThrownBy(() -> budget.execute(() -> {
            runnerCalls.incrementAndGet();
            return okResult("third\n", 15.0);
        }))
                .isInstanceOf(RunBudgetExceededException.class)
                .satisfies(e -> {
                    RunBudgetExceededException ex = (RunBudgetExceededException) e;
                    assertThat(ex.getRequestId()).isEqualTo("req-cap");
                    assertThat(ex.getLimitKind()).isEqualTo(BudgetLimitKind.MAX_RUNS_PER_REQUEST);
                    assertThat(ex.getReason()).isEqualTo("max_runs_per_request");
                    assertThat(ex.getMessage()).contains("2 runs per request");
                    assertThat(ex.getRunsCompleted()).isEqualTo(2);
                    assertThat(ex.getMaxRuns()).isEqualTo(2);
                    assertThat(ex.getRetryAfterSeconds()).isEqualTo(30);
                    assertThat(ex.getCompletedResults())
                            .extracting(ExecutionResult::getStdout)
                            .containsExactly("first\n", "second\n");
                });

        // Third run was never invoked; prior runs remain intact on the budget
        assertThat(runnerCalls.get()).isEqualTo(2);
        assertThat(budget.getCompletedResults()).hasSize(2);
        assertThat(gate.inFlight()).isZero();
    }

    @Test
    @DisplayName("Aggregate time budget refuses next run with AGGREGATE_TIME_BUDGET and preserves completed runs")
    void execute_whenAggregateTimeBudgetReached_refusesNextRunAndPreservesPriorRuns() {
        ExecutionLimitsProperties props = limits(5, 5, 1000, 10, 30);
        RunQueueGate gate = new RunQueueGate(props);
        RunBudgetFactory factory = new RunBudgetFactory(props, gate);

        // Use a deterministic monotonic clock to simulate a slow runner without slow sleeps
        AtomicLong clockNanos = new AtomicLong(0);
        RunBudget budget = factory.create("req-time", clockNanos::get);

        AtomicInteger runnerCalls = new AtomicInteger();
        budget.execute(() -> {
            runnerCalls.incrementAndGet();
            clockNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(600));
            return okResult("slow-1\n", 600.0);
        });
        budget.execute(() -> {
            runnerCalls.incrementAndGet();
            clockNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
            return okResult("slow-2\n", 500.0);
        });

        assertThat(budget.getTotalRunTimeMs()).isGreaterThanOrEqualTo(1100.0);

        assertThatThrownBy(() -> budget.execute(() -> {
            runnerCalls.incrementAndGet();
            return okResult("slow-3\n", 100.0);
        }))
                .isInstanceOf(RunBudgetExceededException.class)
                .satisfies(e -> {
                    RunBudgetExceededException ex = (RunBudgetExceededException) e;
                    assertThat(ex.getRequestId()).isEqualTo("req-time");
                    assertThat(ex.getLimitKind()).isEqualTo(BudgetLimitKind.AGGREGATE_TIME_BUDGET);
                    assertThat(ex.getReason()).isEqualTo("aggregate_time_budget");
                    assertThat(ex.getMessage())
                            .isEqualTo("Aggregate repeated-run execution budget of 1000ms exceeded (NFR14).");
                    assertThat(ex.getRunsCompleted()).isEqualTo(2);
                    assertThat(ex.getAggregateTimeBudgetMs()).isEqualTo(1000L);
                    assertThat(ex.getRetryAfterSeconds()).isEqualTo(30);
                    assertThat(ex.getCompletedResults())
                            .extracting(ExecutionResult::getStdout)
                            .containsExactly("slow-1\n", "slow-2\n");
                });

        assertThat(runnerCalls.get()).isEqualTo(2);
        assertThat(budget.getCompletedResults()).hasSize(2);
    }

    @Test
    @DisplayName("Slow FakeCodeRunner hits aggregate time budget via real wall-clock delay")
    void execute_withSlowFakeCodeRunner_enforcesAggregateTimeBudget() {
        ExecutionLimitsProperties props = limits(5, 5, 150, 10, 30);
        RunQueueGate gate = new RunQueueGate(props);
        RunBudgetFactory factory = new RunBudgetFactory(props, gate);
        FakeCodeRunner fakeRunner = new FakeCodeRunner();

        RunBudget budget = factory.create("req-fake-slow");
        RunSpec slowSpec = new RunSpec(
                "python",
                List.of(new RunFile("main.py", "# delay 100")),
                "main.py",
                "100"
        );

        // Two ~100ms runs -> total >= 200ms >= 150ms budget
        budget.execute(() -> fakeRunner.execute(slowSpec));
        budget.execute(() -> fakeRunner.execute(slowSpec));

        assertThatThrownBy(() -> budget.execute(() -> fakeRunner.execute(slowSpec)))
                .isInstanceOf(RunBudgetExceededException.class)
                .satisfies(e -> {
                    RunBudgetExceededException ex = (RunBudgetExceededException) e;
                    assertThat(ex.getLimitKind()).isEqualTo(BudgetLimitKind.AGGREGATE_TIME_BUDGET);
                    assertThat(ex.getRunsCompleted()).isEqualTo(2);
                    assertThat(ex.getCompletedResults()).hasSize(2);
                });
    }

    @Test
    @DisplayName("Per-run 5s timeout from FakeCodeRunner is preserved and counted toward aggregate budget")
    void execute_whenSingleRunTimesOut_countsTowardBudgetAndPreservesTimeoutResult() {
        ExecutionLimitsProperties props = limits(5, 5, 8000, 10, 30);
        RunQueueGate gate = new RunQueueGate(props);
        RunBudgetFactory factory = new RunBudgetFactory(props, gate);
        FakeCodeRunner fakeRunner = new FakeCodeRunner();

        RunBudget budget = factory.create("req-timeout");
        RunSpec infiniteLoop = new RunSpec(
                "python",
                List.of(new RunFile("main.py", "while True: pass")),
                "main.py",
                "100"
        );

        // First run hits 5000ms timeout (NFR4.1)
        ExecutionResult r1 = budget.execute(() -> fakeRunner.execute(infiniteLoop));
        assertThat(r1.isTimedOut()).isTrue();
        assertThat(r1.getFailureKind()).isEqualTo("timeout");
        assertThat(r1.getExecutionTimeMs()).isEqualTo(5000.0);

        // Second run hits 5000ms timeout -> cumulative 10000ms >= 8000ms aggregate budget
        ExecutionResult r2 = budget.execute(() -> fakeRunner.execute(infiniteLoop));
        assertThat(r2.isTimedOut()).isTrue();

        // Third run is refused by aggregate time budget
        assertThatThrownBy(() -> budget.execute(() -> fakeRunner.execute(infiniteLoop)))
                .isInstanceOf(RunBudgetExceededException.class)
                .satisfies(e -> {
                    RunBudgetExceededException ex = (RunBudgetExceededException) e;
                    assertThat(ex.getLimitKind()).isEqualTo(BudgetLimitKind.AGGREGATE_TIME_BUDGET);
                    assertThat(ex.getCompletedResults()).hasSize(2);
                });
    }

    @Test
    @DisplayName("Budgeted runs use the single RunQueueGate: full queue refuses with QUEUE_FULL without losing prior runs")
    void execute_whenQueueFull_throwsQueueFullAndDoesNotConsumeBudgetOrLosePriorRuns() throws Exception {
        ExecutionLimitsProperties props = limits(1, 7, 20000, 5, 30);
        RunQueueGate gate = new RunQueueGate(props);
        RunBudgetFactory factory = new RunBudgetFactory(props, gate);
        RunBudget budget = factory.create("req-queue");

        // Run 1 succeeds while queue has a slot
        budget.execute(() -> okResult("done-1\n", 10.0));
        assertThat(budget.getRunsCompleted()).isEqualTo(1);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> held = pool.submit(() -> gate.run("other-run", () -> {
                started.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicInteger calls = new AtomicInteger();
            assertThatThrownBy(() -> budget.execute(() -> {
                calls.incrementAndGet();
                return okResult("should-not-run\n", 10.0);
            }))
                    .isInstanceOf(QueueFullException.class)
                    .satisfies(e -> {
                        QueueFullException q = (QueueFullException) e;
                        assertThat(q.getRequestId()).isEqualTo("req-queue");
                        assertThat(q.getMessage()).isEqualTo(QueueFullException.MESSAGE);
                        assertThat(q.getRetryAfterSeconds()).isEqualTo(7);
                    });

            assertThat(calls.get()).isZero();
            assertThat(budget.getRunsCompleted()).isEqualTo(1);
            assertThat(budget.getCompletedResults()).hasSize(1);

            release.countDown();
            held.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Budget is strictly per-request (not global): exhausting one request does not affect another")
    void budgets_areIndependentPerRequest() {
        ExecutionLimitsProperties props = limits(5, 5, 20000, 1, 30);
        RunQueueGate gate = new RunQueueGate(props);
        RunBudgetFactory factory = new RunBudgetFactory(props, gate);

        RunBudget firstRequest = factory.create("req-A");
        firstRequest.execute(() -> okResult("A1\n", 10.0));
        assertThatThrownBy(() -> firstRequest.execute(() -> okResult("A2\n", 10.0)))
                .isInstanceOf(RunBudgetExceededException.class);

        // Separate request gets a fresh budget
        RunBudget secondRequest = factory.create("req-B");
        ExecutionResult b1 = secondRequest.execute(() -> okResult("B1\n", 10.0));
        assertThat(b1.getStdout()).isEqualTo("B1\n");
        assertThat(secondRequest.getRunsCompleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("Factory fails fast at startup when configured limits are below 1")
    void factory_rejectsInvalidLimitsAtStartup() {
        ExecutionLimitsProperties validQueue = limits(2, 5, 20000, 10, 30);
        RunQueueGate gate = new RunQueueGate(validQueue);

        assertThatThrownBy(() -> new RunBudgetFactory(limits(2, 5, 0, 10, 30), gate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aggregate-time-budget-ms");

        assertThatThrownBy(() -> new RunBudgetFactory(limits(2, 5, 20000, 0, 30), gate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-runs-per-request");

        assertThatThrownBy(() -> new RunBudgetFactory(limits(2, 5, 20000, 10, 0), gate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget-retry-after-seconds");
    }
}
