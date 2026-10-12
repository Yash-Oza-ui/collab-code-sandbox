# execution-service Dockerfile
# Multi-stage build: compile with Maven, then run with a slim JRE image.
# Java 21 (Temurin) – matches the Java version in pom.xml and CI (T-09).

# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Copy Maven wrapper and POM first (layer-cached until pom.xml changes).
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml .

# Download dependencies (cached unless pom.xml changes).
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline -q

# Copy source and build.
COPY src src
RUN ./mvnw -B package -DskipTests -q

# ── Stage 2: run ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Copy the fat jar produced by the build stage.
COPY --from=build /build/target/execution-service-*.jar app.jar

# Port 8082 – T-06 section 1.
EXPOSE 8082

# Spring Boot reads SERVER_PORT from the environment (docker-compose.yml).
ENTRYPOINT ["java", "-jar", "app.jar"]
