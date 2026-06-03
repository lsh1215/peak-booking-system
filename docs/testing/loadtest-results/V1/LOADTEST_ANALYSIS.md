# Isolated Load Test Analysis

실행 시각: 2026-06-03 KST

테스트 목적은 시나리오 오염을 줄이고, 각 장애 조건에서 정합성/성능/관측 증거를 따로 확인하는 것이다. 테스트 후 서버는 내리지 않았고, booking-service 2대와 Redis 1대는 정상 Ready 상태로 복구했다.

## 실행 환경

| 구성요소 | 배치/스펙 |
| --- | --- |
| Cloud / Zone | GCP `asia-northeast3-a` |
| Hardware | GCE 6 nodes, all `e2-standard-2` |
| Node spec | node당 2 vCPU / 8 GiB RAM, 총 12 vCPU / 48 GiB RAM |
| Node placement | Traefik/control-plane 1, WAS 2, MySQL 1, Redis 1, LGTM 1 |

## 보관 증거

- 대시보드 PNG 캡처는 파일 수/용량을 줄이기 위해 정리했다.
- 판정 재검증에 필요한 `logs/SUITE_SUMMARY*.tsv`, `logs/*-summary.json`, `logs/*-inventory.txt`, `logs/*-cluster.txt`, `logs/*.log`는 남겼다.
- 05번 Redis down 해석은 보관된 k6 summary와 DB snapshot을 기준으로 한다.

## 결과 요약

| # | 시나리오 | 판정 | 핵심 수치 | 해석 |
| --- | --- | --- | --- | --- |
| 01 | 정상 1000rps peak | 통과 | iterations 60001, dropped 0, http_failed 0, 전체 p95 75.9ms, DB confirmed 10 | 정상 피크에서는 Redis admission + MySQL final guard가 10개 판매를 안정적으로 닫았다. |
| 02 | 중복 클릭 500rps | 통과 | booking p95 72.4ms, checkout p95 69.3ms, DB confirmed 10 | 중복 요청이 재고 초과나 중복 결제로 이어지지 않았다. |
| 03 | PG timeout 500rps | 통과 | booking p95 59.3ms, DB confirmed 0, occupied 0 | 전부 timeout인 조건에서는 예약 확정을 만들지 않고 재고를 회수했다. |
| 04 | WAS 1대 steady 500rps | 통과 | booking p95 40.1ms, checkout p95 42.6ms, DB confirmed 10 | Redis 정상 + WAS 1대 상태에서는 10개 판매까지 수렴했다. |
| 05 | Redis down 500rps | 정합성 통과, 성능 실패 | dropped 266, booking p95 4368ms, checkout p95 3053ms, DB confirmed 10 | DB fallback은 oversell 없이 10개를 팔았지만, 장애 모드 응답시간은 서비스 품질 기준에 못 미친다. |
| 06 | mixed 500rps clean rerun | 실패 | booking p95 215.6ms, DB confirmed 9, occupied 9 | 60초 후에도 9개에서 멈췄다. `payment_attempt REQUESTED` 1건이 `next_reconcile_at=NULL`로 남아 backfill/복구 경로가 닫히지 않는다. |
| 07 | shared DB + Redis down 500rps | 통과 | booking p95 366.0ms, checkout p95 95.5ms, DB confirmed 10 | MySQL에 별도 부하가 있어도 500rps fallback은 정합성과 기본 성능을 유지했다. |
| 08 | shared DB + Redis down 1000rps | 실패 | dropped 11057, http_failed 3.26%, technical_failure 3.26%, booking p95 8223ms, DB confirmed 10 | DB 정합성은 지켰지만 연결 거부/리셋/타임아웃이 발생했다. 이 조건은 현재 운영 기준으로 감당 불가다. |

## 유효/무효 처리

- `06-mixed-500` 최초 실행은 Redis down 직후의 WAS-local fallback state가 섞였을 가능성이 있어 판정에서 제외했다.
- `06-mixed-500-rerun`은 booking-service를 재시작하고 Redis 정상 상태에서 다시 측정한 clean run이므로 유효하다.
- `07` 최초 시도는 legacy DB load pod가 Redis 노드에서 image pull에 실패해 무효 처리했다.
- `07-shared-db-redis-down-500-rerun2`, `08-shared-db-redis-down-1000-rerun2`는 legacy DB load pod가 MySQL 노드에 배치된 유효 실행이다.

## 현재 문제

1. Redis down 500rps는 정합성은 통과하지만 응답시간이 길다. DB가 터진 것은 아니지만, 사용자 입장에서는 장애 모드 품질이 낮다.
2. Mixed 500rps clean run은 최종 판매가 9개에서 멈춘다. `REQUESTED` payment attempt가 recovery 대상이 되지 않는 구현 결함 가능성이 높다.
3. shared DB + Redis down 1000rps는 DB 최종 guard는 지키지만 gateway/app 입구에서 연결 실패가 발생한다. 현재 용량과 fallback 정책으로는 이 조합을 합격으로 볼 수 없다.

## 다음 조치 후보

- Mixed backfill/복구 경로에서 `REQUESTED` payment attempt가 terminal/recovery 상태로 빠지지 않는 이유를 코드 단위로 추적한다.
- Redis down fallback에서 Redis timeout/circuit/fallback 진입 비용과 DB fallback semaphore 대기/거절 흐름을 분리해 응답시간 원인을 확인한다.
- shared DB + Redis down 1000rps는 Traefik/connection 한계, WAS bulkhead, DB fallback budget 중 어느 지점에서 연결 실패가 시작되는지 대시보드와 로그로 분해한다.
