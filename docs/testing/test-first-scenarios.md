# Test-First Scenarios

이 문서는 현재 `docs/requirements.md` 기준으로 구현 전에 고정해야 할 테스트 후보를 정리한다. 요구사항에 없는 세부 정책은 `미결정`으로 표시한다.

## 테스트 단계와 실행 순서

구현은 TDD로 진행한다. 테스트는 작은 단위에서 큰 단위로 확장하며, 부하 테스트는 기능 구현이 끝난 뒤 staging과 유사한 환경에서 검증한다. 이때 부하 테스트는 정상 경로 TPS만 재는 테스트가 아니라, WAS/Redis/PG 같은 일부 구성요소가 장애 상태여도 정합성과 제한적 서비스 동작이 유지되는지 확인하는 장애 주입 부하 테스트를 포함한다.

| 단계 | 테스트 종류 | 목적 | 실행 시점 | 비고 |
|---|---|---|---|---|
| 1 | Unit test | domain rule, value object, state transition, combination policy, request hash canonicalization 검증 | 구현 중 RED 단계에서 먼저 작성 | infra dependency 없이 빠르게 실행 |
| 2 | Slice test | controller validation/serialization, repository query, DB mapping 검증 | 관련 layer 구현 전에 작성 | `@WebMvcTest`, `@DataJpaTest` 등으로 좁게 유지 |
| 3 | Integration / Acceptance test | MySQL/Redis/Mock PG를 포함한 booking/payment 흐름과 장애 시나리오 검증 | application service/API 흐름 구현 전에 작성 | Testcontainers 기반을 기본으로 고려 |
| 4 | k6 smoke test | 배포된 환경에서 API와 metric pipeline이 정상 동작하는지 확인 | 기능 구현 후 local 또는 staging-like 환경 배포 직후 | 낮은 RPS, 짧은 시간 |
| 5 | k6 load test | 평시 `50 TPS`, 피크 `500~1000 TPS`에서 정상 경로 correctness/resource threshold 검증 | 기능 구현 후 staging-like 환경 | Traefik, 2+ WAS, MySQL, Redis, LGTM 포함 |
| 6 | k6 resilience / failure-mix test | 부하 중 Redis failover, WAS 1대 down, PG timeout, duplicate click, `00시` spike 조합 검증 | load test 기준을 통과한 뒤 | 선택 사항이 아니라 `DECISIONS.md` 쟁점 7 필수 범위 |

부하 테스트는 TDD의 RED/GREEN 루프가 아니라, 구현 완료 후 설계 가정과 runtime budget을 검증하는 단계다. k6 결과로 `DECISIONS.md` 쟁점 6/7의 초기값을 보정하되, hard correctness fail은 완화하지 않는다.

따라서 k6 검증은 최소 두 축을 모두 포함해야 한다. 첫째, 모든 주요 구성요소가 정상인 상태에서 `50 TPS` baseline과 `500~1000 TPS` peak를 버티는지 본다. 둘째, 같은 부하 조건에서 WAS replica 1대 종료, Redis failover, Mock PG timeout/unknown, 중복 클릭 폭주가 발생해도 초과판매, 중복 결제, DB collapse, 무제한 fallback이 발생하지 않는지 본다.

부하 테스트 pass/fail 기준은 별도 문서로 관리한다.

- 초기 예상 기준: [loadtest-success-criteria-initial.md](./loadtest-success-criteria-initial.md)
- 실측 보정 기준: [loadtest-success-criteria-calibrated.md](./loadtest-success-criteria-calibrated.md)

초기 기준은 첫 검증을 시작하기 위한 추정값이고, 실측 보정 기준은 실제 k3s/GCP 측정 결과를 반영한 실행 기준이다. 초과판매 금지, 재고 불변식, 중복 결제 방지 같은 hard correctness 기준은 어느 쪽에서도 완화하지 않는다.

현재 요구에 맞지 않는 테스트:

- 장시간 soak/endurance test는 `1~5분` 피크 요구에 비해 우선순위가 낮다.
- 한계를 찾기 위한 destructive stress test는 첫 구현의 필수 pass/fail 기준이 아니다.
- 실제 PG 연동 테스트는 범위 밖이며, Mock PG의 confirm/query/cancel/webhook-like 흐름으로 대체한다.
- `mock_pg_scenario`는 local/test/load-test profile에서 장애를 주입하기 위한 테스트 제어값이다. production API 계약에서는 사용자가 결제 결과를 선택하는 field로 노출하면 안 된다.

## 현재 요구사항에서 나온 핵심 불변식

- 확정된 booking/order 수는 대상 상품의 고정 재고 `10개`를 초과하면 안 된다.
- 결제 실패나 시스템 장애 이후에도 재고가 영구적으로 유실되면 안 된다.
- 짧은 간격의 반복 결제 요청이 중복 booking/payment 효과를 만들면 안 된다.
- 결제 실패가 최종 성공 booking/order 상태를 남기면 안 된다.
- Redis 장애 처리 중에도 Redis HA failover pause와 half-open recovery를 통해 재고 정합성과 DB 보호를 보존해야 한다. failover 중 새 admission은 MySQL DB fallback으로 우회하면 안 된다.
- 공정성은 client click time이나 gateway rate-limit 통과 여부가 아니라, authoritative admission gate sequence로 테스트 가능해야 한다.

## 시나리오 백로그

| ID | 시나리오 | 기대 결과 | 테스트 수준 | 상태 |
|---|---|---|---|---|
| TFP-001 | stock=`10` 조건에서 동시 booking 시도 | `HELD + PAYMENT_UNKNOWN + CONFIRMED <= 10`을 보장하고, terminal recovery 이후 confirmed count가 `10`을 초과하지 않아야 한다. 중복 retry가 같은 사용자의 admission position을 앞당기면 안 된다. | Integration/Load | 수용: 쟁점 1 |
| TFP-002 | 같은 user/client가 같은 `booking_attempt_id`로 결제 요청을 짧은 간격으로 반복 | 하나의 논리적 booking/payment 효과만 남고 terminal 상태는 `24h` retention 안에서 stored logical response로 replay되어야 한다. 별도 status endpoint 없이 `POST /bookings` replay로 현재 상태를 조회할 수 있어야 한다. | Integration | 수용: 쟁점 3 |
| TFP-003 | 같은 `booking_attempt_id`로 request body의 side-effect 필드가 바뀜 | `request_hash` conflict로 거절하고 새 PG confirm 또는 booking side effect를 만들지 않아야 한다. request hash에는 side-effect 필드만 포함하고 요청 시각/trace/header 순서 같은 volatile 필드는 제외해야 한다. | Integration | 수용: 쟁점 3 |
| TFP-004 | booking path에서 Redis failover 또는 `WAIT` timeout | 새 admission은 MySQL DB fallback으로 우회하지 않고 `ADMISSION_TEMPORARILY_UNAVAILABLE + Retry-After`로 빠르게 통제 거절되어야 한다. oversell, DB collapse, app crash가 없어야 하며, half-open probe가 Redis write + WAIT 성공을 확인한 뒤에만 admission이 재개되어야 한다. | Fault injection/Load | 수용: Redis HA/failover pause |
| TFP-005 | `HELD` reservation 이후 payment business failure | `HELD -> RELEASED`로 전이하고 `reserved_count`가 감소해야 하며 성공 final booking/order가 남지 않아야 한다. | Integration | 수용: 쟁점 1/4 |
| TFP-006 | 한도 초과 같은 payment approval 실패 | 실패 응답을 반환하고 성공 final booking/order를 만들지 않아야 한다. | Integration | 후보 |
| TFP-007 | Traefik을 통과한 `500~1000 TPS`, `1~5분` peak traffic | 쟁점 6/7 metrics 기준으로 Traefik/app/DB backpressure가 WAS/DB collapse를 막아야 한다. 정상 부하와 장애 주입 부하를 분리해 모두 실행해야 한다. | Load/Resilience with k6 | 수용: 쟁점 7 |
| TFP-007a | spike 중 낮은 WAS-local permit이 Redis admission보다 먼저 동작 | 낮은 pre-Redis app semaphore가 공정성 기준을 바꾸면 안 된다. DB write bulkhead는 Redis admission 후보가 MySQL write로 진입할 때만 좁게 적용되어야 한다. | Unit/Integration/Load | 수용: 쟁점 6 |
| TFP-007b | candidate pool 밖 peak 요청이 DB product/gate-mode read를 매번 수행 | 정상 Redis 경로에서는 candidate pool 밖 요청이 MySQL read/write를 유발하지 않아야 한다. gate-mode 확인은 짧은 TTL cache로 제한하고, accepted candidate만 bounded DB access로 들어가야 한다. | Unit/Integration/Load | 수용: 쟁점 6 |
| TFP-007c | controlled rejection이 요청별 WARN 로그를 남김 | 의도된 `429`/busy/sold-out은 metric으로 관측하고 요청별 WARN 로그로 CPU/I/O를 잠식하지 않아야 한다. | Unit/Load | 수용: 쟁점 7 |
| TFP-008 | booking/payment flow 중 app instance crash | PG 호출 전/중 남은 stale `HELD`와 `PAYMENT_UNKNOWN`을 WAS 내부 recovery worker가 MySQL lease로 claim해 중복 없이 recovery 해야 한다. `30s` 안에 확정하지 못한 재고 점유는 release되어야 하며, stale worker의 늦은 결과 update는 `lease_token`과 deadline 조건으로 막아야 한다. | Fault injection | 수용: 쟁점 4 |
| TFP-009 | booking 전 checkout 정보 조회 | 상품 정보, 사용 가능한 Y포인트, 서버 발급 `booking_attempt_id`를 반환해야 한다. | Integration | 수용: 쟁점 3 |
| TFP-010 | 허용되지 않는 결제 조합: credit card + Y페이 | `CombinationPolicy`가 booking/payment request를 거절해야 한다. | Unit/Integration | 수용: 쟁점 5 |
| TFP-011 | Mock PG confirm timeout 이후 payment status 조회 또는 webhook 수신 | `PAYMENT_UNKNOWN`을 즉시 success로 확정하지 않고, `30s` inventory deadline 안에 성공 확인이 끝나지 않으면 reservation을 `RELEASED/EXPIRED`로 닫아야 한다. 이후 늦은 PG success는 booking 확정이 아니라 cancel/refund/reconciliation 대상이며, `5분` 안에도 payment/cancel 상태가 불명확하면 `payment_attempt.MANUAL_REVIEW_REQUIRED`로 전이해야 한다. | Integration/Fault injection | 수용: 쟁점 4 |
| TFP-012 | Redis admission은 성공했지만 MySQL admission persistence 실패 | client에게 admission 성공으로 알리면 안 된다. 시스템은 retryable unavailable 또는 failover pause로 처리해야 하며 새 후보를 DB fallback으로 뽑으면 안 된다. | Integration/Fault injection | 수용: Redis HA/failover pause |
| TFP-013 | Redis failover 후 복구 | pause TTL 이후 half-open probe가 Redis write + WAIT 성공을 확인해야 `REDIS` admission으로 복구한다. probe 실패 중에는 Retry-After window 동안 반복 probe를 억제해야 한다. | Integration/Fault injection/Load | 수용: Redis HA/failover pause |
| TFP-014 | MySQL official admission sequence 동시 발급 | `db_admission_seq`가 product/sale event별로 유일해야 하고, lock wait가 threshold 안에 머물러야 한다. 단, Redis failover 중 새 admission을 DB fallback으로 우회해 이 sequence를 폭주시켜서는 안 된다. | Integration/Load | 수용 |
| TFP-015 | Y포인트 포함 복합 결제에서 외부 PG 실패 또는 unknown | Y포인트는 `hold` 상태에서 최종 차감되지 않아야 하며, PG success가 reservation deadline 안에 확인되면 `capture`, 명확한 failure 또는 deadline release면 `release`되어야 한다. 늦은 외부 PG success는 booking 확정이 아니라 cancel/refund/reconciliation 대상이다. | Integration | 수용: 쟁점 5 |
| TFP-016 | 1~10번 중 일부가 `PAYMENT_UNKNOWN`이고 11~30번 후보가 대기 | 11번째 이후 `WAITING_CANDIDATE`는 재고를 점유하지 않고 최대 `60s`만 사용자-facing 대기 상태로 남아야 한다. 선순위 `PAYMENT_UNKNOWN`이 `30s` deadline으로 release되면 `db_admission_seq` 순서대로 승격하고, 60초 안에 승격되지 않으면 대기 종료 응답을 받아야 한다. 단, FIFO 선두에서 `PAYMENT_UNKNOWN`이 연속 발생하면 뒤 순번을 앞지르지 않는 현재 공정성 정책상 underfill이 발생할 수 있다. 이 경우 oversell/중복 결제/무제한 retry가 없는지를 adversarial mixed로 검증하고, 10개 판매 달성 여부는 realistic mixed와 분리 판정한다. 30번 밖 요청은 추가 tranche 없이 fast reject되어야 한다. | Integration/Fault injection | 수용: 쟁점 4/6 |
| TFP-017 | `PAYMENT_UNKNOWN` 상태에서 같은 `booking_attempt_id`로 `POST /bookings` 반복 | 새 PG confirm을 호출하지 않고 현재 logical state를 반환해야 한다. 동일 request hash는 recovery/status path로 연결하고, 다른 request hash는 conflict로 거절해야 한다. | Integration | 수용: 쟁점 3/4 |
| TFP-018 | `WAITING_EXPIRED` 이후 같은 사용자가 같은 sale event에 재요청 | `(sale_event_id, product_id, user_id)` unique admission 축 때문에 새 admission chance를 주면 안 된다. 기존 terminal 상태 replay 또는 sold-out 계열 응답으로 처리해야 하며, 새 기회는 별도 `sale_event_id`나 명시적 추가 판매 정책이 있을 때만 가능하다. | Integration | 수용: 쟁점 1/4 |
| TFP-019 | recovery backoff가 inventory deadline보다 길어지는 경우 | `next_reconcile_at`은 `hold_expires_at` 또는 `unknown_inventory_deadline_at`을 넘지 않도록 cap되어야 한다. backoff 때문에 stale `HELD`/`PAYMENT_UNKNOWN` release가 `30s`를 넘으면 실패다. | Unit/Integration | 수용: 쟁점 4 |
| TFP-020 | deadline release 이후 늦은 PG success 수신 | 이미 `RELEASED/EXPIRED`된 reservation은 절대 `CONFIRMED`로 되살리면 안 된다. payment_attempt는 `LATE_SUCCESS_CANCEL_PENDING`으로 전이하고 cancel/refund 성공 시 `CANCELLED_AFTER_RELEASE`, 불명확하면 `MANUAL_REVIEW_REQUIRED`로 전이해야 한다. | Integration/Fault injection | 수용: 쟁점 4 |

## 쟁점 7 초기 k6 시나리오

`constant-arrival-rate` executor로 arrival rate를 고정한다. Fast fail은 `http_reqs`에는 포함되지만, 의도된 `429`, sold out, candidate rejected, duplicate replay는 technical failure에서 제외한다. technical failure는 absolute count 0이 아니라 rate `< 1%`로 판정한다.

| 시나리오 | 부하 | Pass 기준 |
|---|---:|---|
| baseline | `50 RPS`, `60s` | latency/error baseline 확보, technical 5xx/timeout `< 1%` |
| peak-500 | `500 RPS`, `1~5m` | `confirmed_booking_count <= 10`, controlled rejection, app restart `0` |
| peak-1000 | `1000 RPS`, `1~5m` | Traefik/app bulkhead와 DB protection이 지속되고 Hikari pending이 `30s` 이상 증가하지 않음 |
| duplicate-click | 같은 user/attempt 반복 | 같은 `booking_attempt_id`의 booking/payment side effect `1회 이하` |
| redis-failover | `500~1000 RPS` 중 Redis master failover 또는 `WAIT` timeout | failover 중 controlled rejection, DB fallback 우회 없음, oversell 없음, half-open recovery 후 admission 재개 |
| was-one-down | peak 중 WAS replica 1개 종료 | LB가 생존 replica로 라우팅하고 duplicate/oversell 없음 |
| pg-timeout | 일부 confirm timeout | `PAYMENT_UNKNOWN` 응답 p95 `<= 700ms`, 새 PG confirm 중복 없음, `30s` 안에 확정되지 않은 reservation은 release, 이후 payment는 `5분` reconciliation |
| payment-failure | 명확한 PG business failure | confirmed booking을 만들지 않고 reservation/point hold를 release하며, 다음 후보에게 판매 기회가 넘어감 |
| late-success | reservation deadline 이후 늦은 PG success | 이미 release된 reservation을 되살리지 않고 cancel/refund/reconciliation 대상으로 전이 |
| waiting-candidate | 선순위 일부 `PAYMENT_UNKNOWN`, 후순위 후보 대기 | `WAITING_CANDIDATE` 사용자-facing 대기 `<= 60s`, 선순위 release 후 승격은 `db_admission_seq` 순서 |
| realistic-mixed | duplicate + 명확한 payment failure + 낮은 비율의 PG timeout | DB 기준 `confirmed_count = 10`, hard correctness fail 없음, resource threshold 유지 |
| adversarial-mixed | Redis failover + WAS 1대 down + 높은 PG timeout + duplicate | hard correctness fail 없음, 무제한 retry/DB collapse 없음. 연속 unknown으로 인한 underfill은 60초 대기 정책의 accepted trade-off인지 별도 판단 |

부하 테스트 원시 결과는 `loadtest-results/`에 로컬 보존하고 Git에는 올리지 않는다. 제출 문서에서는 [부하 테스트 증거 인덱스](./loadtest-evidence-index.md)를 통해 각 주장과 테스트 증거의 연결 상태를 확인한다. Redis master failover k6는 최신 코드 기준으로 다시 실행해 이 인덱스에 연결해야 한다.

## 쟁점 7 초기 Threshold

초기 threshold 원본은 [loadtest-success-criteria-initial.md](./loadtest-success-criteria-initial.md)에 보존한다. 실측 후 보정한 실행 기준은 [loadtest-success-criteria-calibrated.md](./loadtest-success-criteria-calibrated.md)를 따른다.

테스트 코드와 k6 script는 다음 원칙을 지켜야 한다.

- DB connection acquisition timeout, DB unavailable, query timeout은 uncontrolled 500이 아니라 controlled overload 응답으로 처리되어야 한다.
- `GET /checkout`은 booking write path와 별도 read bulkhead를 사용해 주문서 조회 폭주가 booking write/recovery DB budget을 잠식하지 않아야 한다.
- p99는 pass/fail이 아니라 warning/관측 지표로 수집한다.
- k6 응답 counter는 user-facing 응답 수를 세는 보조 지표이며, 초과판매 판정은 DB snapshot으로 한다.

## 첨부할 근거

- Test class 또는 load script 경로
- 테스트 실행 command
- 결과 요약
- metrics screenshot 또는 text output
- 알려진 제한 사항 또는 pending decision
