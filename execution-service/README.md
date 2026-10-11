# execution-service

Spring Boot service that runs user code in isolated Docker containers.

- **Port:** `8082` (T-06 section 1)
- **Health:** `GET /actuator/health`
- **Run:** `POST /v1/runs`

> **Sprint 1 / T-03 status:** The run endpoint returns a **placeholder** response.
> Real Docker execution is added in US-08 (Jay Limbasiya) and US-09 (Quincy Vadi).

---

## Prerequisites

- Java 21 (Temurin recommended)
- Maven 3.9+ **or** use the Maven Wrapper (`./mvnw`) – no install needed

---

## How to run locally

```bash
cd execution-service

# Run all tests
./mvnw -B test          # Linux / macOS
mvnw.cmd -B test        # Windows

# Start the service (stays running on port 8082)
./mvnw spring-boot:run
```

---

## How to verify (curl)

Replace `<port>` with `8082` and `<run-path>` with `/v1/runs`.

```bash
# 1. Health – expected: 200 {"status":"UP"}
curl -i http://localhost:8082/actuator/health

# 2. Valid run request – expected: 200 with stdout, stderr and all result fields
curl -s -X POST http://localhost:8082/v1/runs \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
    "room_id":    "room-demo-01",
    "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
    "language":   "python",
    "code":       "print(\"hello\")",
    "stdin":      ""
  }'

# 3. Missing 'code' – expected: 400 INVALID_REQUEST
curl -i -X POST http://localhost:8082/v1/runs \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
    "room_id":    "room-demo-01",
    "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
    "language":   "java"
  }'

# 4. Unsupported language – expected: 422 UNSUPPORTED_LANGUAGE
curl -i -X POST http://localhost:8082/v1/runs \
  -H "Content-Type: application/json" \
  -d '{
    "request_id": "0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10",
    "room_id":    "room-demo-01",
    "session_id": "7c1e4a52-3b0d-4f6a-9a2e-8d5f1b6c3e90",
    "language":   "ruby",
    "code":       "puts \"hi\""
  }'
```

---

## Resource limits (DR3)

Limits are read from `src/main/resources/application.yml` under `app.execution.limits`.
They are **never hard-coded** in Java source. Current values are placeholders;
Sprint 2 sets real limits (NFR4).

| Key | Placeholder | Notes |
|---|---|---|
| `wall-time-seconds` | 5 | NFR4.1 – killed after this many seconds |
| `memory-mb` | 128 | NFR4.2 – container memory cap |
| `pids-limit` | 64 | NFR4.3 – max processes inside container |
| `max-output-bytes` | 1 048 576 | NFR4.4 – 1 MiB stdout+stderr cap |
| `max-request-body-bytes` | 1 048 576 | T-06 section 2 – request body cap |

Override any value by setting `APP_EXECUTION_LIMITS_MEMORY_MB=256` (etc.) in your environment.

---

## Supported languages (DR1)

`cpp` · `python` · `c` · `java` · `javascript`

---

## Project structure

```
execution-service/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/collabcode/execution/
    │   │   ├── ExecutionServiceApplication.java   # entry point
    │   │   ├── config/
    │   │   │   └── ExecutionLimitsProperties.java # DR3 limits (Bhavya)
    │   │   ├── controller/
    │   │   │   ├── RunController.java             # POST /v1/runs
    │   │   │   └── GlobalExceptionHandler.java    # 4xx error shapes
    │   │   └── model/
    │   │       ├── RunRequest.java                # T-06 §4.1
    │   │       └── RunResponse.java               # T-06 §4.2
    │   └── resources/
    │       └── application.yml                    # port, limits config
    └── test/
        └── java/com/collabcode/execution/
            ├── config/
            │   └── ExecutionLimitsPropertiesTest.java  # DR3 test (Bhavya)
            └── controller/
                └── RunControllerTest.java              # endpoint tests
```

---

## CI

The CI job for this service is in `.github/workflows/ci.yml` (T-09).
It detects `pom.xml` and runs `./mvnw -B verify` automatically.
