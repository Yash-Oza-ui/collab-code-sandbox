# ---------------------------------------------------------------------------
# T-14 · Sprint 1 · collab-code-sandbox
# Runtime image for Python 3.12 (first Sprint 1 language)
#
# Language choice: Python 3.12
# Rationale: DR1 requires C++ first, but NFR4.5 defers compiled-language
# support until Sprint 2. Python is an interpreted language that needs no
# separate compile step, making it the correct Sprint 1 runtime choice.
# C++ support will be added in Sprint 2 under US-14.
#
# Design decisions:
#   - Base image pinned to python:3.12.9-slim-bookworm (no floating `latest`)
#   - Non-root user 'sandbox' (UID 1001) runs submitted code (NFR3.2 ready)
#   - No user source code is baked in; code is supplied per-run via stdin
#     or read-only bind mount (FR5.2, DR2)
#   - No network access needed at runtime (NFR3.1)
#   - No secrets or credentials
#   - Minimal image: only what Python needs to interpret submitted scripts
# ---------------------------------------------------------------------------

FROM python:3.12.9-slim-bookworm

# ---- Metadata ----
LABEL org.opencontainers.image.title="collab-sandbox-python" \
      org.opencontainers.image.description="Python 3.12 sandboxed execution runtime (T-14)" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.source="https://github.com/Yash-Oza-ui/collab-code-sandbox"

# ---- Non-root user (NFR3.2) ----
# UID/GID 1001 keeps it away from root (0) and common service accounts.
RUN groupadd --gid 1001 sandbox \
 && useradd --uid 1001 --gid sandbox --no-create-home --shell /usr/sbin/nologin sandbox

# ---- Working directory ----
# /sandbox/code is the mount-point where the execution service places source
# files per run. The directory is created here so its ownership is correct
# before the user is switched. At run time it is mounted read-only (NFR3.3).
WORKDIR /sandbox/code

# ---- Ownership and permissions ----
# /sandbox is writable by the sandbox user for temporary stdout/stderr
# capture when the service uses a write mount alongside the read-only
# source mount. /sandbox/code itself is expected to be read-only at runtime.
RUN chown -R sandbox:sandbox /sandbox

# ---- Switch to non-root user ----
USER sandbox

# ---- No default CMD / ENTRYPOINT ----
# The execution service (US-09) supplies the full command per run:
#   python /sandbox/code/solution.py
# No hard-coded entry point is set because the service controls execution.
