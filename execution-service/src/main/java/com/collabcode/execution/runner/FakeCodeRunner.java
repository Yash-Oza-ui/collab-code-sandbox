package com.collabcode.execution.runner;

import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Deterministic in-memory stub for CodeRunner.
 *
 * Updated in T-19 (Decision 8 & 9, Jay and Yash) to execute RunSpec and produce Decision 9 fields:
 *  1. If any file contains "oom", simulates an out-of-memory kill:
 *     oom_killed: true, failure_kind: "non_zero_exit", exit 137.
 *  2. If any file contains "syntax_error" (case-insensitive), writes a compiler
 *     error to stderr, exit 1, failure_kind "compile_error".
 *  3. If any file contains "stderr" (case-insensitive), writes "boom\n" to stderr
 *     and exits 2 — simulating a runtime failure ("non_zero_exit").
 *  4. If stdin is non-empty, echoes its UPPER-CASE version to stdout.
 *  5. If any file contains "sleep" or "delay", the run is held for 0.5 to 5 seconds
 *     (3 seconds by default) so tests can keep a queue slot busy (US-11, FR16),
 *     then it continues to case 6.
 *  6. Otherwise, returns "Hello from <language>!\n" in stdout, exit 0.
 *
 * Simulated outcomes:
 *  1. Content containing an OOM marker returns exit 137,
 *     oom_killed=true, failure_kind="non_zero_exit".
 *  2. Content containing "syntax_error" returns a simulated compile error.
 *  3. Content containing "stderr" returns a simulated runtime failure.
 *  4. Non-empty stdin is echoed in uppercase.
 *  5. Otherwise, a deterministic success result is returned.
 */
@Component
@Profile("stub-runner")
public class FakeCodeRunner implements CodeRunner {

    @Override
    public ExecutionResult execute(RunSpec spec) {
        if (spec == null) {
            return invalidRequest("Run specification must not be null.");
        }

        String language = spec.getLanguage();
        String stdin = spec.getStdin();

        Double compileTime = isCompiled(language) ? 12.0 : null;

        // Case 0: simulate an out-of-memory kill (Decision 9).
        boolean hasOom = spec.getFiles() != null && spec.getFiles().stream()
                .filter(file -> file != null)
                .map(RunFile::getContent)
                .filter(content -> content != null)
                .anyMatch(FakeCodeRunner::matchesOom);

        if (hasOom) {
            String failingModule = findFailingFile(spec, "oom");
            return new ExecutionResult(
                    "",
                    "Killed: Out of memory\n",
                    137,
                    25.0,
                    64.0,
                    false,
                    true,
                    "non_zero_exit",
                    false,
                    "Memory limit exceeded",
                    compileTime,
                    failingModule
            );
        }

        // Case 1: simulate a compile error.
        boolean hasSyntaxError = spec.getFiles() != null && spec.getFiles().stream()
                .filter(file -> file != null)
                .map(RunFile::getContent)
                .filter(content -> content != null)
                .anyMatch(content ->
                        content.toLowerCase(Locale.ROOT).contains("syntax_error"));

        if (hasSyntaxError) {
            String failingModule = findFailingFile(spec, "syntax_error");
            return new ExecutionResult(
                    "",
                    language + ": SyntaxError: unexpected token near line 1\n",
                    1,
                    0.0,
                    0.0,
                    false,
                    false,
                    "compile_error",
                    false,
                    null,
                    isCompiled(language) ? 0.0 : null,
                    failingModule
            );
        }

        // Case 2: simulate a stderr / runtime failure.
        boolean hasStderr = spec.getFiles() != null && spec.getFiles().stream()
                .filter(file -> file != null)
                .map(RunFile::getContent)
                .filter(content -> content != null)
                .anyMatch(content ->
                        content.toLowerCase(Locale.ROOT).contains("stderr"));

        if (hasStderr) {
            String failingModule = findFailingFile(spec, "stderr");
            return new ExecutionResult(
                    "",
                    "boom\n",
                    2,
                    12.0,
                    4.0,
                    false,
                    false,
                    "non_zero_exit",
                    false,
                    null,
                    compileTime,
                    failingModule
            );
        }

        // Case 3: stdin echoing.
        if (stdin != null && !stdin.isEmpty()) {
            return new ExecutionResult(
                    stdin.toUpperCase(Locale.ROOT) + "\n",
                    "",
                    0,
                    10.0,
                    4.0,
                    false,
                    false,
                    null,
                    false,
                    null,
                    compileTime,
                    null
            );
        }

        // Case 4: simulate bounded in-flight execution for testing (FR16 / US-11)
        String code = spec.getFiles() == null ? "" : spec.getFiles().stream()
                .map(RunFile::getContent)
                .filter(c -> c != null)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (code.toLowerCase().contains("sleep") || code.toLowerCase().contains("delay")) {
            long delayMs = 3000;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:sleep|delay)[\\s_\\(]+(\\d+)").matcher(code.toLowerCase());
            if (m.find()) {
                try {
                    long requested = Long.parseLong(m.group(1));
                    delayMs = requested <= 10 ? requested * 1000 : requested;
                    delayMs = Math.min(Math.max(delayMs, 500), 5000);
                } catch (NumberFormatException ignored) {}
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Case 5: normal success
        return new ExecutionResult(
                "Hello from " + language + "!\n",
                "",
                0,
                15.0,
                4.2,
                false,
                false,
                null,
                false,
                null,
                compileTime,
                null
        );
    }

    private ExecutionResult invalidRequest(String message) {
        return new ExecutionResult(
                "",
                message + "\n",
                2,
                0.0,
                0.0,
                false,
                false,
                "non_zero_exit",
                false,
                message,
                null,
                null
        );
    }

    private static boolean isCompiled(String language) {
        if (language == null) {
            return false;
        }
        return "cpp".equalsIgnoreCase(language)
                || "c".equalsIgnoreCase(language)
                || "java".equalsIgnoreCase(language);
    }

    private static boolean matchesOom(String content) {
        if (content == null) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        return (lower.contains("oom") && !lower.contains("boom"))
                || lower.contains("out_of_memory");
    }

    private static String findFailingFile(RunSpec spec, String token) {
        if (spec.getFiles() == null) {
            return null;
        }

        return spec.getFiles().stream()
                .filter(file -> file != null && file.getContent() != null)
                .filter(file -> {
                    String content = file.getContent();
                    if ("oom".equalsIgnoreCase(token)) {
                        return matchesOom(content);
                    }
                    return content.toLowerCase(Locale.ROOT)
                            .contains(token.toLowerCase(Locale.ROOT));
                })
                .map(RunFile::getName)
                .findFirst()
                .orElse(null);
    }
}