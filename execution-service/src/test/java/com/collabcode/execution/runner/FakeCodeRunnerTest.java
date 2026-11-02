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

    // -------------------------------------------------------------- Decision 9: OOM kill rule

    @Test
    @DisplayName("OOM kill: failure_kind is 'non_zero_exit' with oom_killed: true, never 'oom' (Decision 9)")
    void execute_oomKill_failureKindIsNonZeroExit() {
        ExecutionResult result = runner.execute(singleFileSpec("python", "allocate_oom_memory()", ""));

        assertThat(result.isOomKilled()).isTrue();
        assertThat(result.getFailureKind()).isEqualTo("non_zero_exit");
        assertThat(result.getFailureKind()).isNotEqualTo("oom"); // Decision 9 rule: 'oom' is never used
        assertThat(result.getExitCode()).isEqualTo(137);
        assertThat(result.getStderr()).contains("Out of memory");
    }

    @Test
    @DisplayName("OOM kill: oom_killed is false for normal non-zero-exit (no OOM)")
    void execute_normalFailure_oomKilledFalse() {
        ExecutionResult result = runner.execute(singleFileSpec("python", "import sys; sys.stderr.write('boom')", ""));

        assertThat(result.isOomKilled()).isFalse();
        assertThat(result.getFailureKind()).isEqualTo("non_zero_exit");
    }

    // -------------------------------------------------------------- Decision 9: compile_time_ms

    @Test
    @DisplayName("Compiled languages (cpp, c, java) have non-null compile_time_ms (Decision 9)")
    void execute_compiledLanguage_hasCompileTime() {
        for (String lang : new String[]{"cpp", "c", "java"}) {
            ExecutionResult result = runner.execute(singleFileSpec(lang, "// ok", ""));
            assertThat(result.getCompileTimeMs())
                    .as("compile_time_ms for language %s should be non-null", lang)
                    .isNotNull();
            assertThat(result.getCompileTimeMs())
                    .as("compile_time_ms for language %s should be >= 0", lang)
                    .isGreaterThanOrEqualTo(0.0);
        }
    }

    @Test
    @DisplayName("Interpreted languages (python, javascript) have null compile_time_ms (Decision 9)")
    void execute_interpretedLanguage_noCompileTime() {
        for (String lang : new String[]{"python", "javascript"}) {
            ExecutionResult result = runner.execute(singleFileSpec(lang, "// ok", ""));
            assertThat(result.getCompileTimeMs())
                    .as("compile_time_ms for language %s should be null (no compile stage)", lang)
                    .isNull();
        }
    }

    // -------------------------------------------------------------- Decision 9: failingModule

    @Test
    @DisplayName("OOM kill names the failing file as failingModule (Decision 9)")
    void execute_oomKill_namesFailingModule() {
        RunSpec spec = new RunSpec(
                "python",
                List.of(
                        new RunFile("main.py", "# normal"),
                        new RunFile("heavy.py", "allocate_oom_heavy()")
                ),
                "main.py",
                ""
        );

        ExecutionResult result = runner.execute(spec);
        assertThat(result.isOomKilled()).isTrue();
        assertThat(result.getFailingModule()).isEqualTo("heavy.py");
    }

    @Test
    @DisplayName("Normal success has null failingModule (Decision 9)")
    void execute_success_failingModuleIsNull() {
        ExecutionResult result = runner.execute(singleFileSpec("python", "print('hi')", ""));

        assertThat(result.getFailingModule()).isNull();
    }
}
