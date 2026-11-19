# ---------------------------------------------------------------------------
# US-14 · Sprint 3 · collab-code-sandbox
# Runtime image for JavaScript (Node.js 22) (Priority 5 language under DR1)
#
# Language choice: JavaScript (Node.js)
# Rationale: DR1 sets the priority order as C++, Python, C, Java, JavaScript.
# JavaScript is an interpreted runtime executed directly via node.
#
# Design decisions:
#   - Base image pinned to node:22.14.0-bookworm-slim (no floating `latest`)
#   - Non-root user 'sandbox' (UID 1001, GID 1001) runs submitted code (NFR3.2)
#   - No user source code is baked in; code is supplied per-run via read-only
#     bind mount (FR5.2, DR2, NFR3.3)
#   - No network access needed at runtime (NFR3.1)
#   - No secrets or credentials
# ---------------------------------------------------------------------------

FROM node:22.14.0-bookworm-slim

# ---- Metadata ----
LABEL org.opencontainers.image.title="collab-sandbox-javascript" \
      org.opencontainers.image.description="JavaScript Node.js 22 sandboxed execution runtime (US-14)" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.source="https://github.com/Yash-Oza-ui/collab-code-sandbox"

# ---- Non-root user (NFR3.2) ----
RUN groupadd --gid 1001 sandbox \
 && useradd --uid 1001 --gid sandbox --no-create-home --shell /usr/sbin/nologin sandbox

# ---- Working directory ----
WORKDIR /sandbox/code

# ---- Ownership and permissions ----
RUN chown -R sandbox:sandbox /sandbox

# ---- Switch to non-root user ----
USER sandbox
