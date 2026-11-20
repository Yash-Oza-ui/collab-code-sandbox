package com.collabcode.execution.runner;

import com.collabcode.execution.budget.RunBudget;
import com.collabcode.execution.config.ExecutionLanguageProperties;
import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.BatchRunSpec;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.queue.RunQueueGate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance criteria tests for US-71 (Run a batch of generated tests in one container - FR38.4, FR5.1, NFR4.1, NFR4.2):
 * 1. A batch of 10 tests starts exactly ONE container (FR5.1) and leaves no container behind.
 * 2. An infinite-loop test times out at 5 s and remaining tests still report results (NFR4.1).
 * 3. A memory-hog test is reported as out-of-memory killed for that test only, and remaining tests still run (NFR4.2).
 * 4. Network probe fails inside the batch under sandbox isolation (--network none, NFR3.1).
 * 5. Container and ephemeral workspace are destroyed in finally path after batch completes.
 *
 * Docker-based tests are skipped if Docker engine or the Python runtime image is not present on the test host.
 */
class DockerRunnerBatchTest {

    private static final String IMAGE = "collab-sandbox-python:0.2.0";

    @BeforeAll
    static void requireDockerAndImage() {
        Assumptions.assumeTrue(
                dockerSucceeds(10, "info"),
                "Docker engine is unavailable");
        Assumptions.assumeTrue(
                dockerSucceeds(10, "image", "inspect", IMAGE),
                "Build the project Python runtime image before running integration tests");
    }

    private static String uniquePrefix() {
        return "us71-batch-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    }

    private static DockerRunner runnerWithPrefix(String prefix, Set<String> names, int wallTimeSeconds, int memoryMb) {
        ExecutionLimitsProperties limits = new ExecutionLimitsProperties();
        limits.setWallTimeSeconds(wallTimeSeconds);
        limits.setMemoryMb(memoryMb);
        limits.setPidsLimit(64);
        limits.setMaxOutputBytes(1024 * 1024);
        limits.setCompileTimeoutSeconds(10);
        limits.setMaxRequestBodyBytes(1024 * 1024);

        return new DockerRunner(limits, new ExecutionLanguageProperties()) {
            @Override
            protected String createContainerName() {
                String name = prefix + UUID.randomUUID();
                names.add(name);
                return name;
            }
        };
    }

    private static RunBudget createBudget(int maxRuns, long budgetMs) {
        ExecutionLimitsProperties limits = new ExecutionLimitsProperties();
        limits.setMaxQueueLength(10);
        limits.setQueueRetryAfterSeconds(5);
        RunQueueGate gate = new RunQueueGate(limits);
        return new RunBudget("req-us71-" + UUID.randomUUID(), maxRuns, budgetMs, 30, gate);
    }

    private static List<String> listContainers(String prefix, boolean includeStopped) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("docker", "ps"));
        if (includeStopped) {
            command.add("-a");
        }
        command.addAll(List.of("--filter", "name=" + prefix, "--format", "{{.Names}}"));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        process.getInputStream().transferTo(out);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "docker ps did not finish");
        assertEquals(0, process.exitValue(), "docker ps failed: " + out.toString(StandardCharsets.UTF_8));

        List<String> names = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) {
                names.add(line.trim());
            }
        }
        return names;
    }

    private static boolean dockerSucceeds(long timeoutSeconds, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("AC1: A batch of 10 tests starts exactly ONE container and leaves no leftover container")
    void batchOf10Tests_startsExactlyOneContainer_andLeavesNoneBehind() throws Exception {
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 5, 128);

        List<String> inputs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            inputs.add(String.valueOf(i));
        }

        String code = "print('ECHO:' + input().strip())\n";
        BatchRunSpec spec = new BatchRunSpec(
                "python",
                List.of(new RunFile("main.py", code)),
                "main.py",
                inputs
        );

        RunBudget budget = createBudget(20, 50000);
        List<ExecutionResult> results = runner.executeBatch(spec, budget);

        assertEquals(10, results.size());
        for (int i = 0; i < 10; i++) {
            ExecutionResult res = results.get(i);
            assertEquals(0, res.getExitCode());
            assertEquals("ECHO:" + (i + 1), res.getStdout().trim());
            assertFalse(res.isTimedOut());
            assertFalse(res.isOomKilled());
        }

        assertEquals(1, names.size(), "A batch of 10 tests must start exactly ONE container (FR5.1)");
        assertTrue(listContainers(prefix, true).isEmpty(), "No leftover container after batch execution");
    }

    @Test
    @DisplayName("AC2: An infinite loop test times out at 5 s and remaining tests still report results")
    void infiniteLoopTest_timesOutAt5s_remainingTestsStillRun() throws Exception {
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 2, 128);

        String code = """
                val = input().strip()
                if val == 'loop':
                    while True:
                        pass
                print('DONE:' + val)
                """;

        BatchRunSpec spec = new BatchRunSpec(
                "python",
                List.of(new RunFile("main.py", code)),
                "main.py",
                List.of("alpha", "loop", "beta")
        );

        RunBudget budget = createBudget(10, 50000);
        List<ExecutionResult> results = runner.executeBatch(spec, budget);

        assertEquals(3, results.size());

        // Test 1: pass
        assertEquals(0, results.get(0).getExitCode());
        assertEquals("DONE:alpha", results.get(0).getStdout().trim());
        assertFalse(results.get(0).isTimedOut());

        // Test 2: timed out at configured wall time
        assertTrue(results.get(1).isTimedOut(), "Expected test 2 to time out");
        assertEquals(124, results.get(1).getExitCode());
        assertEquals("Time limit exceeded", results.get(1).getMessage());

        // Test 3: still ran in the same container and reported result
        assertEquals(0, results.get(2).getExitCode());
        assertEquals("DONE:beta", results.get(2).getStdout().trim());
        assertFalse(results.get(2).isTimedOut());

        assertEquals(1, names.size(), "Batch must execute in exactly one container");
        assertTrue(listContainers(prefix, true).isEmpty(), "No container left behind");
    }

    @Test
    @DisplayName("AC3: A memory-hog test reports OOM for that test only, remaining tests still run")
    void memoryHogTest_reportsOomForThatTestOnly_remainingTestsStillRun() throws Exception {
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 10, 32);

        String code = """
                val = input().strip()
                if val == 'oom':
                    data = []
                    while True:
                        data.append(bytearray(10 * 1024 * 1024))
                print('DONE:' + val)
                """;

        BatchRunSpec spec = new BatchRunSpec(
                "python",
                List.of(new RunFile("main.py", code)),
                "main.py",
                List.of("before", "oom", "after")
        );

        RunBudget budget = createBudget(10, 50000);
        List<ExecutionResult> results = runner.executeBatch(spec, budget);

        assertEquals(3, results.size());

        // Test 1: pass
        assertEquals(0, results.get(0).getExitCode());
        assertEquals("DONE:before", results.get(0).getStdout().trim());

        // Test 2: killed by OOM
        assertTrue(results.get(1).isOomKilled(), "Expected test 2 to be OOM killed");
        assertEquals(137, results.get(1).getExitCode());
        assertEquals("Memory limit exceeded", results.get(1).getMessage());

        // Test 3: container survived and executed remaining test
        assertEquals(0, results.get(2).getExitCode());
        assertEquals("DONE:after", results.get(2).getStdout().trim());

        assertEquals(1, names.size(), "Batch must execute in exactly one container");
        assertTrue(listContainers(prefix, true).isEmpty(), "No container left behind");
    }

    @Test
    @DisplayName("AC6: Network probe inside batch fails and container is destroyed afterwards")
    void networkProbe_insideBatchFails_andContainerDestroyed() throws Exception {
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 5, 128);

        String code = """
                import socket, sys
                try:
                    socket.create_connection(('8.8.8.8', 53), timeout=2).close()
                    print('NETWORK REACHABLE')
                    sys.exit(2)
                except OSError:
                    print('NETWORK BLOCKED')
                    sys.exit(0)
                """;

        BatchRunSpec spec = new BatchRunSpec(
                "python",
                List.of(new RunFile("main.py", code)),
                "main.py",
                List.of("1")
        );

        RunBudget budget = createBudget(10, 50000);
        List<ExecutionResult> results = runner.executeBatch(spec, budget);

        assertEquals(1, results.size());
        assertEquals(0, results.get(0).getExitCode());
        assertEquals("NETWORK BLOCKED", results.get(0).getStdout().trim());
        assertFalse(results.get(0).getStdout().contains("REACHABLE"));

        assertEquals(1, names.size());
        assertTrue(listContainers(prefix, true).isEmpty(), "Container destroyed after network probe test");
    }
}
