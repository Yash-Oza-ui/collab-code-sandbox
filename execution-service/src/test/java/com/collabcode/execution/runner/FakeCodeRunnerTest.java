package com.collabcode.execution.runner;

import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for FakeCodeRunner (updated for T-19 RunSpec, Jay).
 *
 * These tests run entirely offline: no network, no Docker (FR5.1, NFR3).
 * They verify the deterministic behaviours with RunSpec before DockerCodeRunner lands.
 */
class FakeCodeRunnerTest {

    private final FakeCodeRunner runner = new FakeCodeRunner();

    private static RunSpec singleFileSpec(String language, String code, String stdin) {
        String filename = "main.py";
        return new RunSpec(language, List.of(new RunFile(filename, code)), filename, stdin);
    }

    // -------------------------------------------------------------- success

    @Test
    @DisplayName("Normal run returns stdout, empty stderr, exit 0 and null failure_kind")
    void execute_normalRun_success() {
        ExecutionResult result = runner.execute(singleFileSpec("python", "print('hello')", ""));

        assertThat(result.getStdout()).isEqualTo("Hello from python!\n");
        assertThat(result.getStderr()).isEmpty();
        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getFailureKind()).isNull();
        assertThat(result.isTimedOut()).isFalse();
        assertThat(result.isOomKilled()).isFalse();
        assertThat(result.isOutputTruncated()).isFalse();
    }

    @Test
    @DisplayName("All five DR1 languages return a successful result")
    void execute_allLanguages_success() {
        for (String lang : new String[]{"cpp", "python", "c", "java", "javascript"}) {
            ExecutionResult result = runner.execute(singleFileSpec(lang, "// ok", ""));
            assertThat(result.getExitCode())
                    .as("exit code for language %s", lang)
                    .isEqualTo(0);
            assertThat(result.getFailureKind())
                    .as("failure_kind for language %s", lang)
                    .isNull();
        }
    }

    @Test
    @DisplayName("Multi-file spec runs successfully and uses file names")
    void execute_multiFileSpec_success() {
        RunSpec spec = new RunSpec(
                "python",
                List.of(
                        new RunFile("main.py", "from helper import add\nprint(add(1, 2))"),
                        new RunFile("helper.py", "def add(a, b): return a + b")
                ),
                "main.py",
                ""
        );

        ExecutionResult result = runner.execute(spec);
        assertThat(result.getStdout()).isEqualTo("Hello from python!\n");
        assertThat(result.getExitCode()).isEqualTo(0);
    }

    // -------------------------------------------------------------- stdin

    @Test
    @DisplayName("Non-empty stdin is echoed UPPER-CASE to stdout")
    void execute_withStdin_echoesUpperCase() {
        ExecutionResult result = runner.execute(singleFileSpec("python", "s = input().upper()", "abc"));

        assertThat(result.getStdout()).isEqualTo("ABC\n");
        assertThat(result.getStderr()).isEmpty();
        assertThat(result.getExitCode()).isEqualTo(0);
    }

    // -------------------------------------------------------------- runtime failure

    @Test
    @DisplayName("Code containing 'stderr' returns non-zero exit and stderr text")
    void execute_runtimeFailure_nonZeroExitAndStderr() {
        ExecutionResult result = runner.execute(singleFileSpec("python",
                "import sys; sys.stderr.write('boom')", ""));

        assertThat(result.getStderr()).contains("boom");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getFailureKind()).isEqualTo("non_zero_exit");
        assertThat(result.getStdout()).isEmpty();
    }

    @Test
    @DisplayName("Multi-file spec triggers runtime failure if helper file contains stderr")
    void execute_multiFileRuntimeFailure_inHelper() {
        RunSpec spec = new RunSpec(
                "python",
                List.of(
                        new RunFile("main.py", "import helper"),
                        new RunFile("helper.py", "raise Exception('boom stderr failure')")
                ),
                "main.py",
                ""
        );

        ExecutionResult result = runner.execute(spec);
        assertThat(result.getStderr()).contains("boom");
        assertThat(result.getExitCode()).isEqualTo(2);
        assertThat(result.getFailureKind()).isEqualTo("non_zero_exit");
    }

    // -------------------------------------------------------------- compile error

    @Test
    @DisplayName("Code containing 'syntax_error' returns compile_error failure_kind")
    void execute_compileError_failureKindSet() {
        ExecutionResult result = runner.execute(singleFileSpec("python", "def syntax_error(:", ""));

        assertThat(result.getStderr()).isNotEmpty();
        assertThat(result.getFailureKind()).isEqualTo("compile_error");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getStdout()).isEmpty();
    }

    // -------------------------------------------------------------- field completeness

    @Test
    @DisplayName("ExecutionResult never returns null for stdout or stderr")
    void execute_stdoutAndStderrNeverNull() {
        ExecutionResult result = runner.execute(singleFileSpec("java", "class Foo {}", ""));

        assertThat(result.getStdout()).isNotNull();
        assertThat(result.getStderr()).isNotNull();
    }
}
