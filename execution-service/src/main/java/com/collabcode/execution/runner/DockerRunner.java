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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executes RunSpecs for all supported languages in fresh, isolated Docker containers (DR1, US-14).
 * Supports execution metrics and peak memory tracking (US-10).
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

    private static final String PYTHON_EXECUTABLE = "python";
    private static final String PYTHON_RUNNER_PATH = "/usr/local/bin/collab-sandbox-runner";
    private static final long CONTAINER_STARTUP_GRACE_SECONDS = 30;
    private static final int SIGKILL_EXIT_CODE = 137;
    private static final int OOM_FLAG_RECHECKS = 5;
    private static final long OOM_FLAG_RECHECK_DELAY_MS = 100;
    private static final double OOM_PEAK_FRACTION_OF_LIMIT = 0.9;

    record RunMetrics(double executionTimeMs, double peakMemoryMb, boolean timedOut) {}

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
        RunMetrics metrics = new RunMetrics(0.0, 0.0, false);

        try {
            validateLimits();

            String validationError = validateRunSpec(spec);
            if (validationError != null) {
                return failureResult(
                        validationError,
                        2,
                        "non_zero_exit",
                        0.0,
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

            Path metricsDir = workspace.resolve("metrics");
            Files.createDirectories(metricsDir);
            setDirectoryPermissions(metricsDir);

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
                }

                int compileExitCode = compileProcess.exitValue();
                CapturedOutput compileStderr = compileStderrFuture.get();
                compileStdoutFuture.get(); // Drain stdout

                compileTimeMs = elapsedMillis(compileStart);

                if (!compileFinished || compileExitCode != 0) {
                    return new ExecutionResult(
                            "",
                            compileStderr.text(),
                            compileFinished ? compileExitCode : 124,
                            0.0,
                            0.0,
                            !compileFinished,
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
                    "--name", containerName
            ));

            if ("python".equals(language)) {
                // US-10: Python runner with cgroup metrics requires root container with caps dropped to drop to UID 1001
                runDockerArgs.addAll(List.of(
                        "--cap-drop=ALL",
                        "--cap-add=SETUID",
                        "--cap-add=SETGID",
                        "--security-opt=no-new-privileges",
                        "--user", "0:0"
                ));
            } else {
                runDockerArgs.addAll(List.of(
                        "--user", "1001:1001"
                ));
            }

            runDockerArgs.addAll(List.of(
                    "--memory", limits.getMemoryMb() + "m",
                    "--memory-swap", limits.getMemoryMb() + "m",
                    "--pids-limit", String.valueOf(limits.getPidsLimit()),
                    "--read-only",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
                    "--mount", "type=bind,source=" + workspace.toAbsolutePath() + ",target=/sandbox/code,readonly",
                    "--mount", "type=bind,source=" + metricsDir.toAbsolutePath() + ",target=/sandbox/metrics"
            ));

            if (isCompiled && buildDir != null) {
                runDockerArgs.add("--mount");
                runDockerArgs.add("type=bind,source=" + buildDir.toAbsolutePath() + ",target=/sandbox/build,readonly");
            }

            runDockerArgs.add(image);
            if ("python".equals(language)) {
                runDockerArgs.add(PYTHON_EXECUTABLE);
                runDockerArgs.add(PYTHON_RUNNER_PATH);
                runDockerArgs.add(String.valueOf(limits.getWallTimeSeconds()));
                runDockerArgs.add("/sandbox/code/" + spec.getEntry());
            } else {
                runDockerArgs.addAll(runCommand);
            }

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

            if (spec.getStdin() != null) {
                try {
                    process.getOutputStream().write(
                            spec.getStdin().getBytes(StandardCharsets.UTF_8)
                    );
                    process.getOutputStream().flush();
                } catch (IOException ignored) {
                    // Process may exit before consuming stdin.
                } finally {
                    try {
                        process.getOutputStream().close();
                    } catch (IOException ignored) {
                    }
                }
            } else {
                process.getOutputStream().close();
            }

            boolean finished = process.waitFor(
                    limits.getWallTimeSeconds() + CONTAINER_STARTUP_GRACE_SECONDS,
                    TimeUnit.SECONDS
            );

            boolean infrastructureTimeout = !finished;

            if (!finished) {
                stopContainer(containerName);
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }

            exitCode = process.exitValue();
            metrics = readRunMetrics(workspace);

            // If metrics file did not provide execution time (e.g. non-python run), use elapsed time
            if (metrics.executionTimeMs() == 0.0 && finished) {
                metrics = new RunMetrics(elapsedMillis(runStart), metrics.peakMemoryMb(), metrics.timedOut());
            }

            if (infrastructureTimeout || metrics.timedOut()) {
                timedOut = true;
                exitCode = 124;
            } else if (exitCode == SIGKILL_EXIT_CODE) {
                oomKilled = confirmOomKill(containerName, metrics);
            } else {
                oomKilled = inspectOomKilled(containerName);
            }

            CapturedOutput stdout = stdoutFuture.get();
            CapturedOutput stderr = stderrFuture.get();

            boolean truncated = outputTruncated.get()
                    || stdout.truncated()
                    || stderr.truncated();

            String resultStderr = infrastructureTimeout
                    ? stderr.text() + "Docker runner did not finish within the container startup grace period.\n"
                    : stderr.text();

            String failureKind = classifyFailure(
                    language,
                    exitCode,
                    timedOut,
                    oomKilled,
                    resultStderr
            );

            String failingModule = "compile_error".equals(failureKind)
                    ? spec.getEntry()
                    : null;

            String message = resultMessage(
                    exitCode,
                    timedOut,
                    oomKilled,
                    failureKind,
                    outputTruncated.get()
            );

            return new ExecutionResult(
                    stdout.text(),
                    resultStderr,
                    exitCode,
                    metrics.executionTimeMs(),
                    metrics.peakMemoryMb(),
                    timedOut,
                    oomKilled,
                    failureKind,
                    truncated,
                    message,
                    compileTimeMs,
                    failingModule
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return failureResult(
                    "Execution was interrupted.",
                    130,
                    "non_zero_exit",
                    0.0,
                    compileTimeMs,
                    null
            );

        } catch (Exception e) {
            String detail = e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getMessage();

            return failureResult(
                    "Docker execution failed: " + detail,
                    125,
                    "non_zero_exit",
                    0.0,
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
        // Best-effort world-writable for Linux non-root containers (no-op/ignored on Windows)
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
            default -> {
            }
        }
        return cmd;
    }

    private List<String> buildRunCommand(String language, RunSpec spec) {
        List<String> cmd = new ArrayList<>();
        switch (language) {
            case "cpp", "c" -> cmd.add("/sandbox/build/a.out");
            case "java" -> {
                String entry = spec.getEntry();
                String className = entry.endsWith(".java")
                        ? entry.substring(0, entry.length() - ".java".length())
                        : entry;
                cmd.addAll(List.of("java", "-cp", "/sandbox/build", className));
            }
            case "javascript" -> cmd.addAll(List.of("node", "/sandbox/code/" + spec.getEntry()));
            case "python" -> cmd.addAll(List.of("python", "/sandbox/code/" + spec.getEntry()));
            default -> throw new IllegalArgumentException("Unknown language: " + language);
        }
        return cmd;
    }

    private void cleanupAfterExecution(Process process, String containerName, Path workspace) {
        boolean interrupted = false;

        if (process != null) {
            interrupted |= waitForProcessUninterruptibly(
                    process,
                    2,
                    TimeUnit.SECONDS
            );

            if (process.isAlive()) {
                process.destroyForcibly();
                interrupted |= waitForProcessUninterruptibly(
                        process,
                        2,
                        TimeUnit.SECONDS
                );
            }
        }

        if (containerName != null) {
            interrupted |= runCleanupDockerCommand(
                    5,
                    TimeUnit.SECONDS,
                    "rm",
                    "-f",
                    containerName
            );
        }

        if (workspace != null) {
            try {
                if (Files.exists(workspace)) {
                    try (var stream = Files.walk(workspace)) {
                        stream.sorted(Comparator.reverseOrder())
                                .forEach(path -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (IOException ignored) {
                                    }
                                });
                    }
                }
            } catch (IOException ignored) {
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean runCleanupDockerCommand(
            long timeout,
            TimeUnit unit,
            String... arguments
    ) {
        boolean interrupted = false;

        try {
            Process process = new ProcessBuilder(
                    buildDockerCommand(arguments)
            ).start();

            interrupted = waitForProcessUninterruptibly(process, timeout, unit);

            if (process.isAlive()) {
                process.destroyForcibly();
                interrupted |= waitForProcessUninterruptibly(
                        process,
                        timeout,
                        unit
                );
            }

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
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;

        while (true) {
            try {
                process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
                return interrupted;

            } catch (InterruptedException e) {
                interrupted = true;
                remainingNanos = deadline - System.nanoTime();

                if (remainingNanos <= 0) {
                    return true;
                }
            }
        }
    }

    private String validateRunSpec(RunSpec spec) {
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
            return "At least one source file is required.";
        }

        Set<String> names = new HashSet<>();
        boolean entryFound = false;

        for (RunFile file : files) {
            if (file == null) {
                return "Source file entries must not be null.";
            }

            String name = file.getName();
            if (!isValidFileName(name)) {
                return "Invalid file name: " + name;
            }

            if (!names.add(name)) {
                return "Duplicate file name: " + name;
            }

            if (name.equals(spec.getEntry())) {
                entryFound = true;
            }
        }

        if (!entryFound) {
            return "Entry file was not included in the run specification: "
                    + spec.getEntry();
        }

        return null;
    }

    private boolean isValidFileName(String name) {
        return name != null
                && !name.isBlank()
                && !name.contains("/")
                && !name.contains("\\")
                && !name.contains("..")
                && name.indexOf('\0') < 0;
    }

    private void writeRunFiles(RunSpec spec, Path workspace) throws IOException {
        for (RunFile file : spec.getFiles()) {
            Path filePath = workspace.resolve(file.getName());

            if (!filePath.normalize().startsWith(workspace)) {
                throw new SecurityException("Path traversal attempt: " + file.getName());
            }

            Files.writeString(
                    filePath,
                    file.getContent() == null ? "" : file.getContent(),
                    StandardCharsets.UTF_8
            );
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

    RunMetrics readRunMetrics(Path workspace) {
        if (workspace == null) {
            return new RunMetrics(0.0, 0.0, false);
        }

        Path metricsFile = workspace.resolve("metrics").resolve("run-metrics.properties");

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metricsFile)) {
            properties.load(input);
            double executionTimeMs = Double.parseDouble(
                    properties.getProperty("execution_time_ms", "0"));
            long peakMemoryBytes = Long.parseLong(
                    properties.getProperty("peak_memory_bytes", "0"));
            boolean timedOut = Boolean.parseBoolean(
                    properties.getProperty("timed_out", "false"));

            return new RunMetrics(
                    Math.max(0.0, executionTimeMs),
                    Math.max(0.0, peakMemoryBytes / (1024.0 * 1024.0)),
                    timedOut
            );
        } catch (IOException | NumberFormatException ignored) {
            return new RunMetrics(0.0, 0.0, false);
        }
    }

    private String resultMessage(
            int exitCode,
            boolean timedOut,
            boolean oomKilled,
            String failureKind,
            boolean truncated
    ) {
        if (truncated) {
            return "Output limit exceeded (1048576 bytes). Output was truncated.";
        }

        if (oomKilled) {
            return "Memory limit exceeded";
        }

        if (timedOut) {
            return "Time limit exceeded";
        }

        if ("compile_error".equals(failureKind)) {
            return "Compilation or syntax error reported.";
        }

        if (exitCode != 0) {
            return "Program exited with a non-zero status.";
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
                message + "\n",
                exitCode,
                executionTimeMs,
                0.0,
                false,
                false,
                failureKind,
                false,
                message,
                compileTimeMs,
                failingModule
        );
    }

    private void stopContainer(String containerName) {
        Process stop = null;

        try {
            stop = new ProcessBuilder(
                    "docker",
                    "stop",
                    "--time",
                    "2",
                    containerName
            ).start();

            stop.waitFor(10, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

        } catch (IOException ignored) {
        }
    }

    private boolean confirmOomKill(String containerName, RunMetrics metrics) {
        for (int attempt = 0; attempt < OOM_FLAG_RECHECKS; attempt++) {
            try {
                Thread.sleep(OOM_FLAG_RECHECK_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (inspectOomKilled(containerName)) {
                return true;
            }
        }
        return metrics.peakMemoryMb() >= limits.getMemoryMb() * OOM_PEAK_FRACTION_OF_LIMIT;
    }

    private boolean inspectOomKilled(String containerName) {
        Process inspect = null;

        try {
            inspect = new ProcessBuilder(
                    "docker", "inspect",
                    "--format", "{{.State.OOMKilled}}",
                    containerName
            ).start();

            boolean finished = inspect.waitFor(5, TimeUnit.SECONDS);

            if (finished && inspect.exitValue() == 0) {
                String out = new String(
                        inspect.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8
                ).trim();

                return Boolean.parseBoolean(out);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

        } catch (IOException ignored) {
        }

        return false;
    }

    private CapturedOutput captureStream(
            InputStream stream,
            AtomicLong totalBytes,
            AtomicBoolean truncated
    ) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];

        try {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                long current = totalBytes.get();

                if (current >= limits.getMaxOutputBytes()) {
                    truncated.set(true);
                    continue;
                }

                int toWrite = (int) Math.min(
                        read,
                        limits.getMaxOutputBytes() - current
                );

                buffer.write(chunk, 0, toWrite);
                totalBytes.addAndGet(toWrite);

                if (toWrite < read) {
                    truncated.set(true);
                }
            }

        } catch (IOException ignored) {
        }

        return new CapturedOutput(
                buffer.toString(StandardCharsets.UTF_8),
                truncated.get()
        );
    }

    private double elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private void validateLimits() {
        if (limits.getMemoryMb() < 4) {
            throw new IllegalArgumentException(
                    "Configured memory limit too small: " + limits.getMemoryMb() + "MB"
            );
        }

        if (limits.getPidsLimit() < 1) {
            throw new IllegalArgumentException(
                    "Configured pids limit too small: " + limits.getPidsLimit()
            );
        }
    }
}
