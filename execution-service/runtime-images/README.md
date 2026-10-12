# T-14 · Runtime Images

**Part of:** EPIC 2 – Sandboxed Execution Engine | Sprint 1  
**Traceability:** FR5, DR1, DR2 · T-14  
**Reviewers:** Jay Limbasiya  
**Bhavya's PR branch:** `feature/T-14-runtime-images` | **Quincy's branch:** `feature/T-14-runtime-images-checks`

---

## 1. Purpose

T-14 delivers the **Docker runtime image(s)** that the container runner (US-09) needs in order to execute user-submitted code. Without a runtime image there is nothing to run code _in_. This folder also defines the **run convention** — how source code reaches the container, how stdin is fed, and how stdout, stderr, and the exit code are captured — so that US-09 can be built against a stable, agreed contract.

A second goal is to make it easy to add a new language later (US-14, Sprint 2) without rewriting the existing language support.

---

## 2. Directory Structure

```
execution-service/runtime-images/
├── README.md                   ← this file
├── python/                     ← Sprint 1 runtime (Python 3.12)
│   ├── Dockerfile              ← image definition, pinned base python:3.12.9-slim-bookworm
│   └── samples/                ← small programs used during verification
│       ├── hello_world.py      ← no-stdin hello-world
│       ├── stdin_echo.py       ← reads stdin, writes uppercased to stdout
│       ├── stderr_split.py     ← writes one line each to stdout and stderr
│       ├── exit_nonzero.py     ← exits with code 42
│       └── network_probe.py    ← attempts a TCP connection (must fail in sandbox)
├── cpp/                        ← Second runtime (C++ GCC 14.2, added & verified by Quincy)
│   ├── Dockerfile              ← image definition, pinned base gcc:14.2.0-bookworm
│   └── samples/                ← C++ verification sample programs
│       ├── hello_world.cpp     ← no-stdin hello-world
│       ├── stdin_echo.cpp      ← reads stdin, writes uppercased to stdout
│       ├── stderr_split.cpp    ← writes one line each to stdout and stderr
│       ├── exit_nonzero.cpp    ← exits with code 42
│       └── network_probe.cpp   ← attempts socket connection (fails in sandbox)
└── tests/
    ├── verify.sh               ← Python verification script (Bash, supports --all)
    ├── verify.ps1              ← Python verification script (PowerShell, supports -All)
    ├── verify_cpp.sh           ← Quincy's C++ verification script (Bash)
    └── verify_cpp.ps1          ← Quincy's C++ verification script (PowerShell)
```

The `samples/` directories are **never baked into the images**. They are bind-mounted read-only at run time, exactly as real user code will be.

---

## 3. Language Choice and Rationale

| Language | Scope / Role | Base Image | Non-Root User |
|---|---|---|---|
| **Python 3.12** | Sprint 1 primary runtime | `python:3.12.9-slim-bookworm` | `sandbox` (UID 1001, GID 1001) |
| **C++ (GCC 14.2)** | Second language / US-14 foundation | `gcc:14.2.0-bookworm` | `sandbox` (UID 1001, GID 1001) |

**Why Python for Sprint 1, and C++ as Second Language / US-14 Prototype?**

- **DR1** sets the eventual priority order as C++, Python, C, Java, JavaScript.
- **NFR4.5** reserves a separate compile-stage timeout for _compiled_ languages and explicitly defers compiled-language support to Sprint 2.
- C++ and Java both require an explicit compilation step before execution.
- Python is an **interpreted** language: `python solution.py` executes directly without a separate compile phase, making it the primary Sprint 1 choice.
- To fulfill the T-14 Definition of Done (*"A teammate adds a second language following only the README"*), Quincy followed §9 and added the C++ runtime (`cpp/`), verifying the multi-language extension procedure and providing the foundation for US-14.

**Language ambiguity note — Java as a runtime vs. project implementation language:**

The project's backend services (execution-service, sync-service) are implemented in **Java / Spring Boot**. This refers to the _implementation language_ of the services themselves. T-14 is about the _submitted-code runtime language_ — the language whose source code runs inside the Docker container. These are separate concerns. Java source files also require compilation (even Java source-file launch mode performs internal compilation), so Java as a submitted-code runtime is also Sprint 2 work under NFR4.5. No team decision document was found confirming Java as a Sprint 1 submitted-code runtime. Python is Sprint 1, C++ is second language, Java submitted-code runtime follows in Sprint 2.

---

## 4. Prerequisites

| Requirement | Version / Detail |
|---|---|
| Docker Engine | 24.x or later |
| OS | Linux, macOS, or Windows with WSL 2 |
| Available disk | ~200 MB for the image |
| Internet access | Required only at _build_ time (to pull the base image) |

---

## 5. Build the Image

Run from the **repository root**:

```bash
docker build \
  -t collab-sandbox-python:0.1.0 \
  execution-service/runtime-images/python
```

Verify the build succeeded:

```bash
docker images collab-sandbox-python
# Expected output: a row showing collab-sandbox-python  0.1.0  ...
```

Record the reported size in §11 (Image Size) and update this README before raising the PR.

---

## 6. Run Convention

This section defines the **contract** between the execution service (US-09) and the runtime image. All invocations must follow it exactly.

### 6.1 How source code reaches the container

Source code is **never baked into the image**. For every run the execution service:

1. Writes the submitted source file to a temporary host directory, e.g. `/tmp/run-<uuid>/solution.py`.
2. Bind-mounts that directory into the container at `/sandbox/code`, read-only.
3. Passes the execution command that references the mounted path.

```
/tmp/run-<uuid>/solution.py   (host, write once, then read-only)
         ↕  bind mount, read-only
/sandbox/code/solution.py     (container, read-only)
```

The temporary directory and its contents are deleted by the service after the container exits.

### 6.2 Exact invocation

```bash
# No stdin
docker run \
  --rm \
  --network none \
  --name run-<uuid> \
  -v /tmp/run-<uuid>:/sandbox/code:ro \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/solution.py

# With stdin (pipe from the execution service)
echo "<stdin content>" | docker run \
  --rm \
  -i \
  --network none \
  --name run-<uuid> \
  -v /tmp/run-<uuid>:/sandbox/code:ro \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/solution.py
```

| Flag | Purpose |
|---|---|
| `--rm` | Destroys the container and its writable layer immediately on exit (FR5.2) |
| `--network none` | No network access during execution (NFR3.1) |
| `--name run-<uuid>` | Unique name so parallel runs never collide |
| `-v .../run-<uuid>:/sandbox/code:ro` | Read-only mount of submitted source (NFR3.3) |
| `-i` | Keep stdin open when piping user input (omit if no stdin) |

### 6.3 Capturing stdout, stderr, and exit code

The execution service captures streams and the exit code like this:

```bash
# Capture stdout and stderr independently, preserve exit code
STDOUT=$(docker run --rm --network none -i \
  --name run-<uuid> \
  -v /tmp/run-<uuid>:/sandbox/code:ro \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/solution.py \
  2>/tmp/run-<uuid>/stderr.txt < /tmp/run-<uuid>/stdin.txt)
EXIT_CODE=$?
STDERR=$(cat /tmp/run-<uuid>/stderr.txt)
```

In Java (the execution service language), the equivalent is `ProcessBuilder` with `redirectErrorStream(false)`, reading `getInputStream()` and `getErrorStream()` concurrently, then calling `waitFor()` for the exit code.

### 6.4 Exit codes

| Exit code | Meaning |
|---|---|
| 0 | Program terminated successfully |
| 1-254 | Program exited with that code (propagated as-is) |
| 124 | Reserved for timeout kill (Sprint 2, NFR4.1) |
| 137 | OOM kill (Sprint 2, NFR4.2) |
| Non-zero from `docker run` | Container startup failure (image missing, mount error, etc.) |

The execution service must distinguish a container-startup failure (docker exits nonzero before the program runs) from a program-level nonzero exit. Sprint 2 will add resource limits and timeout handling.

### 6.5 Container cleanup

`--rm` ensures Docker removes the container automatically after it exits, including when the program crashes. The execution service must additionally:

1. Remove the temporary host directory: `rm -rf /tmp/run-<uuid>`
2. If the container fails to start, call `docker rm -f run-<uuid>` defensively.

### 6.6 What is NOT in scope for Sprint 1

The following are Sprint 2 additions (NFR4, NFR3 full enforcement):

- `--memory 128m --memory-swap 128m` — memory cap (NFR4.2)
- `--pids-limit 64` — process/thread cap (NFR4.3)
- `--cpus` / `--cpu-shares` — CPU cap
- `timeout` wrapper or `--stop-timeout` — wall-clock timeout (NFR4.1)
- `--read-only` container filesystem — full filesystem lockdown (NFR3.3 full)
- Output size cap (NFR4.4)

Do not hard-code any of these values; they will be read from service configuration (DR3) in Sprint 2.

### 6.7 C++ Run Convention (Compiled Language Prototype)

For compiled languages (C++), compilation and execution are performed in the container:
1. Source code is mounted read-only at `/sandbox/code/solution.cpp`.
2. A writable ephemeral scratch directory is supplied at `/sandbox/run` (e.g. via `--tmpfs /sandbox/run:rw,noexec=false,size=64m`).
3. Invocation command:
```bash
docker run --rm --network none \
  --tmpfs /sandbox/run:rw,noexec=false,size=64m \
  -v /tmp/run-<uuid>:/sandbox/code:ro \
  collab-sandbox-cpp:0.1.0 \
  sh -c "g++ -O2 -std=c++20 /sandbox/code/solution.cpp -o /sandbox/run/a.out && /sandbox/run/a.out"
```

---

## 7. Exact Commands for Verification

All commands are run from the **repository root** with the image already built.

### 7.1 Hello-world (no stdin)

```bash
docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/hello_world.py
```

**Expected stdout:**
```
Hello, Sandbox!
```
**Expected stderr:** (empty)  
**Expected exit code:** `0`

### 7.2 Hello-world (with stdin — stdin is ignored)

```bash
echo "ignored input" | docker run --rm -i --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/hello_world.py
```

**Expected stdout:** `Hello, Sandbox!`  
**Expected exit code:** `0`

### 7.3 stdin piped to program (uppercase echo)

```bash
echo "hello sandbox" | docker run --rm -i --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/stdin_echo.py
```

**Expected stdout:** `HELLO SANDBOX`  
**Expected exit code:** `0`

### 7.4 stdout and stderr captured independently

```bash
docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/stderr_split.py \
  2>/tmp/stderr_check.txt

echo "stdout: $(cat /tmp/stderr_check.txt)"  # Will be empty - stdout was above
# Or capture separately:
STDOUT=$(docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/stderr_split.py 2>/tmp/stderr_check.txt)
STDERR=$(cat /tmp/stderr_check.txt)
echo "stdout='$STDOUT'  stderr='$STDERR'"
```

**Expected:** `stdout='STDOUT line'  stderr='STDERR line'`

### 7.5 Nonzero exit code preserved

```bash
docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/exit_nonzero.py
echo "Exit code: $?"
```

**Expected exit code:** `42`

### 7.6 Non-root user

```bash
docker run --rm --network none collab-sandbox-python:0.1.0 id
```

**Expected output (example):** `uid=1001(sandbox) gid=1001(sandbox) groups=1001(sandbox)`

### 7.7 Network isolation

```bash
docker run --rm --network none \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/network_probe.py
echo "Exit code: $?"
```

**Expected stdout:** `NETWORK BLOCKED`  
**Expected exit code:** `1`  
**Expected stderr:** contains `Expected error: ...` (OSError detail)

### 7.8 Code not baked into image

```bash
docker run --rm --network none collab-sandbox-python:0.1.0 \
  sh -c 'test -f /sandbox/code/hello_world.py && echo FOUND || echo ABSENT'
```

**Expected:** `ABSENT`

### 7.9 Container cleanup

```bash
docker run --rm --network none --name t14-cleanup-test \
  -v "$(pwd)/execution-service/runtime-images/python/samples:/sandbox/code:ro" \
  collab-sandbox-python:0.1.0 \
  python /sandbox/code/hello_world.py

docker ps -a --filter "name=t14-cleanup-test" --format '{{.Names}}'
# Expected: (empty — container was removed by --rm)
```

### 7.10 Run all checks with the verification script

**Linux / macOS / Git Bash:**
```bash
bash execution-service/runtime-images/tests/verify.sh
```

**Windows (PowerShell):**
```powershell
powershell -ExecutionPolicy Bypass -File execution-service/runtime-images/tests/verify.ps1
```

Expected: all 11 checks print `PASS`.

---

## 8. Image Size

```bash
docker images collab-sandbox-python:0.1.0 --format "{{.Size}}"
docker images collab-sandbox-cpp:0.1.0 --format "{{.Size}}"
```

- **collab-sandbox-python:0.1.0:** `177.6 MB` (186,189,789 bytes, compressed image size ~45.4 MB on disk). Built from `python:3.12.9-slim-bookworm`.
- **collab-sandbox-cpp:0.1.0:** `1.42 GB` (GCC 14.2 + full standard library & toolchain on Debian Bookworm). Built from `gcc:14.2.0-bookworm`.

---

## 9. How to Add a New Language (US-14) — Verified by Quincy

Adding a new language is a **one-folder operation**. It does not change the Python folder or any shared infrastructure.

> **Validation Note:** To fulfill the T-14 Definition of Done (*"A teammate adds a second language following only the README"*), Quincy (`feature/T-14-runtime-images-checks`) followed this guide to implement and verify the C++ runtime image (`cpp/`), confirming the specification is complete and reproducible.

### Step-by-step

1. **Create a new language folder** alongside `python/`:

   ```
   execution-service/runtime-images/
   └── cpp/              ← new language
       ├── Dockerfile
       └── samples/
           ├── hello_world.cpp
           ├── stdin_echo.cpp
           ├── stderr_split.cpp
           ├── exit_nonzero.cpp
           └── network_probe.cpp
   ```

2. **Write the Dockerfile.** Follow the same conventions:
   - Pin the base image to a specific version (no `latest`).
   - Create a `sandbox` user (UID 1001, GID 1001) and switch to it.
   - Set `WORKDIR /sandbox/code`.
   - Provide `/sandbox/run` scratch directory for compiled binaries.
   - Do NOT add a `CMD` or `ENTRYPOINT`; the service supplies the command.
   - Do NOT bake source code into the image.
   - No secrets or credentials.

3. **Build and tag** the new image independently:

   ```bash
   docker build -t collab-sandbox-cpp:0.1.0 \
                execution-service/runtime-images/cpp
   ```

4. **Write sample programs** in `cpp/samples/` matching the same test scenarios (hello-world, stdin, stderr split, nonzero exit, network probe). Use the language's native source extension and compiler/interpreter invocation.

5. **Run the verification script** against the new image:

   ```bash
   bash execution-service/runtime-images/tests/verify_cpp.sh
   # Or on Windows PowerShell:
   powershell -ExecutionPolicy Bypass -File execution-service/runtime-images/tests/verify_cpp.ps1
   ```

6. **Document** the run command convention for that language (§6.7).

7. **Tell the execution service** about the new language: add the image tag and command template to the service's language configuration (DR3). Do not hard-code.

### Files each language folder must provide

| File | Required | Purpose |
|---|---|---|
| `Dockerfile` | ✅ | Image definition |
| `samples/hello_world.<ext>` | ✅ | Basic smoke test |
| `samples/stdin_echo.<ext>` | ✅ | stdin verification |
| `samples/stderr_split.<ext>` | ✅ | Stream independence |
| `samples/exit_nonzero.<ext>` | ✅ | Exit code preservation |
| `samples/network_probe.<ext>` | ✅ | Isolation verification |
| `README.md` or section in this file | ✅ | Run command convention, image size |

---

## 10. Constraints, Trade-offs, and Limitations

| Topic | Detail |
|---|---|
| Python 3.12 primary | Python is the primary Sprint 1 runtime. C++ is verified as second language prototype. |
| No resource limits yet | Memory, CPU, process, and time limits are Sprint 2 (NFR4). |
| No output size cap | Sprint 2 (NFR4.4). The service should currently truncate large output defensively. |
| Non-root recommended | NFR3.2 is implemented now; it becomes a hard requirement in Sprint 2. |
| Network disabled | `--network none` is applied now; NFR3.1 is ready. |
| Read-only mount | Implemented at runtime via `:ro`; NFR3.3 is met. |
| Not a full security sandbox | `--network none` and a non-root user are hardening measures, not a complete isolation boundary. Full sandbox hardening (seccomp, AppArmor, capabilities) is out of scope for Sprint 1. |
| Python source-file mode | No compilation is needed. Python 3.12 interprets `.py` files directly. |

---

## 11. Decisions Confirmed with Team (Bhavya, Quincy, Jay)

| Item | Status | Confirmation & Details |
|---|---|---|
| Python as Sprint 1 runtime | **Agreed & Confirmed** | Justified by T-14 issue text (NFR4.5) — interpreted runtime without separate compile phase. |
| Java as Sprint 1 runtime | **Confirmed Deferred** | Java requires compilation; deferred to Sprint 2 under NFR4.5. |
| C++ as Second Language | **Agreed & Confirmed** | Follows DR1 priority order; added and verified by Quincy per DoD item 3. |
| Source file naming (`solution.<ext>`) | **Agreed & Confirmed** | Standardized as `/sandbox/code/solution.py` and `/sandbox/code/solution.cpp`. |
| UID/GID 1001 for sandbox user | **Agreed & Confirmed** | Pinned to `sandbox` (1001:1001) across all runtime images. |
| Image tag format `collab-sandbox-<lang>:<version>` | **Agreed & Confirmed** | Consistent tagging scheme `collab-sandbox-python:0.1.0` and `collab-sandbox-cpp:0.1.0`. |

---

## 12. Acceptance-Criteria & Definition of Done Traceability

| # | Acceptance Criterion | Implementation | Test / Command | Status |
|---|---|---|---|---|
| AC1 | Language choice recorded with rationale | §3 of this README | README review | ✅ Done |
| AC2 | Dockerfile exists with pinned base | `python/Dockerfile` (`python:3.12.9-slim-bookworm`) & `cpp/Dockerfile` (`gcc:14.2.0-bookworm`) | `docker image inspect` | ✅ Verified (PASS) |
| AC3 | Hello-world runs in the image | `samples/hello_world.py` & `samples/hello_world.cpp` | §7.1 / verify check 2 | ✅ Verified (PASS) |
| AC4a | Hello-world works without stdin | `samples/hello_world.py` & `samples/hello_world.cpp` | §7.1 / verify check 2 | ✅ Verified (PASS) |
| AC4b | Hello-world works with stdin supplied | `samples/hello_world.py` & `samples/hello_world.cpp` | §7.2 / verify check 3 | ✅ Verified (PASS) |
| AC5 | Run convention documented (code, stdin, stdout, stderr, exit) | §6 & §6.7 of this README | §7.3–7.5 / verify checks 4–6 | ✅ Verified (PASS) |
| AC6 | README explains how to add a language | §9 of this README | Quincy added C++ (`cpp/`) following guide | ✅ Done |
| AC7 | Tests verify requirements and are reproducible | `verify.sh`, `verify.ps1`, `verify_cpp.sh`, `verify_cpp.ps1` | `verify.sh --all` / `verify.ps1 -All` | ✅ Verified (PASS - 22/22 checks) |
| AC8 | README contains image size | §8 | `docker images` | ✅ Done |
| AC9 | Compatible with Java architecture | Runtime is Python/C++ (submitted code); service is Java. No conflict. | Architecture review | ✅ Done |

### Definition of Done Checklist (Issue #6)

- [x] **Dockerfile exists for each chosen language with a pinned base version** (`python:3.12.9-slim-bookworm`, `gcc:14.2.0-bookworm`).
- [x] **A hello-world program runs in the image, with and without stdin, and prints the expected output.**
- [x] **The run convention is documented:** how the code reaches the container, how stdin is passed, how stdout, stderr and exit code are collected.
- [x] **The README explains how to add another language as a new folder, not a rewrite.**
- [x] **Build and run output pasted in the PR description.**
- [x] **Language choice and run convention agreed with Quincy and Jay.**
- [x] **A teammate adds a second language following only the README** (Quincy added C++ `cpp/` following §9, verified 11/11 tests pass).

**Requirement traceability:**

| Requirement | Satisfied by |
|---|---|
| FR5.1 — each run in its own container | `--rm --name run-<uuid>` (§6.2) |
| FR5.2 — container destroyed after run | `--rm` flag + temp dir cleanup (§6.5) |
| DR1 — five languages eventually | Python now; §9 extension procedure for the rest |
| DR2 — team's own Docker containers | `python:3.12.9-slim-bookworm` pulled and maintained by team (Dockerfile in repo) |
| NFR3.1 — no network at runtime | `--network none` (§6.2) |
| NFR3.2 — non-root user | `sandbox` UID 1001 in Dockerfile |
| NFR3.3 — read-only code mount | `:ro` bind mount (§6.2) |
| NFR4.5 — compiled languages deferred | Python chosen; C++ (Sprint 2) will add compile step |

---

## 13. Sprint 2 Work (Out of Scope Here)

- Add resource limits to `docker run`: `--memory`, `--memory-swap`, `--pids-limit`, `--cpus`
- Add wall-clock timeout (NFR4.1) — wrap with `timeout` or use Docker's `--stop-timeout`
- Add C++ runtime image (DR1, US-14)
- Add remaining languages: C, Java, JavaScript
- Read all limits from configuration (DR3)
- Add output size cap (NFR4.4)
- Full NFR3 enforcement: seccomp profile, read-only filesystem flag
