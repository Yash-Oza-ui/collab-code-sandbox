package com.collabcode.execution.runner;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests verifying compiler flags and configurable memory limits for sanitizer runs (US-73, DR3, DR5).
 */
class DockerRunnerSanitizerTest {

    @Test
    @DisplayName("C++ sanitizer compile command includes -fsanitize=address,undefined -no-pie -g -O1 (US-73)")
    void buildCompileCommand_cppSanitizerFlags() {
        ExecutionLimitsProperties limits = new ExecutionLimitsProperties();
        limits.setSanitizerMemoryMb(512);
        limits.setMemoryMb(128);
        limits.setPidsLimit(64);

        DockerRunner runner = new DockerRunner(limits);

        RunSpec normalSpec = new RunSpec(
                "cpp",
                List.of(new RunFile("main.cpp", "int main() { return 0; }")),
                "main.cpp",
                ""
        );
        List<String> normalCmd = runner.buildCompileCommand("cpp", normalSpec);
        assertTrue(normalCmd.contains("-O2"));
        assertFalse(normalCmd.contains("-fsanitize=address,undefined"));

        RunSpec sanitizerSpec = new RunSpec(
                "cpp",
                List.of(new RunFile("main.cpp", "int main() { return 0; }")),
                "main.cpp",
                "",
                true,
                List.of("address", "undefined")
        );
        List<String> sanCmd = runner.buildCompileCommand("cpp", sanitizerSpec);
        assertTrue(sanCmd.contains("g++"));
        assertTrue(sanCmd.contains("-fsanitize=address,undefined"), "Must compile with ASan and UBSan");
        assertTrue(sanCmd.contains("-no-pie"));
        assertTrue(sanCmd.contains("-g"));
        assertTrue(sanCmd.contains("-O1"));
        assertFalse(sanCmd.contains("-O2"));
    }

    @Test
    @DisplayName("C sanitizer compile command includes -fsanitize=address,undefined -no-pie -g -O1 (US-73)")
    void buildCompileCommand_cSanitizerFlags() {
        ExecutionLimitsProperties limits = new ExecutionLimitsProperties();
        limits.setSanitizerMemoryMb(512);
        limits.setMemoryMb(128);
        limits.setPidsLimit(64);

        DockerRunner runner = new DockerRunner(limits);

        RunSpec sanitizerSpec = new RunSpec(
                "c",
                List.of(new RunFile("main.c", "int main(void) { return 0; }")),
                "main.c",
                "",
                true,
                List.of("address", "undefined")
        );
        List<String> sanCmd = runner.buildCompileCommand("c", sanitizerSpec);
        assertTrue(sanCmd.contains("gcc"));
        assertTrue(sanCmd.contains("-fsanitize=address,undefined"), "Must compile with ASan and UBSan");
        assertTrue(sanCmd.contains("-no-pie"));
        assertTrue(sanCmd.contains("-g"));
        assertTrue(sanCmd.contains("-O1"));
        assertFalse(sanCmd.contains("-O2"));
    }

    @Test
    @DisplayName("Sanitizer run container applies configured sanitizer-memory-mb (DR3, DR5, US-73)")
    void buildIsolatedContainerProcess_appliesSanitizerMemoryLimit() {
        ExecutionLimitsProperties limits = new ExecutionLimitsProperties();
        limits.setMemoryMb(128);
        limits.setSanitizerMemoryMb(512);
        limits.setPidsLimit(64);

        DockerRunner runner = new DockerRunner(limits);
        Path ws = Path.of("/tmp/test-ws");
        Path metrics = Path.of("/tmp/test-metrics");
        Path build = Path.of("/tmp/test-build");

        // Normal run container
        ProcessBuilder pbNormal = runner.buildIsolatedContainerProcess(
                "test-normal",
                ws,
                metrics,
                build,
                "collab-sandbox-cpp:0.1.0",
                "cpp",
                List.of("/sandbox/build/a.out"),
                false
        );
        List<String> normalCmd = pbNormal.command();
        int memIdxNormal = normalCmd.indexOf("--memory");
        assertEquals("128m", normalCmd.get(memIdxNormal + 1));

        // Sanitizer run container
        ProcessBuilder pbSan = runner.buildIsolatedContainerProcess(
                "test-san",
                ws,
                metrics,
                build,
                "collab-sandbox-cpp:0.1.0",
                "cpp",
                List.of("/sandbox/build/a.out"),
                true
        );
        List<String> sanCmd = pbSan.command();
        int memIdxSan = sanCmd.indexOf("--memory");
        assertEquals("512m", sanCmd.get(memIdxSan + 1), "Sanitizer container must use configured sanitizerMemoryMb");

        // Configurable limit override check
        limits.setSanitizerMemoryMb(256);
        ProcessBuilder pbCustom = runner.buildIsolatedContainerProcess(
                "test-custom",
                ws,
                metrics,
                build,
                "collab-sandbox-cpp:0.1.0",
                "cpp",
                List.of("/sandbox/build/a.out"),
                true
        );
        List<String> customCmd = pbCustom.command();
        int memIdxCustom = customCmd.indexOf("--memory");
        assertEquals("256m", customCmd.get(memIdxCustom + 1));
    }
}
