# 부하 테스트 성공 기준 - 실측 보정 기준

이 문서는 실제 k3s/GCP 부하 테스트 결과를 반영해 보정한 성공 기준이다. 초기 예상 기준은 [부하 테스트 성공 기준 - 초기 예상 기준](./loadtest-success-criteria-initial.md)에 보존한다.

## 기준 분리 원칙

- DB 정합성, 중복 결제 방지, 재고 점유 deadline 같은 hard correctness 기준은 보정하지 않는다.
- latency, dropped iteration, warning threshold는 테스트 환경과 부하 생성기의 실측값을 근거로 보정할 수 있다.
- k6 응답 counter는 user-facing 응답 개수를 세는 지표다. 최종 초과판매 여부는 반드시 DB snapshot의 `sale_inventory`, `reservation`, `payment_attempt`, `booking_admission` 기준으로 판단한다.
- controlled rejection은 실패가 아니라 overload control 결과다. technical failure와 분리해 측정한다.

## 실측 환경

| 항목 | 값 |
|---|---|
| 1차 측정 일시 | `2026-06-01T15:48:00Z` 시작 |
| 1차 결과 디렉터리 | `loadtest-results/20260601T154800Z-criteria-calibration` |
| 2차 재측정 일시 | `2026-06-01T16:05:49Z` 시작 |
| 2차 결과 디렉터리 | `loadtest-results/20260601T160549Z-criteria-rerun` |
| Cloud | GCP `asia-northeast3-a` |
| Instance | `peak-booking-k3s-20260601122904`, `e2-standard-4` |
| Kubernetes | single-node k3s + Traefik |
| App replica | `2`개, WAS 1대 장애 시 `1`개 |
| DB / Redis / Observability | MySQL 8.4, Redis 7.4, LGTM |
| Load generator | local macOS k6 `v1.5.0`, remote app URL `http://34.47.72.186` |
| Mock PG | normal delay `100ms`, timeout profile 별도 |
| Monitoring sanity | Grafana `/api/health`, Prometheus readiness, `up{application="peak-booking-service"}`, JVM/Hikari metric 존재 확인 |

## 1차 실측 요약

| 시나리오 | Rate / Duration | k6 결과 | DB 최종 상태 | 주요 관측 |
|---|---:|---|---|---|
| health | `20/s`, `30s` | pass | occupied `0` | p95 `12.4ms`, dropped `0` |
| baseline | `50/s`, `1m` | pass | confirmed `8` | technical failure `0.035%`, dropped `27`; 정상 판매 10개 미달은 재검증 필요 |
| duplicate | `100/s`, `30s` | pass | confirmed `10` | duplicate 응답 counter와 DB 최종 상태를 분리해야 함 |
| pg-timeout | `100/s`, `45s` | pass | confirmed `0`, released `10` | `PAYMENT_UNKNOWN` 후 reservation release, payment는 post-release reconciliation |
| was-one-down | `100/s`, `45s` | pass | confirmed `10` | replica 1개 상태에서도 oversell 없음 |
| redis-down | `100/s`, `45s` | latency threshold fail | confirmed `0` | p95 `1117ms`, dropped `79`; 현재 장애 주입은 DB fallback이 아니라 controlled Redis rejection으로 관측됨 |
| mixed | `100/s`, `45s` | latency threshold fail | confirmed `10` | k6 confirmed response counter `12`, DB confirmed `10`. 이후 k6 metric은 `booking_confirmed_response_total`로 이름을 분리 |
| peak | `1000/s`, `2m` | pass | confirmed `10` | booking p95 `290ms`, checkout p95 `265ms`, dropped `387` |

## 2차 재측정 요약

2차 재측정은 이 문서의 보정 기준을 `k6/booking-resilience.js`에 반영한 뒤 같은 GCP/k3s 환경에서 실행했다. 결과적으로 hard correctness는 Redis timeout controlled reject 시나리오를 제외한 정상/장애 흐름에서 초과판매 없이 유지됐지만, k6 threshold 기준으로는 `baseline`과 `peak`가 아직 완전 통과하지 못했다.

| 시나리오 | k6 exit | DB 최종 상태 | 주요 관측 | 판정 |
|---|---:|---|---|---|
| health | `0` | occupied `0` | p95 `8.5ms`, dropped `0` | 통과 |
| baseline | `99` | confirmed `10` | dropped `31/3000`로 `< 1%` 기준 초과. booking p95 `18ms`, checkout p95 `30ms` | 정합성 통과, 부하 생성 누락 기준 실패 |
| duplicate | `0` | confirmed `10` | technical failure `0.023%`, dropped `11/3000` | 통과 |
| pg-timeout | `0` | confirmed `0`, released `10`, payment `RECONCILING_AFTER_RELEASE 10` | `PAYMENT_UNKNOWN` 후 release 확인 | 통과 |
| was-one-down | `0` | confirmed `10` | 생존 replica 1개에서도 초과판매 없음 | 통과 |
| redis-down | `0` | confirmed `0`, occupied `0` | Redis scale-to-zero에서는 DB fallback 판매가 아니라 controlled Redis rejection으로 관측 | 부분 통과. bounded DB fallback 별도 검증 필요 |
| mixed | `0` | confirmed `8`, released `2` | 실패/timeout 혼합 후 후순위 후보 follow-up이 충분히 검증되지 않음 | threshold 통과, 미달 판매 재검증 필요 |
| peak | `99` | confirmed `10` | booking p95 `340ms` 통과, checkout p95 `383ms`로 `350ms` 초과, dropped `267/120000` | 정합성 통과, checkout latency 기준 실패 |

2차 재측정에서 `baseline`의 1차 underfill 의심은 재현되지 않았다. 반면 `mixed` underfill은 남아 있으므로, 현재 k6 script가 `WAITING_CANDIDATE` follow-up/replay를 충분히 모델링하지 못한 것인지, 실제 구현이 후순위 승격으로 10개 판매를 채우지 못하는 것인지 분리해야 한다.

## 추가 분리 검증

### Waiting promotion targeted verification

결과 디렉터리: `loadtest-results/20260601T165626Z-waiting-promotion-targeted`

목적은 `mixed`에서 confirmed `8`로 끝난 원인이 서버 승격 로직 문제인지, k6 mixed 시나리오의 대기자 replay 부재인지 분리하는 것이다. 절차는 다음과 같다.

1. dataset reset.
2. 1~10번 사용자는 `PG=TIMEOUT`으로 `PAYMENT_UNKNOWN`을 만든다.
3. 11~30번 사용자는 같은 이벤트에 진입해 `WAITING_CANDIDATE`가 된다.
4. `40s` 대기해 `30s` inventory deadline과 recovery worker release를 기다린다.
5. `WAITING_CANDIDATE`였던 사용자들이 같은 `booking_attempt_id`로 `POST /bookings`를 replay한다.

관측 결과:

| 단계 | 결과 |
|---|---|
| 초기 요청 | `PAYMENT_UNKNOWN 10`, `WAITING_CANDIDATE 20` |
| 대기자 replay | `BOOKING_CONFIRMED 10`, `WAITING_CANDIDATE 10` |
| DB 최종 재고 | `confirmed_count 10`, occupied `10` |
| reservation | `RELEASED 10`, `CONFIRMED 10` |
| payment_attempt | `RECONCILING_AFTER_RELEASE 10`, `CONFIRMED 10` |

판단: 서버는 대기자가 같은 attempt로 replay할 때 앞 순번 10명을 승격해 재고 10개를 채울 수 있다. 따라서 `mixed` confirmed `8`은 현재까지의 증거로는 core promotion 로직 결함이라기보다, 기존 k6 mixed 시나리오가 `WAITING_CANDIDATE` 사용자의 후속 replay/polling을 모델링하지 않은 검증 공백으로 본다.

### Checkout-only peak verification

결과 디렉터리: `loadtest-results/20260601T165744Z-checkout-only-peak`

`peak`에서 checkout p95가 `383ms`로 보정 기준 `350ms`를 넘은 원인을 보기 위해 `GET /checkout`만 `1000/s`, `2m`로 분리 실행했다.

| 지표 | 값 |
|---|---:|
| iterations | `117,895` |
| dropped iterations | `2,106` |
| http failure rate | `0` |
| checkout p95 | `266ms` |
| checkout avg | `73ms` |

판단: checkout 단독 latency는 보정 기준 `350ms` 안에 들어온다. 따라서 직전 `peak`의 checkout p95 `383ms`는 checkout read path 단독 병목이라기보다, checkout과 booking write가 같은 ingress/network/load-generator 조건에서 섞일 때 생기는 combined-load 영향으로 본다. 다만 checkout-only도 dropped iteration이 약 `1.76%`로 높으므로, 부하 생성기 위치와 네트워크/Traefik 왕복 지연을 분리한 재검증이 필요하다.

### Checkout p95 원인 분리: app direct vs Traefik

결과 디렉터리:

- `loadtest-results/20260601T171844Z-checkout-incluster-split`
- `loadtest-results/20260601T172452Z-checkout-traefik-rate-sweep`
- `loadtest-results/20260601T172755Z-checkout-traefik-scale2`

외부 네트워크와 local k6 영향을 제거하기 위해 k6 pod를 k3s 클러스터 내부에 띄우고 `GET /checkout`만 분리 측정했다.

| 경로 | Rate / Duration | dropped | checkout p95 | checkout avg | 판단 |
|---|---:|---:|---:|---:|---|
| `booking-service:8080` direct | `1000/s`, `60s` | `0` | `70ms` | `19ms` | Spring checkout read path 자체는 충분히 빠름 |
| Traefik 1 replica | `250/s`, `30s` | `0` | `11ms` | `6ms` | 통과 |
| Traefik 1 replica | `500/s`, `30s` | `0` | `99ms` | `26ms` | 통과 |
| Traefik 1 replica | `750/s`, `30s` | `97` | `431ms` | `131ms` | p95 기준 초과 |
| Traefik 1 replica | `1000/s`, `30s` | `5769` | `1223ms` | `638ms` | 명확한 gateway 병목 |
| Traefik 2 replicas | `750/s`, `30s` | `0` | `249ms` | `64ms` | 통과 |
| Traefik 2 replicas | `1000/s`, `30s` | `4598` | `1099ms` | `409ms` | 여전히 초과 |

판단:

- checkout p95 spike는 현재 증거상 Spring Boot checkout read path 문제가 아니라 Traefik 경유 path의 용량/큐잉 문제다.
- Traefik `average=2500`, `burst=3000`이라 1000/s를 rate limiter가 의도적으로 막은 것은 아니다. 단일 Traefik pod가 프록시 병목이 되며, 2 replicas로 늘리면 750/s는 통과한다.
- 현재 single-node `e2-standard-4` k3s 환경에서 Traefik 경유 1000/s checkout-only는 통과하지 못했다. 따라서 end-to-end 1000/s checkout p95 `350ms` 기준은 app 코드 기준이 아니라 gateway/infra 기준으로 별도 보정하거나, Traefik replica/resource를 명시적으로 튜닝한 뒤 다시 측정해야 한다.

### Traefik resource 보정 재검증

결과 디렉터리: `loadtest-results/20260602T055611Z-traefik-resource-retest`

현재 요구사항은 `2+` WAS 앞에 LB/Ingress가 필요하다는 것이지, Traefik replica 자체를 늘리는 것이 목표는 아니다. 따라서 먼저 단일 Traefik replica에 명시적인 리소스 요청/제한을 부여하고 다시 측정했다.

적용값:

| 항목 | 값 |
|---|---:|
| CPU request | `1000m` |
| Memory request | `256Mi` |
| CPU limit | `2000m` |
| Memory limit | `512Mi` |

재검증 결과:

| 시나리오 | Rate / Duration | dropped | p95 | avg | DB confirmed |
|---|---:|---:|---:|---:|---:|
| checkout-only via Traefik | `1000/s`, `60s` | `876 / 59,101` | `245ms` | `69ms` | 해당 없음 |
| full peak via Traefik | `1000/s`, `2m` | `0 / 120,001` | booking `30ms`, checkout `33ms` | booking `17ms`, checkout `15ms` | `10` |

판단:

- 이전 checkout p95 spike의 직접 원인은 Spring Boot checkout path가 아니라, k3s 기본 Traefik pod에 리소스 요청이 없어 노드 경합 상황에서 충분한 CPU share를 보장받지 못한 것이다.
- Traefik replica를 늘리지 않아도 단일 LB/Ingress replica에 적절한 리소스 요청을 주면 `1000/s`, `2m` full peak에서 latency, dropped iteration, DB 정합성 기준을 모두 통과했다.
- 따라서 현 단계의 권장안은 Traefik 2 replicas가 아니라, k3s 기본 Traefik에도 load-test/staging-like 환경에서 명시적인 CPU/memory request/limit을 적용하는 것이다. 이 값은 `scripts/loadtest/gcloud-k3s/common.sh`와 `deploy.sh`에 재현 가능하도록 반영한다.

### Mixed follow-up 보완 검증

결과 디렉터리:

- `loadtest-results/20260601T171236Z-mixed-followup-k6-v2`
- `loadtest-results/20260601T173036Z-waiting-cascade-targeted`
- `loadtest-results/20260601T173245Z-waiting-cascade-targeted-45s`
- `loadtest-results/20260601T173720Z-mixed-fast-poll-failures`
- `loadtest-results/20260601T173847Z-mixed-fast-poll-realistic-timeout`

기존 `mixed` script는 `WAITING_CANDIDATE`를 받은 사용자가 같은 `booking_attempt_id`로 다시 `POST /bookings`를 replay하는 흐름을 충분히 모델링하지 못했다. 이를 보완하기 위해 mixed 시나리오에 대기자 follow-up replay를 추가하고, failure/timeout/duplicate 비율을 환경변수로 조절 가능하게 했다.

| 시나리오 | Fault 비율 | Follow-up | DB 최종 상태 | 판단 |
|---|---|---|---|---|
| mixed follow-up v2 | duplicate `20%`, timeout `10%`, failure `10%` | 첫 확인 `35s`, 이후 `5s` 간격 | confirmed `8`, `PAYMENT_UNKNOWN 1` | 강한 timeout 비율에서는 underfill 재현 |
| waiting cascade targeted | 1~10 timeout, 11~20 timeout replay, 21~30 success replay | 고정 순번 재현 | confirmed `0`, `PAYMENT_UNKNOWN 1`, 21~30 `WAITING_EXPIRED` | FIFO 공정성 + 60초 대기창의 구조적 한계 확인 |
| mixed fast-poll failures | timeout `0%`, failure `10%` | `5s`부터 `60s`까지 polling | confirmed `10` | 명확한 결제 실패는 재고 누수 없이 다음 순번으로 채워짐 |
| mixed fast-poll realistic timeout | timeout `2%`, failure `8%` | `5s`부터 `60s`까지 polling | confirmed `10` | 낮은 timeout 비율에서는 현재 60초 정책 안에서 10개 판매 확인 |

판단:

- 결제 실패처럼 결과가 명확한 failure는 빠른 대기자 replay가 있으면 재고 10개를 채울 수 있다.
- 반면 PG `PAYMENT_UNKNOWN`이 FIFO 선두에서 연속 발생하면, 뒤 순번을 앞질러 판매하지 않는 현재 공정성 정책상 60초 대기창 안에 모든 후보가 승격되지 못할 수 있다. 이는 Redis/DB 정합성 버그라기보다 `FIFO 공정성`, `30s inventory deadline`, `60s user-facing wait` 사이의 정책 trade-off다.
- 따라서 mixed는 두 종류로 나눠야 한다. `realistic mixed`는 낮은 timeout 비율에서 confirmed `10`을 기대하고, `adversarial mixed`는 연속 unknown 상황에서 oversell/중복 결제/무제한 retry가 없는지를 본다. 연속 unknown에서도 반드시 10개 판매를 강제하려면 60초 대기 제한을 늘리거나 FIFO strictness를 완화하는 별도 설계 결정이 필요하다.

### Scenario gap follow-up

결과 디렉터리:

- `loadtest-results/20260602T061112Z-scenario-gap-rerun`
- `loadtest-results/20260602T061840Z-coverage-followup`
- `loadtest-results/20260602T062912Z-was-one-down-rerun`

DB 장애와 Traefik 장애는 이번 검증 범위에서 제외했다. 둘 다 single-instance infra로 둔다는 요구조건에 맞춰, 장애 주입 대상은 WAS replica, Redis, Mock PG, duplicate/mixed traffic로 한정했다.

| 시나리오 | Rate / Duration | k6 threshold | DB 최종 상태 | 판단 |
|---|---:|---|---|---|
| payment-failure | `100/s`, `45s` | 통과 | confirmed `0`, reservation `RELEASED 14`, payment `FAILED 14` | 명확한 PG 실패는 예약 확정을 만들지 않고 재고를 release |
| late-success immediate | `100/s`, `45s` | 통과 | confirmed `8`, released `2`, payment `RECONCILING_AFTER_RELEASE 2` | 늦은 성공 대상은 booking으로 되살리지 않고 reconciliation 대상으로 분리 |
| late-success after `330s` | `100/s`, `45s` + wait | k6 통과, 후속 DB 관찰 | confirmed `5`, released `5`, payment `MANUAL_REVIEW_REQUIRED 5` | 재고/예약은 되살리지 않음. `5분` 적극 reconciliation 뒤에도 cancel/refund 결과가 불명확하면 수동 확인 상태로 닫는 것이 설계된 종료 조건 |
| realistic-mixed | `100/s`, `45s` | 통과 | confirmed `10`, payment `CONFIRMED 10` | 낮은 timeout 비율의 현실적 혼합 장애에서 10개 판매 확인 |
| adversarial-mixed with WAS 1 + Redis down | `100/s`, `45s` | 통과 | confirmed `0`, occupied `0` | 판매 지속보다 controlled reject로 DB 보호. oversell/DB collapse 없음 |
| WAS one-down peak | `500/s`, `1m` | 1차 통과, DB confirmed `9`; 재실행 통과 | 재실행 confirmed `10` | 1차의 confirmed `9`는 EOF 1회와 함께 발생했고 재현되지 않음. 재실행은 500/s에서 생존 replica로 판매 완료 |
| Redis hard-down peak | `500/s`, `45s` | 실패 | confirmed `0`, occupied `0` | 정합성은 안전하지만 latency/dropped 기준 실패. Redis hard-down에서 500/s를 사용자 응답시간 기준 안에 흡수하려면 별도 fail-fast/circuit breaker 보강 필요 |

판단:

- 현재 k6 시나리오는 요구사항의 주요 비-DB/비-Traefik 장애 축을 모두 덮는다: 정상 baseline/peak, 중복 클릭, PG timeout/failure/late success, WAS 1대 down, Redis down, realistic/adversarial mixed.
- `loadtest-results/`는 로컬 증거 보존용이며 Git 추적 대상이 아니다.
- 남은 성능 리스크는 Redis hard-down 고강도 부하이다. Redis가 완전히 내려간 상태에서 `500/s`를 걸면 app은 DB를 보호하고 controlled reject하지만, Redis command timeout 대기와 request concurrency 때문에 k6 latency/dropped threshold는 넘는다. 이 시나리오는 hard correctness pass, user-facing latency/backpressure fail로 분리한다.

## 보정한 Pass/Fail 기준

### Hard Correctness

아래 항목은 어떤 환경 실측으로도 완화하지 않는다.

| 기준 | Pass |
|---|---|
| 초과판매 | DB 기준 `confirmed_count <= 10` |
| 재고 불변식 | DB 기준 `reserved_count + payment_unknown_count + confirmed_count <= total_count` |
| 정상/피크 성공 판매 | PG success 정상 경로에서는 terminal recovery 후 DB confirmed `10`을 목표로 하며, 반복 미달이면 blocker로 분석 |
| 사용자 중복 확정 | 같은 `user_id + sale_event_id + product_id` confirmed 중복 `0` |
| 결제 중복 효과 | 같은 `booking_attempt_id`의 PG confirm side effect `1회 이하` |
| PG timeout/unknown | `30s` 안에 확정되지 않은 reservation은 release되고, 늦은 PG success는 booking 확정이 아니라 cancel/refund/reconciliation 대상 |
| Redis 장애 | oversell과 unlimited DB fallback이 없어야 하며, bounded DB fallback을 검증하는 장애 주입은 Redis timeout controlled reject와 별도 시나리오로 분리 |

### Calibrated Latency

| 경로 / 시나리오 | Pass | Warning | 보정 근거 |
|---|---:|---:|---|
| health | p95 `<= 500ms` | p95 `> 100ms` | 배포/ingress sanity check |
| `GET /checkout` normal/peak | p95 `<= 350ms` | p95 `> 250ms` | peak `1000/s` 2분 실측 p95 `265ms` + buffer |
| `POST /bookings` normal/peak | p95 `<= 400ms` | p95 `> 300ms` | peak `1000/s` 2분 실측 p95 `290ms` + buffer |
| mixed booking | p95 `<= 500ms` | p95 `> 350ms` | duplicate/PG failure/timeout 혼합 실측 p95 `216ms` + buffer |
| Redis timeout controlled reject | p95 `<= 1500ms` | p95 `> 1200ms` | Redis scale-to-zero 실측 p95 `1117ms`; Redis command timeout/retry 비용 포함 |
| PG timeout -> `PAYMENT_UNKNOWN` | p95 `<= 700ms` | p95 `> 600ms` | 초기 기준 유지. 1차 실측은 p95 `38ms`였지만 timeout profile 재검증 필요 |

p99는 여전히 pass/fail이 아니라 warning/관측 지표로 둔다.

### Dropped Iteration / Load Generator

| 시나리오 | Pass |
|---|---|
| health | dropped iterations `0` |
| normal / duplicate / PG timeout / WAS one down / mixed | expected iterations의 `< 1%` |
| peak `500~1000/s` | expected iterations의 `< 1%` |
| Redis timeout / Redis degraded | expected iterations의 `< 3%` |

dropped iteration이 기준을 넘으면 즉시 backend 실패로 단정하지 않는다. 부하 생성기 CPU, 네트워크, Traefik, app latency 중 병목 위치를 먼저 나눈다.

## 이번 실측에서 드러난 재검증 항목

1. `baseline` 1차 underfill 의심은 2차에서 재현되지 않았지만, dropped iteration이 `31/3000`으로 `< 1%` 기준을 아주 근소하게 넘었다. backend hard correctness 실패가 아니라 부하 생성/네트워크/ingress jitter 기준 실패로 분리한다.
2. `peak`는 DB confirmed `10`으로 정합성은 통과했지만, checkout p95가 `383ms`로 보정 기준 `350ms`를 넘었다. 분리 측정 결과 app direct checkout은 `1000/s`에서 p95 `70ms`로 통과했고, Traefik 경유 path가 `750~1000/s` 구간에서 병목이 됐다.
3. `mixed`는 기존 script 기준 DB confirmed `8`로 끝났다. follow-up replay를 보완한 뒤 명확한 failure 및 낮은 timeout 비율에서는 confirmed `10`을 확인했다. 다만 연속 PG unknown은 FIFO 공정성과 60초 대기 제한 때문에 의도적으로 underfill될 수 있으므로 `realistic mixed`와 `adversarial mixed`를 분리한다.
4. `redis-down`은 oversell은 없었지만 DB fallback 판매가 아니라 controlled Redis rejection으로 관측됐다. bounded DB fallback 요구를 검증하려면 Redis hard failure와 Redis command timeout 시나리오를 분리해야 한다.
5. k6 `booking_confirmed_response_total`은 confirmed 응답/replay까지 셀 수 있으므로 oversell hard fail 근거로 사용하지 않는다. DB snapshot이 authoritative evidence다.
