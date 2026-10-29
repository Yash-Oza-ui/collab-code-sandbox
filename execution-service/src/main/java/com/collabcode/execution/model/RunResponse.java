package com.collabcode.execution.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Run result body – T-06 section 4.2.
 *
 * All fields are present in every response, including failed runs (FR4.2).
 * Field names intentionally match T-06 section 4.2 and docs/ai-ml-contract.md.
 *
 * History:
 *  T-03 scaffold – stub factory method (stubPlaceholder).
 *  US-08 (Jay)   – full field population from ExecutionResult; stub removed.
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

    // ----- getters (Jackson serialises these) -----

    public String  getRequest_id()        { return request_id; }
    public String  getRoom_id()           { return room_id; }
    public String  getLanguage()          { return language; }
    public String  getStdout()            { return stdout; }
    public String  getStderr()            { return stderr; }
    public int     getExit_code()         { return exit_code; }
    public double  getExecution_time_ms() { return execution_time_ms; }
    public double  getPeak_memory_mb()    { return peak_memory_mb; }
    public boolean isTimed_out()          { return timed_out; }
    public boolean isOom_killed()         { return oom_killed; }
    public String  getFailure_kind()      { return failure_kind; }
    public boolean isOutput_truncated()   { return output_truncated; }
    public String  getMessage()           { return message; }

    // ----- setters (used by RunController to build from ExecutionResult) -----

    public void setRequest_id(String request_id)               { this.request_id = request_id; }
    public void setRoom_id(String room_id)                     { this.room_id = room_id; }
    public void setLanguage(String language)                   { this.language = language; }
    public void setStdout(String stdout)                       { this.stdout = stdout; }
    public void setStderr(String stderr)                       { this.stderr = stderr; }
    public void setExit_code(int exit_code)                    { this.exit_code = exit_code; }
    public void setExecution_time_ms(double execution_time_ms) { this.execution_time_ms = execution_time_ms; }
    public void setPeak_memory_mb(double peak_memory_mb)       { this.peak_memory_mb = peak_memory_mb; }
    public void setTimed_out(boolean timed_out)                { this.timed_out = timed_out; }
    public void setOom_killed(boolean oom_killed)              { this.oom_killed = oom_killed; }
    public void setFailure_kind(String failure_kind)           { this.failure_kind = failure_kind; }
    public void setOutput_truncated(boolean output_truncated)  { this.output_truncated = output_truncated; }
    public void setMessage(String message)                     { this.message = message; }
}
