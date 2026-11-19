package com.collabcode.execution.runner;

import com.collabcode.execution.config.ExecutionLanguageProperties;
import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executes RunSpecs for all supported languages in fresh, isolated Docker containers (DR1, US-14).
 *
 * Supported languages:
 *   1. C++ ("cpp")
 *   2. Python ("python")
 *   3. C ("c")
 *   4. Java ("java")
 *   5. JavaScript ("javascript")
 *
 * Compiled languages (C++, C, Java) compile in a compile stage (NFR4.5) and run in a run stage.
 * If compilation fails, a compile_error ExecutionResult is returned with compiler output in stderr.
 */
@Component
@Profile("!stub-runner")
public class DockerRunner implements CodeRunner {

    private final ExecutionLimitsProperties limits;
    private final ExecutionLanguageProperties languageProperties;

    public DockerRunner(ExecutionLimitsProperties limits) {
        this(limits, new ExecutionLanguageProperties());
    }

    @Autowired
    public DockerRunner(ExecutionLimitsProperties limits, ExecutionLanguageProperties languageProperties) {
        this.limits = limits;
        this.languageProperties = (languageProperties != null) ? languageProperties : new ExecutionLanguageProperties();
    }

    private record CapturedOutput(String text, boolean truncated) {}

    protected String createContainerName() {
        return "run-" + UUID.randomUUID();
    }

    @Override
    public ExecutionResult execute(RunSpec spec) {
        long start = System.nanoTime();
        String runId = UUID.randomUUID().toString();
        String containerName = createContainerName();
        String compileContainerName = containerName + "-compile";

        Path workspace = null;
        Path buildDir = null;
        Process process = null;
        Process compileProcess = null;
        boolean timedOut = false;
        boolean oomKilled = false;
        int exitCode = 1;

        AtomicLong outputBytes = new AtomicLong(0);
        AtomicBoolean outputTruncated = new AtomicBoolean(false);

        Double compileTimeMs = null;

        try {
            validateLimits();

            String validationError = validateSpec(spec);
            if (validationError != null) {
                return failureResult(
                        validationError,
                        2,
                        "non_zero_exit",
                        elapsedMillis(start),
                        null,
                        null
                );
            }

            String language = spec.getLanguage().toLowerCase(Locale.ROOT);
            String image = languageProperties.getImageForLanguage(language);
            boolean isCompiled = ExecutionLanguageProperties.isCompiledLanguage(language);

            Path workspaceRoot = Path.of("/tmp/collab-code-workspaces");
            Files.createDirectories(workspaceRoot);

            workspace = Files.createTempDirectory(
                    workspaceRoot,
                    "run-" + runId + "-"
            );

            // Allow non-root runtime user (1001) to access the workspace.
            setDirectoryPermissions(workspace);
            writeRunFiles(spec, workspace);

            if (isCompiled) {
                buildDir = workspace.resolve("build");
                Files.createDirectories(buildDir);
                setDirectoryPermissions(buildDir);

                // =================================================================
                // STAGE 1: COMPILE STAGE (NFR4.5)
                // =================================================================
                long compileStart = System.nanoTime();
                List<String> compileCommand = buildCompileCommand(language, spec);

                List<String> compileDockerArgs = new ArrayList<>(List.of(
                        "docker", "run",
                        "--interactive",
                        "--network", "none",
                        "--name", compileContainerName,
                        "--user", "1001:1001",
                        "--memory", limits.getMemoryMb() + "m",
                        "--memory-swap", limits.getMemoryMb() + "m",
                        "--pids-limit", String.valueOf(limits.getPidsLimit()),
                        "--read-only",
                        "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
                        "--mount", "type=bind,source=" + workspace.toAbsolutePath() + ",target=/sandbox/code,readonly",
                        "--mount", "type=bind,source=" + buildDir.toAbsolutePath() + ",target=/sandbox/build,readonly=false",
                        image
                ));
                compileDockerArgs.addAll(compileCommand);

                ProcessBuilder compilePb = new ProcessBuilder(compileDockerArgs);
                compileProcess = compilePb.start();
                Process runningCompile = compileProcess;

                AtomicLong compileOutputBytes = new AtomicLong(0);
                AtomicBoolean compileOutputTruncated = new AtomicBoolean(false);

                CompletableFuture<CapturedOutput> compileStdoutFuture =
                        CompletableFuture.supplyAsync(() -> captureStream(
                                runningCompile.getInputStream(),
                                compileOutputBytes,
                                compileOutputTruncated
                        ));

                CompletableFuture<CapturedOutput> compileStderrFuture =
                        CompletableFuture.supplyAsync(() -> captureStream(
                                runningCompile.getErrorStream(),
                                compileOutputBytes,
                                compileOutputTruncated
                        ));

                boolean compileFinished = compileProcess.waitFor(
                        limits.getCompileTimeoutSeconds(),
                        TimeUnit.SECONDS
                );

                if (!compileFinished) {
                    stopContainer(compileContainerName);
                    if (compileProcess.isAlive()) {
                        compileProcess.destroyForcibly();
                        compileProcess.waitFor();
                    }
                    return failureResult(
                            "Compile time limit exceeded",
                            124,
                            "timeout",
                            0.0,
                            elapsedMillis(compileStart),
                            spec.getEntry()
                    );
                }

                int compileExit = compileProcess.exitValue();
                CapturedOutput compileStdout = compileStdoutFuture.join();
                CapturedOutput compileStderr = compileStderrFuture.join();

                compileTimeMs = elapsedMillis(compileStart);

                // Cleanup compile container immediately
                runCleanupDockerCommand(5, TimeUnit.SECONDS, "rm", "-f", compileContainerName);

                if (compileExit != 0) {
                    String compilerOutput = !compileStderr.text().isEmpty()
                            ? compileStderr.text()
                            : compileStdout.text();

                    return new ExecutionResult(
                            "",
                            compilerOutput,
                            compileExit,
                            0.0,
                            0.0,
                            false,
                            false,
                            "compile_error",
                            compileOutputTruncated.get(),
                            "Compilation failed.",
                            compileTimeMs,
                            spec.getEntry()
                    );
                }
            }

            // =================================================================
            // STAGE 2: RUN STAGE
            // =================================================================
            long runStart = System.nanoTime();
            List<String> runCommand = buildRunCommand(language, spec);

            List<String> runDockerArgs = new ArrayList<>(List.of(
                    "docker", "run",
                    "--interactive",
                    "--network", "none",
                    "--name", containerName,
                    "--user", "1001:1001",
                    "--memory", limits.getMemoryMb() + "m",
                    "--memory-swap", limits.getMemoryMb() + "m",
                    "--pids-limit", String.valueOf(limits.getPidsLimit()),
                    "--read-only",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
                    "--mount", "type=bind,source=" + workspace.toAbsolutePath() + ",target=/sandbox/code,readonly"
            ));

            if (isCompiled && buildDir != null) {
                runDockerArgs.add("--mount");
                runDockerArgs.add("type=bind,source=" + buildDir.toAbsolutePath() + ",target=/sandbox/build,readonly");
            }

            runDockerArgs.add(image);
            runDockerArgs.addAll(runCommand);

            ProcessBuilder builder = new ProcessBuilder(runDockerArgs);
            process = builder.start();
            Process runningProcess = process;

            CompletableFuture<CapturedOutput> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> captureStream(
                            runningProcess.getInputStream(),
                            outputBytes,
                            outputTruncated
                    ));

            CompletableFuture<CapturedOutput> stderrFuture =
                    CompletableFuture.supplyAsync(() -> captureStream(
                            runningProcess.getErrorStream(),
                            outputBytes,
                            outputTruncated
                    ));

            try (var output = process.getOutputStream()) {
                String stdin = spec.getStdin();
                if (stdin != null && !stdin.isEmpty()) {
                    output.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                if (process.isAlive()) {
                    throw e;
                }
            }

            boolean finished = process.waitFor(
                    limits.getWallTimeSeconds(),
                    TimeUnit.SECONDS
            );

            if (!finished) {
                stopContainer(containerName);
                oomKilled = inspectOomKilled(containerName);

                if (oomKilled) {
                    timedOut = false;
                    exitCode = 137;
                } else {
                    timedOut = true;
                    exitCode = 124;
                }

                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } else {
                exitCode = process.exitValue();
                oomKilled = inspectOomKilled(containerName);

                if (oomKilled) {
                    timedOut = false;
                    exitCode = 137;
                }
            }

            CapturedOutput stdout = stdoutFuture.join();
            CapturedOutput stderr = stderrFuture.join();

            boolean truncated = outputTruncated.get()
                    || stdout.truncated()
                    || stderr.truncated();

            String failureKind = classifyFailure(
                    language,
                    exitCode,
                    timedOut,
                    oomKilled,
                    stderr.text()
            );

            String failingModule = "compile_error".equals(failureKind)
                    ? spec.getEntry()
                    : null;

            double executionTimeMs = elapsedMillis(runStart);

            return new ExecutionResult(
                    stdout.text(),
                    stderr.text(),
                    exitCode,
                    executionTimeMs,
                    0.0,
                    timedOut,
                    oomKilled,
                    failureKind,
                    truncated,
                    resultMessage(
                            exitCode,
                            timedOut,
                            oomKilled,
                            failureKind
                    ),
                    compileTimeMs,
                    failingModule
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return failureResult(
                    "Execution was interrupted.",
                    130,
                    "non_zero_exit",
                    elapsedMillis(start),
                    compileTimeMs,
                    null
            );

        } catch (IOException | RuntimeException e) {
            String detail = e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getMessage();

            return failureResult(
                    "Docker execution failed: " + detail,
                    125,
                    "non_zero_exit",
                    elapsedMillis(start),
                    compileTimeMs,
                    null
            );

        } finally {
            cleanupAfterExecution(compileProcess, compileContainerName, null);
            cleanupAfterExecution(process, containerName, workspace);
        }
    }

    private static void setDirectoryPermissions(Path dir) throws IOException {
        if (!dir.toFile().setReadable(true, false)
                || !dir.toFile().setExecutable(true, false)) {
            throw new IOException("Unable to set workspace permissions");
        }
        // Best-effort world-writable for Linux/Docker non-root user (ignored on Windows)
        dir.toFile().setWritable(true, false);
    }

    private List<String> buildCompileCommand(String language, RunSpec spec) {
        List<String> cmd = new ArrayList<>();
        switch (language) {
            case "cpp" -> {
                cmd.addAll(List.of("g++", "-O2", "-std=c++20", "-I/sandbox/code"));
                for (RunFile file : spec.getFiles()) {
                    String name = file.getName();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx")) {
                        cmd.add("/sandbox/code/" + name);
                    }
                }
                cmd.addAll(List.of("-o", "/sandbox/build/a.out"));
            }
            case "c" -> {
                cmd.addAll(List.of("gcc", "-O2", "-std=c17", "-I/sandbox/code"));
                for (RunFile file : spec.getFiles()) {
                    String name = file.getName();
                    if (name.toLowerCase(Locale.ROOT).endsWith(".c")) {
                        cmd.add("/sandbox/code/" + name);
                    }
                }
                cmd.addAll(List.of("-o", "/sandbox/build/a.out", "-lm"));
            }
            case "java" -> {
                cmd.addAll(List.of("javac", "-d", "/sandbox/build"));
                for (RunFile file : spec.getFiles()) {
                    String name = file.getName();
                    if (name.toLowerCase(Locale.ROOT).endsWith(".java")) {
                        cmd.add("/sandbox/code/" + name);
                    }
                }
            }
            default -> throw new IllegalArgumentException("Language is not compiled: " + language);
        }
        return cmd;
    }

    private List<String> buildRunCommand(String language, RunSpec spec) {
        return switch (language) {
            case "cpp", "c" -> List.of("/sandbox/build/a.out");
            case "java" -> {
                String entry = spec.getEntry();
                String className = entry.replaceAll("(?i)\\.java$", "");
                yield List.of("java", "-cp", "/sandbox/build", className);
            }
            case "python" -> List.of("python", "/sandbox/code/" + spec.getEntry());
            case "javascript" -> List.of("node", "/sandbox/code/" + spec.getEntry());
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        };
    }

    private void cleanupAfterExecution(
            Process executionProcess,
            String containerName,
            Path workspace
    ) {
        boolean interrupted = Thread.interrupted();

        try {
            if (executionProcess != null && executionProcess.isAlive()) {
                executionProcess.destroyForcibly();

                interrupted |= waitForProcessUninterruptibly(
                        executionProcess,
                        5,
                        TimeUnit.SECONDS
                );
            }

            if (containerName != null) {
                interrupted |= runCleanupDockerCommand(
                        3,
                        TimeUnit.SECONDS,
                        "stop",
                        "--time",
                        "1",
                        containerName
                );

                interrupted |= runCleanupDockerCommand(
                        5,
                        TimeUnit.SECONDS,
                        "rm",
                        "-f",
                        containerName
                );
            }

            if (workspace != null) {
                try (var paths = Files.walk(workspace)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
                } catch (IOException ignored) {
                }
            }

        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean runCleanupDockerCommand(
            long timeout,
            TimeUnit unit,
            String... arguments
    ) {
        Process cleanup = null;
        boolean interrupted = false;

        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command(buildDockerCommand(arguments));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);

            cleanup = builder.start();

            long deadline = System.nanoTime() + unit.toNanos(timeout);

            while (cleanup.isAlive()) {
                long remaining = deadline - System.nanoTime();

                if (remaining <= 0) {
                    cleanup.destroyForcibly();
                    break;
                }

                try {
                    cleanup.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }

            if (cleanup.isAlive()) {
                cleanup.destroyForcibly();
            }

            interrupted |= waitForProcessUninterruptibly(
                    cleanup,
                    2,
                    TimeUnit.SECONDS
            );

        } catch (IOException ignored) {
        }

        return interrupted;
    }

    private List<String> buildDockerCommand(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(arguments));
        return command;
    }

    private boolean waitForProcessUninterruptibly(
            Process process,
            long timeout,
            TimeUnit unit
    ) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        while (process.isAlive()) {
            long remaining = deadline - System.nanoTime();

            if (remaining <= 0) {
                break;
            }

            try {
                process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        return interrupted;
    }

    private String validateSpec(RunSpec spec) {
        if (spec == null) {
            return "Run specification must not be null.";
        }

        String language = spec.getLanguage();
        if (language == null || !languageProperties.isSupportedLanguage(language)) {
            return "Unsupported language. The configured runtime supports: "
                    + String.join(", ", languageProperties.getImages().keySet());
        }

        List<RunFile> files = spec.getFiles();

        if (files == null || files.isEmpty()) {
            return "Run specification must contain at least one source file.";
        }

        Set<String> names = new HashSet<>();

        for (RunFile file : files) {
            if (file == null) {
                return "Run specification contains a null file.";
            }

            String name = file.getName();

            if (!isSafeFileName(name)) {
                return "Invalid source filename. Filenames must be plain names without path components.";
            }

            if (!names.add(name)) {
                return "Run specification contains duplicate filename: " + name;
            }

            if (file.getContent() == null) {
                return "Source file content must not be null: " + name;
            }
        }

        String entry = spec.getEntry();

        if (!isSafeFileName(entry)) {
            return "Run specification must identify a valid entry filename.";
        }

        if (!names.contains(entry)) {
            return "Entry file was not included in the run specification: " + entry;
        }

        return null;
    }

    private boolean isSafeFileName(String name) {
        return name != null
                && !name.isBlank()
                && !".".equals(name)
                && !"..".equals(name)
                && !name.contains("..")
                && !name.contains("/")
                && !name.contains("\\")
                && name.indexOf('\0') < 0;
    }

    private void writeRunFiles(RunSpec spec, Path workspace) throws IOException {
        for (RunFile file : spec.getFiles()) {
            Path filePath = workspace.resolve(file.getName());

            Files.writeString(
                    filePath,
                    file.getContent(),
                    StandardCharsets.UTF_8
            );

            if (!filePath.toFile().setReadable(true, false)) {
                throw new IOException(
                        "Unable to make source file readable: " + file.getName()
                );
            }
        }
    }

    private String classifyFailure(
            String language,
            int exitCode,
            boolean timedOut,
            boolean oomKilled,
            String stderr
    ) {
        if (timedOut) {
            return "timeout";
        }

        if (exitCode == 0 && !oomKilled) {
            return null;
        }

        if (oomKilled) {
            return "non_zero_exit";
        }

        String lower = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);

        if ("python".equals(language)
                && (lower.contains("syntaxerror") || lower.contains("indentationerror"))) {
            return "compile_error";
        }

        if ("javascript".equals(language) && lower.contains("syntaxerror")) {
            return "compile_error";
        }

        return "non_zero_exit";
    }

    private String resultMessage(
            int exitCode,
            boolean timedOut,
            boolean oomKilled,
            String failureKind
    ) {
        if (oomKilled) {
            return "Memory limit exceeded";
        }

        if (timedOut) {
            return "Time limit exceeded";
        }

        if ("compile_error".equals(failureKind)) {
            return "Compilation or syntax error reported. Peak memory measurement is unavailable.";
        }

        if (exitCode != 0) {
            return "Program exited with a non-zero status. Peak memory measurement is unavailable.";
        }

        return null;
    }

    private ExecutionResult failureResult(
            String message,
            int exitCode,
            String failureKind,
            double executionTimeMs,
            Double compileTimeMs,
            String failingModule
    ) {
        return new ExecutionResult(
                "",
                message + System.lineSeparator(),
                exitCode,
                executionTimeMs,
                0.0,
                "timeout".equals(failureKind),
                false,
                failureKind,
                false,
                message,
                compileTimeMs,
                failingModule
        );
    }

    private double elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private void stopContainer(String containerName) {
        Process stop = null;

        try {
            stop = new ProcessBuilder(
                    "docker",
                    "kill",
                    containerName
            ).start();

            if (!stop.waitFor(3, TimeUnit.SECONDS)) {
                stop.destroyForcibly();
                stop.waitFor();
            }

        } catch (InterruptedException e) {
            if (stop != null && stop.isAlive()) {
                stop.destroyForcibly();
            }

            Thread.currentThread().interrupt();

        } catch (IOException ignored) {
        }
    }

    private boolean inspectOomKilled(String containerName) {
        Process inspect = null;

        try {
            inspect = new ProcessBuilder(
                    "docker",
                    "inspect",
                    "--format",
                    "{{.State.OOMKilled}}",
                    containerName
            ).start();

            if (!inspect.waitFor(5, TimeUnit.SECONDS)) {
                inspect.destroyForcibly();
                inspect.waitFor();

                return false;
            }

            if (inspect.exitValue() != 0) {
                return false;
            }

            byte[] output;

            try (InputStream stream = inspect.getInputStream()) {
                output = stream.readAllBytes();
            }

            return "true".equalsIgnoreCase(
                    new String(output, StandardCharsets.UTF_8).trim()
            );

        } catch (InterruptedException e) {
            if (inspect != null && inspect.isAlive()) {
                inspect.destroyForcibly();
            }

            Thread.currentThread().interrupt();

            return false;

        } catch (Exception ignored) {
            if (inspect != null && inspect.isAlive()) {
                inspect.destroyForcibly();
            }

            return false;
        }
    }

    private CapturedOutput captureStream(
            InputStream stream,
            AtomicLong outputBytes,
            AtomicBoolean outputTruncated
    ) {
        long maxBytes = limits.getMaxOutputBytes();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try (stream) {
            byte[] buffer = new byte[8192];
            int count;

            while ((count = stream.read(buffer)) != -1) {
                int allowed;

                while (true) {
                    long used = outputBytes.get();
                    long remaining = Math.max(0, maxBytes - used);

                    allowed = (int) Math.min(count, remaining);

                    if (outputBytes.compareAndSet(
                            used,
                            used + allowed
                    )) {
                        break;
                    }
                }

                if (allowed > 0) {
                    captured.write(buffer, 0, allowed);
                }

                if (allowed < count) {
                    outputTruncated.set(true);
                }
            }

        } catch (IOException e) {
            throw new CompletionException(e);
        }

        return new CapturedOutput(
                captured.toString(StandardCharsets.UTF_8),
                outputTruncated.get()
        );
    }

    private void validateLimits() {
        if (limits.getWallTimeSeconds() <= 0
                || limits.getMemoryMb() <= 0
                || limits.getPidsLimit() <= 0
                || limits.getMaxOutputBytes() <= 0
                || limits.getCompileTimeoutSeconds() <= 0
                || limits.getMaxRequestBodyBytes() <= 0) {

            throw new IllegalStateException(
                    "Execution limits must all be greater than zero."
            );
        }
    }
}
