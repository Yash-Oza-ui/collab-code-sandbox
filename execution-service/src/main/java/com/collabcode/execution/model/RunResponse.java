package com.collabcode.execution.model;

import com.collabcode.execution.snapshot.MemberInfo;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Run result body – T-17 V2.9 (Decision 9).
 *
 * Extends the run result of section 4.2 with decision 9 fields:
 * runner, ran_at, visibility, saved_versions, failure, compile_time_ms.
 *
 * All fields are present in every response, including failed runs (FR4.2).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class RunResponse {

    /** Echoed from the request. */
    private String request_id;

    /** Echoed from the request. */
    private String room_id;

    /** Echoed from the request. */
    private String language;

    /** Standard output – always present, may be "" (FR4.2). */
    private String stdout;

    /** Standard error – always present, may be "" (FR4.2). */
    private String stderr;

    /** Exit code of the process (FR4.4). */
    private int exit_code;

    /** Wall-clock time in milliseconds for the run stage only (FR4.3, FR6, Decision 9). */
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
     * An out-of-memory kill is failure_kind: "non_zero_exit" with oom_killed: true, never "oom".
     */
    private String failure_kind;

    /** True if stdout or stderr was cut at the output cap (NFR4.4). */
    private boolean output_truncated;

    /**
     * Short sentence shown by the UI, e.g. "Time limit exceeded".
     * Null when there is nothing to say.
     */
    private String message;

    /**
     * Time of the compile stage in milliseconds.
     * Null when the language has no compile stage (Decision 9).
     */
    private Double compile_time_ms;

    /** Member who pressed Run: member_id and display_name (FR26, Decision 9). */
    private MemberInfo runner;

    /** When the run started, as ISO 8601 UTC with milliseconds and Z (FR26). */
    private String ran_at;

    /** "room" in Pair mode, "private" in Open mode (FR27, Decision 10). */
    private String visibility;

    /**
     * Open mode: snapshot module versions.
     * Empty array [] in Pair mode (never null).
     */
    private List<SavedVersionInfo> saved_versions = new ArrayList<>();

    /**
     * Open mode: failing module details if run failed and points to one module.
     * Null for success, in Pair mode, or when failure is not pinned to one module.
     */
    private FailureInfo failure;

    // ----- getters -----

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
    public Double  getCompile_time_ms()   { return compile_time_ms; }
    public MemberInfo getRunner()         { return runner; }
    public String  getRan_at()            { return ran_at; }
    public String  getVisibility()        { return visibility; }
    public List<SavedVersionInfo> getSaved_versions() { return saved_versions; }
    public FailureInfo getFailure()       { return failure; }

    // ----- setters -----

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
    public void setCompile_time_ms(Double compile_time_ms)     { this.compile_time_ms = compile_time_ms; }
    public void setRunner(MemberInfo runner)                   { this.runner = runner; }
    public void setRan_at(String ran_at)                       { this.ran_at = ran_at; }
    public void setVisibility(String visibility)               { this.visibility = visibility; }
    public void setSaved_versions(List<SavedVersionInfo> saved_versions) {
        this.saved_versions = (saved_versions != null) ? new ArrayList<>(saved_versions) : new ArrayList<>();
    }
    public void setFailure(FailureInfo failure)                { this.failure = failure; }
}
