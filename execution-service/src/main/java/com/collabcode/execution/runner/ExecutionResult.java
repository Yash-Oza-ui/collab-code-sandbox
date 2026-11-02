package com.collabcode.execution.runner;

/**
 * The output produced by one run of user code.
 *
 * Updated for T-19 (Decision 9):
 *  - executionTimeMs is the run stage only.
 *  - compileTimeMs is the compile stage (null if no compile stage).
 *  - failingModule names the module that caused the failure, or null.
 *  - OOM kill rule: failure_kind is "non_zero_exit" with oom_killed: true, never "oom".
 */
public class ExecutionResult {

    /** Standard output captured from the process. Always non-null (FR4.2). */
    private final String stdout;

    /** Standard error captured from the process. Always non-null (FR4.2). */
    private final String stderr;

    /** Process exit code (FR4.4). */
    private final int exitCode;

    /** Wall-clock time in milliseconds for the run stage only (FR4.3, FR6, Decision 9). */
    private final double executionTimeMs;

    /** Peak memory usage in megabytes (FR4.3, FR6). */
    private final double peakMemoryMb;

    /** True if the process was killed for exceeding the wall-time limit (NFR4.1). */
    private final boolean timedOut;

    /** True if the process was killed by the OOM killer (NFR4.2). */
    private final boolean oomKilled;

    /**
     * Null on success. One of: "compile_error", "non_zero_exit", "timeout".
     * An OOM kill is always "non_zero_exit" with oomKilled = true, never "oom".
     */
    private final String failureKind;

    /** True if stdout or stderr was truncated at the output cap (NFR4.4). */
    private final boolean outputTruncated;

    /**
     * Short human-readable sentence the UI shows beside the output.
     * Null when there is nothing extra to say.
     */
    private final String message;

    /**
     * Time of compile stage in milliseconds; null if no compile stage.
     */
    private final Double compileTimeMs;

    /**
     * Name or id of the module that failed, or null.
     */
    private final String failingModule;

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
        this(stdout, stderr, exitCode, executionTimeMs, peakMemoryMb, timedOut, oomKilled, failureKind, outputTruncated, message, null, null);
    }

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
            String message,
            Double compileTimeMs,
            String failingModule) {
        this.stdout          = stdout != null ? stdout : "";
        this.stderr          = stderr != null ? stderr : "";
        this.exitCode        = exitCode;
        this.executionTimeMs = executionTimeMs;
        this.peakMemoryMb    = peakMemoryMb;
        this.timedOut        = timedOut;
        this.oomKilled       = oomKilled;
        // Decision 9 rule: an out-of-memory kill is non_zero_exit with oom_killed: true, never "oom"
        if (oomKilled && (failureKind == null || "oom".equalsIgnoreCase(failureKind))) {
            this.failureKind = "non_zero_exit";
        } else {
            this.failureKind = failureKind;
        }
        this.outputTruncated = outputTruncated;
        this.message         = message;
        this.compileTimeMs   = compileTimeMs;
        this.failingModule   = failingModule;
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
    public Double  getCompileTimeMs()   { return compileTimeMs; }
    public String  getFailingModule()   { return failingModule; }
}
