
package com.collabcode.execution.runner;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DockerRunnerTest {

    private static final String IMAGE = "collab-sandbox-python:0.1.0";

    private DockerRunner runner;

    @BeforeAll
    static void requireDockerAndImage() {
        try {
            Process info = new ProcessBuilder("docker", "info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            if (!info.waitFor(10, TimeUnit.SECONDS)) {
                info.destroyForcibly();
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false, "Docker engine is unavailable");
            }

            org.junit.jupiter.api.Assumptions.assumeTrue(
                    info.exitValue() == 0, "Docker engine is unavailable");

            Process image = new ProcessBuilder(
                    "docker", "image", "inspect", IMAGE)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            if (!image.waitFor(10, TimeUnit.SECONDS)) {
                image.destroyForcibly();
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false, "Required Python image is unavailable");
            }

            org.junit.jupiter.api.Assumptions.assumeTrue(
                    image.exitValue() == 0,
                    "Build the project Python runtime image before running integration tests");
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false, "Docker engine is unavailable: " + e.getMessage());
        }
    }

    @BeforeEach
    void setUp() {
        runner = createRunner(15);
    }

    private DockerRunner createRunner(int wallTimeSeconds) {
        ExecutionLimitsProperties limits = new ExecutionLimitsProperties();
        limits.setWallTimeSeconds(wallTimeSeconds);
        limits.setMemoryMb(128);
        limits.setPidsLimit(64);
        limits.setMaxOutputBytes(1024 * 1024);
        limits.setCompileTimeoutSeconds(10);
        limits.setMaxRequestBodyBytes(1024 * 1024);

        return new DockerRunner(limits);
    }

    @Test
    void executesPythonAndCapturesStdout() {
        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py",
                        "print('Hello from DockerRunner')")),
                "main.py",
                "");

        ExecutionResult result = runner.execute(spec);

        assertEquals(0, result.getExitCode());
        assertEquals("Hello from DockerRunner", result.getStdout().trim());
        assertFalse(result.isTimedOut());
        assertFalse(result.isOomKilled());
        assertNull(result.getFailureKind());
    }

    @Test
    void executesMultipleSourceFiles() {
        RunSpec spec = new RunSpec(
                "python",
                List.of(
                        new RunFile("main.py",
                                "from helper import greet\nprint(greet())"),
                        new RunFile("helper.py",
                                "def greet():\n    return 'Multiple files work'")
                ),
                "main.py",
                "");

        ExecutionResult result = runner.execute(spec);

        assertEquals(0, result.getExitCode());
        assertEquals("Multiple files work", result.getStdout().trim());
    }

    @Test
    void passesStdinToPythonProgram() {
        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py",
                        "value = input()\nprint(value.upper())")),
                "main.py",
                "hello docker\n");

        ExecutionResult result = runner.execute(spec);

        assertEquals(0, result.getExitCode());
        assertEquals("HELLO DOCKER", result.getStdout().trim());
    }

    @Test
    void capturesRuntimeError() {
        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py",
                        "raise RuntimeError('intentional test error')")),
                "main.py",
                "");

        ExecutionResult result = runner.execute(spec);

        assertNotEquals(0, result.getExitCode());
        assertTrue(result.getStderr().contains("intentional test error"));
        assertEquals("non_zero_exit", result.getFailureKind());
    }

    @Test
    void stopsExecutionWhenWallTimeLimitIsExceeded() {
        DockerRunner shortTimeoutRunner = createRunner(2);

        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py",
                        "import time\ntime.sleep(10)\nprint('too late')")),
                "main.py",
                "");

        ExecutionResult result = shortTimeoutRunner.execute(spec);

        assertTrue(result.isTimedOut());
        assertEquals("timeout", result.getFailureKind());
        assertEquals("Time limit exceeded", result.getMessage());
        assertNotEquals(0, result.getExitCode());
    }

    @Test
    void removesItsContainerAfterExecution() throws Exception {
        String expectedContainerName = "run-" + UUID.randomUUID();

        DockerRunner testRunner = new DockerRunner(createLimits()) {
            @Override
            protected String createContainerName() {
                return expectedContainerName;
            }
        };

        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py", "print('cleanup test')")),
                "main.py",
                "");

        ExecutionResult result = testRunner.execute(spec);

        assertEquals(0, result.getExitCode());

        Process inspect = new ProcessBuilder(
                "docker", "container", "inspect",
                expectedContainerName)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        assertTrue(inspect.waitFor(10, TimeUnit.SECONDS));
        assertNotEquals(0, inspect.exitValue(),
                "The execution container should be removed after completion");
    }

    private ExecutionLimitsProperties createLimits() {
        ExecutionLimitsProperties limits = new ExecutionLimitsProperties();
        limits.setWallTimeSeconds(15);
        limits.setMemoryMb(128);
        limits.setPidsLimit(64);
        limits.setMaxOutputBytes(1024 * 1024);
        limits.setCompileTimeoutSeconds(10);
        limits.setMaxRequestBodyBytes(1024 * 1024);
        return limits;
    }
}
