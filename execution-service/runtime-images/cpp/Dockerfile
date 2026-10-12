# ---------------------------------------------------------------------------
# T-14 · Sprint 1 / Sprint 2 Bridge · collab-code-sandbox
# Runtime image for C++ (second language verification following README §9)
#
# Language choice: C++ (DR1 top priority language)
# Contributor: Quincy (feature/T-14-runtime-images-checks)
# Rationale: Verifies the "How to Add a New Language" specification (DoD item 3)
# and establishes the C++ container runtime foundation for US-14 / Sprint 2.
#
# Design decisions:
#   - Base image pinned to gcc:14.2.0-bookworm (no floating `latest`)
#   - Non-root user 'sandbox' (UID 1001, GID 1001) runs compiled binaries (NFR3.2)
#   - /sandbox/run directory provided for compiled binary placement (writable tmpfs/scratch)
#   - No user source code baked into the image (FR5.2, DR2)
#   - No network access at runtime (NFR3.1)
#   - No secrets or credentials
# ---------------------------------------------------------------------------

FROM gcc:14.2.0-bookworm

# ---- Metadata ----
LABEL org.opencontainers.image.title="collab-sandbox-cpp" \
      org.opencontainers.image.description="C++ (GCC 14.2) sandboxed execution runtime (T-14)" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.source="https://github.com/Yash-Oza-ui/collab-code-sandbox"

# ---- Non-root user (NFR3.2) ----
# UID/GID 1001 keeps it away from root (0) and common service accounts.
RUN groupadd --gid 1001 sandbox \
 && useradd --uid 1001 --gid sandbox --no-create-home --shell /usr/sbin/nologin sandbox

# ---- Working and execution directories ----
# /sandbox/code is the mount-point where source code is mounted read-only (NFR3.3).
# /sandbox/run is a scratch directory where the compiled binary is generated and executed.
WORKDIR /sandbox/code
RUN mkdir -p /sandbox/run \
 && chown -R sandbox:sandbox /sandbox

# ---- Switch to non-root user ----
USER sandbox

# ---- No default CMD / ENTRYPOINT ----
# The execution service (US-09) supplies the compilation and execution commands per run:
#   sh -c "g++ -O2 -std=c++20 /sandbox/code/solution.cpp -o /sandbox/run/a.out && /sandbox/run/a.out"
