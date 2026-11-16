package com.collabcode.execution.runner;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-09 verification tests (FR5.1, FR5.2): every run gets its own new Docker
 * container, the container and its workspace are destroyed afterwards, and no
 * run state is reused.
 *
 * These tests drive the real {@link DockerRunner}. They are skipped (not
 * failed) when the Docker engine or the project Python image is missing, in
 * the same way as {@link DockerRunnerTest}.
 *
 * Each test gives its runner a unique container-name prefix, so it only counts
 * its own containers. Other containers on the machine do not matter.
 */
class DockerRunnerIsolationTest {

    private static final String IMAGE = "collab-sandbox-python:0.1.0";
    private static final Path WORKSPACE_ROOT = Path.of("/tmp/collab-code-workspaces");

    @BeforeAll
    static void requireDockerAndImage() throws Exception {
        Assumptions.assumeTrue(
                dockerSucceeds(10, "info"),
                "Docker engine is unavailable");
        Assumptions.assumeTrue(
                dockerSucceeds(10, "image", "inspect", IMAGE),
                "Build the project Python runtime image before running integration tests");
    }

    // ---------------------------------------------------------------------
    // No state reuse
    // ---------------------------------------------------------------------

    @Test
    void secondRunCannotSeeFileWrittenByFirstRun() throws Exception {
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 15);

        ExecutionResult first = runner.execute(python(
                "with open('/tmp/marker.txt', 'w') as f:\n"
                        + "    f.write('run1')\n"
                        + "print('written')\n"));

        ExecutionResult second = runner.execute(python(
                "import os\n"
                        + "print('FOUND' if os.path.exists('/tmp/marker.txt') else 'NOT FOUND')\n"));

        assertEquals(0, first.getExitCode());
        assertEquals("written", first.getStdout().trim());
        assertEquals(0, second.getExitCode());
        assertEquals("NOT FOUND", second.getStdout().trim(),
                "A new run must not see files written by an earlier run");

        assertEquals(2, names.size(), "Two runs must use two different containers");
        assertTrue(listContainers(prefix, true).isEmpty(),
                "No container may be left behind");
    }

    // ---------------------------------------------------------------------
    // Cleanup after success and after every kind of failure
    // ---------------------------------------------------------------------

    @Test
    void containerIsRemovedAfterSuccess() throws Exception {
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 15);

        ExecutionResult result = runner.execute(python("print('ok')\n"));

        assertEquals(0, result.getExitCode());
        assertNull(result.getFailureKind());
        assertEquals(1, names.size());
        assertTrue(listContainers(prefix, true).isEmpty(),
                "Container must be removed after a successful run");
    }

    @Test
    void containerIsRemovedAfterCrash() throws Exception {
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 15);

        ExecutionResult result = runner.execute(python(
                "raise RuntimeError('intentional crash')\n"));

        assertNotEquals(0, result.getExitCode());
        assertEquals("non_zero_exit", result.getFailureKind());
        assertTrue(result.getStderr().contains("intentional crash"));
        assertEquals(1, names.size());
        assertTrue(listContainers(prefix, true).isEmpty(),
                "Container must be removed after a crash");
    }

    @Test
    void containerIsRemovedAfterTimeout() throws Exception {
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 2);

        ExecutionResult result = runner.execute(python(
                "while True:\n"
                        + "    pass\n"));

        assertTrue(result.isTimedOut());
        assertEquals("timeout", result.getFailureKind());
        assertEquals(1, names.size());
        assertTrue(listContainers(prefix, true).isEmpty(),
                "Container must be removed after the run is stopped for timeout");
    }

    @Test
    void containerIsRemovedAfterOutOfMemoryKill() throws Exception {
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 15);

        // Fills about 400 MB, well above the 128 MB container limit.
        ExecutionResult result = runner.execute(python(
                "data = b'x' * (400 * 1024 * 1024)\n"
                        + "print(len(data))\n"));

        assertNotEquals(0, result.getExitCode(), "The run must not succeed");
        assertEquals(1, names.size());
        assertTrue(listContainers(prefix, true).isEmpty(),
                "Container must be removed after an out-of-memory kill");
    }

    // ---------------------------------------------------------------------
    // Parallel runs
    // ---------------------------------------------------------------------

    @Test
    void fiveParallelRunsUseFiveDistinctContainersAndLeaveNoneBehind() throws Exception {
        final int runs = 5;
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 25);

        ExecutorService pool = Executors.newFixedThreadPool(runs);
        try {
            List<Future<ExecutionResult>> futures = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                futures.add(pool.submit(() -> runner.execute(python(
                        "import time\n"
                                + "time.sleep(8)\n"
                                + "print('done')\n"))));
            }

            // While the runs are alive, five distinct containers must show up.
            Set<String> seenRunning = new HashSet<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && seenRunning.size() < runs) {
                seenRunning.addAll(listContainers(prefix, false));
                Thread.sleep(250);
            }
            assertEquals(runs, seenRunning.size(),
                    "Expected " + runs + " distinct running containers, saw " + seenRunning);

            for (Future<ExecutionResult> future : futures) {
                ExecutionResult result = future.get(60, TimeUnit.SECONDS);
                assertEquals(0, result.getExitCode());
                assertEquals("done", result.getStdout().trim());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(runs, names.size(), "Each run must have its own container name");
        assertTrue(listContainers(prefix, true).isEmpty(),
                "No container may be left after all parallel runs finish");
    }

    // ---------------------------------------------------------------------
    // Network and workspace
    // ---------------------------------------------------------------------

    @Test
    void outsideNetworkConnectionFails() throws Exception {
        String prefix = uniquePrefix();
        Set<String> names = ConcurrentHashMap.newKeySet();
        DockerRunner runner = runnerWithPrefix(prefix, names, 15);

        ExecutionResult result = runner.execute(python(
                "import socket, sys\n"
                        + "try:\n"
                        + "    socket.create_connection(('8.8.8.8', 53), timeout=3).close()\n"
                        + "    print('NETWORK REACHABLE')\n"
                        + "    sys.exit(2)\n"
                        + "except OSError:\n"
                        + "    print('NETWORK BLOCKED')\n"
                        + "    sys.exit(1)\n"));

        assertEquals("NETWORK BLOCKED", result.getStdout().trim());
        assertEquals(1, result.getExitCode());
        assertFalse(result.getStdout().contains("REACHABLE"));
        assertTrue(listContainers(prefix, true).isEmpty());
    }

    @Test
    void workspaceDirectoryIsRemovedAfterRun() throws Exception {
        Set<String> before = workspaceEntries();

        DockerRunner runner = runnerWithPrefix(uniquePrefix(), ConcurrentHashMap.newKeySet(), 15);
        ExecutionResult result = runner.execute(python("print('workspace test')\n"));
        assertEquals(0, result.getExitCode());

        Set<String> leftBehind = workspaceEntries();
        leftBehind.removeAll(before);
        assertTrue(leftBehind.isEmpty(),
                "Run workspace must be destroyed after execution, left: " + leftBehind);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static RunSpec python(String code) {
        return new RunSpec("python", List.of(new RunFile("main.py", code)), "main.py", "");
    }

    private static String uniquePrefix() {
        return "us09-iso-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    }

    /** A runner whose container names start with {@code prefix}; every name is recorded. */
    private static DockerRunner runnerWithPrefix(String prefix, Set<String> names, int wallTimeSeconds) {
        ExecutionLimitsProperties limits = new ExecutionLimitsProperties();
        limits.setWallTimeSeconds(wallTimeSeconds);
        limits.setMemoryMb(128);
        limits.setPidsLimit(64);
        limits.setMaxOutputBytes(1024 * 1024);
        limits.setMaxRequestBodyBytes(1024 * 1024);

        return new DockerRunner(limits) {
            @Override
            protected String createContainerName() {
                String name = prefix + UUID.randomUUID();
                names.add(name);
                return name;
            }
        };
    }

    /** Names of containers whose name contains {@code prefix}; {@code includeStopped} adds exited ones. */
    private static List<String> listContainers(String prefix, boolean includeStopped)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("docker", "ps"));
        if (includeStopped) {
            command.add("-a");
        }
        command.addAll(List.of("--filter", "name=" + prefix, "--format", "{{.Names}}"));

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
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

    private static Set<String> workspaceEntries() throws IOException {
        Set<String> entries = new HashSet<>();
        if (Files.isDirectory(WORKSPACE_ROOT)) {
            try (Stream<Path> children = Files.list(WORKSPACE_ROOT)) {
                children.forEach(p -> entries.add(p.getFileName().toString()));
            }
        }
        return entries;
    }

    private static boolean dockerSucceeds(long timeoutSeconds, String... args) throws Exception {
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
    }
}
