package com.collabcode.execution.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Run result body – T-06 section 4.2.
 *
 * All fields are present in every response, including failed runs (FR4.2).
 * Placeholder values are clearly marked in this scaffold; real values come
 * from US-08 (Jay) and US-09 (Quincy) which plug in actual Docker execution.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class RunResponse {

    /** Echoed from the request (T-06 section 4.2). */
    private String request_id;

    /** Echoed from the request (T-06 section 4.2). */
    private String room_id;

    /** Echoed from the request (T-06 section 4.2). */
    private String language;

    /** Standard output – always present, may be "" (FR4.2). */
    private String stdout;

    /** Standard error – always present, may be "" (FR4.2). */
    private String stderr;

    /** Exit code of the process (FR4.4). */
    private int exit_code;

    /** Wall-clock time in milliseconds (FR4.3, FR6). */
    private double execution_time_ms;

    /** Peak memory in megabytes (FR4.3, FR6). */
    private double peak_memory_mb;

    /** True if killed by wall-time limit (NFR4.1). */
    private boolean timed_out;

    /** True if killed by memory limit (NFR4.2). */
    private boolean oom_killed;

    /**
     * Null when the run succeeded.
     * Values: "compile_error" | "non_zero_exit" | "timeout" | null
     * (T-06 section 4.2, same as T-07).
     */
    private String failure_kind;

    /** True if stdout or stderr was cut at the output cap (NFR4.4). */
    private boolean output_truncated;

    /**
     * Short sentence shown by the UI, e.g. "Time limit exceeded".
     * Null when there is nothing to say.
     */
    private String message;

    // ----- static factory for the stub placeholder (T-03) -----

    /**
     * Returns a clearly-marked stub result.
     * US-08 replaces this with real Docker output once T-03 merges.
     */
    public static RunResponse stubPlaceholder(RunRequest req) {
        RunResponse r = new RunResponse();
        r.request_id      = req.getRequest_id();
        r.room_id         = req.getRoom_id();
        r.language        = req.getLanguage();
        // PLACEHOLDER – replaced by US-08 (Docker execution)
        r.stdout          = "[PLACEHOLDER] No code was executed. US-08 will provide real output.";
        r.stderr          = "";
        r.exit_code       = 0;
        r.execution_time_ms = 0.0;
        r.peak_memory_mb  = 0.0;
        r.timed_out       = false;
        r.oom_killed      = false;
        r.failure_kind    = null;
        r.output_truncated = false;
        r.message         = null;
        return r;
    }

    // ----- getters (Jackson serialises these) -----

    public String getRequest_id()        { return request_id; }
    public String getRoom_id()           { return room_id; }
    public String getLanguage()          { return language; }
    public String getStdout()            { return stdout; }
    public String getStderr()            { return stderr; }
    public int    getExit_code()         { return exit_code; }
    public double getExecution_time_ms() { return execution_time_ms; }
    public double getPeak_memory_mb()    { return peak_memory_mb; }
    public boolean isTimed_out()         { return timed_out; }
    public boolean isOom_killed()        { return oom_killed; }
    public String getFailure_kind()      { return failure_kind; }
    public boolean isOutput_truncated()  { return output_truncated; }
    public String getMessage()           { return message; }
}
