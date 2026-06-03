# 부하 테스트 성공 기준 - 초기 예상 기준

이 문서는 초기 예상 기준을 보존한다. 이 기준은 Jeff Dean latency numbers 관점의 sanity check, Little's Law 기반 동시성 추정, Mock PG normal delay `100ms`, Redis/MySQL 왕복, app overhead, peak contention buffer를 근거로 한 첫 검증 출발점이다.

이 문서의 수치는 최종 운영값이 아니라 첫 k6/LGTM 검증을 시작하기 위한 가정값이다. 실측 후 보정한 기준은 [부하 테스트 성공 기준 - 실측 보정 기준](./loadtest-success-criteria-calibrated.md)을 따른다. 단, hard correctness 기준은 실측으로 완화하지 않는다.

## 적용 환경 가정

| 항목 | 값 |
|---|---|
| Backend | Java 21, Spring Boot 3.x |
| App replica | 2개 이상 stateless WAS |
| DB / cache | MySQL 8, Redis |
| Gateway / LB | k3s Traefik |
| Observability | LGTM |
| Load tool | k6 `constant-arrival-rate` |
| Mock PG normal delay | `100ms` |
| 대상 재고 | `10` |
| 트래픽 요구 | 평시 `50 TPS`, 피크 `500~1000 TPS`, `1~5분` |

## Hard Correctness 기준

아래 기준은 모든 부하/장애 시나리오에서 깨지면 즉시 실패다.

| 기준 | Pass |
|---|---|
| 초과판매 | DB 기준 `confirmed_booking_count <= 10` |
| 재고 불변식 | DB 기준 `HELD + PAYMENT_UNKNOWN + CONFIRMED <= total_stock` |
| 사용자 중복 확정 | 같은 `user_id + sale_event_id` confirmed 중복 `0` |
| 결제 중복 효과 | 같은 `booking_attempt_id`의 PG confirm side effect `1회 이하` |
| Redis 장애 fallback | unlimited DB fallback 없음. Redis HA 반영 후에는 failover 중 새 admission이 DB fallback으로 우회하지 않고 controlled rejection + half-open recovery로 처리되어야 함 |
| PG unknown | 즉시 success로 조용히 확정하지 않으며, `30s` deadline 뒤 reservation 확정을 금지하고 재고를 release함 |

## 초기 Latency 기준

| 경로 | Pass | Warning |
|---|---:|---:|
| `GET /checkout` | p95 `<= 200ms` | p95 `> 100ms` |
| `POST /bookings` normal confirmed | p95 `<= 500ms` | p95 `> 300ms` |
| DB/PG 없는 controlled rejection | p95 `<= 200ms` | p95 `> 100ms` |
| Redis HA failover controlled rejection | p95 `<= 500ms` | p95 `> 300ms` |
| PG timeout -> `PAYMENT_UNKNOWN` | p95 `<= 700ms` | p95 `> 600ms` |

p99는 초기 pass/fail 기준이 아니라 warning/관측 지표로 둔다.

## 초기 Resource / Recovery 기준

- 의도하지 않은 app restart는 `0`이어야 한다.
- technical 5xx/timeout rate는 `< 1%`여야 한다.
- 의도된 `429`, sold out, candidate rejected, duplicate replay는 technical failure에서 제외한다.
- Hikari pending이 `30s` 이상 지속 증가하면 fail이다.
- DB deadlock은 `0`을 목표로 하며, lock wait timeout이 발생하면 blocker로 분석한다.
- stale `HELD`와 `PAYMENT_UNKNOWN`의 재고 점유는 `30s`를 초과하면 fail이다.
- reservation release 이후 payment_attempt reconciliation은 `5분` 적극 status/cancel window 안에서 계속 진행한다.
- 후순위 `WAITING_CANDIDATE`가 사용자-facing 대기 상태로 `60s`를 초과해 남아 있으면 fail이다.

## 보정 원칙

- latency/resource 기준은 k6/LGTM 실측으로 보정할 수 있다.
- hard correctness 기준은 보정 대상이 아니다.
- threshold를 올리기 전에 먼저 병목이 code, architecture, test generator, network, k3s/Traefik 환경 중 어디에 있는지 구분한다.
- measured 기준은 반드시 실행 환경, 결과 디렉터리, 관측값, 보정 사유를 함께 남긴다.
