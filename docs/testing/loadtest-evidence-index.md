# Load Test Evidence: V1 vs V2

작성일: 2026-06-03

이 문서는 한정 재고 예약 시스템의 부하 테스트가 무엇을 검증했고, 결과적으로 어떤 판단을 내려도 되는지를 남기는 기술 기록이다. 원시 증거 파일은 `docs/testing/loadtest-results/V1/logs/`, `docs/testing/loadtest-results/V2/logs/` 아래에 보관하고, 이 문서는 의사결정에 필요한 해석만 요약한다.

## 무엇을 알아보기 위한 테스트였나

이 테스트의 질문은 단순한 "몇 TPS까지 버티는가"가 아니다. 핵심은 **자정 플래시세일처럼 짧은 시간에 500~1000 TPS가 들어와도 재고 10개를 초과 판매하지 않고, 중복 클릭/결제 실패/PG timeout/Redis 장애/WAS 장애가 겹쳐도 예약 정합성이 깨지지 않는가**다.

V1은 Redis 장애 시 DB fallback 경로가 정합성은 대체로 지키지만, 성능 저하와 판매 수렴 실패가 남아 있었다. V2는 Redis HA와 WAS-local bounded queue fallback을 적용한 뒤, 같은 위험 시나리오에서 정합성과 장애 중 응답 품질이 실제로 개선됐는지 확인하기 위한 비교 대상이다.

## 결론

**V2 latest backend rerun은 이번 합격 기준을 통과했다.** 최신 V2 실행에서는 10개 시나리오 모두 k6 threshold가 PASS였고, DB snapshot 기준 confirmed/payment_unknown 합계가 항상 stock `10` 이하로 유지됐다. `payment-failure`는 confirmed `0`, `pg-timeout`은 payment_unknown `10`으로 기대 상태를 만족했다.

**가장 중요한 변화는 Redis 장애 구간이다.** V1은 Redis down 500rps에서 초과판매는 막았지만 p95가 수 초대로 밀렸고, shared DB + Redis down 1000rps에서는 dropped iteration과 technical failure가 크게 발생했다. V2 최신 실행은 Redis master pod 삭제 중 `dropped_iterations=25`가 있었지만 허용치 안에서 PASS했고, `booking_technical_failure_total=0`, 최종 confirmed `10`을 유지했다.

**다만 이것은 운영 최종 승인 문서가 아니라 짧은 burst 검증 문서다.** local queue accepted/full, worker drain 시간, Hikari active/pending, MySQL lock/connection 지표는 아직 직접 계측하지 못했다. 1~5분 이상 soak/endurance와 DB pressure raw export는 다음 검증으로 남긴다.

## 판정 기준

이번 부하 테스트는 아래 기준으로 합격/보류를 판단했다.

| 기준 | 합격 조건 | 이번 V2 결과 |
|---|---|---|
| 재고 정합성 | confirmed + payment_unknown 합계가 stock `10` 이하 | PASS |
| 중복 요청 | duplicate click/retry가 중복 예약/결제로 이어지지 않음 | PASS |
| 결제 실패 | PG failure가 confirmed booking을 만들지 않음 | PASS |
| PG timeout | timeout은 확정이 아니라 payment_unknown/recovery 대상으로 남음 | PASS |
| 정상 peak | 500~1000 TPS 짧은 burst에서 k6 threshold 통과 | PASS |
| WAS 장애 | booking-service 1 replica 상태에서도 초과판매 없음 | PASS |
| Redis 장애 | Redis master 삭제 중 controlled response 유지, technical failure 없음 | PASS |
| 장애 후 복구 | Redis HA가 master 1 + replicas 2 + Sentinel 3 정상 상태로 복구 | PASS |
| queue/drain 가시성 | local queue accepted/full/drain을 metric으로 확인 | WATCH |
| DB pressure 가시성 | Hikari/MySQL lock/connection 지표를 run별 보존 | WATCH |

## 테스트 환경

부하 테스트 결과는 환경 크기에 종속되므로 URL보다 하드웨어 스펙과 배치가 중요하다.

| 항목 | 값 |
|---|---|
| Cloud / Zone | GCP `asia-northeast3-a` |
| Cluster | k3s on GCE |
| Hardware | GCE 6 nodes, all `e2-standard-2` |
| Node spec | node당 2 vCPU / 8 GiB RAM |
| Total capacity | 12 vCPU / 48 GiB RAM |
| Node placement | control-plane/Traefik 1, WAS 2, MySQL 1, Redis 1, LGTM 1 |
| App runtime | booking-service 2 replicas, WAS 장애 테스트는 1 replica |
| Data stores | MySQL 8, Redis primary/replicas + Sentinel |
| Load generator | local k6 |

## 무엇을 테스트했고 무엇을 안 봤나

이번 테스트가 점검한 것은 예약 write path의 짧은 peak/failure behavior다. 구체적으로 health, 정상 예약, 중복 클릭, PG timeout, PG failure, realistic mixed, 500 TPS peak, 1000 TPS peak, WAS 1대 down, Redis master failover를 확인했다.

이번 테스트가 직접 점검하지 않은 것도 분명하다. 실제 결제사 연동 지연 분포, 장시간 soak, 운영 트래픽의 지역별 네트워크 변동, autoscaling, real production data volume, DB connection pool/row lock 시계열, local queue drain latency는 포함하지 않았다. 따라서 V2는 "짧은 플래시세일 burst와 주요 장애 시나리오에서 정합성/제어 응답을 확인했다"까지 말할 수 있고, "운영 장시간 안정성까지 검증됐다"라고 말하면 과장이다.

## V1에서 무엇이 문제였나

V1의 핵심 문제는 **정합성은 지키지만 장애 중 품질과 수렴성이 불안정했다**는 점이다.

Redis down 500rps에서는 DB fallback이 초과판매를 막았지만 booking p95가 `4368ms`, checkout p95가 `3053ms`까지 늘었다. mixed 500rps clean rerun은 60초 후에도 confirmed `9`에서 멈췄고, shared DB + Redis down 1000rps는 dropped `11057`, http failure `3.26%`, technical failure `3.26%`가 발생했다.

따라서 V1은 "DB final guard 덕분에 oversell은 막는다"까지는 확인했지만, "장애 중에도 사용자 요청을 통제된 방식으로 처리하고 최종 10개 판매까지 수렴한다"까지는 확인하지 못했다.

## V2에서 무엇이 개선됐나

V2 최신 실행은 Redis HA + bounded local queue fallback 적용 후, 같은 위험군을 다시 검증했다. 결과적으로 정상 peak, 중복 클릭, PG timeout/failure, WAS 1대 down, Redis master pod 삭제 시나리오가 모두 PASS했다.

특히 Redis down 최신 실행은 confirmed response `10`, controlled rejected `1895`, waiting `6`, technical failures `0`이었다. failover 중 `dropped_iterations=25`가 있었지만 시나리오 허용치 안이었고, DB snapshot은 confirmed `10`, reserved `0`, payment_unknown `0`으로 종료됐다.

이 결과는 V2가 Redis 장애 중 무제한 DB fallback으로 밀어붙이는 구조가 아니라, 제한된 queue/fallback 경로로 DB 붕괴를 피하면서 최종 재고 정합성을 유지한다는 방향의 증거다.

## 최신 V2 시나리오 결과

| 시나리오 | Rate / Duration | 무엇을 확인했나 | DB 최종 상태 | 판정 |
|---|---:|---|---|---|
| health | 20/s / 10s | 배포/ingress 기본 생존성 | inventory 변화 없음 | PASS |
| booking | 50/s / 20s | 정상 예약 write path | confirmed 10 | PASS |
| duplicate | 30/s / 20s | 중복 클릭/멱등성 | confirmed 10 | PASS |
| pg-timeout | 30/s / 20s | PG timeout 상태 전이 | payment_unknown 10 | PASS |
| payment-failure | 30/s / 20s | 결제 실패 시 예약 미확정 | confirmed 0, released 63 | PASS |
| realistic-mixed | 30/s / 20s | 성공/실패/timeout 혼합 | confirmed 10 | PASS |
| peak-500 | 500/s / 20s | 짧은 peak burst | confirmed 10 | PASS |
| peak-1000 | 1000/s / 20s | 최대 목표 burst | confirmed 10 | PASS |
| was-one-down | 100/s / 30s | WAS 1 replica 장애 상태 | confirmed 10 | PASS |
| redis-down | 100/s / 45s | Redis master 삭제/failover | confirmed 10 | PASS |

## V1 vs V2 요약 비교

| 검증 항목 | V1 | V2 latest | 해석 |
|---|---|---|---|
| oversell 방지 | PASS | PASS | 두 버전 모두 DB final guard로 stock `10` 초과는 막음 |
| 정상/peak 판매 수렴 | 일부 mixed 수렴 실패 | PASS | V2 latest는 주요 성공 시나리오에서 confirmed `10` 도달 |
| Redis 장애 중 성능 | 500rps p95 수 초대 | PASS | V2 redis-down booking p95 `233.8ms` |
| Redis 장애 중 technical failure | 1000rps shared DB 조건에서 3.26% | 0 | V2 latest는 controlled response로 종료 |
| dropped iterations | 최대 `11057` | redis-down `25`, 그 외 `0` | V2에서 장애 중 load shedding이 크게 안정화 |
| 관측성 | dashboard capture 중심, 현재 정리됨 | k6/DB snapshot 중심 | queue/DB pressure metric은 추가 필요 |

## 남은 리스크와 다음 테스트

1. local queue accepted/full/drain metric을 k6 summary 또는 Prometheus export에 추가한다.
2. Hikari active/pending, MySQL row lock, connection usage를 run별 raw evidence로 보관한다.
3. 1~5분 midnight burst 뒤 recovery 상태와 drain 완료 시간을 soak/endurance test로 확인한다.
4. 이번 결과는 `e2-standard-2` 6노드 검증 환경의 결과다. 다른 machine type, managed Redis, managed DB 환경에서는 baseline을 다시 잡아야 한다.

## 증거 위치

| 구분 | 위치 | 내용 |
|---|---|---|
| V1 요약 | `docs/testing/loadtest-results/V1/LOADTEST_ANALYSIS.md` | V1 해석과 실패 지점 |
| V1 증거 | `docs/testing/loadtest-results/V1/logs/` | k6 logs, summary JSON, inventory/cluster snapshots, suite TSV |
| V2 기존 요약 | `docs/testing/loadtest-results/V2/LOADTEST_ANALYSIS.md` | V2 초기/isolated rerun 해석 |
| V2 latest 요약 | `docs/testing/loadtest-results/V2/LATEST_BACKEND_RERUN.md` | 최신 backend rerun 요약 |
| V2 latest 증거 | `docs/testing/loadtest-results/V2/logs/latest-backend-rerun/` | k6 stdout, summary JSON, DB snapshots, Redis injection log |

## 작성 기준

이 문서는 다음 작성 관례를 반영했다.

- AWS Well-Architected는 production-like 환경, 시나리오/파라미터 정의, 성능 metric 기록, 병목 분석, 결과 문서화를 load test의 핵심 단계로 둔다: <https://docs.aws.amazon.com/wellarchitected/2023-10-03/framework/perf_process_culture_load_test.html>
- AWS Prescriptive Guidance는 재실행 가능한 환경 스펙 문서화와 실제 사용자 행동을 닮은 scenario modeling을 강조한다: <https://docs.aws.amazon.com/prescriptive-guidance/latest/load-testing/foundation.html>
- Azure Well-Architected는 가설/합격 기준/threshold를 먼저 정하고, 결과를 baseline과 비교해 의사결정에 쓰라고 권장한다: <https://learn.microsoft.com/en-us/azure/well-architected/performance-efficiency/performance-test>
- Grafana k6 문서는 threshold, checks, p95 latency, error rate, custom metrics 같은 end-of-test summary를 결과 분석의 기본 단위로 본다: <https://grafana.com/docs/k6/latest/examples/get-started-with-k6/analyze-results/>
