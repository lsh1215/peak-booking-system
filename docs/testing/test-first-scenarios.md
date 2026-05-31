# Test-First Scenarios

이 문서는 현재 `docs/requirements.md` 기준으로 구현 전에 고정해야 할 테스트 후보를 정리한다. 요구사항에 없는 세부 정책은 `미결정`으로 표시한다.

## 현재 요구사항에서 나온 핵심 불변식

- 확정된 booking/order 수는 대상 상품의 고정 재고 `10개`를 초과하면 안 된다.
- 결제 실패나 시스템 장애 이후에도 재고가 영구적으로 유실되면 안 된다.
- 짧은 간격의 반복 결제 요청이 중복 booking/payment 효과를 만들면 안 된다.
- 결제 실패가 최종 성공 booking/order 상태를 남기면 안 된다.
- Redis 장애 처리 중에도 bounded DB admission fallback을 통해 재고 정합성을 보존해야 한다.
- 공정성은 client click time이나 gateway rate-limit 통과 여부가 아니라, authoritative admission gate sequence로 테스트 가능해야 한다.

## 시나리오 백로그

| ID | 시나리오 | 기대 결과 | 테스트 수준 | 상태 |
|---|---|---|---|---|
| TFP-001 | stock=`10` 조건에서 동시 booking 시도 | confirmed count <= 10을 보장하고 영구 미달 판매가 없어야 한다. 중복 retry가 같은 사용자의 admission position을 앞당기면 안 된다. | Integration/Load | 미결정: Redis admission details / RDB guard |
| TFP-002 | 같은 user/client가 결제 요청을 짧은 간격으로 반복 | 선택한 idempotency 정책에 따라 하나의 논리적 booking/payment 효과만 남아야 한다. | Integration | 미결정: idempotency contract |
| TFP-003 | key/hash 정책을 선택한 경우, 같은 dedupe key/token으로 request body가 바뀜 | 선택한 conflict/reject/replay 동작이 강제되어야 한다. | Integration | 미결정: idempotency contract |
| TFP-004 | booking path에서 Redis 사용 불가 | bounded DB admission fallback이 재고 정합성을 보존하고 candidate pool/DB 접근을 제한하며 collapse를 피해야 한다. | Fault injection | 후보: DEC-007 초기 budget 적용 |
| TFP-005 | 재고가 임시로 영향을 받은 뒤 payment 실패 | 성공 final booking/order가 남지 않고 stock을 회복할 수 있어야 한다. | Integration | 미결정: inventory/payment state model |
| TFP-006 | 한도 초과 같은 payment approval 실패 | 실패 응답을 반환하고 성공 final booking/order를 만들지 않아야 한다. | Integration | 후보 |
| TFP-007 | Traefik을 통과한 `500~1000 TPS`, `1~5분` peak traffic | DEC-007 metrics 기준으로 Traefik/app/DB backpressure가 WAS/DB collapse를 막아야 한다. | Load with k6 | 후보: 초기 pass/fail 기준 수용 |
| TFP-008 | booking/payment flow 중 app instance crash | 선택한 정책에 따라 durable state를 retry 또는 recovery 할 수 있어야 한다. | Fault injection | 미결정: recovery model |
| TFP-009 | booking 전 checkout 정보 조회 | 상품 정보와 사용 가능한 Y포인트를 반환해야 한다. | Integration | 후보 |
| TFP-010 | 허용되지 않는 결제 조합: credit card + Y페이 | booking/payment request를 거절해야 한다. | Unit/Integration | 후보 |
| TFP-011 | Mock PG confirm timeout 이후 payment status 조회 또는 webhook 수신 | 중복 charge/booking이 없어야 하며 최종 상태는 DEC-005 recovery policy를 따라야 한다. | Integration/Fault injection | 후보: PG timeout threshold 적용 |
| TFP-012 | Redis admission은 성공했지만 MySQL admission persistence 실패 | client에게 admission 성공으로 알리면 안 된다. 시스템은 failure policy에 따라 안전하게 retry하거나 `DB_FALLBACK`으로 진입해야 한다. | Integration/Fault injection | 후보 |
| TFP-013 | 같은 event epoch 중 Redis 실패 후 복구 | 같은 epoch은 `DB_FALLBACK`에 머물러야 하며 Redis와 DB admission order를 병합하면 안 된다. | Integration/Fault injection | 후보 |
| TFP-014 | bounded DB admission sequence 동시 발급 | `db_admission_seq`가 product/epoch별로 유일해야 하고, lock wait가 DEC-008 threshold 안에 머물며 Hikari pending이 무제한 증가하면 안 된다. | Integration/Load | 후보 |

## DEC-008 초기 k6 시나리오

`constant-arrival-rate` executor로 arrival rate를 고정한다. Fast fail은 `http_reqs`에는 포함되지만, 의도된 `429`, sold out, candidate rejected, duplicate replay는 technical failure에서 제외한다.

| 시나리오 | 부하 | Pass 기준 |
|---|---:|---|
| baseline | `50 RPS`, `60s` | latency/error baseline 확보, technical 5xx/timeout `< 1%` |
| peak-500 | `500 RPS`, `1~5m` | `confirmed_booking_count <= 10`, controlled rejection, app restart `0` |
| peak-1000 | `1000 RPS`, `1~5m` | Traefik/app bulkhead와 DB protection이 지속되고 Hikari pending이 `30s` 이상 증가하지 않음 |
| duplicate-click | 같은 user/attempt 반복 | 같은 `booking_attempt_id`의 booking/payment side effect `1회 이하` |
| redis-down | `500~1000 RPS` 중 Redis 차단 | candidate pool `30`과 DB fallback bulkhead가 적용되고 oversell 없음 |
| was-one-down | peak 중 WAS replica 1개 종료 | LB가 생존 replica로 라우팅하고 duplicate/oversell 없음 |
| pg-timeout | 일부 confirm timeout | `PAYMENT_UNKNOWN` 응답 p95 `<= 700ms`, 새 PG confirm 중복 없음 |
| mixed | Redis down + PG timeout + duplicate | hard correctness fail 없음, resource threshold 유지 |

## DEC-008 초기 Threshold

| 경로 | Pass | Warning |
|---|---:|---:|
| `GET /checkout` | p95 `<= 200ms` | p95 `> 100ms` |
| `POST /bookings` normal confirmed | p95 `<= 500ms` | p95 `> 300ms` |
| DB/PG 없는 controlled rejection | p95 `<= 200ms` | p95 `> 100ms` |
| Redis down DB fallback rejection | p95 `<= 500ms` | p95 `> 300ms` |
| PG timeout -> `PAYMENT_UNKNOWN` | p95 `<= 700ms` | p95 `> 600ms` |

p99는 초기 pass/fail이 아니라 warning/관측 지표로 수집한다.

## 첨부할 근거

- Test class 또는 load script 경로
- 테스트 실행 command
- 결과 요약
- metrics screenshot 또는 text output
- 알려진 제한 사항 또는 pending decision
