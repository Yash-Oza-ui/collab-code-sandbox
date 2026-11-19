package com.collabcode.execution.runner;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import com.collabcode.execution.model.RunFile;
import com.collabcode.execution.model.RunSpec;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executes Python RunSpecs in a fresh, isolated Docker container.
 * Additional languages require their own repository-built runtime images.
 */
@Component
@Profile("!stub-runner")
public class DockerRunner implements CodeRunner {

    private static final String PYTHON_IMAGE = "collab-sandbox-python:0.2.0";
    private static final String PYTHON_EXECUTABLE = "python";
    private static final long CONTAINER_STARTUP_GRACE_SECONDS = 30;
    private static final int SIGKILL_EXIT_CODE = 137;
    private static final int OOM_FLAG_RECHECKS = 5;
    private static final long OOM_FLAG_RECHECK_DELAY_MS = 100;
    private static final double OOM_PEAK_FRACTION_OF_LIMIT = 0.9;

    record RunMetrics(double executionTimeMs, double peakMemoryMb, boolean timedOut) {}

    private final ExecutionLimitsProperties limits;

    public DockerRunner(ExecutionLimitsProperties limits) {
        this.limits = limits;
    }

    private record CapturedOutput(String text, boolean truncated) {}

    protected String createContainerName() {
    return "run-" + UUID.randomUUID();
    }

    public ExecutionResult execute(RunSpec spec) {
        long start = System.nanoTime();
       String runId = UUID.randomUUID().toString();
       String containerName = createContainerName();

        Path workspace = null;
        Process process = null;
        boolean timedOut = false;
        boolean oomKilled = false;
        int exitCode = 1;

        AtomicLong outputBytes = new AtomicLong(0);
        AtomicBoolean outputTruncated = new AtomicBoolean(false);

        try {
            validateLimits();

            String validationError = validateSpec(spec);
            if (validationError != null) {
                return failureResult(
                        validationError,
                        2,
                        "non_zero_exit",
                        0.0,
                        null
                );
            }

            Path workspaceRoot = Path.of("/tmp/collab-code-workspaces");
            Files.createDirectories(workspaceRoot);

            workspace = Files.createTempDirectory(
                    workspaceRoot,
                    "run-" + runId + "-"
            );

            // Allow non-root runtime user (1001) to access the workspace.
            if (!workspace.toFile().setReadable(true, false)
                    || !workspace.toFile().setExecutable(true, false)) {
                throw new IOException("Unable to set workspace permissions");
            }
            writeRunFiles(spec, workspace);

            Path metricsDir = workspace.resolve("metrics");
            Files.createDirectories(metricsDir);
            metricsDir.toFile().setReadable(true, false);
            metricsDir.toFile().setWritable(true, false);
            metricsDir.toFile().setExecutable(true, false);

            ProcessBuilder builder = new ProcessBuilder(
                    "docker", "run",
                    "--interactive",
                    "--network", "none",
                    "--name", containerName,
                    "--cap-drop=ALL",
                    "--cap-add=SETUID",
                    "--cap-add=SETGID",
                    "--security-opt=no-new-privileges",
                    "--user", "0:0",
                    "--memory", limits.getMemoryMb() + "m",
                    "--memory-swap", limits.getMemoryMb() + "m",
                    "--pids-limit", String.valueOf(limits.getPidsLimit()),
                    "--read-only",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
                    "--mount",
                    "type=bind,source=" + workspace.toAbsolutePath()
                            + ",target=/sandbox/code,readonly",
                    "--mount",
                    "type=bind,source=" + metricsDir.toAbsolutePath()
                            + ",target=/sandbox/metrics",
                    PYTHON_IMAGE,
                    PYTHON_EXECUTABLE,
                    "/usr/local/bin/collab-sandbox-runner",
                    String.valueOf(limits.getWallTimeSeconds()),
                    "/sandbox/code/" + spec.getEntry()
            );

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
            oomKilled = inspectOomKilled(containerName);
            RunMetrics metrics = readRunMetrics(workspace);
            timedOut = metrics.timedOut();
            if (!oomKilled && !timedOut && !infrastructureTimeout && exitCode == SIGKILL_EXIT_CODE) {
                oomKilled = confirmOomKill(containerName, metrics);
            }
            if (oomKilled) {
                exitCode = 137;
            } else if (timedOut) {
                exitCode = 124;
            } else if (infrastructureTimeout) {
                exitCode = 125;
            }


            CapturedOutput stdout = stdoutFuture.join();
            CapturedOutput stderr = stderrFuture.join();

            boolean truncated = outputTruncated.get()
                    || stdout.truncated()
                    || stderr.truncated();

            String resultStderr = infrastructureTimeout
                    ? stderr.text() + "Docker runner did not finish within the container startup grace period.\n"
                    : stderr.text();

            String failureKind = classifyFailure(
                    exitCode,
                    timedOut,
                    oomKilled,
                    resultStderr
            );

            String failingModule = "compile_error".equals(failureKind)
                    ? spec.getEntry()
                    : null;

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
                    resultMessage(
                            exitCode,
                            timedOut,
                            oomKilled,
                            failureKind
                    ),
                    null,
                    failingModule
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return failureResult(
                    "Execution was interrupted.",
                    130,
                    "non_zero_exit",
                    0.0,
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
                    0.0,
                    null
            );

        } finally {
            cleanupAfterExecution(process, containerName, workspace);
        }
    }

    /**
     * Performs best-effort cleanup even when the execution thread was
     * interrupted. Interrupts received during cleanup are remembered and
     * restored after all cleanup attempts have been made.
     */
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

            // Stop the container by name because the Docker CLI may have
            // exited while the container is still running.
            interrupted |= runCleanupDockerCommand(
                    3,
                    TimeUnit.SECONDS,
                    "stop",
                    "--time",
                    "1",
                    containerName
            );

            // Always attempt removal, including if the container has
            // already exited or does not exist.
            interrupted |= runCleanupDockerCommand(
                    5,
                    TimeUnit.SECONDS,
                    "rm",
                    "-f",
                    containerName
            );

            if (workspace != null) {
                try (var paths = Files.walk(workspace)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // Best-effort workspace cleanup.
                                }
                            });
                } catch (IOException ignored) {
                    // Preserve the original execution result.
                }
            }

        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Runs a Docker cleanup command with a bounded wait.
     * Returns true if the current thread was interrupted while waiting.
     */
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
            // Docker may be unavailable; preserve the original result.
        }

        return interrupted;
    }

    private List<String> buildDockerCommand(String... arguments) {
        List<String> command = new java.util.ArrayList<>();
        command.add("docker");
        command.addAll(List.of(arguments));

        return command;
    }

    /**
     * Waits for a process without abandoning cleanup on interruption.
     * Returns true if an interrupt was observed.
     */
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

        if (spec.getLanguage() == null
                || !PYTHON_EXECUTABLE.equals(
                        spec.getLanguage().toLowerCase(Locale.ROOT))) {
            return "Unsupported language. The configured runtime currently supports Python only.";
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

    private void writeRunFiles(RunSpec spec, Path workspace)
            throws IOException {

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

        String lower = stderr == null
                ? ""
                : stderr.toLowerCase(Locale.ROOT);

        if (lower.contains("syntaxerror")
                || lower.contains("indentationerror")) {
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
            String failureKind
    ) {
        if (oomKilled) {
            return "Memory limit exceeded";
        }

        if (timedOut) {
            return "Time limit exceeded";
        }

        if ("compile_error".equals(failureKind)) {
            return "Python reported a syntax or indentation error.";
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
                null,
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
                    "stop",
                    "--time",
                    "2",
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
            // The container may already have stopped.
        }
    }

    /**
     * Docker sets {@code State.OOMKilled} asynchronously, so a single inspect right after the
     * container exits can read {@code false} even though the kernel OOM-killed the process. Only
     * called for a SIGKILL exit (137) that was not a timeout: re-read the flag a few times, then
     * fall back to the measured cgroup peak, which sits at the memory limit after an OOM kill.
     */
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