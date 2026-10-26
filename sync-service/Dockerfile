# syntax=docker/dockerfile:1
#
# sync-service Docker image (T-02)
#
# Multi-stage build:
#   Stage 1 (build): Maven image compiles the project and produces the fat jar.
#   Stage 2 (run):   JRE-only image runs the jar, keeping the image small.
#
# The T-08 smoke test checks for this file to decide whether the service is
# "present" (tests/integration/lib.sh). Adding this file enables the smoke test.

# --- Stage 1: build ---
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Download dependencies first (Docker layer cache).
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Compile and package (skip tests; tests run in CI via mvn verify).
COPY src ./src
RUN mvn -B package -DskipTests -q

# --- Stage 2: run ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the fat jar produced by the build stage.
COPY --from=build /app/target/sync-service-*.jar app.jar

# Port 8081 agreed in T-06 (docs/api-contracts.md section 1).
EXPOSE 8081

# Pass environment variables at runtime, not build time (NFR10):
#   SERVER_PORT       – override port (docker-compose.yml uses this)
#   CLIENT_ORIGIN     – browser origin for WebSocket handshake check
#   INTERNAL_API_TOKEN – shared secret for /internal endpoint
ENTRYPOINT ["java", "-jar", "app.jar"]
