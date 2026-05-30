# ── Stage 1: dependency cache ─────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS deps

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew .
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x gradlew && \
    ./gradlew dependencies --no-daemon

# ── Stage 2: compile & package ────────────────────────────────────────────────
FROM deps AS builder

COPY src src

RUN ./gradlew bootJar -x test --no-daemon

# ── Stage 3: extract layered jar ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS extractor

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

RUN java -Djarmode=layertools -jar app.jar extract

# ── Stage 3b: fetch OpenTelemetry Java agent ─────────────────────────────────
FROM curlimages/curl:8.11.0 AS otel-agent
ARG OTEL_AGENT_VERSION=2.20.1
RUN curl -fSL \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar" \
    -o /tmp/opentelemetry-javaagent.jar

# ── Stage 4: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

RUN groupadd -r app && useradd -r -g app app

WORKDIR /app

COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./

COPY --from=otel-agent /tmp/opentelemetry-javaagent.jar /app/otel/opentelemetry-javaagent.jar
COPY entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh && chown -R app:app /app

USER app

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
