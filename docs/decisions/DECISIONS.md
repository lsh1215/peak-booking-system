# Technical Decisions

이 문서는 현재 `docs/requirements.md`에서 요구하는 기술적 쟁점의 선택지, 트레이드오프, 최종 판단을 기록한다.

중요 원칙:

- 현재 요구사항에 없는 값을 임의로 확정하지 않는다.
- 기술 결정의 최종 권한자는 user다.
- `미결정` 상태의 항목은 설계 후보일 뿐 구현 기준이 아니다.
- 원격 `main`에 이미 들어온 bootstrap 선택 중 Java 21, Spring Boot 3.x, MySQL 8, k6, LGTM은 user가 직접 승인한 프로젝트 결정이다.

## 결정 인덱스

| ID | 주제 | 상태 | 최종 결정권자 | 마지막 수정 |
|---|---|---|---|---|
| DEC-000 | 현재 repo stack/tooling 채택 | 수용 | User | 2026-05-30 |
| DEC-001 | 재고 모델과 공정성 정책 | 수용 | User | 2026-05-31 |
| DEC-002 | Redis 장애 fallback 정책 | 수용 | User | 2026-05-31 |
| DEC-003 | RDB 재고 정합성 guard | 수용 | User | 2026-05-31 |
| DEC-004 | Idempotency 정책 | 수용 | User | 2026-05-31 |
| DEC-005 | 결제 실패와 PG abstraction | 수용 | User | 2026-05-31 |
| DEC-006 | 결제 수단 확장성 | 수용 | User | 2026-05-31 |
| DEC-007 | HA, load shedding, backpressure | 수용 | User | 2026-05-31 |
| DEC-008 | 테스트, load-test, observability 전략 | 수용 | User | 2026-05-31 |

---

## DEC-000: Current Repo Stack/Tooling Acceptance

### 요구사항 근거

현재 요구사항은 Java 8 이상 또는 Kotlin, Spring Boot 2.7 이상, MySQL/MariaDB 계열, Redis를 요구한다. 부하 테스트 도구와 관측 스택은 직접 명시하지 않는다.

### 결정

Java 21, Spring Boot 3.x, MySQL 8, k6, LGTM stack을 이 프로젝트의 공식 baseline stack/tooling으로 채택한다.

### 수용한 선택

| 영역 | 수용한 선택 | 이유 |
|---|---|---|
| 언어 | Java 21 | 요구사항의 Java 8 이상 조건을 만족하고 현재 backend bootstrap과 일치한다. |
| 프레임워크 | Spring Boot 3.x | 요구사항의 Spring Boot 2.7 이상 조건을 만족하고 현재 bootstrap과 일치한다. |
| RDB | MySQL 8 | 요구사항의 MySQL 계열 조건을 만족하고 현재 local infra와 일치한다. |
| Cache | Redis | 요구사항에 명시되어 있다. |
| Load test tool | k6 | 현재 repo에 smoke load-test scaffold가 존재하며 피크 트래픽 검증 도구로 사용한다. |
| Observability | LGTM stack | 현재 repo에 local observability scaffold가 존재하며 부하/장애 검증 관측 기반으로 사용한다. |

### 결과와 영향

- 이 항목들은 더 이상 미결정 사항이 아니다.
- DEC-008은 k6/LGTM 채택 여부가 아니라, 어떤 부하 시나리오와 pass/fail 기준을 둘지에 집중한다.
- MySQL 8을 사용하며, 구체 재고 정합성 guard 방식은 DEC-003에서 수용한 reservation row + atomic counter guard를 따른다.

---

## DEC-001: Stock Model And Fairness Policy

### 요구사항 근거

- `00시` 트래픽 집중 상황에서 미달되거나 초과판매가 발생하지 않아야 한다.
- 대상 초특가 숙소 상품 재고는 `10개`로 제한된다.
- 모든 사용자가 동등한 확률로 상품을 구매할 수 있는 구조를 고민해야 한다.

### 수용한 공정성 결정

공정성은 클라이언트 클릭 시각이 아니라, 이벤트 오픈 이후 유효한 첫 Booking 시도가 시스템의 권위 있는 admission gate에서 부여받은 순서로 판단한다.

- 정상 상태의 admission gate는 Redis가 담당한다.
- Redis 장애 fallback 상태의 admission gate는 MySQL 기반 bounded DB admission gate가 담당한다.
- 중복 클릭과 재시도는 같은 사용자/상품에 대해 성공 확률을 높이지 못해야 한다.
- Traefik 같은 gateway rate limit은 WAS/DB 보호 수단이며, 선착순 공정성의 원장이 아니다.
- 현재 인증/인가는 구현 범위 밖이므로 `X-User-Id` 또는 equivalent request principal은 mock authenticated principal로만 취급한다. 운영 수준에서는 JWT subject 또는 인증된 principal에서 user id를 얻는 구조로 교체해야 한다.

### 재검토 조건

- 현재 설계는 `(sale_event_id, product_id, user_id)` 단위의 중복 admission 방지를 수용했다. 향후 여러 객실/수량 구매 같은 요구가 추가되면 이 unique 축을 재검토한다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Fixed stock=`10` + RDB-enforced no oversell | 요구사항의 고정 재고를 직접 검증할 수 있음 | hot row/per-unit/reservation 중 구체 모델이 필요 | 수용 |
| User-level first valid admission rule | 중복 클릭/재시도 남용을 줄일 수 있음 | mock principal 신뢰 경계와 최종 구매 제한 정책은 별도 명시 필요 | 방향 수용 |
| Authoritative gate order fairness | 선착순 의미가 서버 측에서 측정 가능함 | Redis/DB gate 전환, `sale_event_id`, 감사 로그가 필요 | 방향 수용 |
| Random admission fairness | 구현이 단순할 수 있음 | 선착순 체감과 충돌 가능 | 거절 |

### 수용한 Redis Admission 세부 방향

Redis 정상 admission은 fast admission pre-gate로만 사용한다. Redis는 최종 inventory correctness guard도, durable fairness ledger도 아니다.

- 자료구조: Redis ZSET + Hash + String counter.
- 원자성 보장 방식: Lua script.
- 기본 경로에서 사용하지 않는 방식: Redis `MULTI`/`EXEC` transaction, distributed lock/Redlock.
- Redis admission sequence만으로는 provisional 상태다. MySQL admission row 저장이 성공한 뒤에만 admission이 유효하다.
- candidate limit 초과와 같은 사용자 retry는 불필요한 DB write 전에 Redis에서 응답해야 한다.
- 자세한 근거는 [`docs/system-design/redis-admission-design.md`](../system-design/redis-admission-design.md)에 기록한다.

---

## DEC-002: Redis Failure Fallback Policy

### 요구사항 근거

Redis 장애 시 fallback 전략과 근거를 제시해야 한다.

### 결정

Redis 장애 시 Booking write path를 단순 fail-closed로 닫지 않고, bounded DB admission gate로 전환한다. 단, Redis 장애 중 모든 요청을 무제한으로 DB에 보내지 않는다.

### 수용한 정책

- Redis 정상 상태에서는 Redis admission gate가 빠른 선착순 admission을 담당한다.
- Redis 장애 시에는 MySQL admission table이 사용자/상품별 첫 유효 요청에 admission sequence를 부여한다.
- DB fallback admission은 candidate pool, gateway/app rate limit, semaphore/bulkhead, 짧은 timeout으로 제한한다.
- candidate pool은 재고 `10개`와 동일하게 잡지 않는다. 결제 실패/timeout/중복 요청을 흡수할 수 있는 충분한 후보 수를 둔다.
- 첫 구현의 candidate pool은 sale event당 `30`으로 고정한다. 추가 tranche는 열지 않으며, pool 밖 요청은 fast reject한다.
- 동시 결제 또는 재고 hold 진행 수는 재고 수량 이하로 제한한다.
- Redis 장애 fallback은 DB 최종 재고 guard를 대체하지 않는다.
- unlimited DB fallback은 금지한다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Fail-closed for Booking write path | 재고 정합성 방어가 단순함 | Redis 장애 중 정상 사용자도 실패 | 주 정책으로 거절 |
| Bounded DB fallback | 일부 요청 처리 가능 | rate limit, bulkhead, timeout, fairness budget 필요 | 수용 |
| Degraded read-only / checkout-only mode | 장애 영향 범위가 명확함 | Booking 성공 가능성이 사라짐 | 기본 정책으로 거절 |
| Unlimited DB fallback | 구현은 단순 | DB 붕괴와 공정성 훼손 위험 큼 | 거절 |

### 남은 구현/검증 항목

- Checkout read path cache fallback 세부 정책.

### 수용한 복구 정책

Redis admission 장애가 특정 `sale_event_id`에서 감지되면 해당 sale event는 `DB_FALLBACK`으로 전환하고, Redis가 복구되어도 같은 `sale_event_id` 안에서는 Redis gate로 복귀하지 않는다. 다음 `sale_event_id`는 Redis health/state가 정상일 때 Redis admission을 다시 사용할 수 있다.

---

## DEC-003: RDB Inventory Correctness Guard

### 요구사항 근거

초과판매와 미달 판매가 발생하지 않도록 재고 정합성을 보장해야 한다. RDB는 MySQL 또는 MariaDB 계열이어야 한다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Conditional stock count update | 단순하고 구현 비용 낮음 | 상태 전이/audit만으로는 payment unknown 설명이 약함 | 단독 사용 거절 |
| Per-unit inventory row | 단위 재고 선점 추적이 명확함 | 현재 `10개` 한정 이벤트에 비해 schema/state complexity가 큼 | 기본안으로 거절 |
| Reservation row + MySQL atomic counter guard | 결제 실패/timeout/unknown 복구 표현이 좋고 DB 최종 guard가 명확함 | 상태 전이와 counter 동기화 테스트 필요 | 수용 |
| Redis-only inventory correctness | 빠름 | Redis 장애/복구 시 최종 정합성 근거로 약함 | 거절 |

### 수용한 Inventory Guard 결정

최종 재고 정합성은 MySQL에서 보장한다. Redis admission은 최종 재고 원장이 아니다.

- 재고 점유 불변식은 `HELD + PAYMENT_UNKNOWN + CONFIRMED <= total_stock`이다.
- 대상 sale event의 `total_stock`은 `10`이다.
- 재고 점유는 `reservation` row와 MySQL `inventory` counter row를 함께 사용한다.
- reservation 상태 중 `HELD`, `PAYMENT_UNKNOWN`, `CONFIRMED`는 재고를 점유한다.
- `PAYMENT_UNKNOWN`은 `unknown_inventory_deadline_at`까지만 재고를 점유한다. 초기값은 최초 unknown 기록 후 `30s`다.
- `MANUAL_REVIEW_REQUIRED`는 reservation 재고 상태가 아니라 `payment_attempt`의 reconciliation 상태다. 이 상태는 재고를 점유하지 않는다.
- `RELEASED`, `EXPIRED`, `REJECTED`는 재고를 점유하지 않는다.
- 재고 점유 시작은 짧은 MySQL atomic update로 수행한다.
- `SELECT ... FOR UPDATE` 후 update하는 긴 transaction보다, 가능한 경우 조건부 `UPDATE` 한 문장으로 lock 보유 시간을 줄인다.

초기 재고 점유 SQL 패턴:

```sql
UPDATE sale_inventory
SET reserved_count = reserved_count + 1
WHERE product_id = ?
  AND sale_event_id = ?
  AND reserved_count + payment_unknown_count + confirmed_count < total_count;
```

위 update affected row가 `1`이면 같은 짧은 transaction 안에서 `reservation` row를 `HELD`로 생성한다. affected row가 `0`이면 sold out 또는 capacity exhausted로 거절한다.

상태 전이별 counter 변경 원칙:

| 전이 | Counter 변경 |
|---|---|
| create `HELD` | `reserved_count + 1` |
| `HELD -> CONFIRMED` | `reserved_count - 1`, `confirmed_count + 1` |
| `HELD -> RELEASED/EXPIRED` | `reserved_count - 1` |
| `HELD -> PAYMENT_UNKNOWN` | `reserved_count - 1`, `payment_unknown_count + 1` |
| `PAYMENT_UNKNOWN -> CONFIRMED` | `payment_unknown_count - 1`, `confirmed_count + 1` |
| `PAYMENT_UNKNOWN -> RELEASED/EXPIRED` | `payment_unknown_count - 1` |

counter update와 reservation 상태 전이는 같은 DB transaction 안에서 처리한다.

`PAYMENT_UNKNOWN -> CONFIRMED`는 `unknown_inventory_deadline_at` 안에 PG 성공을 확인하고 reservation 상태 전이를 완료한 경우에만 허용한다. deadline이 지난 뒤에는 reservation을 `RELEASED` 또는 `EXPIRED`로 바꾸고 다음 `WAITING_CANDIDATE` 승격 대상이 되게 한다. 이후 늦게 PG 성공이 확인되더라도 해당 reservation을 다시 `CONFIRMED`로 되살리지 않는다. 늦은 성공은 `payment_attempt`의 cancel/refund/reconciliation 대상으로 처리한다.

### 수용한 Admission Ledger 방향

MySQL admission table은 durable fairness/audit ledger다. Redis admission sequence는 MySQL admission row 저장이 성공하기 전까지 provisional 값이다.

- `booking_admission`은 `(product_id, sale_event_id, user_id)` 단위의 unique admission을 강제해야 한다.
- `db_admission_seq`가 공식 ordering sequence다.
- `redis_seq`는 정상 경로 진단/참고 데이터로만 남긴다.
- `gate_mode`는 admission이 `REDIS` 경로인지 `DB_FALLBACK` 경로인지 기록한다.
- `admission_sequence` counter row를 sequence source로 수용하되, 구현 시 hot-row lock 시간을 줄이기 위해 짧은 atomic update 패턴을 우선 고려한다.

후보 MySQL sequence 패턴:

```sql
UPDATE admission_sequence
SET next_seq = LAST_INSERT_ID(next_seq + 1)
WHERE product_id = ? AND sale_event_id = ?;

SELECT LAST_INSERT_ID();
```

생성된 sequence는 `booking_admission` insert에 사용한다. 이 방식은 여전히 hot row를 만들기 때문에 bounded DB admission traffic만 접근해야 하며 lock-wait/load test로 검증해야 한다.

### 수용한 DB Schema/Index 원칙

최종 DDL은 현대적인 관계형 DB 설계 원칙을 따른다.

- 각 table은 단일 surrogate primary key를 기본으로 둔다.
- table 간 관계는 비식별 관계로 연결한다. 자식 table의 primary key에 부모 key를 포함하지 않는다.
- unique key는 실제로 unique해야 하는 비즈니스 조건에만 둔다.
- 현재 확정된 대표 unique 축은 `(sale_event_id, product_id, user_id)` 단위의 중복 admission 방지다.
- additional secondary index는 조회 조건과 카디널리티를 함께 보고 결정한다.
- 카디널리티가 낮은 `status` 같은 column 단독 index는 기본으로 만들지 않는다.
- 애매한 index는 선반영하지 않는다. query가 느리거나 lock/read pressure가 관측되면 `EXPLAIN`, slow query log, k6/LGTM 결과를 근거로 추가한다.

### 수용한 최소 DDL 범위

초기 구현은 별도 `booking` table 없이 `reservation.CONFIRMED`를 최종 예약으로 취급한다. 예약 변경, 바우처, 별도 booking projection이 필요해지면 그때 분리한다.

최소 table set:

- `sale_inventory`
- `admission_sequence`
- `booking_admission`
- `reservation`
- `idempotency_record`
- `payment_attempt`
- `point_account`
- `point_hold`

### 수용한 Recovery / Expiry 구현 정책

- 위 최소 table set의 column/type/migration 파일.
- stale `HELD`와 `PAYMENT_UNKNOWN`은 모두 recovery worker의 대상이다.
- `HELD`는 PG confirm 호출 전/중 WAS crash로 방치될 수 있으므로 `hold_expires_at`을 둔다. 초기값은 재고 hold 생성 후 `30s`다.
- `PAYMENT_UNKNOWN`은 DEC-005에 따라 `unknown_inventory_deadline_at`까지 재고를 점유하고, deadline 내 확정하지 못하면 reservation을 `RELEASED` 또는 `EXPIRED`로 전이한다.
- `MANUAL_REVIEW_REQUIRED`는 `payment_attempt` reconciliation 상태로만 사용하며 재고 counter에 포함하지 않는다.
- lock wait timeout, deadlock retry 여부, 추가 index 구성은 구현/부하 테스트에서 검증한다.

---

## DEC-004: Idempotency Policy

### 요구사항 근거

주문서에서 아주 짧은 간격으로 연속 결제 요청이 발생해도 중복 처리되지 않아야 한다.

### 수용한 결정

멱등성의 권위 있는 key는 client가 임의로 만들지 않고, 서버가 주문서 진입 단계에서 발급하는 `booking_attempt_id`로 둔다.

- `GET /checkout/{productId}`는 예약/결제 시도를 식별할 수 있는 `booking_attempt_id`를 발급하거나, 같은 `sale_event_id + product_id + user_id`에 이미 사용 가능한 active attempt가 있으면 재사용 가능한 attempt를 반환한다.
- `POST /bookings`는 `booking_attempt_id`를 받아 같은 논리적 결제 시도를 이어간다.
- `sale_event_id + product_id + user_id` 축의 unique constraint로 같은 사용자의 중복 클릭/재시도가 admission chance를 높이지 못하게 한다.
- `booking_attempt_id`는 같은 결제 시도의 멱등 재시도와 stored response replay 기준이다.
- 요청 body에서 결제 수단, 금액, 포인트 사용액, product/sale event 식별자처럼 side effect에 영향을 주는 필드를 canonicalize해 `request_hash`로 저장한다.
- 같은 `booking_attempt_id`로 다른 `request_hash`가 들어오면 새 결제 시도로 처리하지 않고 conflict로 거절한다.
- terminal 상태의 반복 요청은 저장된 logical response를 replay한다.
- in-progress 또는 `PAYMENT_UNKNOWN` 상태의 반복 요청은 새 PG confirm을 만들지 않고 현재 상태를 반환하거나 recovery/status 조회 경로로 연결한다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Client-provided idempotency key | 일반적인 API retry contract와 유사 | client 오염/남용 가능성이 있고 checkout 흐름과 덜 맞음 | 기본안으로 거절 |
| Server-generated checkout/booking attempt token | client 부담 감소, 주문서 진입 흐름과 연결 쉬움 | checkout 선행 흐름 의존 | 수용 |
| Request hash comparison | 같은 key의 다른 요청 탐지 가능 | canonicalization 규칙 필요 | 수용 |
| Stored logical response replay | timeout/retry UX가 안정적 | TTL/storage/recovery 정책 필요 | 수용 |
| DB uniqueness only | 최종 중복 방어 가능 | 같은 응답 replay와 중복 결제 방어는 부족함 | 단독 사용 거절 |

### 수용한 구현 정책

`request_hash`는 side effect에 영향을 주는 필드만 안정적으로 정규화한 뒤 `SHA-256`으로 저장한다.

포함 필드:

- `sale_event_id`
- `product_id`
- 인증된 principal의 `user_id`
- `booking_attempt_id`
- 결제 수단 조합과 수단별 금액
- 포인트 사용액
- PG 승인 대상 금액
- `total_amount`
- `currency`
- `payment_policy_version`

제외 필드:

- 요청 시각
- User-Agent
- client IP
- trace id
- retry count
- HTTP header 순서
- 화면 표시용 문자열

정규화 규칙:

- JSON key는 정렬한다.
- 금액은 정수 minor unit 또는 고정 소수점 규칙으로 통일한다.
- 결제 수단 배열은 domain order로 정렬한다.
- `null`, 빈 문자열, 누락 필드의 의미를 schema에서 분리한다.

Terminal response snapshot은 raw HTTP/proxy 응답 전체가 아니라 client replay에 필요한 logical response만 저장한다.

저장 필드:

- `http_status`
- `business_code`
- `booking_attempt_id`
- `reservation_id` 또는 `booking_id`
- `reservation_status`
- `payment_status`
- `confirmed_at`, `failed_at`, `updated_at`
- `message_key`
- `retryable`
- `next_action`

저장하지 않는 필드:

- 카드 번호, PG secret, 인증 token, PII
- gateway trace header 전체
- Mock PG raw payload 전체

멱등성 record retention은 `24h`로 둔다.

- 이 값은 재고 hold 시간이나 사용자 대기 시간을 의미하지 않는다.
- 24시간은 운영 추적, 지연 retry, webhook/recovery 확인, 장애 조사 buffer를 위한 보관 시간이다.
- Stripe 공식 idempotency 문서는 idempotency key를 최소 24시간 이후 pruning할 수 있고, 같은 key의 parameter 비교와 stored response replay를 제공한다.
- 구현은 DB `expires_at` 기반 cleanup을 기본으로 하며, Redis TTL만으로 correctness를 보장하지 않는다.

상태 조회는 별도 endpoint를 MVP 필수로 만들지 않는다. 같은 `booking_attempt_id`와 같은 `request_hash`로 `POST /bookings`가 반복되면 다음처럼 동작한다.

- terminal 상태면 저장된 logical response를 replay한다.
- in-progress면 새 PG confirm을 만들지 않고 현재 처리 상태를 반환한다.
- `PAYMENT_UNKNOWN`이면 새 PG confirm을 만들지 않고 recovery/status query 대상 상태를 반환한다.
- 같은 `booking_attempt_id`로 다른 `request_hash`가 들어오면 conflict로 거절한다.

---

## DEC-005: Payment Failure And PG Abstraction

### 요구사항 근거

- 결제 실패 케이스 대응 로직이 필요하다.
- 실제 PG사 연동은 생략하되 interface와 Mock 구현 등을 통해 구조적 흐름은 이어져야 한다.

### 출처 기반 Mock PG 가정

- Toss Payments는 서버가 `POST /v1/payments/confirm`로 `paymentKey`, `orderId`, `amount`를 전달해 결제를 승인하고, `GET /v1/payments/{paymentKey}` 및 `GET /v1/payments/orders/{orderId}`로 승인된 결제를 조회하며, `POST /v1/payments/{paymentKey}/cancel`로 전액/부분 취소를 수행하는 API를 제공한다.
- Toss Payments는 `PAYMENT_STATUS_CHANGED`, `CANCEL_STATUS_CHANGED` 등 결제/취소 상태 변경 웹훅 이벤트를 제공한다.
- PortOne V2 REST API도 `POST /payments/{paymentId}/confirm`, `GET /payments/{paymentId}`, `POST /payments/{paymentId}/cancel`, 웹훅 재발송/취소 결과 웹훅 흐름을 제공한다.
- 따라서 이 프로젝트의 Mock PG는 최소한 `confirm`, `get/status query`, `cancel`, `status changed webhook/event`, `timeout/unknown` 시뮬레이션을 지원한다.

### 수용한 결정

- PG `confirm` 호출은 DB transaction 안에서 수행하지 않는다. 외부 PG 지연/timeout이 DB lock과 connection을 오래 붙잡지 않도록, durable DB state를 먼저 기록한 뒤 transaction 밖에서 PG interface를 호출한다.
- 결제 승인 전에는 reservation을 `HELD`로 기록하고, PG business failure가 명확하면 `RELEASED`로 전이한다.
- PG confirm timeout, connection loss, 응답 유실처럼 결과를 알 수 없는 경우 즉시 성공이나 실패로 확정하지 않고 `PAYMENT_UNKNOWN`으로 기록한다.
- `PAYMENT_UNKNOWN`은 재고를 즉시 release하지 않지만, 무기한 점유하지도 않는다. `unknown_inventory_deadline_at` 초기값은 최초 unknown 기록 후 `30s`다.
- `30s` 안에 PG 성공을 확인하고 reservation을 `CONFIRMED`로 전이하지 못하면 reservation을 `RELEASED` 또는 `EXPIRED`로 전이하고 다음 `WAITING_CANDIDATE`에게 판매 기회를 넘긴다.
- deadline 뒤 늦게 PG 성공이 확인되더라도 이미 release된 reservation을 `CONFIRMED`로 되살리지 않는다. 해당 payment는 cancel/refund/reconciliation 대상으로 처리한다.
- 늦은 PG 성공을 취소/환불하는 과정에서 PG 취소 수수료, 환불 비용, CS 비용이 발생할 수 있다. 이 비용은 초과판매 방지와 미달판매 방지를 동시에 만족하기 위한 accepted compensation cost로 둔다. 특정 수수료 금액은 PG 계약/정책에 따라 달라지므로 이 설계에서 고정하지 않는다.
- recovery worker/scheduler를 둔다. 단, 별도 인프라를 늘리지 않고 기존 stateless WAS 내부에서 작은 전용 thread/batch/concurrency budget으로 실행한다.
- recovery worker는 `PAYMENT_UNKNOWN`뿐 아니라 PG 호출 전/중 WAS crash로 남은 stale `HELD`도 처리한다.
- 2개 이상 WAS replica가 같은 recovery 대상 row를 중복 처리하지 않도록 MySQL lease/claim column을 사용한다.
- webhook/event 수신은 빠른 상태 반영 경로로 사용할 수 있지만, 유일한 정합성 경로로 두지 않는다. webhook 유실/지연을 보완하는 status query 기반 recovery worker가 최종 안전망이다.
- 사용자 결제 재시도는 새 결제 시도를 만드는 기능으로는 범위 밖이다. 같은 `booking_attempt_id`의 멱등 재시도/상태 조회는 중복 PG confirm 없이 현재 상태를 회복하거나 반환한다.
- `PAYMENT_UNKNOWN`의 재고 점유 deadline과 후순위 사용자에게 보여주는 대기 window를 분리한다. 후순위 후보를 내부 PG 불확실성만큼 오래 붙잡지 않는다.
- 11번째 이후 후보는 재고를 점유하지 않는 `WAITING_CANDIDATE`로 둘 수 있으며, 사용자-facing 대기 window는 최대 `60s`로 제한한다.
- `WAITING_CANDIDATE`가 `60s` 안에 선순위 reservation release를 만나면 `db_admission_seq` 순서대로 승격한다.
- `60s` 안에 승격되지 않으면 해당 후보의 대기 의무를 종료하고 `WAITING_EXPIRED` 또는 sold-out 계열 응답을 반환한다.
- `WAITING_EXPIRED` 뒤 같은 `sale_event_id + product_id + user_id`는 새 admission chance를 받지 않는다. 같은 사용자의 재요청은 기존 terminal 상태 replay 또는 sold-out 계열 응답으로 처리한다. 새 기회가 필요하면 별도 `sale_event_id` 또는 명시적인 추가 판매 정책이 필요하다.
- `WAITING_CANDIDATE`는 고정 candidate pool `30` 안에서만 만든다. 추가 tranche는 열지 않고, pool 밖 요청은 fast reject한다.
- reservation이 deadline 때문에 release된 뒤에도 `payment_attempt` reconciliation은 계속 진행한다.
- payment reconciliation의 적극 status/cancel window는 최초 unknown 기록 후 `5분`으로 둔다. 이 값은 재고 점유 시간이 아니다.
- deadline 안에서 status query가 PG 성공을 반환하면 `CONFIRMED`로 확정한다. 명확한 실패/미승인/만료를 반환하면 `RELEASED`로 전이한다.
- deadline 안에서 PG가 취소 가능한 승인/매입 전 상태를 반환하면 cancel을 호출하고, cancel 성공이 확인된 뒤 `RELEASED`로 전이한다.
- deadline까지 최종 성공을 확인하지 못하면 reservation을 release하고, payment_attempt는 계속 status/cancel을 시도한다.
- release 이후 늦은 PG 성공을 확인하면 `payment_attempt`는 `LATE_SUCCESS_CANCEL_PENDING` 상태로 두고 cancel/refund를 시도한다. cancel/refund 성공 시 `CANCELLED_AFTER_RELEASE`로 닫는다.
- `5분` 적극 reconciliation window 안에도 payment/cancel 상태가 불명확하면 `payment_attempt.MANUAL_REVIEW_REQUIRED`로 전이하고 고빈도 retry 대상에서 제외한다. 이 상태는 재고를 점유하지 않는다.
- `MANUAL_REVIEW_REQUIRED`는 운영 확인 또는 늦은 webhook/status query로만 payment/cancel 정산 상태를 닫는다. reservation은 이미 `CONFIRMED`가 아니면 다시 확정하지 않는다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| DB transaction 안에서 PG mock 호출 | 흐름이 단순해 보임 | 실제 외부 PG 지연/timeout 모델과 다르고 DB resource를 오래 점유 | 거절 |
| DB state 저장 후 PG interface 호출 분리 | 실제 시스템의 불확실성을 더 잘 반영하고 DB lock 시간을 줄임 | HELD/UNKNOWN/recovery 정책 필요 | 수용 |
| 결제 실패만 처리하고 timeout/unknown은 제외 | 구현 단순 | 실전 장애 대응 설명이 약하고 duplicate charge 위험 | 거절 |
| timeout/unknown도 reconciliation 대상으로 처리 | 장애 설명력이 높고 PG 불확실성에 안전 | worker/scheduler/상태 전이 필요 | 수용 |
| webhook only reconciliation | 빠른 상태 반영 가능 | webhook 유실/지연 시 stuck 위험 | 단독 사용 거절 |
| WAS 내부 recovery scheduler | 별도 worker infra 없이 scale-out WAS에서 실행 가능 | lease/budget/backoff 설계 필요 | 수용 |
| 후순위 사용자를 PG reconciliation 완료까지 대기 | 선순위 보장이 강함 | 10개 한정 판매 UX에서 과도한 대기와 숨은 waiting room이 됨 | 거절 |
| 사용자-facing `WAITING_CANDIDATE` 최대 `60s` | 사용자 대기를 짧고 예측 가능하게 제한 | 60초 뒤 release된 재고는 기존 후보에게 무기한 보장하지 않음 | 수용 |
| `PAYMENT_UNKNOWN` 재고 점유를 payment reconciliation 완료까지 유지 | 늦은 PG 성공을 기존 reservation으로 확정하기 쉬움 | 미달 판매를 만들 수 있음 | 거절 |
| `PAYMENT_UNKNOWN` 재고 점유 deadline `30s` 후 다음 후보 승격 | 미달 판매 방지와 사용자 대기 제한에 맞음 | 늦은 PG 성공은 cancel/refund/reconciliation 처리 필요 | 수용 |
| 늦은 PG 성공 시 재고를 되살려 예약 확정 | 결제한 사용자 입장에서는 직관적일 수 있음 | 다음 후보에게 이미 판매됐을 수 있어 oversell 위험 | 거절 |
| 늦은 PG 성공 시 cancel/refund + 보상 비용 감수 | oversell 없이 다음 후보 판매를 유지할 수 있음 | PG 취소 수수료/환불/CS 비용 발생 가능 | 수용 |
| payment reconciliation `5분` 후 `MANUAL_REVIEW_REQUIRED` | retry storm과 무기한 고빈도 polling을 막음 | 수동 확인 상태가 필요 | 수용 |

### 남은 구현 파라미터

- recovery lease는 `payment_attempt` row의 MySQL lease column으로 구현한다. column은 `next_reconcile_at`, `reconcile_attempt_count`, `first_unknown_at`, `last_reconcile_at`, `lease_owner`, `lease_token`, `lease_until`, `last_error_code`, `manual_review_reason`을 둔다.
- reservation에는 `hold_expires_at`, `unknown_inventory_deadline_at`, `released_reason`을 둔다.
- scheduler는 `5s fixed delay + 0~5s initial jitter`, batch는 WAS당 `5`, PG status concurrency는 WAS당 `1`, lease timeout은 `30s`로 둔다.
- due row claim은 stale `HELD`와 `PAYMENT_UNKNOWN` 모두 대상으로 한다. 짧은 transaction에서 `FOR UPDATE SKIP LOCKED`로 잡고 `lease_owner`, `lease_token`, `lease_until`만 갱신한 뒤 commit한다.
- `next_reconcile_at`은 inventory deadline을 넘기지 않도록 `min(backoff_next_time, hold_expires_at 또는 unknown_inventory_deadline_at)`으로 잡는다. 즉, payment reconciliation backoff 때문에 재고 release가 `30s`를 넘게 밀리면 안 된다.
- PG status/cancel 호출은 DB transaction 밖에서 수행한다. 결과 반영은 `lease_token`이 일치할 때만 수행해 stale worker의 늦은 update를 막는다.
- deadline 이후 늦은 worker/webhook 결과는 reservation 상태가 아직 `HELD` 또는 `PAYMENT_UNKNOWN`이고 deadline 안일 때만 `CONFIRMED`로 반영할 수 있다. 이미 `RELEASED/EXPIRED`면 payment cancel/refund/reconciliation만 수행한다.
- `MANUAL_REVIEW_REQUIRED`는 상세 운영 기능을 요구사항으로 확장하지 않고, 수동 확인 대상 payment 상태와 최소 audit timestamp만 남긴다.

---

## DEC-006: Payment Method Extensibility

### 요구사항 근거

신용카드, Y페이, Y포인트를 지원하고, `신용카드 + Y포인트` 또는 `Y페이 + Y포인트` 복합 결제를 허용하며, 신용카드와 Y페이는 혼용할 수 없다. 새 결제 수단 추가 시 `Booking API` 핵심 비즈니스 로직 수정을 최소화해야 한다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Payment method strategy / processor | 결제 수단별 실행/검증 분리 | strategy registry 필요 | 수용 |
| Combination policy object | 조합 검증이 명확함 | 정책 DSL/규칙 관리 필요 가능 | 수용 |
| Hard-coded if/else in Booking service | 빠른 구현 | 확장성 요구와 충돌 가능 | 거절 |

### 수용한 결정

- Booking API의 핵심 비즈니스 로직은 결제 수단별 if/else를 직접 소유하지 않는다.
- 요청 결제 정보는 `PaymentPlan`으로 정규화한다.
- `CombinationPolicy`가 허용 조합을 검증한다. 허용 조합은 `신용카드`, `Y페이`, `Y포인트`, `신용카드 + Y포인트`, `Y페이 + Y포인트`이고, `신용카드 + Y페이` 혼용은 금지한다.
- 결제 수단별 실행은 `PaymentProcessor` 또는 strategy registry로 분리한다.
- Y포인트도 결제 수단으로 보고 `hold -> capture -> release` 상태를 둔다. 복합 결제에서 외부 PG 실패/unknown이 발생해도 포인트를 즉시 최종 차감하지 않고 결제 전체 상태 전이와 맞춰 capture/release한다.
- Y포인트는 범용 포인트 플랫폼이 아니라 이 booking/payment 흐름 안의 최소 정합성 모델로만 구현한다.
- 최소 모델은 사용자별 point balance와 `booking_attempt_id` 기준 point hold record다.
- point hold record는 같은 `booking_attempt_id`에서 중복 hold/capture/release가 발생하지 않도록 unique하게 둔다.
- `hold`는 가용 포인트가 충분할 때만 atomic하게 생성한다. 외부 PG가 명확히 실패하면 `release`, 외부 PG가 deadline 안에 성공하면 `capture`, 외부 PG가 unknown인 채 reservation deadline을 넘겨 release되면 point hold도 `release`한다. 이후 늦은 외부 PG 성공은 예약 확정이 아니라 cancel/refund/reconciliation 대상이다.
- 별도 포인트 적립, 소멸 예정 포인트, 포인트 선물, 복잡한 환불 정산, 운영자 포인트 조정 기능은 요구사항 밖이므로 설계하지 않는다.

### 남은 구현 파라미터

- 새 결제 수단 추가 시 registry 등록 방식과 테스트 fixture.

---

## DEC-007: HA, Load Shedding, And Backpressure

### 요구사항 근거

평시 `50 TPS`, 프로모션 시작 후 `1~5분` 동안 `500~1000 TPS`가 예상되며, 시스템 붕괴를 막는 구조가 필요하다. 인프라 증설은 제한적이다.

### 수용한 방향

k3s 환경에서 Traefik을 API Gateway/LB 역할의 1차 과부하 방어 수단으로 사용한다. Traefik rate limit은 `POST /bookings`의 WAS 보호용이며, 중복 예약 방지나 사용자별 공정성 원장으로 사용하지 않는다.

### Guardrails

- Traefik rate limit은 route/global 유량 제한으로 시작한다. 현재 인증/인가가 out of scope이므로 사용자별 rate limit은 신뢰 가능한 JWT/principal 도입 전에는 최종 공정성 장치로 사용하지 않는다.
- Traefik rate limit은 Redis-backed distributed limiter로 구성하지 않는다. Redis 장애 시 gateway 보호막까지 같은 Redis에 묶이면 장애 결합이 커지므로, Traefik은 instance-local route/global token bucket 수준의 1차 방어로 둔다.
- 사용자별 중복 admission/booking 방지는 application idempotency와 RDB unique constraint가 담당한다.
- Redis 장애 fallback 시 app 내부 semaphore/bulkhead와 DB admission limit을 함께 사용해 Traefik을 통과한 요청이 DB를 압도하지 못하게 한다.
- Little's Law는 초기 concurrency budget 산정에 사용하되, 미측정 p99를 안정 입력값으로 확정하지 않는다.
- 초기값은 최종 운영 최적값이 아니라 k6/LGTM 검증을 시작하기 위한 safety budget이다.

### 수용한 초기 운영값

아래 값은 `2`개 WAS replica, Mock PG normal confirm delay `100ms`, stock `10`을 전제로 한 첫 검증 profile이다. 실제 local/k3s resource limit에 따라 낮춰 실행할 수 있지만, DEC-008에서는 같은 비율과 보호 목적을 유지한다.

| 항목 | 초기값 | 근거 |
|---|---:|---|
| Traefik `POST /bookings` route limit | average `1000 req/s`, burst `1000`, period `1s` | 요구사항 peak upper bound를 통과시키되 그 이상 accidental spike를 1차 제한 |
| booking endpoint concurrency | WAS당 `64` | `1000 RPS * 100ms = 100` 전체 동시성, 2개 WAS 분산 + burst buffer |
| Hikari maximum pool | WAS당 `10` | HikariCP upstream starting point와 default를 기준으로 작게 시작 |
| Hikari connection timeout | `250ms` | pool 고갈을 30초 기본값처럼 오래 끌지 않고 fail-fast |
| DB write bulkhead | WAS당 `6` | Hikari pool 전체 잠식을 방지 |
| DB fallback admission bulkhead | WAS당 `2` | Redis 장애 중 DB admission hot row 보호 |
| Redis admission command timeout | `50ms` | 같은 cluster/local Redis 기준으로 느린 Redis를 빠르게 fallback 처리 |
| Redis/DB admission candidate pool | sale event당 `30` | `ceil(stock 10 / success_rate_floor 0.5) + buffer 10` |
| PG confirm concurrency | WAS당 `5`, 전체 `10` | stock 수량과 동시 hold/confirm 진행 수를 맞춤 |
| Mock PG client timeout | `500ms` | normal delay `100ms`의 5배 + 작은 latency 증가 buffer |
| Recovery scheduler | `5s` fixed delay + `0~5s` initial jitter | unknown drain 지연과 polling pressure 균형 |
| Recovery batch / status query | WAS당 batch `5`, PG status concurrency `1` | API path와 PG mock을 잠식하지 않는 bounded worker |
| Recovery backoff | `5s -> 15s -> 45s -> 2m -> 5m`, jitter | AWS retry/backoff guidance를 반영 |

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Fast reject / load shedding | dependency 보호에 유리 | 사용자 실패 응답 증가 | 수용 |
| 고정 candidate pool `30` + 추가 tranche 없음 | 구현이 단순하고 DB/PG 대기열 폭주를 막음 | 30명 밖 요청은 later release를 기다릴 수 없음 | 수용 |
| Dynamic candidate tranche 추가 개방 | release가 계속 날 때 더 많은 사용자에게 기회 제공 가능 | 운영/공정성/DB 부하 제어가 복잡해짐 | 거절 |
| Connection pool/bulkhead/timeouts | 붕괴 범위를 제한 | 수치 튜닝 필요 | 수용 |
| Simple best-effort processing | 구현 단순 | 피크에서 붕괴 가능 | 거절 |

### 수용한 보정 원칙

초기값은 수용하지만, 첫 k6/LGTM 결과로 조정한다. 단, correctness hard fail은 완화하지 않는다.

| 관측 결과 | 조정 방향 |
|---|---|
| Hikari pending이 `30s` 이상 증가 | DB write/fallback bulkhead 감소, query/lock 분석 |
| DB lock wait timeout 또는 deadlock 발생 | threshold 조정이 아니라 blocker로 보고 DDL/query/transaction 수정 |
| app CPU/heap 고갈 | booking concurrency 또는 Traefik route limit 감소 |
| stale `HELD` 또는 `PAYMENT_UNKNOWN` 재고 점유가 `30s` 초과 | blocker로 보고 release/worker/lease 구현을 수정 |
| payment reconciliation backlog가 `5분` 초과하고 자원 여유 있음 | recovery PG status concurrency를 WAS당 `1 -> 2`까지만 실험 |
| normal p95가 `500ms` 초과하지만 자원 고갈 없음 | threshold 상향 전 병목 분석 |
| confirmed count가 반복적으로 `10`에 못 미치고 DB/PG 자원 여유 있음 | candidate pool 변경은 자동 튜닝이 아니라 별도 설계 변경으로 처리 |

p99는 초기 pass/fail 기준이 아니라 warning/관측 지표로 유지한다. controlled rejection은 technical failure와 분리해 측정한다.

---

## DEC-008: Test, Load-Test, And Observability Strategy

### 요구사항 근거

코드 수정 없이 실행 가능해야 하며, README에 실행 방법, 아키텍처, 시퀀스 다이어그램/플로우차트, ERD/DDL, 주요 기술 판단을 포함해야 한다.

### 수용한 기반

DEC-000에 따라 k6와 LGTM stack은 공식 baseline tooling으로 채택되었다.

Mock PG normal confirm delay는 `100ms`로 둔다. 이 값은 실제 PG SLO가 아니라 프로젝트 검증을 위한 deterministic mock latency profile이다. PG timeout/unknown 시나리오는 별도 timeout profile로 검증한다.

### 수용한 테스트 실행 단계

구현은 TDD로 진행한다. 작은 단위에서 큰 단위로 검증 범위를 넓히며, k6 부하 테스트는 기능 구현이 끝난 뒤 staging과 유사한 환경에서 수행한다. DEC-008의 k6 범위는 단순 정상 경로 부하 테스트가 아니라, 부하 중 일부 구성요소 장애가 발생해도 정합성과 제한적 서비스 동작이 유지되는지 검증하는 resilience/fault-injection load test를 포함한다.

| 단계 | 테스트 종류 | 목적 | 실행 시점 |
|---|---|---|---|
| 1 | Unit test | domain rule, state transition, combination policy, request hash 같은 순수 로직 검증 | 구현 중 RED 단계 |
| 2 | Slice test | controller validation, serialization, repository query, DB mapping 검증 | 해당 layer 구현 전 |
| 3 | Integration / Acceptance test | MySQL/Redis/Mock PG를 포함한 booking/payment 흐름과 장애 시나리오 검증 | application/API 흐름 구현 전 |
| 4 | k6 smoke test | 배포된 환경의 API와 metric pipeline sanity check | 기능 구현 후 local 또는 staging-like 배포 직후 |
| 5 | k6 load test | 평시 `50 TPS`, 피크 `500~1000 TPS`의 정상 경로 correctness/resource threshold 검증 | staging-like 환경 |
| 6 | k6 resilience / failure-mix test | 부하 중 Redis down, WAS 1대 down, PG timeout/unknown, duplicate click, `00시` spike 조합 검증 | load test 기준 통과 후 |

부하 테스트는 TDD RED/GREEN 루프가 아니라 구현 완료 후 설계 가정과 runtime budget을 검증하는 단계다. 장시간 soak/endurance test와 destructive stress test는 현재 `1~5분` 피크 요구의 1차 필수 기준이 아니다.

정상 부하 테스트만 통과하는 것은 DEC-008 통과가 아니다. Redis 장애 시 bounded DB fallback, WAS 1대 장애 시 LB/나머지 replica 처리, PG timeout/unknown 시 recovery 상태 전이, 중복 클릭 폭주 시 멱등성 replay가 같은 k6/LGTM 검증 묶음 안에서 확인되어야 한다.

### 수용한 초기 Pass/Fail 기준

#### Hard correctness fail

아래 항목은 어떤 부하/장애 시나리오에서도 깨지면 즉시 실패다.

| 기준 | Pass |
|---|---|
| 초과판매 | `confirmed_booking_count <= 10` |
| 재고 불변식 | `HELD + PAYMENT_UNKNOWN + CONFIRMED <= total_stock` |
| 사용자 중복 확정 | 같은 `user_id + sale_event_id` confirmed 중복 `0` |
| 결제 중복 효과 | 같은 `booking_attempt_id`의 PG confirm side effect `1회 이하` |
| Redis 장애 fallback | unlimited DB fallback 없음 |
| PG unknown | 즉시 success로 조용히 확정하지 않으며, `30s` deadline 뒤 reservation 확정을 금지하고 재고를 release함 |

#### Latency threshold

초기 threshold는 Jeff Dean latency numbers 관점의 sanity check, Mock PG `100ms`, Redis/MySQL 왕복, app overhead, peak contention buffer를 반영한 starting point다. p99는 초기 pass/fail이 아니라 warning/관측 지표로 둔다.

| 경로 | Pass | Warning |
|---|---:|---:|
| `GET /checkout` | p95 `<= 200ms` | p95 `> 100ms` |
| `POST /bookings` normal confirmed | p95 `<= 500ms` | p95 `> 300ms` |
| DB/PG 없는 controlled rejection | p95 `<= 200ms` | p95 `> 100ms` |
| Redis down DB fallback rejection | p95 `<= 500ms` | p95 `> 300ms` |
| PG timeout -> `PAYMENT_UNKNOWN` | p95 `<= 700ms` | p95 `> 600ms` |

#### Resource / recovery threshold

- 의도하지 않은 app restart는 `0`이어야 한다.
- technical 5xx/timeout rate는 `< 1%`여야 한다. 의도된 `429`, sold out, candidate rejected, duplicate replay는 technical failure에서 제외한다.
- Hikari pending이 `30s` 이상 지속 증가하면 fail이다.
- DB deadlock은 `0`을 목표로 하며, lock wait timeout이 발생하면 blocker로 분석한다.
- stale `HELD`와 `PAYMENT_UNKNOWN`의 재고 점유는 `30s`를 초과하면 fail이다. deadline 이후에는 reservation을 `RELEASED/EXPIRED`로 닫고 다음 후보에게 판매 기회를 넘긴다.
- reservation release 이후에도 payment_attempt reconciliation은 `5분` 적극 status/cancel window 안에서 계속 진행한다. 끝까지 불명확하면 `payment_attempt.MANUAL_REVIEW_REQUIRED`로 전이한다.
- 후순위 `WAITING_CANDIDATE`가 사용자-facing 대기 상태로 `60s`를 초과해 남아 있으면 fail이다.

### 검토한 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| k6로 50 TPS baseline + 500~1000 TPS peak 시나리오 작성 | 요구사항 트래픽과 직접 연결 | script와 metric tagging 필요 | 수용 |
| k6 장애 주입 부하 시나리오 작성 | Redis/WAS/PG 일부 장애 중에도 서비스가 통제된 상태로 동작하는지 검증 가능 | 장애 제어 script와 metric tagging 필요 | 수용 |
| LGTM으로 app/DB/Redis/payment path 지표 관측 | 붕괴 방지 근거를 남길 수 있음 | dashboard/query 구성 필요 | 수용 |
| 최소 smoke test만 유지 | 구현 부담 감소 | 고가용성 요구사항 검증이 약해짐 | 거절 |

### 남은 구현 산출물

구현 후 concrete metric name, dashboard layout, alert rule, k6 script path를 작성한다. 이는 DEC-008의 미결정 정책이 아니라 구현 산출물이다.
