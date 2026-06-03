# 부하 테스트 증거 인덱스

이 문서는 `DECISIONS.md`, `sdd.md`, `README.md`가 주장하는 성능/장애 대응 내용을 어떤 증거로 확인했는지 연결한다.

`loadtest-results/`는 원시 로그, k6 summary, DB snapshot, Grafana capture를 담기 때문에 Git 추적 대상에서 제외한다. 대신 이 문서에는 제출자가 재현하거나 검토할 수 있도록 **요약, 로컬 원시 결과 위치, repository에 남은 테스트 코드, 남은 공백을** 기록한다.

## 증거 상태 요약

| 주장 | Repository 증거 | 로컬 원시 결과 | 상태 |
|---|---|---|---|
| 정상 `1000 RPS` 피크에서 confirmed booking은 `10`을 초과하지 않는다 | `BookingFlowIntegrationTest`, k6 script, MySQL final guard 코드 | `loadtest-results/20260602T184646Z-isolated-from-01-with-captures/01-peak-1000-*` | 확인 |
| 중복 클릭은 중복 결제/중복 예약을 만들지 않는다 | idempotency test, `PaymentProcessorRegistryTest`, booking integration test | `loadtest-results/20260602T184646Z-isolated-from-01-with-captures/02-duplicate-500-*` | 확인 |
| PG timeout은 즉시 성공 예약을 만들지 않고 reservation을 release한다 | recovery/integration test, `PAYMENT_UNKNOWN` 상태 전이 | `loadtest-results/20260602T184646Z-isolated-from-01-with-captures/03-pg-timeout-500-*` | 확인 |
| WAS 1대만 남아도 oversell 없이 `10`개 판매까지 수렴한다 | stateless service 구조, k6 resilience script | `loadtest-results/20260602T184646Z-isolated-from-01-with-captures/04-was-one-down-500-*` | 확인 |
| Redis hard-down 중 DB fallback은 최종 정책이 아니다 | `DECISIONS.md` 쟁점 2, Redis failover pause 코드 | `05-redis-down-500-*`, `07/08-shared-db-redis-down-*` | 역사적 참고 |
| Redis master failover 중 새 admission은 DB fallback으로 우회하지 않는다 | `BookingAdmissionServiceTest`, `RedisAdmissionGatewayTest`, `BookingControllerTest` | 최신 raw failover k6 결과 파일은 repository에 없음 | 재실측 필요 |
| Redis master failover 중 fast-fail latency와 dropped iteration 기준을 만족한다 | threshold 문서와 k6 script | 최신 raw failover k6 결과 파일은 repository에 없음 | 재실측 필요 |
| mixed 장애에서 underfill 원인이 core reservation guard가 아니라 replay/recovery 시나리오인지 분리한다 | recovery test, `REQUESTED` recovery 보완 코드 | `06-mixed-500-*`는 구현 수정 전/중간 결과를 포함 | 재실측 필요 |

## 현재 제출 문서에서 조심해야 하는 표현

- Redis HA 설계는 **정합성 방향과 코드 테스트는 확보됐지만**, Redis master failover의 최종 k6 수치는 최신 원시 결과를 다시 남긴 뒤 주장해야 한다.
- `redis-down` 단일 Redis scale-to-zero 결과는 현재 채택한 HA 정책의 합격/불합격 기준이 아니다. 이는 bounded DB fallback 후보를 폐기하게 만든 역사적 참고 증거다.
- `loadtest-results`의 로컬 파일은 재현 과정 검토에는 유용하지만, Git으로 제출되는 공식 증거는 이 문서와 테스트 코드, k6 script, dashboard manifest다.

## 다음에 남겨야 할 Redis HA failover 증거

Redis master failover를 다시 실행하면 아래 파일을 같은 run directory에 남긴다.

| 파일 | 목적 |
|---|---|
| `redis-master-failover-500-summary.json` | k6 summary와 threshold 결과 |
| `redis-master-failover-500.log` | k6 실행 로그 |
| `redis-master-failover-500-inventory.txt` | MySQL inventory/reservation/payment snapshot |
| `redis-master-failover-500-cluster.txt` | pod restart, readiness, Redis/Sentinel 상태 |
| `captures/*redis*`, `captures/*loadtest*`, `captures/*bottleneck*` | Grafana/Prometheus 관측 증거 |

합격으로 기록하려면 최소 조건은 다음이다.

- confirmed booking `<= 10`.
- occupied reservation `<= 10`.
- Redis failover 중 새 admission이 MySQL DB fallback으로 우회하지 않는다.
- failover pause 응답은 `ADMISSION_TEMPORARILY_UNAVAILABLE + Retry-After`로 분리된다.
- half-open probe는 Redis write + `WAIT` 성공 뒤에만 admission을 재개한다.
- technical failure와 controlled rejection을 분리해 집계한다.
