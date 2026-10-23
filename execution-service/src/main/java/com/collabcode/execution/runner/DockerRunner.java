package com.collabcode.execution.runner;

import com.collabcode.execution.config.ExecutionLimitsProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DockerRunner {

    private static final String IMAGE = "collab-sandbox-python:0.1.0";

    private final ExecutionLimitsProperties limits;

    public DockerRunner(ExecutionLimitsProperties limits) {
        this.limits = limits;
    }

    public record ExecutionResult(
            String stdout,
            String stderr,
            int exitCode,
            long executionTimeMs,
            boolean timedOut,
            boolean outputTruncated,
            boolean oomKilled
    ) {
        public ExecutionResult(
                String stdout,
                String stderr,
                int exitCode,
                long executionTimeMs
        ) {
            this(stdout, stderr, exitCode, executionTimeMs,
                    false, false, false);
        }

        public ExecutionResult(
                String stdout,
                String stderr,
                int exitCode,
                long executionTimeMs,
                boolean timedOut,
                boolean outputTruncated
        ) {
            this(stdout, stderr, exitCode, executionTimeMs,
                    timedOut, outputTruncated, false);
        }
    }

    private record CapturedOutput(String text, boolean truncated) {}

    public ExecutionResult execute(String code, String stdin)
            throws IOException, InterruptedException {

        validateLimits();

        String runId = UUID.randomUUID().toString();
        String containerName = "run-" + runId;
        Path workspace = Files.createTempDirectory("run-" + runId + "-");

        long start = System.nanoTime();
        Process process = null;
        boolean timedOut = false;
        boolean oomKilled = false;
        int exitCode = 1;

        AtomicLong outputBytes = new AtomicLong(0);
        AtomicBoolean outputTruncated = new AtomicBoolean(false);

        try {
            Files.writeString(
                    workspace.resolve("solution.py"),
                    code,
                    StandardCharsets.UTF_8
            );

            ProcessBuilder builder = new ProcessBuilder(
                    "docker", "run",
                    "--interactive",
                    "--network", "none",
                    "--name", containerName,
                    "--user", "1001:1001",
                    "--memory", limits.getMemoryMb() + "m",
                    "--pids-limit", String.valueOf(limits.getPidsLimit()),
                    "--read-only",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
                    "--mount",
                    "type=bind,source=" + workspace.toAbsolutePath()
                            + ",target=/sandbox/code,readonly",
                    IMAGE,
                    "python", "/sandbox/code/solution.py"
            );

            // Keep the container until its exit state has been inspected.
            process = builder.start();
            Process runningProcess = process;

            CompletableFuture<CapturedOutput> stdoutFuture =
                    CompletableFuture.supplyAsync(
                            () -> captureStream(
                                    runningProcess.getInputStream(),
                                    outputBytes,
                                    outputTruncated
                            )
                    );

            CompletableFuture<CapturedOutput> stderrFuture =
                    CompletableFuture.supplyAsync(
                            () -> captureStream(
                                    runningProcess.getErrorStream(),
                                    outputBytes,
                                    outputTruncated
                            )
                    );

            try (var output = process.getOutputStream()) {
                if (stdin != null && !stdin.isEmpty()) {
                    output.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // The container may exit before consuming stdin.
                if (process.isAlive()) {
                    throw e;
                }
            }

            boolean finished = process.waitFor(
                    limits.getWallTimeSeconds(),
                    TimeUnit.SECONDS
            );

            if (!finished) {
                /*
                 * The deadline expired. Stop the container, then inspect its
                 * state before removing it. This also handles an OOM event
                 * that occurred at approximately the same time as the deadline.
                 */
                stopContainer(containerName);

                oomKilled = inspectOomKilled(containerName);

                if (oomKilled) {
                    // Docker confirms that the container was OOM-killed.
                    timedOut = false;
                    exitCode = 137;
                } else {
                    // No OOM evidence: classify this as a wall-time timeout.
                    timedOut = true;
                    exitCode = 124;
                }

                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } else {
                exitCode = process.exitValue();

                // Inspect the state before the finally block removes the container.
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

            return new ExecutionResult(
                    stdout.text(),
                    stderr.text(),
                    exitCode,
                    (System.nanoTime() - start) / 1_000_000,
                    timedOut,
                    truncated,
                    oomKilled
            );

        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();

                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Always attempt container cleanup.
            try {
                Process cleanup = new ProcessBuilder(
                        "docker", "rm", "-f", containerName
                ).start();

                if (!cleanup.waitFor(5, TimeUnit.SECONDS)) {
                    cleanup.destroyForcibly();
                    cleanup.waitFor();
                }
            } catch (Exception ignored) {
                // Preserve the original execution result or exception.
            }

            // Remove the temporary workspace and its contents.
            try (var paths = Files.walk(workspace)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best-effort workspace cleanup.
                    }
                });
            } catch (IOException ignored) {
                // Preserve the original execution result or exception.
            }
        }
    }

    private void stopContainer(String containerName) {
        Process stop = null;

        try {
            stop = new ProcessBuilder(
                    "docker", "kill", containerName
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

    private boolean inspectOomKilled(String containerName) {
        Process inspect = null;

        try {
            inspect = new ProcessBuilder(
                    "docker", "inspect",
                    "--format", "{{.State.OOMKilled}}",
                    containerName
            ).start();

            boolean finished = inspect.waitFor(5, TimeUnit.SECONDS);

            if (!finished) {
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

                    if (outputBytes.compareAndSet(used, used + allowed)) {
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
                || limits.getMaxRequestBodyBytes() <= 0) {
            throw new IllegalStateException(
                    "Execution limits must all be greater than zero."
            );
        }
    }
}