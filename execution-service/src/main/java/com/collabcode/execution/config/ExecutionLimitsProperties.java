package com.collabcode.execution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Resource limits for sandboxed execution (DR3, NFR4).
 *
 * Values are read from application.yml under app.execution.limits.
 * They are NEVER hard-coded in Java source (DR3).
 *
 * Sprint 3 (US-13): all NFR4 limits are now real, enforced values.
 * US-14 will add per-language compile-stage support when non-Python runtimes land.
 */
@Component
@ConfigurationProperties(prefix = "app.execution.limits")
public class ExecutionLimitsProperties {

    /** NFR4.1 – maximum wall-clock time per run, in seconds. */
    private int wallTimeSeconds;

    /** NFR4.2 – maximum memory per container, in megabytes. */
    private int memoryMb;

    /** NFR4.3 – maximum number of processes / threads inside the container. */
    private int pidsLimit;

    /** NFR4.5 – compile-stage timeout in seconds. */
    private int compileTimeoutSeconds;

    /** NFR4.4 – maximum total output (stdout + stderr combined), in bytes. */
    private long maxOutputBytes;

    /** Maximum size of a single run request body, in bytes (T-06 section 2). */
    private long maxRequestBodyBytes;

    /** FR16 – runs waiting or running at once; a new run beyond this is refused (US-11). */
    private int maxQueueLength;

    /** FR16 – seconds the client is told to wait after a QUEUE_FULL refusal (T-06 section 7.1). */
    private int queueRetryAfterSeconds;

    /** NFR14 / US-64 – aggregate wall-clock time budget per repeated-run request, in milliseconds. */
    private long aggregateTimeBudgetMs;

    /** NFR14 / US-64 – maximum number of runs allowed per repeated-run request. */
    private int maxRunsPerRequest;

    /** NFR14 / US-64 – seconds sent in Retry-After and error.retry_after_seconds on RUN_BUDGET_EXCEEDED. */
    private int budgetRetryAfterSeconds;

    // ----- getters and setters -----

    public int getWallTimeSeconds()            { return wallTimeSeconds; }
    public void setWallTimeSeconds(int v)      { this.wallTimeSeconds = v; }

    public int getMemoryMb()                   { return memoryMb; }
    public void setMemoryMb(int v)             { this.memoryMb = v; }

    public int getPidsLimit()                  { return pidsLimit; }
    public void setPidsLimit(int v)            { this.pidsLimit = v; }

    public int getCompileTimeoutSeconds()      { return compileTimeoutSeconds; }
    public void setCompileTimeoutSeconds(int v) { this.compileTimeoutSeconds = v; }

    public long getMaxOutputBytes()            { return maxOutputBytes; }
    public void setMaxOutputBytes(long v)      { this.maxOutputBytes = v; }

    public long getMaxRequestBodyBytes()       { return maxRequestBodyBytes; }
    public void setMaxRequestBodyBytes(long v) { this.maxRequestBodyBytes = v; }

    public int getMaxQueueLength()             { return maxQueueLength; }
    public void setMaxQueueLength(int v)       { this.maxQueueLength = v; }

    public int getQueueRetryAfterSeconds()     { return queueRetryAfterSeconds; }
    public void setQueueRetryAfterSeconds(int v) { this.queueRetryAfterSeconds = v; }

    public long getAggregateTimeBudgetMs()     { return aggregateTimeBudgetMs; }
    public void setAggregateTimeBudgetMs(long v) { this.aggregateTimeBudgetMs = v; }

    public int getMaxRunsPerRequest()          { return maxRunsPerRequest; }
    public void setMaxRunsPerRequest(int v)    { this.maxRunsPerRequest = v; }

    public int getBudgetRetryAfterSeconds()    { return budgetRetryAfterSeconds; }
    public void setBudgetRetryAfterSeconds(int v) { this.budgetRetryAfterSeconds = v; }
}
