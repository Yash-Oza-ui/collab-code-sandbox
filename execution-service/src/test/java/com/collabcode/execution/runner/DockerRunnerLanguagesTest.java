package com.collabcode.execution.runner;

import com.collabcode.execution.config.ExecutionLanguageProperties;
import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for multi-language runner configuration and execution logic (DR1, US-14).
 * Verifies all 5 coursework languages in priority order: C++, Python, C, Java, JavaScript.
 */
class DockerRunnerLanguagesTest {

    private ExecutionLimitsProperties limits;
    private ExecutionLanguageProperties languageProperties;
    private DockerRunner runner;

    @BeforeEach
    void setUp() {
        limits = new ExecutionLimitsProperties();
        limits.setWallTimeSeconds(5);
        limits.setMemoryMb(128);
        limits.setPidsLimit(64);
        limits.setMaxOutputBytes(1024 * 1024);
        limits.setCompileTimeoutSeconds(10);
        limits.setMaxRequestBodyBytes(1024 * 1024);

        languageProperties = new ExecutionLanguageProperties();
        runner = new DockerRunner(limits, languageProperties);
    }

    @Test
    @DisplayName("All five DR1 languages are mapped to their pinned runtime images")
    void allFiveLanguagesMappedToPinnedImages() {
        Map<String, String> images = languageProperties.getImages();

        assertEquals("collab-sandbox-cpp:0.1.0", images.get("cpp"));
        assertEquals("collab-sandbox-python:0.2.0", images.get("python"));
        assertEquals("collab-sandbox-c:0.1.0", images.get("c"));
        assertEquals("collab-sandbox-java:0.1.0", images.get("java"));
        assertEquals("collab-sandbox-javascript:0.1.0", images.get("javascript"));

        assertTrue(languageProperties.isSupportedLanguage("cpp"));
        assertTrue(languageProperties.isSupportedLanguage("python"));
        assertTrue(languageProperties.isSupportedLanguage("c"));
        assertTrue(languageProperties.isSupportedLanguage("java"));
        assertTrue(languageProperties.isSupportedLanguage("javascript"));
    }

    @Test
    @DisplayName("Compiled vs interpreted languages are properly identified (NFR4.5)")
    void compiledVsInterpretedIdentification() {
        assertTrue(ExecutionLanguageProperties.isCompiledLanguage("cpp"));
        assertTrue(ExecutionLanguageProperties.isCompiledLanguage("c"));
        assertTrue(ExecutionLanguageProperties.isCompiledLanguage("java"));

        assertFalse(ExecutionLanguageProperties.isCompiledLanguage("python"));
        assertFalse(ExecutionLanguageProperties.isCompiledLanguage("javascript"));
        assertFalse(ExecutionLanguageProperties.isCompiledLanguage(null));
        assertFalse(ExecutionLanguageProperties.isCompiledLanguage("unknown"));
    }

    @Test
    @DisplayName("Unsupported language is rejected before container execution")
    void unsupportedLanguageRejected() {
        RunSpec spec = new RunSpec(
                "ruby",
                List.of(new RunFile("main.rb", "puts 1")),
                "main.rb",
                ""
        );

        ExecutionResult result = runner.execute(spec);

        assertEquals(2, result.getExitCode());
        assertEquals("non_zero_exit", result.getFailureKind());
        assertTrue(result.getStderr().contains("Unsupported language"));
    }

    @Test
    @DisplayName("Custom image overrides can be configured via ExecutionLanguageProperties (DR3)")
    void imageOverridesSupported() {
        languageProperties.setImages(Map.of("cpp", "custom-registry/cpp:1.0.0"));

        assertEquals("custom-registry/cpp:1.0.0", languageProperties.getImageForLanguage("cpp"));
        assertEquals("collab-sandbox-python:0.2.0", languageProperties.getImageForLanguage("python"));
    }

    @Test
    @DisplayName("Entry file not present in files list is rejected with validation error")
    void missingEntryFileRejected() {
        RunSpec spec = new RunSpec(
                "cpp",
                List.of(new RunFile("helper.cpp", "// helper")),
                "main.cpp",
                ""
        );

        ExecutionResult result = runner.execute(spec);

        assertEquals(2, result.getExitCode());
        assertTrue(result.getStderr().contains("Entry file was not included"));
    }

    @Test
    @DisplayName("Compilation timeout returns failure_kind 'timeout' and timed_out true")
    void compileStageTimeoutReportsTimeoutFailureKind() {
        DockerRunner timeoutRunner = new DockerRunner(limits, languageProperties) {
            @Override
            protected Process startCompileProcess(List<String> dockerArgs) {
                return new FakeProcess(false, 137, "compile timed out");
            }

            @Override
            protected void stopContainer(String containerName) {
                // No-op in test
            }
        };

        RunSpec spec = new RunSpec(
                "cpp",
                List.of(new RunFile("main.cpp", "int main() { return 0; }")),
                "main.cpp",
                ""
        );

        ExecutionResult result = timeoutRunner.execute(spec);

        assertTrue(result.isTimedOut(), "timed_out must be true on compilation timeout");
        assertEquals("timeout", result.getFailureKind(), "failure_kind must be 'timeout' when compile stage times out");
        assertEquals("Time limit exceeded", result.getMessage());
        assertEquals(124, result.getExitCode(), "exit_code must be 124 on timeout");
        assertNull(result.getFailingModule(), "failing_module must be null on timeout");
    }

    @Test
    @DisplayName("Compiler exiting non-zero returns failure_kind 'compile_error' and timed_out false")
    void compilerExitsNonZeroReportsCompileErrorFailureKind() {
        String compilerStderr = "/sandbox/code/main.cpp:1:1: error: expected unqualified-id\n";
        DockerRunner errorRunner = new DockerRunner(limits, languageProperties) {
            @Override
            protected Process startCompileProcess(List<String> dockerArgs) {
                return new FakeProcess(true, 1, compilerStderr);
            }

            @Override
            protected void stopContainer(String containerName) {
                // No-op in test
            }
        };

        RunSpec spec = new RunSpec(
                "cpp",
                List.of(new RunFile("main.cpp", "invalid c++ code")),
                "main.cpp",
                ""
        );

        ExecutionResult result = errorRunner.execute(spec);

        assertFalse(result.isTimedOut(), "timed_out must be false on compiler syntax error");
        assertEquals("compile_error", result.getFailureKind(), "failure_kind must be 'compile_error' when compiler exits non-zero");
        assertEquals("Compilation failed.", result.getMessage());
        assertEquals(1, result.getExitCode());
        assertEquals("main.cpp", result.getFailingModule());
        assertTrue(result.getStderr().contains("error: expected unqualified-id"));
    }

    private static class FakeProcess extends Process {
        private final boolean finishes;
        private final int exitCode;
        private final String stderrText;

        FakeProcess(boolean finishes, int exitCode, String stderrText) {
            this.finishes = finishes;
            this.exitCode = exitCode;
            this.stderrText = stderrText;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(stderrText.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return finishes;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return !finishes;
        }
    }
}
