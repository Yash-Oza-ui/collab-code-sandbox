package com.collabcode.execution.runner;

/**
 * The output produced by one run of user code.
 *
 * This record is the shared contract between the CodeRunner interface
 * (US-08, Jay) and its implementations:
 *   - FakeCodeRunner  – deterministic stub used until US-09 merges
 *   - DockerCodeRunner – real Docker runner (US-09, Quincy)
 *
 * Field names intentionally match T-06 section 4.2 and docs/ai-ml-contract.md
 * so that RunController can copy them straight into RunResponse without
 * translation.
 */
public class ExecutionResult {

    /** Standard output captured from the process. Always non-null (FR4.2). */
    private final String stdout;

    /** Standard error captured from the process. Always non-null (FR4.2). */
    private final String stderr;

    /** Process exit code (FR4.4). */
    private final int exitCode;

    /** Wall-clock time measured by execution-service in milliseconds (FR4.3, FR6). */
    private final double executionTimeMs;

    /** Peak memory usage in megabytes (FR4.3, FR6). */
    private final double peakMemoryMb;

    /** True if the process was killed for exceeding the wall-time limit (NFR4.1). */
    private final boolean timedOut;

    /** True if the process was killed by the OOM killer (NFR4.2). */
    private final boolean oomKilled;

    /**
     * Null on success. One of: "compile_error", "non_zero_exit", "timeout".
     * Same values as T-07 so execution-service passes them through unchanged.
     */
    private final String failureKind;

    /** True if stdout or stderr was truncated at the output cap (NFR4.4). */
    private final boolean outputTruncated;

    /**
     * Short human-readable sentence the UI shows beside the output.
     * Null when there is nothing extra to say.
     * Examples: "Time limit exceeded", "Memory limit exceeded".
     */
    private final String message;

    public ExecutionResult(
            String stdout,
            String stderr,
            int exitCode,
            double executionTimeMs,
            double peakMemoryMb,
            boolean timedOut,
            boolean oomKilled,
            String failureKind,
            boolean outputTruncated,
            String message) {
        this.stdout          = stdout          != null ? stdout          : "";
        this.stderr          = stderr          != null ? stderr          : "";
        this.exitCode        = exitCode;
        this.executionTimeMs = executionTimeMs;
        this.peakMemoryMb    = peakMemoryMb;
        this.timedOut        = timedOut;
        this.oomKilled       = oomKilled;
        this.failureKind     = failureKind;
        this.outputTruncated = outputTruncated;
        this.message         = message;
    }

    // ----- getters -----

    public String  getStdout()          { return stdout; }
    public String  getStderr()          { return stderr; }
    public int     getExitCode()        { return exitCode; }
    public double  getExecutionTimeMs() { return executionTimeMs; }
    public double  getPeakMemoryMb()    { return peakMemoryMb; }
    public boolean isTimedOut()         { return timedOut; }
    public boolean isOomKilled()        { return oomKilled; }
    public String  getFailureKind()     { return failureKind; }
    public boolean isOutputTruncated()  { return outputTruncated; }
    public String  getMessage()         { return message; }
}
