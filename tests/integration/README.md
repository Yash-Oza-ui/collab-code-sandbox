# Integration tests (T-08)

This folder starts the services together and checks they work together.
Right now it holds one **smoke test** that checks each service is alive. Later
integration tests (Editor + Execution in Sprint 2, AI + Execution in Sprint 3) go here too.

## What you need

- Docker Desktop (or Docker Engine) with `docker compose`
- `bash` and `curl`. On Windows, use **Git Bash**, which includes both.

## Run it

From the repository root:

```bash
bash tests/integration/up.sh       # build and start every service that exists
bash tests/integration/smoke.sh    # one PASS / SKIP / FAIL line per service
docker compose --profile "*" down  # stop everything when you are done
```

Expected output while only some services exist:

```text
SKIP  client             (client/ has no Dockerfile yet)
PASS  sync-service       http://localhost:8081/actuator/health -> 200
SKIP  execution-service  (execution-service/ has no Dockerfile yet)
SKIP  ai-ml-service      (ai-ml-service/ has no Dockerfile yet)
----
SMOKE TEST PASSED (1 service(s) up)
```

If a service is down, its line says `FAIL` with the service name, and the script exits with code 1:

```text
FAIL  sync-service       http://localhost:8081/actuator/health -> no response (is 'sync-service' running? try: docker compose ps)
----
SMOKE TEST FAILED. Service(s) down: sync-service
```

To run the smoke test **and** every other integration test:

```bash
bash tests/integration/run-all.sh
```

## How it decides what to check

- A service is checked only when its folder has a `Dockerfile`. A placeholder folder (only `.gitkeep`) is `SKIP`, so the harness never breaks while scaffolds (T-01 to T-04) are still landing.
- Each service sits behind a Compose **profile** with its own name. `up.sh` switches on only the profiles whose folder has a Dockerfile, so you never need to edit `docker-compose.yml` to start a subset.
- The smoke test only sends `GET` requests to health URLs, so it has no side effects.
- The client has no health endpoint; the test checks that its page (`/`) returns HTTP 200.

## Ports and health paths

Defaults are in `.env.example` at the repo root. They are **provisional** until T-06
(`docs/api-contracts.md`) fixes them. To change them on your machine, copy
`.env.example` to `.env` (git-ignored) and edit it. Variables already set in your
shell (for example in CI) take priority over both files.

| Service | Port variable | Default | Health path variable | Default |
|---|---|---|---|---|
| client | `CLIENT_PORT` | 3000 | `CLIENT_HEALTH_PATH` | `/` |
| sync-service | `SYNC_SERVICE_PORT` | 8081 | `SYNC_SERVICE_HEALTH_PATH` | `/actuator/health` |
| execution-service | `EXECUTION_SERVICE_PORT` | 8082 | `EXECUTION_SERVICE_HEALTH_PATH` | `/actuator/health` |
| ai-ml-service | `AI_ML_SERVICE_PORT` | 8000 | `AI_ML_SERVICE_HEALTH_PATH` | `/health` |

`SMOKE_TIMEOUT` (default 60) is the total number of seconds the smoke test waits for services to come up.

## Add a new service to the stack

1. Make sure the service folder has a `Dockerfile` and a health endpoint that returns HTTP 200.
2. Add a block to `docker-compose.yml`, with a profile that matches the service name:

   ```yaml
   my-service:
     profiles: ["my-service"]
     build: ./my-service
     ports:
       - "${MY_SERVICE_PORT:-8090}:${MY_SERVICE_PORT:-8090}"
   ```

3. Add the defaults to `.env.example`: `MY_SERVICE_PORT=8090` and `MY_SERVICE_HEALTH_PATH=/health`.
4. Add one line to the `SERVICES` list in `tests/integration/lib.sh`:

   ```bash
   "my-service        my-service        MY_SERVICE_PORT        MY_SERVICE_HEALTH_PATH"
   ```

5. Run `up.sh` and `smoke.sh` and check the new line says `PASS`.

## Add a new integration test

1. Create a file in this folder named `test-<what-it-checks>.sh`. `run-all.sh` picks up every `test-*.sh` file automatically.
2. Start from this template:

   ```bash
   #!/usr/bin/env bash
   # test-example.sh: one sentence on what this checks and which requirement (e.g. FR4.2).
   set -euo pipefail
   . "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

   base="$(service_url execution-service)"   # -> http://localhost:8082

   # Skip cleanly if the service is not in the stack yet.
   service_present execution-service || { skip execution-service "not built yet"; exit 0; }

   code="$(curl -s -o /dev/null -w '%{http_code}' "$base/actuator/health")" || true
   if [ "$code" = "200" ]; then
     pass example "health is 200"
   else
     fail example "expected 200, got $code"
     exit 1
   fi
   ```

3. Rules for every test:
   - Exit with code `0` on success and non-zero on failure, and print a `FAIL` line that says what broke.
   - Clean up anything you create (rooms, runs) so tests can run in any order.
   - Never put keys or tokens in the test. Read them from environment variables (NFR10).
4. Run `bash tests/integration/run-all.sh` and check your test shows `PASS`.

## CI

The `integration` job in `.github/workflows/ci.yml` runs on every pull request and push to `main`.
It copies `.env.example` to `.env`, runs `up.sh`, then `run-all.sh` (the smoke test plus every
`test-*.sh`). It is part of the `build-and-test` required check. On failure it prints
`docker compose ps` and the last 200 log lines, and it always tears the stack down.
A service folder without a `Dockerfile` is skipped, so the job passes while scaffolds are landing.
