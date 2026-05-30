#!/bin/sh
# Service launcher. Optionally attaches the OpenTelemetry Java agent when
# a collector endpoint is provided via env; otherwise starts Spring Boot
# with zero tracing overhead.
#
# Activation contract:
#   OTEL_EXPORTER_OTLP_ENDPOINT  present  -> agent attached
#   OTEL_SERVICE_NAME            required when agent attaches
#   OTEL_RESOURCE_ATTRIBUTES     optional (deployment.environment, etc.)
#
# Leaving OTEL_EXPORTER_OTLP_ENDPOINT unset is the supported path for
# local `docker run`, k3d clusters without the monitoring stack, or any
# environment where the OTel Collector / Alloy is not reachable.

set -e

JAVA_OPTS="${JAVA_OPTS:-}"

if [ -n "${OTEL_EXPORTER_OTLP_ENDPOINT:-}" ]; then
  JAVA_OPTS="${JAVA_OPTS} -javaagent:/app/otel/opentelemetry-javaagent.jar"
  # Agent honours OTEL_SERVICE_NAME / OTEL_RESOURCE_ATTRIBUTES / OTEL_EXPORTER_*
  # from the environment directly, so nothing else is added here.
fi

exec java ${JAVA_OPTS} org.springframework.boot.loader.launch.JarLauncher "$@"
