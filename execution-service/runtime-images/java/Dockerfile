# ---------------------------------------------------------------------------
# US-14 · Sprint 3 · collab-code-sandbox
# Runtime image for Java 21 (Priority 4 language under DR1)
#
# Language choice: Java 21 (Eclipse Temurin JDK)
# Rationale: DR1 sets the priority order as C++, Python, C, Java, JavaScript.
# Java is compiled in a compile stage (NFR4.5) via javac and executed via java.
#
# Design decisions:
#   - Base image pinned to eclipse-temurin:21.0.6_7-jdk-noble (no floating `latest`)
#   - Toolchain: javac, java
#   - Non-root user 'sandbox' (UID 1001, GID 1001) runs submitted code (NFR3.2)
#   - No user source code is baked in; code is supplied per-run via read-only
#     bind mount (FR5.2, DR2, NFR3.3)
#   - No network access needed at runtime (NFR3.1)
#   - No secrets or credentials
# ---------------------------------------------------------------------------

FROM eclipse-temurin:21.0.6_7-jdk-noble

# ---- Metadata ----
LABEL org.opencontainers.image.title="collab-sandbox-java" \
      org.opencontainers.image.description="Java 21 sandboxed execution runtime (US-14)" \
      org.opencontainers.image.version="0.1.0" \
      org.opencontainers.image.source="https://github.com/Yash-Oza-ui/collab-code-sandbox"

# ---- Non-root user (NFR3.2) ----
RUN groupadd --gid 1001 sandbox \
 && useradd --uid 1001 --gid sandbox --no-create-home --shell /usr/sbin/nologin sandbox

# ---- Working directory ----
WORKDIR /sandbox/code

# ---- Ownership and permissions ----
# /sandbox is writable by the sandbox user so /sandbox/build can receive compiled
# .class files during the compile stage. /sandbox/code is mounted read-only at runtime.
RUN mkdir -p /sandbox/build \
 && chown -R sandbox:sandbox /sandbox

# ---- Switch to non-root user ----
USER sandbox
