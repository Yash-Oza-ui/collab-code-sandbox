# US-14 · Runtime Images for Main Coursework Languages

**Part of:** EPIC 2 – Sandboxed Execution Engine | Sprint 3
**Traceability:** DR1, DR2, DR3, DR4, DR5, NFR3, NFR4, FR5 · US-14
**PR Branch:** `feature/US-14-language-images`
**Reviewers:** Yash Oza, Digvijay Parmar (sanitizer check)

---

## 1. Purpose

US-14 expands sandboxed execution from Python-only (T-14) to all five main coursework languages specified by **DR1**: **C++, Python, C, Java, and JavaScript**.

Each language executes inside its own dedicated runtime image built from a pinned base image in the repository. Compiled languages (**C++, C, Java**) run through a distinct compile stage (**NFR4.5**) followed by a run stage. Interpreted languages (**Python, JavaScript**) execute directly in a run stage.

The **C and C++ images include full toolchain support for AddressSanitizer (ASan) and UndefinedBehaviorSanitizer (UBSan)** to prepare for the sanitizer run feature (**US-73**), without enabling sanitizers during normal runs.

---

## 2. Supported Languages & Testing Priority Order (DR1)

Testing and build priority strictly follows **DR1**:

| Priority | Language Identifier | Runtime Image Tag | Base Image (Pinned) | Type | File Naming Rule (DR4, T-17 V2.8) |
|---|---|---|---|---|---|
| **1** | `cpp` | `collab-sandbox-cpp:0.1.0` | `debian:12.9-slim` | Compiled | `.cpp`, `.cc`, `.cxx` (e.g. `main.cpp`) |
| **2** | `python` | `collab-sandbox-python:0.1.0` | `python:3.12.9-slim-bookworm` | Interpreted | `.py` (e.g. `main.py`) |
| **3** | `c` | `collab-sandbox-c:0.1.0` | `debian:12.9-slim` | Compiled | `.c` (e.g. `main.c`) |
| **4** | `java` | `collab-sandbox-java:0.1.0` | `eclipse-temurin:21.0.6_7-jdk-noble` | Compiled | `.java` (matches public class name, e.g. `Main.java`) |
| **5** | `javascript` | `collab-sandbox-javascript:0.1.0` | `node:22.14.0-bookworm-slim` | Interpreted | `.js` (e.g. `main.js`) |

> **File Naming Rule (DR4, T-17 V2.8):** File names come dynamically from the run request (`source.entry` and `source.files[i].name`), **not** a fixed `solution.py`-style name. Filenames must be plain names with the language-appropriate extension and without directory traversal characters (`/`, `\`, `..`).

---

## 3. Directory Structure

```
execution-service/runtime-images/
├── README.md                   ← this file
├── cpp/                        ← Priority 1: C++ runtime (g++ 12, ASan/UBSan support)
│   ├── Dockerfile
│   └── samples/
│       ├── hello_world.cpp
│       ├── stdin_echo.cpp
│       ├── stderr_split.cpp
│       ├── exit_nonzero.cpp
│       ├── network_probe.cpp
│       ├── compile_error.cpp
│       └── sanitizer_sample.cpp
├── python/                     ← Priority 2: Python runtime (Python 3.12)
│   ├── Dockerfile
│   └── samples/
│       ├── hello_world.py
│       ├── stdin_echo.py
│       ├── stderr_split.py
│       ├── exit_nonzero.py
│       ├── network_probe.py
│       └── compile_error.py
├── c/                          ← Priority 3: C runtime (gcc 12, ASan/UBSan support)
│   ├── Dockerfile
│   └── samples/
│       ├── hello_world.c
│       ├── stdin_echo.c
│       ├── stderr_split.c
│       ├── exit_nonzero.c
│       ├── network_probe.c
│       ├── compile_error.c
│       └── sanitizer_sample.c
├── java/                       ← Priority 4: Java runtime (Eclipse Temurin JDK 21)
│   ├── Dockerfile
│   └── samples/
│       ├── Main.java
│       ├── StdinEcho.java
│       ├── StderrSplit.java
│       ├── ExitNonzero.java
│       ├── NetworkProbe.java
│       └── CompileError.java
├── javascript/                 ← Priority 5: JavaScript runtime (Node.js 22)
│   ├── Dockerfile
│   └── samples/
│       ├── hello_world.js
│       ├── stdin_echo.js
│       ├── stderr_split.js
│       ├── exit_nonzero.js
│       └── network_probe.js
└── tests/
    ├── verify.sh               ← Bash test script running all checks in priority order
    └── verify.ps1              ← PowerShell equivalent for Windows environments
```

---

## 4. Compiler Sanitizer Support for C and C++ (US-73, DR5)

> **Sanitizer Runtime Support Note:** The C and C++ images contain the compiler and runtime library support (`libasan8` and `libubsan1`) required for AddressSanitizer and UndefinedBehaviorSanitizer runs under **US-73**. Sanitizers are not enabled for normal runs, ensuring zero performance overhead for standard lab execution. Sanitizer builds require higher memory (>128 MB), which is configured under US-16 and enforced in US-73.

To verify sanitizer toolchain readiness in the C and C++ images:
```bash
# C++ sanitizer check:
docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/cpp/samples:/sandbox/code:ro" \
  collab-sandbox-cpp:0.1.0 \
  sh -c "g++ -fsanitize=address,undefined -g -O1 /sandbox/code/sanitizer_sample.cpp -o /tmp/san && /tmp/san"

# C sanitizer check:
docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/c/samples:/sandbox/code:ro" \
  collab-sandbox-c:0.1.0 \
  sh -c "gcc -fsanitize=address,undefined -g -O1 /sandbox/code/sanitizer_sample.c -o /tmp/san && /tmp/san"
```

---

## 5. Build the Images

Build the runtime images from the repository root in testing priority order:

```bash
# 1. C++
docker build -t collab-sandbox-cpp:0.1.0 execution-service/runtime-images/cpp

# 2. Python
docker build -t collab-sandbox-python:0.1.0 execution-service/runtime-images/python

# 3. C
docker build -t collab-sandbox-c:0.1.0 execution-service/runtime-images/c

# 4. Java
docker build -t collab-sandbox-java:0.1.0 execution-service/runtime-images/java

# 5. JavaScript
docker build -t collab-sandbox-javascript:0.1.0 execution-service/runtime-images/javascript
```

---

## 6. Run Conventions & Contract

### 6.1 Two-Stage Execution for Compiled Languages (NFR4.5)

For **C++, C, and Java**, execution proceeds in two distinct stages:

1. **Compile Stage:**
   - Isolated container with `--read-only` root, source mount `:ro` at `/sandbox/code`, and output directory at `/sandbox/build`.
   - Timeout: `compile-timeout-seconds` (configured default 10 s, NFR4.5).
   - If compilation fails (exit code != 0), execution terminates immediately. The runner returns `failure_kind: "compile_error"`, compiler output in `stderr`, `execution_time_ms: 0.0`, and `compile_time_ms` capturing the compile duration.
2. **Run Stage:**
   - Fresh isolated container with `--read-only` root, source mount `:ro`, and compiled build directory mounted `:ro` at `/sandbox/build`.
   - Timeout: `wall-time-seconds` (configured default 5 s, NFR4.1).
   - User stdin is piped to the running process.
   - Stdout and stderr are captured independently up to `max-output-bytes` (1 MiB).

### 6.2 Interpreted Languages

For **Python and JavaScript**, code is executed directly in a single run stage without a compile step:
- Python: `python /sandbox/code/<entry>`
- JavaScript: `node /sandbox/code/<entry>`
- `compile_time_ms` is `null`.

### 6.3 Invocation Commands Summary

| Language | Compile Command (Stage 1) | Run Command (Stage 2) |
|---|---|---|
| **C++** | `g++ -O2 -std=c++20 -I/sandbox/code /sandbox/code/*.cpp -o /sandbox/build/a.out` | `/sandbox/build/a.out` |
| **Python** | _None_ | `python /sandbox/code/<entry>` |
| **C** | `gcc -O2 -std=c17 -I/sandbox/code /sandbox/code/*.c -o /sandbox/build/a.out -lm` | `/sandbox/build/a.out` |
| **Java** | `javac -d /sandbox/build /sandbox/code/*.java` | `java -cp /sandbox/build <ClassName>` |
| **JavaScript** | _None_ | `node /sandbox/code/<entry>` |

---

## 7. Verification

Run the verification test suite in priority order across all five languages with a single command:

**Linux / macOS / Git Bash:**
```bash
cd execution-service/runtime-images && bash tests/verify.sh
# Or from repository root:
bash execution-service/runtime-images/tests/verify.sh
```

**Windows (PowerShell):**
```powershell
powershell -ExecutionPolicy Bypass -File execution-service/runtime-images/tests/verify.ps1
```

### Verification Checks Performed for Each Language:
1. Docker daemon reachability
2. Pinned runtime image presence
3. Hello-world without stdin (exit code 0, stdout `Hello, Sandbox!`)
4. Hello-world with stdin supplied (stdin ignored, exit code 0)
5. Stdin pipe uppercase echo (exit code 0, stdout `HELLO SANDBOX`)
6. Stream independence (stdout and stderr captured separately)
7. Exit code preservation (exit code 42 propagated accurately)
8. Non-root user execution (`UID 1001`, `sandbox` user, NFR3.2)
9. Network isolation (`--network none`, socket connections blocked, NFR3.1)
10. Source code not baked into bare image (FR5.2)
11. Compile error reporting for compiled languages (`failure_kind: "compile_error"`, NFR4.5)
12. AddressSanitizer and UndefinedBehaviorSanitizer toolchain readiness (C and C++, US-73)
13. Container cleanup verification (`--rm`)
14. Measured image size reporting (AC8)

---

## 8. How to Add a New Language

Adding a new language runtime is a structured, isolated operation:

1. **Create a language folder** under `execution-service/runtime-images/<language>/`.
2. **Author the Dockerfile**:
   - Pin base image to an exact tag (no floating `latest`).
   - Create non-root user `sandbox` with UID/GID 1001.
   - Set `WORKDIR /sandbox/code`.
   - Never bake user code into the image.
   - Switch to `USER sandbox`.
3. **Add standard test samples** in `samples/` (`hello_world`, `stdin_echo`, `stderr_split`, `exit_nonzero`, `network_probe`, and `compile_error` if compiled).
4. **Register the language** in `execution-service/src/main/resources/application.yml` under `app.execution.images.<language>`.
5. **Update `DockerRunner.java`** compile and run command templates.
6. **Add test cases** in `tests/verify.sh` and `tests/verify.ps1`.
7. **Document the language** in this README.

---

## 9. Acceptance Criteria Traceability

| Requirement / AC | Description | Status |
|---|---|---|
| **DR1** | Five languages supported: C++, Python, C, Java, JavaScript | ✅ Implemented |
| **DR1 Priority** | Testing priority: C++ first, then Python, C, Java, JavaScript | ✅ Verified in `verify.sh`, `verify.ps1`, README |
| **NFR4.5** | Compiled languages compile in compile stage and run in run stage; compile error returns `failure_kind: "compile_error"` | ✅ Implemented in `DockerRunner` & verified |
| **DR4 / T-17 V2.8** | File names come from request, not fixed `solution.py` name | ✅ Implemented & documented |
| **Pinned Base Images** | All images use pinned base tags without floating `latest` | ✅ Implemented in all Dockerfiles |
| **US-73 Ready** | C and C++ images include ASan and UBSan compiler support without enabling for normal runs | ✅ Built into C/C++ images & verified |
| **NFR3 / NFR4** | 5 s limit, 128 MB memory limit, network isolation, non-root user apply | ✅ Enforced across all runtimes |
| **Integration Test** | Integration test for new behavior added in `tests/integration/` | ✅ `tests/integration/test-us14-language-images.sh` |
