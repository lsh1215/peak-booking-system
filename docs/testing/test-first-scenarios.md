# Test-First Scenarios

이 문서는 AI가 먼저 장애/경쟁/중복 시나리오를 뽑고, 구현 전에 테스트로 고정해야 할 케이스를 정리한다.

## Core Invariants

- Confirmed booking count must never exceed stock quantity.
- A user must not create multiple confirmed bookings for the same limited product.
- Same idempotency key and same request body must return the same logical result.
- Same idempotency key and different request body must be rejected.
- Payment failure must not create a confirmed booking.
- Redis failure must not cause oversell.
- Retry storm must not collapse Redis, DB, or payment client pools.

## Scenario Backlog

| ID | Scenario | Expected Result | Test Level | Status |
|---|---|---|---|---|
| TFP-001 | 1000 concurrent booking attempts for stock=10 | confirmed <= 10, no duplicate user booking | Integration/Load | Proposed |
| TFP-002 | Same user double-clicks with same idempotency key | one logical result replayed | Integration | Proposed |
| TFP-003 | Same idempotency key with changed body | conflict response | Integration | Proposed |
| TFP-004 | Redis unavailable during Booking write path | fail-closed or bounded DB fallback, no oversell | Fault injection | Proposed |
| TFP-005 | Payment timeout after inventory reservation | recoverable PROCESSING state, no permanent stock leak | Integration | Proposed |
| TFP-006 | Payment approval failure | no confirmed booking, reservation released or expires | Integration | Proposed |
| TFP-007 | Client retry storm after 503/timeout | capped retry behavior, stable pool metrics | Load | Proposed |
| TFP-008 | App instance crash after DB commit before response | idempotency replay or booking lookup returns same result | Fault injection | Proposed |

## Evidence To Attach

- Test class or load script path
- Command used to run the test
- Result summary
- Metrics screenshot or text output
- Any known limitation
