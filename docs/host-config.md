# Host Configuration Reference (DR3, US-16)

> [!IMPORTANT]
> **Final per-host values are recorded ONLY after the deployment host is fixed
> and tested.** The values below are placeholder defaults. Every value must be
> overridden via environment variable or a host-specific profile file — never
> by editing Java/Python source.

## How to override a limit

### Spring Boot services (execution-service, sync-service)

Spring Boot automatically maps environment variables to properties:
- Property key: `app.execution.limits.wall-time-seconds`
- Environment variable: `APP_EXECUTION_LIMITS_WALL_TIME_SECONDS=3`

Set the env var in your host's `.env` file (git-ignored), or pass it to
`docker compose up` via the `environment:` block in `docker-compose.yml`.

### Python service (ai-ml-service)

Values are read from environment variables directly in `app/config.py`:
```
AI_MAX_TOKENS_PER_REQUEST=512 python -m uvicorn app.main:app
```
Or set them in `ai-ml-service/.env` (git-ignored, copied from `.env.example`).

---

## Full DR3 Configuration Table

| DR3 Ref  | Description                              | Owner service      | Config key / env var                              | Default     | Story (enforces) |
|----------|------------------------------------------|--------------------|---------------------------------------------------|-------------|-----------------|
| NFR4.1   | Wall-clock run limit                     | execution-service  | `APP_EXECUTION_LIMITS_WALL_TIME_SECONDS`          | `5`         | US-13 ✅         |
| NFR4.2   | Memory limit per container (MB)          | execution-service  | `APP_EXECUTION_LIMITS_MEMORY_MB`                  | `128`       | US-13 ✅         |
| NFR4.3   | Process/thread limit inside container    | execution-service  | `APP_EXECUTION_LIMITS_PIDS_LIMIT`                 | `64`        | US-13 ✅         |
| NFR4.4   | Max output cap (stdout+stderr, bytes)    | execution-service  | `APP_EXECUTION_LIMITS_MAX_OUTPUT_BYTES`           | `1048576`   | US-13 ✅         |
| NFR4.5   | Compile-stage timeout (seconds)          | execution-service  | `APP_EXECUTION_LIMITS_COMPILE_TIMEOUT_SECONDS`    | `10`        | US-13 ✅         |
| DR5      | Sanitizer / request body limit (bytes)   | execution-service  | `APP_EXECUTION_LIMITS_MAX_REQUEST_BODY_BYTES`     | `1048576`   | US-13 ✅         |
| FR16     | Max concurrent runs in queue             | execution-service  | `EXECUTION_MAX_QUEUE_LENGTH`                      | `10`        | US-11 ✅         |
| FR16     | Retry-After on QUEUE_FULL (seconds)      | execution-service  | `APP_EXECUTION_LIMITS_QUEUE_RETRY_AFTER_SECONDS`  | `5`         | US-11 ✅         |
| NFR14    | Aggregate repeated-run time budget (ms)  | execution-service  | `APP_EXECUTION_LIMITS_AGGREGATE_TIME_BUDGET_MS`   | `20000`     | US-64 ✅         |
| NFR14    | Max runs per repeated-run request        | execution-service  | `APP_EXECUTION_LIMITS_MAX_RUNS_PER_REQUEST`       | `10`        | US-64 ✅         |
| NFR14    | Retry-After on RUN_BUDGET_EXCEEDED (s)   | execution-service  | `APP_EXECUTION_LIMITS_BUDGET_RETRY_AFTER_SECONDS` | `30`        | US-64 ✅         |
| NFR15    | Max members per room                     | sync-service       | `APP_ROOM_LIMITS_MAX_MEMBERS_PER_ROOM`            | `10`        | US-16 🟡 (US-xx) |
| FR31.2   | Host-leaves timeout (ms)                 | sync-service       | `APP_ROOM_LIMITS_HOST_LEAVES_TIMEOUT_MS`          | `30000`     | US-16 🟡 (US-xx) |
| NFR14    | Aggregate run time budget per session    | sync-service       | `APP_ROOM_LIMITS_AGGREGATE_RUN_BUDGET_MS`         | `0` (none)  | US-63 🔲         |
| NFR14    | Max runs per session                     | sync-service       | `APP_ROOM_LIMITS_MAX_RUNS_PER_SESSION`            | `0` (none)  | US-63 🔲         |
| FR15     | Run record retention (seconds, provisional) | sync-service | `APP_RUN_HISTORY_RETENTION_SECONDS` | `86400` (24h, not final) | US-30 🟡 |
| FR15     | Run record sweep interval (ms) | sync-service | `APP_RUN_HISTORY_SWEEP_INTERVAL_MS` | `60000` | US-30 ✅ |
| FR34     | Audit log max entries per room           | sync-service       | `APP_AUDIT_LOG_MAX_ENTRIES`                       | `100`       | US-58 ✅ |
| FR37.2   | AI slowdown factor                       | ai-ml-service      | `AI_SLOWDOWN_FACTOR`                              | `1.0`       | US-77 🔲         |
| FR40.4   | LLM request timeout (seconds)            | ai-ml-service      | `LLM_TIMEOUT_SECONDS`                             | `10.0`      | US-16 ✅         |
| FR40.4   | Max tokens per AI request                | ai-ml-service      | `AI_MAX_TOKENS_PER_REQUEST`                       | `4096`      | US-24 ✅         |
| NFR5.3   | Hourly per-room AI request limit         | ai-ml-service      | `ROOM_HOURLY_LIMIT`                               | `20`        | US-22 ✅         |
| NFR7.2   | Monthly AI spend cap (USD)               | ai-ml-service      | `MONTHLY_BUDGET_USD`                              | `10.0`      | US-24 ✅         |

**Notes on AI limits (US-24):**
- `AI_MAX_TOKENS_PER_REQUEST`: Canonical configuration for maximum generation tokens per request (default `4096`). `LLM_MAX_OUTPUT_TOKENS` is supported as a backwards-compatible alias. Both provider request bodies and admission reservation estimates use this unified value.
- `MONTHLY_BUDGET_USD`: Must be a finite positive number (> 0, default `10.0`). Zero, negative, NaN, and infinity values are rejected at startup. Fail-closed budget enforcement: reaching budget strictly blocks all further paid AI calls.
- `ALERT_WEBHOOK_URL`: Optional HTTPS webhook URL for 80% monthly budget alerts (NFR7.4). Dispatched via a non-blocking background worker.

**Legend:** ✅ Enforced | 🟡 Entry created, enforcement in a future story | 🔲 Placeholder, story not yet started

---

## Worked example – lower wall-time for a low-power host

```bash
# .env on the deployment host
APP_EXECUTION_LIMITS_WALL_TIME_SECONDS=3
APP_EXECUTION_LIMITS_MEMORY_MB=64
APP_ROOM_LIMITS_MAX_MEMBERS_PER_ROOM=5
AI_MAX_TOKENS_PER_REQUEST=1024
```

Then start the stack:
```bash
docker compose --profile execution-service --profile sync-service --profile ai-ml-service up -d
```

No code change is needed. The services read the values on startup.

---

## Rules for final per-host values

1. **Do not commit host-specific values** to this repository. Use a git-ignored `.env` file on each host.
2. **Document final values** in a separate internal runbook after the deployment host is benchmarked.
3. **Re-run the full CI suite** after changing any value to ensure no test assumes a previous default.

### US-55 Host departure timeout

`APP_ROOM_LIMITS_HOST_LEAVES_TIMEOUT_MS` (default **30,000 ms**, provisional)
sets how long the Host may be disconnected before automatic closure. The
`APP_ROOM_LIMITS_HOST_LEAVES_CHECK_INTERVAL_MS` scheduler interval defaults to
**1,000 ms**. The latter controls scheduling precision, not the grace period.
The central `HostDepartureManager.closeRoom` retains the closed-room tombstone
for `4410` responses; US-56 will extend it to remove related data and document
the final tombstone retention policy in both PRs.
