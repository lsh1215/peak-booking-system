# ── Stage 1: dependency cache ─────────────────────────────────────────────────
# Copy only build scripts; source changes do NOT invalidate this layer.
FROM eclipse-temurin:21-jdk-alpine AS deps

ARG SERVICE_NAME

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew .
COPY build.gradle .
COPY settings.gradle .

# Copy only build.gradle files from each module (no source)
COPY common/build.gradle common/build.gradle
COPY ${SERVICE_NAME}/build.gradle ${SERVICE_NAME}/build.gradle

# Resolve and cache all dependencies. Layer is invalidated only when a
# build.gradle changes, not when source files change.
RUN chmod +x gradlew && \
    ./gradlew :${SERVICE_NAME}:dependencies --no-daemon

# ── Stage 2: compile & package ────────────────────────────────────────────────
FROM deps AS builder

# Copy source code after deps are cached
COPY common/src common/src
COPY ${SERVICE_NAME}/src ${SERVICE_NAME}/src

# Deps already downloaded; this stage only compiles
RUN ./gradlew :${SERVICE_NAME}:bootJar -x test --no-daemon

# ── Stage 3: extract layered jar ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS extractor

ARG SERVICE_NAME

WORKDIR /app

COPY --from=builder /app/${SERVICE_NAME}/build/libs/*.jar app.jar

RUN java -Djarmode=layertools -jar app.jar extract

# ── Stage 3b: fetch OpenTelemetry Java agent ─────────────────────────────────
# Cached independently from the build — invalidated only when OTEL_AGENT_VERSION
# changes. The agent is platform-independent JVM bytecode; the same jar works
# on both amd64 and arm64 at runtime.
FROM curlimages/curl:8.11.0 AS otel-agent
ARG OTEL_AGENT_VERSION=2.20.1
RUN curl -fSL \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar" \
    -o /tmp/opentelemetry-javaagent.jar

# ── Stage 4: runtime ──────────────────────────────────────────────────────────
# Note: Using non-Alpine (glibc-based) JRE. The Alpine JRE variant excludes the
# jdk.random module, which breaks RandomGeneratorFactory.getDefault() (L32X64MixRandom).
FROM eclipse-temurin:21-jre

RUN groupadd -r app && useradd -r -g app app

WORKDIR /app

# Copy layers in stability order: rarely-changing first, frequently-changing last.
# Docker caches each COPY as a separate layer.
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./

# OpenTelemetry agent. Inert unless OTEL_EXPORTER_OTLP_ENDPOINT is set at
# runtime (entrypoint.sh enforces the activation contract).
COPY --from=otel-agent /tmp/opentelemetry-javaagent.jar /app/otel/opentelemetry-javaagent.jar
COPY entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh && chown -R app:app /app

USER app

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
