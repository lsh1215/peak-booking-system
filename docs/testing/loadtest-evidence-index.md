# Load Test Result Summary Form

> 부하 테스트 결과를 다시 작성하기 위한 단일 폼이다. 기존 v1 결과와 Redis 장애 fallback 수정 후 결과만 이 문서에 정리한다.

## 문서 메타데이터

| 항목 | 값 |
|---|---|
| 작성 날짜 | 2026-06-03 |
| 대상 브랜치/커밋 | `codex/local-redis-fallback-queue` / `a6692e1` |
| 테스트 환경 | gcloud GCE k3s 6 nodes, booking-service 2 replicas, MySQL, Redis Sentinel/replicas, Traefik ingress |
| 비교 대상 | v1 기존 결과 vs local queue fallback 수정 후 결과 |

## 요약 결론

- V2 최신 backend rerun(`loadtest-results/20260603-210017-gcloud-k3s-latest/`) 기준 k6 threshold는 10개 시나리오 모두 PASS.
- hard correctness 기준인 confirmed/payment_unknown 합계는 모든 DB snapshot에서 stock `10` 이하.
- `payment-failure`는 confirmed `0`, `pg-timeout`은 payment_unknown `10`으로 기대 상태를 만족.
- Redis master pod 삭제 중 부하는 `dropped_iterations=25`가 있었지만 redis-down 시나리오 허용치 안에서 PASS했고, 테스트 후 Redis HA는 master 1 + replicas 2 + Sentinel 3 정상 인식 상태로 복구 확인.
- V1 원시 결과는 현재 문서/워크스페이스에 연결된 파일이 없어 정량 비교는 보류.

## 비교 결과

| 구분 | v1 기존 결과 | 수정 후 결과 | 판정 | 비고 |
|---|---|---|---|---|
| 초과판매 여부 | 원시 결과 미보유 | 없음. 모든 snapshot에서 confirmed/payment_unknown 합계 `<= 10` | PASS | stock hard limit 유지 |
| confirmed count | 원시 결과 미보유 | 정상/중복/mixed/peak/WAS-down/Redis-down 최종 confirmed `10`; payment-failure `0`; pg-timeout `0` | PASS | 응답 카운터는 replay/follow-up 포함 가능하므로 DB snapshot 기준 |
| occupied count | 원시 결과 미보유 | 최대 occupied `10` (`pg-timeout`: payment_unknown `10`; 그 외 주요 성공 시나리오: confirmed `10`) | PASS | `reserved_count=0`으로 종료 |
| Redis 장애 중 응답 분포 | 원시 결과 미보유 | `redis-down`: confirmed response `10`, controlled rejected `1895`, waiting `6`, technical failures `0` | PASS | 실행 5초 후 Redis master pod 삭제 |
| local queue accepted/full | 해당 없음 | 미계측 | WATCH | 현재 k6 summary가 accepted/full metric을 export하지 않음 |
| worker drain 시간 | 해당 없음 | 미계측 | WATCH | raw k6/DB snapshot만으로 drain 완료 시간을 직접 산출하지 않음 |
| DB pressure | 원시 결과 미보유 | 미계측 | WATCH | LGTM/Hikari 시계열 raw export 미수집 |
| p95 latency | 원시 결과 미보유 | booking p95 최대 `442.2ms`(payment-failure), `redis-down` booking p95 `233.8ms`, `peak-1000` booking p95 `89.8ms` | PASS | k6 summary 기준 |
| dropped iterations | 원시 결과 미보유 | `redis-down=25`, 나머지 시나리오 `0` | PASS | redis-down 허용치 안 |

## 실행 명령

| 구분 | 명령 | 비고 |
|---|---|---|
| v1 기존 결과 | 원시 명령 미보유 | 정량 비교 보류 |
| 수정 후 결과 | `BASE_URL=http://34.64.61.68 SCENARIO=<scenario> RATE=<rate> DURATION=<duration> k6 run k6/booking-resilience.js` | 각 시나리오 전 원격 MySQL/Redis reset 검증. 배포 이미지 `peak-booking/backend:loadtest-20260603-210017` |

## 원시 결과 위치

| 구분 | 파일/디렉토리 | 포함 내용 |
|---|---|---|
| v1 기존 결과 | 미보유 | 비교 대상 파일 필요 |
| 수정 후 결과 | `loadtest-results/20260603-210017-gcloud-k3s-latest/` | `*-summary.json`, `*-k6.out`, `*-db-snapshot.txt`, `redis-down-injection.txt`, `summary.tsv` |

## DB Snapshot

| 구분 | confirmed | held | payment_unknown | released/failed | 비고 |
|---|---:|---:|---:|---:|---|
| v1 기존 결과 | TODO | TODO | TODO | TODO | 원시 결과 미보유 |
| 수정 후 결과 | 최대 10 | 0 | 최대 10 (`pg-timeout`) | 63 (`payment-failure`) | 모든 scenario에서 occupied `<= 10` |

## 관측 지표

| 지표 | v1 기존 결과 | 수정 후 결과 | 해석 |
|---|---|---|---|
| Hikari active/pending | TODO | 미계측 | LGTM/Prometheus raw export가 필요 |
| Redis timeout/failover | TODO | `redis-down` PASS, dropped iterations `25`, technical failures `0` | failover 중 통제된 응답 유지. 테스트 후 Redis HA 정상 복구 확인 |
| local queue active_count | 해당 없음 | 미계측 | app metric/export 확인 필요 |
| local queue full count | 해당 없음 | 미계측 | app metric/export 확인 필요 |
| PG confirm count | TODO | DB snapshot 기준 `SUCCESS APPROVED=10` 또는 failure/timeout scenario별 기대 상태 | replay/follow-up 응답 카운터보다 DB snapshot 우선 |

## 남은 확인 사항

| ID | 항목 | 필요한 작업 | 우선순위 |
|---|---|---|---|
| LT-001 | V1 정량 비교 | 기존 v1 raw result가 있으면 같은 표에 연결 | P2 |
| LT-002 | local queue accepted/full 계측 | k6 summary 또는 Prometheus export에 queue accepted/full/drain metric 추가 | P1 |
| LT-003 | DB pressure raw evidence | Hikari active/pending, MySQL row lock/connection 지표를 run별 export | P1 |
| LT-004 | 장시간 soak | 1~5분 midnight burst 뒤 recovery 상태와 drain 완료 시간 측정 | P2 |

## 2026-06-03 빠른 로컬 preflight

> 요청 시점의 머신에는 활성 클라우드 Kubernetes context, `BASE_URL`, `LOADTEST_TARGET`, `TARGET_URL`, k6 cloud 대상 설정이 없어 클라우드 환경 직접 검증은 수행하지 못했다. 대신 현재 코드 이미지로 Docker Compose 단일 replica 환경을 새 MySQL volume에서 재기동하고 동일 k6 시나리오를 빠르게 실행했다.

| 항목 | 값 |
|---|---|
| 대상 브랜치/커밋 | `codex/local-redis-fallback-queue` / `f3e5560` |
| 테스트 환경 | local Docker Compose: MySQL 8.4, Redis 7.4, LGTM, booking-service 단일 replica |
| 원시 결과 | `loadtest-results/20260603-202041-local-preflight/` |
| 사전 이슈 | 기존 MySQL volume이 구 schema라 `payment_attempt.provider_order_id` 누락으로 첫 실행 실패. `docker compose down -v` 후 새 schema로 재실행 |

| 시나리오 | Rate / Duration | k6 threshold | DB 최종 snapshot | 판정 |
|---|---:|---|---|---|
| health | 20/s / 10s | PASS | inventory 변화 없음 | PASS |
| booking | 50/s / 20s | PASS | confirmed 5, payment_unknown 5, 합계 10 | PASS |
| duplicate | 30/s / 20s | PASS | confirmed 10 | PASS |
| pg-timeout | 30/s / 20s | PASS | payment_unknown 10 | PASS |
| payment-failure | 30/s / 20s | PASS | confirmed 0, released 600 | PASS |
| realistic-mixed | 30/s / 20s | PASS | confirmed 10 | PASS |
| peak-500 | 500/s / 20s | PASS | confirmed 5, payment_unknown 5, 합계 10 | PASS |

남은 클라우드 검증:

- 클라우드/staging `BASE_URL` 또는 활성 cloud kube context가 필요하다.
- Redis failover, WAS 1대 down, 2+ replica 조건은 local 단일 compose 결과로 대체할 수 없다.
- 정상 `SUCCESS` 부하에서 `PAYMENT_UNKNOWN`이 발생했으므로, cloud 2+ replica 환경에서 payment call guard/DB pressure 지표와 함께 재측정해야 한다.

## 2026-06-03 gcloud/k3s quick load test

> 위 로컬 preflight 뒤 기존 gcloud k3s 클러스터(`peak-booking-k3s-20260601122904`)를 재사용해 현재 backend 이미지를 `linux/amd64`로 빌드/import하고 `k8s/loadtest` overlay를 rollout했다. 첫 cloud run(`loadtest-results/20260603-203816-gcloud-k3s/`)은 원격 DB reset이 실제로 적용되지 않아 판정에서 제외한다. 아래 결과는 Redis master/replica를 고려한 reset을 적용한 두 번째 실행이다.

| 항목 | 값 |
|---|---|
| 대상 브랜치/커밋 | `codex/local-redis-fallback-queue` / `a6692e1` |
| 테스트 환경 | gcloud GCE k3s 6 nodes, booking-service 2 replicas, MySQL, Redis Sentinel/replicas, Traefik ingress |
| 대상 URL | `http://34.64.61.68` |
| 원시 결과 | `loadtest-results/20260603-204320-gcloud-k3s/` |
| 배포 메모 | 최초 arm64 이미지 import로 `exec format error` 발생. `linux/amd64` 이미지 재빌드/import 후 rollout 정상화 |

| 시나리오 | Rate / Duration | k6 threshold | DB 최종 snapshot | 판정 |
|---|---:|---|---|---|
| health | 20/s / 10s | PASS | inventory 변화 없음 | PASS |
| booking | 50/s / 20s | PASS | confirmed 10 | PASS |
| duplicate | 30/s / 20s | PASS | confirmed 10 | PASS |
| pg-timeout | 30/s / 20s | PASS | payment_unknown 10 | PASS |
| payment-failure | 30/s / 20s | PASS | confirmed 0, released 35 | PASS |
| realistic-mixed | 30/s / 20s | PASS | confirmed 10 | PASS |
| peak-500 | 500/s / 20s | PASS | confirmed 10 | PASS |
| peak-1000 | 1000/s / 20s | PASS | confirmed 10 | PASS |
| was-one-down | 100/s / 30s | PASS | confirmed 10 | PASS |
| redis-down | 100/s / 45s | PASS | confirmed 10 | PASS |

관측 요약:

- 모든 유효 시나리오에서 `http_req_failed=0`, `booking_technical_failure_total=0`.
- `redis-down`을 제외한 시나리오는 `dropped_iterations=0`; `redis-down`은 failover 중 `dropped_iterations=32`로 허용치 안에서 PASS.
- hard correctness 기준인 confirmed/payment_unknown 합계는 모든 snapshot에서 stock 10 이하.
- `was-one-down`은 booking-service를 1 replica로 낮춘 상태에서 실행한 뒤 2 replicas로 복구했다.
- `redis-down`은 실행 5초 후 현재 Redis master pod를 삭제해 Sentinel failover를 유도했다. 이 시나리오는 `dropped_iterations=32`였고 k6의 redis-down 허용치 안에서 PASS했다.

## 2026-06-03 gcloud/k3s latest backend rerun

> 현재 로컬 `backend/` source 기준으로 `linux/amd64` 이미지를 고유 태그(`peak-booking/backend:loadtest-20260603-210017`)로 빌드/import하고, k3s deployment image를 해당 태그로 갱신한 뒤 재실행했다. 테스트 전 원격 DB reset 결과(`reservation_count=0`, `payment_count=0`, inventory counters 0)를 확인했고, Redis reset은 master/replica pod 전체에 `FLUSHALL`을 시도해 master에서만 성공하도록 했다.

| 항목 | 값 |
|---|---|
| 대상 브랜치/커밋 | `codex/local-redis-fallback-queue` / `a6692e1` |
| backend 작업트리 diff | 없음 |
| 테스트 환경 | gcloud GCE k3s 6 nodes, booking-service 2 replicas, MySQL, Redis Sentinel/replicas, Traefik ingress |
| 대상 URL | `http://34.64.61.68` |
| 배포 이미지 | `peak-booking/backend:loadtest-20260603-210017` |
| 원시 결과 | `loadtest-results/20260603-210017-gcloud-k3s-latest/` |

| 시나리오 | Rate / Duration | k6 threshold | DB 최종 snapshot | 판정 |
|---|---:|---|---|---|
| health | 20/s / 10s | PASS | inventory 변화 없음 | PASS |
| booking | 50/s / 20s | PASS | confirmed 10 | PASS |
| duplicate | 30/s / 20s | PASS | confirmed 10 | PASS |
| pg-timeout | 30/s / 20s | PASS | payment_unknown 10 | PASS |
| payment-failure | 30/s / 20s | PASS | confirmed 0, released 63 | PASS |
| realistic-mixed | 30/s / 20s | PASS | confirmed 10 | PASS |
| peak-500 | 500/s / 20s | PASS | confirmed 10 | PASS |
| peak-1000 | 1000/s / 20s | PASS | confirmed 10 | PASS |
| was-one-down | 100/s / 30s | PASS | confirmed 10 | PASS |
| redis-down | 100/s / 45s | PASS | confirmed 10 | PASS |

관측 요약:

- 모든 유효 시나리오에서 `http_req_failed=0`, `booking_technical_failure_total=0`.
- `redis-down`을 제외한 시나리오는 `dropped_iterations=0`; `redis-down`은 failover 중 `dropped_iterations=25`로 허용치 안에서 PASS.
- hard correctness 기준인 confirmed/payment_unknown 합계는 모든 snapshot에서 stock 10 이하.
- `redis-down` 후 Redis HA 상태를 복구 검증했다: master 1개, connected slaves 2개, Sentinel 3개 모두 `redis:6379`를 master로 인식.
