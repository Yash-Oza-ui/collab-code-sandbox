# ---------------------------------------------------------------------------
# US-14 · Sprint 3 · collab-code-sandbox
# Runtime image for C (Priority 3 language under DR1)
#
# Language choice: C (GCC)
# Rationale: DR1 sets the priority order as C++, Python, C, Java, JavaScript.
# C is compiled in a compile stage (NFR4.5) and executed in a run stage.
#
# Design decisions:
#   - Base image pinned to debian:12.9-slim (no floating `latest`)
#   - Compiler: gcc, libc6-dev
#   - Includes compiler support for sanitizers (AddressSanitizer and
#     UndefinedBehaviorSanitizer) required for US-73 (libasan8, libubsan1)
#     without enabling them for normal runs (US-14 AC, US-73 ready)
#   - Non-root user 'sandbox' (UID 1001, GID 1001) runs submitted code (NFR3.2)
#   - No user source code is baked in; code is supplied per-run via read-only
#     bind mount (FR5.2, DR2, NFR3.3)
#   - No network access needed at runtime (NFR3.1)
#   - No secrets or credentials
# ---------------------------------------------------------------------------

FROM debian:12.9-slim

# ---- Metadata ----
LABEL org.opencontainers.image.title="collab-sandbox-c" \
      org.opencontainers.image.description="C sandboxed execution runtime with sanitizer toolchain (US-14)" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.source="https://github.com/Yash-Oza-ui/collab-code-sandbox"

# ---- Toolchain installation ----
# Installs gcc, libc development libraries, and runtime support for AddressSanitizer (libasan8)
# and UndefinedBehaviorSanitizer (libubsan1) needed for US-73.
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    libc6-dev \
    libasan8 \
    libubsan1 \
 && rm -rf /var/lib/apt/lists/*

# ---- Non-root user (NFR3.2) ----
RUN groupadd --gid 1001 sandbox \
 && useradd --uid 1001 --gid sandbox --no-create-home --shell /usr/sbin/nologin sandbox

# ---- Working directory ----
WORKDIR /sandbox/code

# ---- Ownership and permissions ----
# /sandbox is writable by the sandbox user so /sandbox/build can receive compiled
# artifacts during the compile stage. /sandbox/code is mounted read-only at runtime.
RUN mkdir -p /sandbox/build \
 && chown -R sandbox:sandbox /sandbox

# ---- Switch to non-root user ----
USER sandbox
