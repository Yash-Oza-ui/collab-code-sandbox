
package com.collabcode.execution.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public class RunResponse {

    private String request_id;
    private String room_id;
    private String language;
    private String stdout;
    private String stderr;
    private int exit_code;
    private double execution_time_ms;
    private double peak_memory_mb;
    private boolean timed_out;
    private boolean oom_killed;
    private String failure_kind;
    private boolean output_truncated;
    private String message;

    public static RunResponse stubPlaceholder(RunRequest req) {
        RunResponse r = new RunResponse();
        r.request_id = req.getRequest_id();
        r.room_id = req.getRoom_id();
        r.language = req.getLanguage();
        r.stdout = "[PLACEHOLDER] No code was executed.";
        r.stderr = "";
        r.exit_code = 0;
        r.execution_time_ms = 0.0;
        r.peak_memory_mb = 0.0;
        r.timed_out = false;
        r.oom_killed = false;
        r.failure_kind = null;
        r.output_truncated = false;
        r.message = null;
        return r;
    }

    public static RunResponse fromExecution(
            RunRequest req, String stdout, String stderr,
            int exitCode, double executionTimeMs
    ) {
        return fromExecution(
                req, stdout, stderr, exitCode, executionTimeMs,
                false, false, false
        );
    }

    public static RunResponse fromExecution(
            RunRequest req, String stdout, String stderr,
            int exitCode, double executionTimeMs,
            boolean timedOut, boolean outputTruncated
    ) {
        return fromExecution(
                req, stdout, stderr, exitCode, executionTimeMs,
                timedOut, outputTruncated, false
        );
    }

    public static RunResponse fromExecution(
            RunRequest req, String stdout, String stderr,
            int exitCode, double executionTimeMs,
            boolean timedOut, boolean outputTruncated,
            boolean oomKilled
    ) {
        RunResponse r = new RunResponse();
        r.request_id = req.getRequest_id();
        r.room_id = req.getRoom_id();
        r.language = req.getLanguage();
        r.stdout = stdout;
        r.stderr = stderr;
        r.exit_code = exitCode;
        r.execution_time_ms = executionTimeMs;
        r.peak_memory_mb = 0.0;
        r.timed_out = timedOut;
        r.oom_killed = oomKilled;
        r.output_truncated = outputTruncated;

        if (timedOut) {
            r.failure_kind = "timeout";
            r.message = "Time limit exceeded";
        } else if (oomKilled) {
            r.failure_kind = "oom";
            r.message = "Memory limit exceeded";
        } else if (exitCode != 0) {
            r.failure_kind = "non_zero_exit";
            r.message = null;
        } else if (outputTruncated) {
            r.failure_kind = null;
            r.message = "Output limit exceeded";
        } else {
            r.failure_kind = null;
            r.message = null;
        }

        return r;
    }

    public String getRequest_id() { return request_id; }
    public String getRoom_id() { return room_id; }
    public String getLanguage() { return language; }
    public String getStdout() { return stdout; }
    public String getStderr() { return stderr; }
    public int getExit_code() { return exit_code; }
    public double getExecution_time_ms() { return execution_time_ms; }
    public double getPeak_memory_mb() { return peak_memory_mb; }
    public boolean isTimed_out() { return timed_out; }
    public boolean isOom_killed() { return oom_killed; }
    public String getFailure_kind() { return failure_kind; }
    public boolean isOutput_truncated() { return output_truncated; }
    public String getMessage() { return message; }
}
