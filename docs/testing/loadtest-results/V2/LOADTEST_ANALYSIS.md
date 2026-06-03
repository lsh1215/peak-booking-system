# V2 Load Test Analysis

V2는 Redis HA(primary + replica 2 + Sentinel 3)와 failover pause 정책을 적용한 현재 구조다. 각 시나리오 전에는 WAS/Redis/Sentinel rollout과 Ready 상태를 확인하고, DB/Redis 데이터를 초기화했다.

## 환경

| 항목 | 값 |
|---|---|
| Cloud | GCP `asia-northeast3-a` |
| Hardware | GCE 6 nodes, all `e2-standard-2` |
| Node spec | node당 2 vCPU / 8 GiB RAM, 총 12 vCPU / 48 GiB RAM |
| Node placement | Traefik/control-plane 1, WAS 2, MySQL 1, Redis 1, LGTM 1 |
| App | `booking-service` 2 replicas, WAS 1대 장애 테스트는 1 replica |
| Redis | primary 1, replica 2, Sentinel 3 |
| Redis consistency guard | `WAIT 1`, timeout `50ms`, `min-replicas-to-write 1` |
| Load generator | local k6 |

## 요약

| 시나리오 | 판정 | k6 exit | dropped | booking p95 | checkout p95 | DB confirmed | DB occupied | 해석 |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `01-peak-1000` | 통과 | `0` | `0` | `96ms` | `88ms` | `10` | `10` | 정상 1000 RPS 피크에서 초과판매 없이 10개 확정 |
| `02-duplicate-500` | 통과 | `0` | `0` | `71ms` | `68ms` | `10` | `10` | 중복 클릭이 중복 결제/중복 예약으로 이어지지 않음 |
| `03-pg-timeout-500` | 통과 | `0` | `0` | `42ms` | N/A | `0` | `0` | PG timeout은 확정 예약을 만들지 않고 reservation release + post-release reconciliation으로 분리 |
| `04-was-one-down-500` | 통과 | `0` | `0` | `31ms` | `32ms` | `10` | `10` | Redis 정상, WAS 1대만 남긴 상태에서 10개 판매까지 수렴 |
| `05-redis-master-failover-500` | 정합성 통과, 성능 실패 | `99` | `1908` | `6879ms` | `4664ms` | `10` | `10` | 직전 WAS scale-up 영향이 섞였을 가능성이 있어 별도 재실측 수행 |
| `05b-redis-master-failover-500-isolated-rerun` | 응답시간 통과, 판매 수렴 실패 | `0` | `0` | `62ms` | `59ms` | `9` | `9` | 오염을 줄인 재실측에서는 latency는 좋아졌지만 failover 중 1건이 `REQUESTED`에 남아 최종 10개까지 자동 수렴하지 않음 |
| `06-realistic-mixed-500` | latency 통과, 판매 수렴 실패 | `0` | `0` | `68ms` | `78ms` | `8` | `8` | 현실 혼합 부하에서 timeout/failure 후 후순위 후보가 충분히 승격되지 않음 |

## 핵심 해석

> 이 문서는 최신 backend rerun 이전의 V2 초기/isolated rerun 분석이다. 최신 최종 판정은 `LATEST_BACKEND_RERUN.md`와 `docs/testing/loadtest-evidence-index.md`를 따른다.

정상 피크, 중복 클릭, PG timeout, WAS 1대 장애는 **정합성/응답시간/부하 생성 기준을 모두 통과**했다.

Redis HA failover는 두 가지를 분리해서 봐야 한다.

- 첫 run은 DB confirmed `10`으로 정합성은 통과했지만, p95가 수 초대로 튀고 dropped iteration이 발생했다.
- 오염을 줄인 재실측은 p95/dropped는 정상화됐지만 DB confirmed가 `9`에 머물렀다.

따라서 이 초기 실행만 놓고 보면 Redis failover 중 oversell/DB collapse는 막았지만, 판매 수렴성은 최신 rerun에서 재확인할 필요가 있었다. 최신 backend rerun에서는 `redis-down` 시나리오가 confirmed `10`, technical failure `0`으로 PASS했다.

`06-realistic-mixed-500`도 비슷하게, 기술 실패나 초과판매는 없지만 최종 confirmed가 `8`이다. 이는 결제 실패/timeout 뒤 대기자 replay/backfill 흐름이 k6 시나리오와 구현에서 충분히 닫혔는지 추가 확인이 필요하다는 뜻이다.

## 원시 파일

원시 증거 파일은 `logs/` 아래에 모았다.

| 파일 패턴 | 내용 |
|---|---|
| `logs/*-summary.json` | k6 metric 요약 |
| `logs/*.log` | k6 실행 로그 |
| `logs/*-inventory.txt` | MySQL 재고/예약/결제 상태 |
| `logs/*-cluster.txt` | pod 상태와 이벤트 |
| `logs/SUITE_SUMMARY.tsv` | suite 전체 요약 표 |
