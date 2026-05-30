# Technical Decisions

이 문서는 Redis, 멱등성, fallback, 부하 테스트 등 주요 선택의 대안, 근거, 기각 이유를 기록한다.

## Decision Index

| ID | Topic | Status | Owner | Last Updated |
|---|---|---|---|---|
| DEC-001 | Redis admission strategy | Proposed | TBD | TBD |
| DEC-002 | Redis failure fallback policy | Proposed | TBD | TBD |
| DEC-003 | DB final consistency guard | Proposed | TBD | TBD |
| DEC-004 | Idempotency key and replay policy | Proposed | TBD | TBD |
| DEC-005 | Payment failure compensation | Proposed | TBD | TBD |
| DEC-006 | Payment method extensibility | Proposed | TBD | TBD |
| DEC-007 | Overload defense and load shedding | Proposed | TBD | TBD |
| DEC-008 | Load test strategy | Proposed | TBD | TBD |

## DEC-001: Redis Admission Strategy

### Context

00:00 오픈 직후 500~1000 TPS burst에서 성공 가능한 예약은 10건뿐이다. 시스템은 초과 판매를 막으면서 sold-out, duplicate, overload 요청을 빠르게 정리해야 한다.

### Options

| Option | Pros | Cons | Decision |
|---|---|---|---|
| Redis Lua admission counter | 빠른 atomic admission, DB hot row 압력 감소 | Redis 장애 정책 필요, 최종 정합성은 DB로 재검증 필요 | Proposed |
| DB-only conditional update | source of truth 단순화, Redis 장애 영향 축소 | burst가 DB hot row로 집중될 수 있음 | Alternative |
| Queue/waiting room | 공정성 제어 가능, backpressure 명확 | 구현 범위와 운영 복잡도 증가 | Rejected for initial scope |

### Current Rationale

Redis는 admission control에 사용하고, 최종 예약 확정은 DB 제약과 transaction으로 방어한다.

### Validation Needed

- Redis 정상 시 1000 TPS에서 DB 진입량이 제한되는지 확인한다.
- Redis 성공 후 DB 실패/결제 실패 시 재고가 영구 잠기지 않는지 확인한다.

## DEC-002: Redis Failure Fallback Policy

### Context

Redis 장애 시 무제한 DB fallback은 DB 붕괴와 공정성 훼손으로 이어질 수 있다.

### Options

| Option | Pros | Cons | Decision |
|---|---|---|---|
| Fail-closed | 초과 판매 방어가 명확함 | Redis 장애 중 정상 사용자도 실패 | Alternative |
| Bounded DB fallback | 일부 요청 처리 가능, 가용성 유지 | rate limit/bulkhead/timeout 없으면 위험 | Proposed |
| Unlimited DB fallback | 구현 단순 | DB hot row 경쟁과 retry storm 위험 | Rejected |

### Current Rationale

Booking write path에서는 bounded DB fallback 또는 fail-closed만 허용한다. Checkout read cache fallback과 Booking admission fallback은 분리한다.

### Validation Needed

- Redis down 상태에서 DB fallback 동시성 테스트를 수행한다.
- fallback 진입량 제한과 빠른 실패 응답을 확인한다.

## DEC-003: DB Final Consistency Guard

### Context

Redis admission이 성공해도 최종 확정 예약 수는 전체 재고 10개를 초과하면 안 된다.

### Options

| Option | Pros | Cons | Decision |
|---|---|---|---|
| Conditional stock update + unique booking constraint | source of truth에서 초과 판매 방어 | hot row lock 경합 가능 | Proposed |
| Application lock only | 구현 쉬움 | 2+ 서버 분산 환경에서 안전하지 않음 | Rejected |
| Redis-only stock | 빠름 | Redis 장애/복구/영속성 이슈에서 최종 정합성 약함 | Rejected |

### Validation Needed

- 1000 concurrent booking attempts에서 confirmed booking count <= 10.
- 동일 사용자/동일 상품 중복 예약이 DB 제약으로 막히는지 확인한다.

## DEC-004: Idempotency Key And Replay Policy

### Context

중복 클릭, timeout 후 재시도, 결제 응답 불확실성은 중복 예약/중복 결제를 만들 수 있다.

### Options

| Option | Pros | Cons | Decision |
|---|---|---|---|
| Idempotency-Key + request hash + stored response | 같은 요청의 재응답과 충돌 감지가 명확 | 저장소 TTL/recovery 정책 필요 | Proposed |
| Client retry only | 서버 구현 단순 | 중복 결제/예약 방어 불충분 | Rejected |
| DB unique only | 최종 중복 예약 방어 가능 | 동일 응답 replay와 결제 중복 방어 부족 | Alternative guard |

### Validation Needed

- 같은 key + 같은 body는 같은 논리 결과를 반환한다.
- 같은 key + 다른 body는 conflict로 거절한다.
- PROCESSING timeout/recovery 정책을 테스트한다.

## DEC-005: Payment Failure Compensation

### Context

결제 실패는 최종 예약 성공이 아니며, 선점 재고가 영구히 잠기면 undersell이 발생한다.

### Current Direction

- 결제 실패 시 confirmed booking을 만들지 않는다.
- Redis/DB reservation은 선택한 TTL/release 정책에 따라 해제 또는 복구한다.
- 실패 응답도 멱등성 저장소에 기록해 같은 요청에 동일하게 재응답한다.

## DEC-006: Payment Method Extensibility

### Context

카드, 간편결제, 포인트와 조합 정책을 지원하면서 새로운 결제 수단 추가 시 Booking 핵심 로직 변경을 줄여야 한다.

### Current Direction

- payment method validator/executor를 strategy로 분리한다.
- combination policy가 허용 조합과 금액 합계를 검증한다.
- 포인트는 ledger 또는 balance transaction으로 별도 정합성을 검토한다.

## DEC-007: Overload Defense And Load Shedding

### Current Direction

- sold-out, duplicate, invalid, overload 요청을 빠르게 실패시킨다.
- Redis/DB/payment client에는 timeout, pool limit, bulkhead, retry cap을 둔다.
- retry에는 exponential backoff + jitter를 적용하고 무한 재시도를 금지한다.

## DEC-008: Load Test Strategy

### Current Direction

- 평시 50 TPS와 00:00 burst 500~1000 TPS를 별도 시나리오로 둔다.
- 성공 수보다 실패 응답 안정성, p99, DB/Redis pool saturation, retry storm 방지를 측정한다.
- oversell, undersell, duplicate booking, processing timeout recovery를 테스트 결과로 증명한다.
