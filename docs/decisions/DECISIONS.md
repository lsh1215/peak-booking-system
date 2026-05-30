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
| DEC-001 | 재고 모델과 공정성 정책 | 부분 수용 | User | 2026-05-31 |
| DEC-002 | Redis 장애 fallback 정책 | 수용 | User | 2026-05-31 |
| DEC-003 | RDB 재고 정합성 guard | 미결정 | User | 2026-05-30 |
| DEC-004 | Idempotency 정책 | 미결정 | User | 2026-05-30 |
| DEC-005 | 결제 실패와 PG abstraction | 미결정 | User | 2026-05-30 |
| DEC-006 | 결제 수단 확장성 | 미결정 | User | 2026-05-30 |
| DEC-007 | HA, load shedding, backpressure | 부분 수용 | User | 2026-05-31 |
| DEC-008 | 테스트, load-test, observability 전략 | 미결정 | User | 2026-05-30 |

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
- MySQL 8을 사용하되, 구체 재고 정합성 guard 방식은 DEC-003에서 별도로 결정한다.

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

### 미결정 사항

- 한 사용자가 같은 상품에 대해 최종 confirmed booking을 몇 개까지 가질 수 있는지, 또는 admission만 사용자당 1회로 제한할지는 구현 정책으로 구체화해야 한다.
- RDB 재고 상태 모델과 최종 inventory correctness guard는 DEC-003에서 결정한다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Fixed stock=`10` + RDB-enforced no oversell | 요구사항의 고정 재고를 직접 검증할 수 있음 | hot row/per-unit/reservation 중 구체 모델은 DEC-003에서 결정 필요 | 방향 수용 |
| User-level first valid admission rule | 중복 클릭/재시도 남용을 줄일 수 있음 | mock principal 신뢰 경계와 최종 구매 제한 정책은 별도 명시 필요 | 방향 수용 |
| Authoritative gate order fairness | 선착순 의미가 서버 측에서 측정 가능함 | Redis/DB gate 전환, epoch, 감사 로그가 필요 | 방향 수용 |
| Random admission fairness | 구현이 단순할 수 있음 | 선착순 체감과 충돌 가능 | 미결정 |

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
- 동시 결제 또는 재고 hold 진행 수는 재고 수량 이하로 제한한다.
- Redis 장애 fallback은 DB 최종 재고 guard를 대체하지 않는다.
- unlimited DB fallback은 금지한다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Fail-closed for Booking write path | 재고 정합성 방어가 단순함 | Redis 장애 중 정상 사용자도 실패 | 미결정 |
| Bounded DB fallback | 일부 요청 처리 가능 | rate limit, bulkhead, timeout, fairness budget 필요 | 수용 |
| Degraded read-only / checkout-only mode | 장애 영향 범위가 명확함 | Booking 성공 가능성이 사라짐 | 미결정 |
| Unlimited DB fallback | 구현은 단순 | DB 붕괴와 공정성 훼손 위험 큼 | 거절 |

### 미결정 사항

- candidate pool 크기, gateway/app rate limit, semaphore, DB connection budget의 초기 수치.
- Checkout read path cache fallback 세부 정책.

### 수용한 복구 정책

Redis admission 장애가 특정 event epoch에서 감지되면 해당 epoch은 `DB_FALLBACK`으로 전환하고, Redis가 복구되어도 같은 epoch 안에서는 Redis gate로 복귀하지 않는다. 다음 event epoch은 Redis health/state가 정상일 때 Redis admission을 다시 사용할 수 있다.

---

## DEC-003: RDB Inventory Correctness Guard

### 요구사항 근거

초과판매와 미달 판매가 발생하지 않도록 재고 정합성을 보장해야 한다. RDB는 MySQL 또는 MariaDB 계열이어야 한다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Conditional stock count update | 단순하고 구현 비용 낮음 | hot row lock 경합 가능 | 미결정 |
| Per-unit inventory row | 단위 재고 선점 추적이 명확함 | row 수와 locking 설계가 필요 | 미결정 |
| Reservation table + expiry/release | 결제 실패/timeout 복구 표현이 좋음 | 상태 전이와 cleanup 정책 필요 | 미결정 |
| Redis-only inventory correctness | 빠름 | Redis 장애/복구 시 최종 정합성 근거로 약함 | 미결정 |

### 수용한 Admission Ledger 방향

MySQL admission table은 durable fairness/audit ledger다. Redis admission sequence는 MySQL admission row 저장이 성공하기 전까지 provisional 값이다.

- `booking_admission`은 `(product_id, event_epoch, user_id)` 단위의 unique admission을 강제해야 한다.
- `db_admission_seq`가 공식 ordering sequence다.
- `redis_seq`는 정상 경로 진단/참고 데이터로만 남긴다.
- `gate_mode`는 admission이 `REDIS` 경로인지 `DB_FALLBACK` 경로인지 기록한다.
- `admission_sequence` counter row를 sequence source로 수용하되, 구현 시 hot-row lock 시간을 줄이기 위해 짧은 atomic update 패턴을 우선 고려한다.

후보 MySQL sequence 패턴:

```sql
UPDATE admission_sequence
SET next_seq = LAST_INSERT_ID(next_seq + 1)
WHERE product_id = ? AND event_epoch = ?;

SELECT LAST_INSERT_ID();
```

생성된 sequence는 `booking_admission` insert에 사용한다. 이 방식은 여전히 hot row를 만들기 때문에 bounded DB admission traffic만 접근해야 하며 lock-wait/load test로 검증해야 한다.

### 필요한 결정

최종 재고 상태 모델과 RDB transaction/constraint 방식을 확정해야 한다. Admission ledger 방향은 수용되었지만 final inventory guard는 아직 미결정이다.

---

## DEC-004: Idempotency Policy

### 요구사항 근거

주문서에서 아주 짧은 간격으로 연속 결제 요청이 발생해도 중복 처리되지 않아야 한다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Client-provided idempotency key | retry/double-click 재응답이 명확함 | client contract 필요 | 미결정 |
| Server-generated checkout/booking attempt token | client 부담 감소, checkout과 연결 쉬움 | checkout 선행 흐름 의존 | 미결정 |
| Request hash comparison | 같은 key의 다른 요청 탐지 가능 | canonicalization 규칙 필요 | 미결정 |
| Stored logical response replay | timeout/retry UX가 안정적 | TTL/storage/recovery 정책 필요 | 미결정 |
| DB uniqueness only | 최종 중복 방어 가능 | 같은 응답 replay와 중복 결제 방어는 부족할 수 있음 | 미결정 |

### 필요한 결정

key 범위, body 비교 여부, replay 정책, TTL, 처리 중 상태의 회복 방식을 확정해야 한다.

---

## DEC-005: Payment Failure And PG Abstraction

### 요구사항 근거

- 결제 실패 케이스 대응 로직이 필요하다.
- 실제 PG사 연동은 생략하되 interface와 Mock 구현 등을 통해 구조적 흐름은 이어져야 한다.

### 출처 기반 Mock PG 가정

- Toss Payments는 서버가 `POST /v1/payments/confirm`로 `paymentKey`, `orderId`, `amount`를 전달해 결제를 승인하고, `GET /v1/payments/{paymentKey}` 및 `GET /v1/payments/orders/{orderId}`로 승인된 결제를 조회하며, `POST /v1/payments/{paymentKey}/cancel`로 전액/부분 취소를 수행하는 API를 제공한다.
- Toss Payments는 `PAYMENT_STATUS_CHANGED`, `CANCEL_STATUS_CHANGED` 등 결제/취소 상태 변경 웹훅 이벤트를 제공한다.
- PortOne V2 REST API도 `POST /payments/{paymentId}/confirm`, `GET /payments/{paymentId}`, `POST /payments/{paymentId}/cancel`, 웹훅 재발송/취소 결과 웹훅 흐름을 제공한다.
- 따라서 이 프로젝트의 Mock PG는 최소한 `confirm`, `get/status query`, `cancel`, `status changed webhook/event`, `timeout/unknown` 시뮬레이션을 지원하는 방향을 후보로 둔다. 이것은 구현 후보이지, recovery worker/scheduler 도입을 자동 확정하지 않는다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| DB transaction 안에서 PG mock 호출 | 흐름이 단순해 보임 | 실제 외부 PG 지연/timeout 모델과 다를 수 있음 | 미결정 |
| DB state 저장 후 PG interface 호출 분리 | 실제 시스템의 불확실성을 더 잘 반영 | HELD/PROCESSING/recovery 정책 필요 | 미결정 |
| 결제 실패만 처리하고 timeout/unknown은 제외 | 구현 단순 | 실전 장애 대응 설명이 약해짐 | 미결정 |
| timeout/unknown도 reconciliation 대상으로 처리 | 장애 설명력이 높음 | worker/scheduler/상태 전이 필요 | 미결정 |

### 필요한 결정

PG interface 호출과 DB transaction boundary를 어떻게 둘지, 결제 실패와 timeout/unknown을 같은 범주로 볼지, status query/webhook 기반 reconciliation을 둘지 결정해야 한다.

---

## DEC-006: Payment Method Extensibility

### 요구사항 근거

신용카드, Y페이, Y포인트를 지원하고, `신용카드 + Y포인트` 또는 `Y페이 + Y포인트` 복합 결제를 허용하며, 신용카드와 Y페이는 혼용할 수 없다. 새 결제 수단 추가 시 `Booking API` 핵심 비즈니스 로직 수정을 최소화해야 한다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Payment method strategy | 결제 수단별 실행/검증 분리 | strategy registry 필요 | 미결정 |
| Combination policy object | 조합 검증이 명확함 | 정책 DSL/규칙 관리 필요 가능 | 미결정 |
| Hard-coded if/else in Booking service | 빠른 구현 | 확장성 요구와 충돌 가능 | 미결정 |

### 필요한 결정

조합 검증과 결제 수단별 실행 책임을 어디에 둘지 결정해야 한다.

---

## DEC-007: HA, Load Shedding, And Backpressure

### 요구사항 근거

평시 `50 TPS`, 프로모션 시작 후 `1~5분` 동안 `500~1000 TPS`가 예상되며, 시스템 붕괴를 막는 구조가 필요하다. 인프라 증설은 제한적이다.

### 수용한 방향

k3s 환경에서 Traefik을 API Gateway/LB 역할의 1차 과부하 방어 수단으로 사용한다. Traefik rate limit은 `POST /bookings`의 WAS 보호용이며, 중복 예약 방지나 사용자별 공정성 원장으로 사용하지 않는다.

### Guardrails

- Traefik rate limit은 route/global 유량 제한으로 시작한다. 현재 인증/인가가 out of scope이므로 사용자별 rate limit은 신뢰 가능한 JWT/principal 도입 전에는 최종 공정성 장치로 사용하지 않는다.
- 사용자별 중복 admission/booking 방지는 application idempotency와 RDB unique constraint가 담당한다.
- Redis 장애 fallback 시 app 내부 semaphore/bulkhead와 DB admission limit을 함께 사용해 Traefik을 통과한 요청이 DB를 압도하지 못하게 한다.
- Little's Law는 초기 concurrency budget 산정에 사용하되, 미측정 p99를 안정 입력값으로 확정하지 않는다.

### 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| Fast reject / load shedding | dependency 보호에 유리 | 사용자 실패 응답 증가 | 미결정 |
| Queue/admission token | 공정성 표현 가능 | 구현 복잡도 증가 | 미결정 |
| Connection pool/bulkhead/timeouts | 붕괴 범위를 제한 | 수치 튜닝 필요 | 미결정 |
| Simple best-effort processing | 구현 단순 | 피크에서 붕괴 가능 | 미결정 |

### 미결정 사항

붕괴 기준, fast-failure 기준, pool/timeouts, retry 제한을 어떻게 둘지 결정해야 한다.

---

## DEC-008: Test, Load-Test, And Observability Strategy

### 요구사항 근거

코드 수정 없이 실행 가능해야 하며, README에 실행 방법, 아키텍처, 시퀀스 다이어그램/플로우차트, ERD/DDL, 주요 기술 판단을 포함해야 한다.

### 수용한 기반

DEC-000에 따라 k6와 LGTM stack은 공식 baseline tooling으로 채택되었다.

### 아직 열린 선택지

| 선택지 | 장점 | 단점 | 결정 |
|---|---|---|---|
| k6로 50 TPS baseline + 500~1000 TPS peak 시나리오 작성 | 요구사항 트래픽과 직접 연결 | 성공/실패 기준 수치 필요 | 미결정 |
| LGTM으로 app/DB/Redis/payment path 지표 관측 | 붕괴 방지 근거를 남길 수 있음 | 어떤 지표를 필수로 볼지 정의 필요 | 미결정 |
| 최소 smoke test만 유지 | 구현 부담 감소 | 고가용성 요구사항 검증이 약해짐 | 미결정 |

### 필요한 결정

k6/LGTM을 사용해 어떤 부하 시나리오, 관측 지표, pass/fail 기준을 둘지 결정해야 한다.
