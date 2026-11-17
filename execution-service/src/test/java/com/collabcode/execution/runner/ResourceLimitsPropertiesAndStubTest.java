package com.collabcode.execution.runner;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests verifying that strict resource limits (US-13, NFR4.1-NFR4.5)
 * are properly wired into configuration and reflected in execution results.
 */
@SpringBootTest
@ActiveProfiles({"stub-runner", "stub-permissions"})
class ResourceLimitsPropertiesAndStubTest {

    @Autowired
    private ExecutionLimitsProperties limits;

    @Autowired
    private CodeRunner codeRunner;

    @Test
    void limitsAreLoadedFromConfiguration() {
        assertNotNull(limits);
        assertEquals(5, limits.getWallTimeSeconds(), "NFR4.1 wall-time limit default is 5s");
        assertEquals(128, limits.getMemoryMb(), "NFR4.2 memory limit default is 128MB");
        assertEquals(64, limits.getPidsLimit(), "NFR4.3 pids limit default is 64");
        assertEquals(1048576, limits.getMaxOutputBytes(), "NFR4.4 output limit default is 1 MiB");
        assertEquals(10, limits.getCompileTimeoutSeconds(), "NFR4.5 compile timeout default is 10s");
    }

    @Test
    void fakeRunnerSimulatesTimeoutWithCorrectContractEnvelope() {
        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py", "while True: pass  # infinite_loop")),
                "main.py",
                ""
        );

        ExecutionResult result = codeRunner.execute(spec);

        assertTrue(result.isTimedOut());
        assertFalse(result.isOomKilled());
        assertEquals("timeout", result.getFailureKind());
        assertEquals("Time limit exceeded", result.getMessage());
        assertEquals(124, result.getExitCode());
    }

    @Test
    void fakeRunnerSimulatesOomWithCorrectContractEnvelope() {
        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py", "# trigger oom")),
                "main.py",
                ""
        );

        ExecutionResult result = codeRunner.execute(spec);

        assertFalse(result.isTimedOut());
        assertTrue(result.isOomKilled());
        assertEquals("non_zero_exit", result.getFailureKind());
        assertEquals("Memory limit exceeded", result.getMessage());
        assertEquals(137, result.getExitCode());
    }

    @Test
    void fakeRunnerSimulatesOutputFloodTruncation() {
        RunSpec spec = new RunSpec(
                "python",
                List.of(new RunFile("main.py", "# output_flood")),
                "main.py",
                ""
        );

        ExecutionResult result = codeRunner.execute(spec);

        assertTrue(result.isOutputTruncated());
        assertNull(result.getFailureKind());
        assertEquals(0, result.getExitCode());
    }
}
