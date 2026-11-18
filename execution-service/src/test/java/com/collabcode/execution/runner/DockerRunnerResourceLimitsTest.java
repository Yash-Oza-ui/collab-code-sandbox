package com.collabcode.execution.runner;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance criteria tests for US-13 (Strict Resource Limits - NFR4.1 to NFR4.5):
 * 1. Infinite loop -> wall-clock timeout (timed_out: true, failure_kind: "timeout", message: "Time limit exceeded")
 * 2. Memory hog -> memory limit exceeded (oom_killed: true, failure_kind: "non_zero_exit", message: "Memory limit exceeded")
 * 3. Fork bomb -> contained by pids-limit, does not hang host or crash runner
 * 4. Output flood -> output truncated (output_truncated: true)
 * 5. Limits configurable from configuration (behavior changes based on configured limit)
 *
 * Docker-based tests are skipped if Docker or the runtime image is not present on the test host.
 */
class DockerRunnerResourceLimitsTest {

    private static final String IMAGE = "collab-sandbox-python:0.1.0";

    @BeforeAll
    static void requireDockerAndImage() throws Exception {
        Assumptions.assumeTrue(
                dockerSucceeds(10, "info"),
                "Docker engine is unavailable");
        Assumptions.assumeTrue(
                dockerSucceeds(10, "image", "inspect", IMAGE),
                "Build the project Python runtime image before running integration tests");
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

    private DockerRunner createRunnerWithLimits(int wallTimeSeconds, int memoryMb, int pidsLimit, long maxOutputBytes) {
        ExecutionLimitsProperties limits = new ExecutionLimitsProperties();
        limits.setWallTimeSeconds(wallTimeSeconds);
        limits.setMemoryMb(memoryMb);
        limits.setPidsLimit(pidsLimit);
        limits.setMaxOutputBytes(maxOutputBytes);
        limits.setCompileTimeoutSeconds(10);
        limits.setMaxRequestBodyBytes(1024 * 1024);
        return new DockerRunner(limits);
    }

    @Test
    void infiniteLoop_hitsWallClockLimit_returnsTimeoutResult() {
        // NFR4.1: Wall-clock time limit -> timed_out: true, failure_kind: "timeout", message: "Time limit exceeded"
        DockerRunner runner = createRunnerWithLimits(2, 128, 64, 1024 * 1024);
        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py", "while True:\n    pass\n")),
                "main.py",
                ""
        );

        ExecutionResult result = runner.execute(spec);

        assertTrue(result.isTimedOut(), "Expected timed_out to be true");
        assertFalse(result.isOomKilled(), "Expected oom_killed to be false");
        assertEquals("timeout", result.getFailureKind());
        assertEquals("Time limit exceeded", result.getMessage());
        assertNotEquals(0, result.getExitCode());
    }

    @Test
    void memoryHog_exceedsMemoryLimit_returnsOomKilledResult() {
        // NFR4.2: Memory limit with swap disabled -> oom_killed: true, failure_kind: "non_zero_exit", message: "Memory limit exceeded"
        DockerRunner runner = createRunnerWithLimits(10, 32, 64, 1024 * 1024);
        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py",
                        "data = []\n"
                                + "while True:\n"
                                + "    data.append(bytearray(10 * 1024 * 1024))\n")),
                "main.py",
                ""
        );

        ExecutionResult result = runner.execute(spec);

        assertTrue(result.isOomKilled(), "Expected oom_killed to be true");
        assertFalse(result.isTimedOut(), "Expected timed_out to be false");
        assertEquals("non_zero_exit", result.getFailureKind());
        assertEquals("Memory limit exceeded", result.getMessage());
        assertEquals(137, result.getExitCode());
    }

    @Test
    void forkBomb_isContainedByPidsLimit() {
        // NFR4.3: Process limit caps fork bombs, container halts or fails without escaping or hanging
        DockerRunner runner = createRunnerWithLimits(5, 64, 16, 1024 * 1024);
        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py",
                        "import os\n"
                                + "try:\n"
                                + "    while True:\n"
                                + "        os.fork()\n"
                                + "except OSError:\n"
                                + "    print('FORK_BLOCKED')\n")),
                "main.py",
                ""
        );

        ExecutionResult result = runner.execute(spec);

        // It either catches the BlockingIOError / OSError when pids limit is reached, or is terminated
        assertTrue(
                result.getExitCode() != 0 || result.getStdout().contains("FORK_BLOCKED"),
                "Fork bomb should be contained and not run indefinitely");
    }

    @Test
    void outputFlood_exceedsMaxOutput_isTruncated() {
        // NFR4.4: Output is capped at maxOutputBytes, output_truncated: true
        long cap = 4096; // 4 KB cap
        DockerRunner runner = createRunnerWithLimits(10, 128, 64, cap);
        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py",
                        "print('X' * 100000)\n")),
                "main.py",
                ""
        );

        ExecutionResult result = runner.execute(spec);

        assertTrue(result.isOutputTruncated(), "Expected output_truncated to be true");
        assertTrue(result.getStdout().length() <= 100000);
        assertTrue(result.getStdout().length() >= cap);
    }

    @Test
    void configurationDrivesLimits_timeoutCanBeExtended() {
        // Changing configuration changes behavior without code modification (DR3)
        DockerRunner fastRunner = createRunnerWithLimits(1, 128, 64, 1024 * 1024);
        DockerRunner slowRunner = createRunnerWithLimits(6, 128, 64, 1024 * 1024);

        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py",
                        "import time\n"
                                + "time.sleep(2)\n"
                                + "print('success')\n")),
                "main.py",
                ""
        );

        ExecutionResult fastResult = fastRunner.execute(spec);
        assertTrue(fastResult.isTimedOut(), "Fast runner should time out");

        ExecutionResult slowResult = slowRunner.execute(spec);
        assertFalse(slowResult.isTimedOut(), "Slow runner should not time out");
        assertEquals(0, slowResult.getExitCode());
        assertEquals("success", slowResult.getStdout().trim());
    }
}
