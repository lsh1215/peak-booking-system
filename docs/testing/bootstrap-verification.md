# Bootstrap Verification

검증 일시: 2026-05-30

## Scope

이번 검증은 도메인 API가 아직 없는 상태에서 백엔드 기본 골격과 실행 인프라가 실제로 동작하는지 확인하기 위한 것이다.

- Spring Boot multi-module bootstrap
- `GET /api/v1/health`
- Local Docker Compose: MySQL, Redis, LGTM, booking service
- Grafana dashboard provisioning
- Prometheus scrape
- k6 health-check smoke load test
- Kubernetes kustomize manifest rendering

## Commands And Results

| Check | Command | Result |
|---|---|---|
| Compile and tests | `./gradlew compileJava test --no-daemon` | Passed. `HealthControllerTest > healthReturnsUp()` passed. |
| Docker image build | `docker compose build booking-service` | Passed. Image `peak-booking/service-booking:local` built. |
| Local stack startup | `docker compose up -d mysql redis lgtm booking-service` | Passed. MySQL/Redis/LGTM/booking-service all running; MySQL and Redis healthy. |
| App health API | `curl http://localhost:8080/api/v1/health` | Passed. Returned `success=true`, `service=peak-booking-service`, `status=UP`. |
| Actuator readiness | `curl http://localhost:8080/actuator/health/readiness` | Passed. Returned `status=UP`. |
| Actuator prometheus | `curl http://localhost:8080/actuator/prometheus` | Passed. Exposed `http_server_requests_seconds_*`, `jvm_memory_used_bytes`, and `application_ready_time_seconds`. |
| Grafana health | `curl http://localhost:3000/api/health` | Passed. Grafana returned database `ok`, version `13.0.1`. |
| Prometheus readiness | `curl http://localhost:9090/-/ready` | Passed. Prometheus server ready. |
| Prometheus target | `curl http://localhost:9090/api/v1/targets?state=active` | Passed. `peak-booking-service` target was `up`. |
| Grafana dashboard provisioning | `curl http://localhost:3000/api/search?query=JVM` | Passed. Found `JVM (Micrometer)` with uid `peak-jvm-micrometer`. |
| k6 smoke load test | `docker compose run --rm -e RATE=20 -e DURATION=10s k6` | Passed. 201 requests, 0% failures, p95 `8.47ms`, checks 100%. |
| Metrics after load test | Prometheus query for `/api/v1/health` count | Passed. Count was `202`. |
| Kubernetes render | `kubectl kustomize k8s/base` and `kubectl kustomize k8s/local` | Passed. Each rendered 10 YAML documents. |
| Kubernetes YAML parse | Ruby YAML load over rendered manifests | Passed. All rendered documents had `apiVersion` and `kind`. |

## Kubernetes Validation Note

`kubectl apply --dry-run=client` attempted to contact the current kube context API server at `localhost:8080` and failed because no local Kubernetes API server was attached in this environment. As a fallback, the manifests were checked with `kubectl kustomize` rendering and YAML parsing. A server-backed dry-run should be re-run once a target cluster context is available.

## Grafana Dashboard Selection

The provisioned dashboard is Grafana dashboard template `4701 JVM (Micrometer)`, revision 10. It is suitable for the current project because Spring Boot exposes Micrometer metrics through `/actuator/prometheus`, and the dashboard expects the `application` metric tag that this project configures in `application-common.yml`.

Sources:

- Grafana dashboard `4701 JVM (Micrometer)`: https://grafana.com/grafana/dashboards/4701-jvm-micrometer/
- Grafana `otel-lgtm` Docker image repository: https://github.com/grafana/docker-otel-lgtm
