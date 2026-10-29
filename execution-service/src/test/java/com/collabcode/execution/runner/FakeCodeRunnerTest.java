package com.collabcode.execution.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for FakeCodeRunner.
 *
 * These tests run entirely offline: no network, no Docker (FR5.1, NFR3).
 * They verify the four deterministic behaviours agreed with Quincy so that
 * the CodeRunner interface is stable before DockerCodeRunner lands.
 */
class FakeCodeRunnerTest {

    private final FakeCodeRunner runner = new FakeCodeRunner();

    // -------------------------------------------------------------- success

    @Test
    @DisplayName("Normal run returns stdout, empty stderr, exit 0 and null failure_kind")
    void execute_normalRun_success() {
        ExecutionResult result = runner.execute("python", "print('hello')", "");

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
            ExecutionResult result = runner.execute(lang, "// ok", "");
            assertThat(result.getExitCode())
                    .as("exit code for language %s", lang)
                    .isEqualTo(0);
            assertThat(result.getFailureKind())
                    .as("failure_kind for language %s", lang)
                    .isNull();
        }
    }

    // -------------------------------------------------------------- stdin

    @Test
    @DisplayName("Non-empty stdin is echoed UPPER-CASE to stdout")
    void execute_withStdin_echoesUpperCase() {
        ExecutionResult result = runner.execute("python", "s = input().upper()", "abc");

        assertThat(result.getStdout()).isEqualTo("ABC\n");
        assertThat(result.getStderr()).isEmpty();
        assertThat(result.getExitCode()).isEqualTo(0);
    }

    // -------------------------------------------------------------- runtime failure

    @Test
    @DisplayName("Code containing 'stderr' returns non-zero exit and stderr text")
    void execute_runtimeFailure_nonZeroExitAndStderr() {
        ExecutionResult result = runner.execute("python",
                "import sys; sys.stderr.write('boom')", "");

        assertThat(result.getStderr()).contains("boom");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getFailureKind()).isEqualTo("non_zero_exit");
        assertThat(result.getStdout()).isEmpty();
    }

    // -------------------------------------------------------------- compile error

    @Test
    @DisplayName("Code containing 'syntax_error' returns compile_error failure_kind")
    void execute_compileError_failureKindSet() {
        ExecutionResult result = runner.execute("python", "def syntax_error(:", "");

        assertThat(result.getStderr()).isNotEmpty();
        assertThat(result.getFailureKind()).isEqualTo("compile_error");
        assertThat(result.getExitCode()).isNotEqualTo(0);
        assertThat(result.getStdout()).isEmpty();
    }

    // -------------------------------------------------------------- field completeness

    @Test
    @DisplayName("ExecutionResult never returns null for stdout or stderr")
    void execute_stdoutAndStderrNeverNull() {
        ExecutionResult result = runner.execute("java", "class Foo {}", "");

        assertThat(result.getStdout()).isNotNull();
        assertThat(result.getStderr()).isNotNull();
    }
}
