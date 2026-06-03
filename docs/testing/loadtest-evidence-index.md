# Load Test Result Summary Form

> 부하 테스트 결과를 다시 작성하기 위한 단일 폼이다. 기존 v1 결과와 Redis 장애 fallback 수정 후 결과만 이 문서에 정리한다.

## 문서 메타데이터

| 항목 | 값 |
|---|---|
| 작성 날짜 | 2026-06-03 |
| 대상 브랜치/커밋 | `codex/local-redis-fallback-queue` / `a6692e1` |
| 테스트 환경 | GCP `asia-northeast3-a`, GCE 6 nodes, all `e2-standard-2` |
| 하드웨어 스펙 | node당 2 vCPU / 8 GiB RAM, 총 12 vCPU / 48 GiB RAM |
| 노드 배치 | control-plane/Traefik 1, WAS 2, MySQL 1, Redis 1, LGTM 1 |
| 비교 대상 | v1 기존 결과 vs local queue fallback 수정 후 결과 |

## 요약 결론

- V1 보관 결과(`loadtest-results/V1/`)는 정합성은 대체로 지켰지만 Redis down 성능, mixed 판매 수렴, shared DB + Redis down 1000rps에서 실패/위험이 있었다.
- V2 최신 backend rerun(`loadtest-results/V2/LATEST_BACKEND_RERUN.md`, 증거 `loadtest-results/V2/logs/latest-backend-rerun/`) 기준 k6 threshold는 10개 시나리오 모두 PASS.
- hard correctness 기준인 confirmed/payment_unknown 합계는 모든 DB snapshot에서 stock `10` 이하.
- `payment-failure`는 confirmed `0`, `pg-timeout`은 payment_unknown `10`으로 기대 상태를 만족.
- Redis master pod 삭제 중 부하는 `dropped_iterations=25`가 있었지만 redis-down 시나리오 허용치 안에서 PASS했고, 테스트 후 Redis HA는 master 1 + replicas 2 + Sentinel 3 정상 인식 상태로 복구 확인.
- 대시보드 캡처 이미지는 정리했고, 로그는 보관한다. 요약 MD와 재검증 가능한 summary JSON/DB snapshot/stdout log를 증거로 남긴다.

## 비교 결과

| 구분 | v1 기존 결과 | 수정 후 결과 | 판정 | 비고 |
|---|---|---|---|---|
| 초과판매 여부 | 없음. 주요 snapshot에서 confirmed/payment_unknown 합계 `<= 10` | 없음. 모든 snapshot에서 confirmed/payment_unknown 합계 `<= 10` | PASS | stock hard limit 유지 |
| confirmed count | mixed 500rps `9`, Redis failover isolated `9`, realistic mixed `8` 등 판매 수렴 실패 존재 | 정상/중복/mixed/peak/WAS-down/Redis-down 최종 confirmed `10`; payment-failure `0`; pg-timeout `0` | PASS | 응답 카운터는 replay/follow-up 포함 가능하므로 DB snapshot 기준 |
| occupied count | 실패 시나리오도 stock `10` 이하 | 최대 occupied `10` (`pg-timeout`: payment_unknown `10`; 그 외 주요 성공 시나리오: confirmed `10`) | PASS | `reserved_count=0`으로 종료 |
| Redis 장애 중 응답 분포 | V1 Redis down 500rps는 정합성 통과, 성능 실패. Redis down + shared DB 1000rps는 technical failure 발생 | `redis-down`: confirmed response `10`, controlled rejected `1895`, waiting `6`, technical failures `0` | PASS | 실행 5초 후 Redis master pod 삭제 |
| local queue accepted/full | 해당 없음 | 미계측 | WATCH | 현재 k6 summary가 accepted/full metric을 export하지 않음 |
| worker drain 시간 | 해당 없음 | 미계측 | WATCH | raw k6/DB snapshot만으로 drain 완료 시간을 직접 산출하지 않음 |
| DB pressure | shared DB + Redis down 1000rps에서 연결 실패/timeout 발생 | 미계측 | WATCH | LGTM/Hikari 시계열 raw export 미수집 |
| p95 latency | 원시 결과 미보유 | booking p95 최대 `442.2ms`(payment-failure), `redis-down` booking p95 `233.8ms`, `peak-1000` booking p95 `89.8ms` | PASS | k6 summary 기준 |
| dropped iterations | Redis down 500rps `266`, shared DB + Redis down 1000rps `11057` | `redis-down=25`, 나머지 시나리오 `0` | PASS | redis-down 허용치 안 |

## 실행 명령

| 구분 | 명령 | 비고 |
|---|---|---|
| v1 기존 결과 | 보관된 실행 로그 기준 | `loadtest-results/V1/LOADTEST_ANALYSIS.md` 참고 |
| 수정 후 결과 | `BASE_URL=<loadtest-target> SCENARIO=<scenario> RATE=<rate> DURATION=<duration> k6 run k6/booking-resilience.js` | 각 시나리오 전 원격 MySQL/Redis reset 검증. 배포 이미지 `peak-booking/backend:loadtest-20260603-210017` |

## 원시 결과 위치

| 구분 | 파일/디렉토리 | 포함 내용 |
|---|---|---|
| v1 기존 결과 | `loadtest-results/V1/` | `LOADTEST_ANALYSIS.md`, `logs/SUITE_SUMMARY*.tsv`, `logs/*-summary.json`, `logs/*-inventory.txt`, `logs/*-cluster.txt`, `logs/*.log` |
| 수정 후 결과 | `loadtest-results/V2/` | `LATEST_BACKEND_RERUN.md`, `logs/latest-backend-rerun/summary.tsv`, `logs/latest-backend-rerun/*-summary.json`, `logs/latest-backend-rerun/*-k6.out`, `logs/latest-backend-rerun/*-db-snapshot.txt`, `logs/latest-backend-rerun/redis-down-injection.txt` |

## DB Snapshot

| 구분 | confirmed | held | payment_unknown | released/failed | 비고 |
|---|---:|---:|---:|---:|---|
| v1 기존 결과 | 최대 10 | 시나리오별 최대 10 이하 | 일부 timeout/failure 시나리오별 상이 | 보관 snapshot 참고 | oversell은 없으나 일부 수렴/성능 실패 |
| 수정 후 결과 | 최대 10 | 0 | 최대 10 (`pg-timeout`) | 63 (`payment-failure`) | 모든 scenario에서 occupied `<= 10` |

## 관측 지표

| 지표 | v1 기존 결과 | 수정 후 결과 | 해석 |
|---|---|---|---|
| Hikari active/pending | 대시보드 캡처 정리됨 | 미계측 | LGTM/Prometheus raw export가 필요 |
| Redis timeout/failover | Redis down 성능 실패와 shared DB + Redis down 1000rps 기술 실패 기록 | `redis-down` PASS, dropped iterations `25`, technical failures `0` | failover 중 통제된 응답 유지. 테스트 후 Redis HA 정상 복구 확인 |
| local queue active_count | 해당 없음 | 미계측 | app metric/export 확인 필요 |
| local queue full count | 해당 없음 | 미계측 | app metric/export 확인 필요 |
| PG confirm count | 보관 snapshot 기준 확인 | DB snapshot 기준 `SUCCESS APPROVED=10` 또는 failure/timeout scenario별 기대 상태 | replay/follow-up 응답 카운터보다 DB snapshot 우선 |

## 남은 확인 사항

| ID | 항목 | 필요한 작업 | 우선순위 |
|---|---|---|---|
| LT-001 | V1 정량 비교 | 현재는 요약/증거 파일과 로그 기준으로 보관. 대시보드 캡처 기반 상세 재분석은 불가 | P3 |
| LT-002 | local queue accepted/full 계측 | k6 summary 또는 Prometheus export에 queue accepted/full/drain metric 추가 | P1 |
| LT-003 | DB pressure raw evidence | Hikari active/pending, MySQL row lock/connection 지표를 run별 export | P1 |
| LT-004 | 장시간 soak | 1~5분 midnight burst 뒤 recovery 상태와 drain 완료 시간 측정 | P2 |

## 정리된 실행 기록

- 로컬 preflight와 첫 gcloud/k3s quick run은 최신 V2 rerun에 의해 대체되어 원시 파일을 정리했다.
- invalid cloud run은 원격 DB reset 실패가 섞여 판정에서 제외했고 원시 파일도 정리했다.
- 유효 증거는 V1/V2 보관 디렉터리와 최신 V2 rerun 디렉터리에 남긴다. V1/V2 실행 로그는 보관한다.

## 2026-06-03 gcloud/k3s latest backend rerun

> 현재 로컬 `backend/` source 기준으로 `linux/amd64` 이미지를 고유 태그(`peak-booking/backend:loadtest-20260603-210017`)로 빌드/import하고, k3s deployment image를 해당 태그로 갱신한 뒤 재실행했다. 테스트 전 원격 DB reset 결과(`reservation_count=0`, `payment_count=0`, inventory counters 0)를 확인했고, Redis reset은 master/replica pod 전체에 `FLUSHALL`을 시도해 master에서만 성공하도록 했다.

| 항목 | 값 |
|---|---|
| 대상 브랜치/커밋 | `codex/local-redis-fallback-queue` / `a6692e1` |
| backend 작업트리 diff | 없음 |
| 테스트 환경 | GCP `asia-northeast3-a`, GCE 6 nodes, all `e2-standard-2` |
| 하드웨어 스펙 | node당 2 vCPU / 8 GiB RAM, 총 12 vCPU / 48 GiB RAM |
| 노드 배치 | control-plane/Traefik 1, WAS 2, MySQL 1, Redis 1, LGTM 1 |
| 배포 이미지 | `peak-booking/backend:loadtest-20260603-210017` |
| 원시 결과 | 요약 `loadtest-results/V2/LATEST_BACKEND_RERUN.md`, 증거 `loadtest-results/V2/logs/latest-backend-rerun/` |

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
