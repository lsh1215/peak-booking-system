# Test-First Scenarios

이 문서는 현재 `docs/requirements.md` 기준으로 구현 전에 고정해야 할 테스트 후보를 정리한다. 요구사항에 없는 세부 정책은 `Pending Decision`으로 표시한다.

## Core Invariants From Current Requirements

- Confirmed booking/order count must not exceed the fixed stock of `10` units for the target product.
- Stock must not be permanently lost after payment failure or system failure.
- Rapid repeated payment requests must not create duplicate processing effects.
- Payment failure must not leave a successful final booking/order.
- Redis failure handling must preserve inventory correctness according to the chosen fallback policy.
- Fairness must be testable after DEC-001 defines "동등한 확률".

## Scenario Backlog

| ID | Scenario | Expected Result | Test Level | Status |
|---|---|---|---|---|
| TFP-001 | Concurrent booking attempts for stock=`10` | confirmed count <= 10, no permanent undersell | Integration/Load | Pending Decision: fairness policy |
| TFP-002 | Same user/client rapidly repeats the payment request | one logical booking/payment effect according to chosen idempotency policy | Integration | Pending Decision: idempotency contract |
| TFP-003 | Same dedupe key/token with changed request body, if key/hash policy is selected | chosen conflict/reject/replay behavior is enforced | Integration | Pending Decision: idempotency contract |
| TFP-004 | Redis unavailable during booking path | chosen fallback policy preserves stock correctness and avoids collapse | Fault injection | Pending Decision: Redis fallback |
| TFP-005 | Payment failure after inventory is tentatively affected | no successful final booking/order remains and stock is recoverable | Integration | Pending Decision: inventory/payment state model |
| TFP-006 | Payment approval failure such as limit exceeded | failure response and no successful final booking/order | Integration | Candidate |
| TFP-007 | Peak traffic `500~1000 TPS` for `1~5분` | system does not collapse according to DEC-007 metrics | Load with k6 | Pending Decision: collapse criteria |
| TFP-008 | App instance crashes during booking/payment flow | durable state can be retried or recovered according to chosen policy | Fault injection | Pending Decision: recovery model |
| TFP-009 | Checkout information lookup before booking | product information and user available Y-points are returned | Integration | Candidate |
| TFP-010 | Disallowed payment combination: credit card + Y페이 | booking/payment request is rejected | Unit/Integration | Candidate |
| TFP-011 | Mock PG confirm times out, then payment status is queried or webhook is received | no duplicate charge/booking; final state follows DEC-005 recovery policy | Integration/Fault injection | Pending Decision: payment reconciliation |

## Evidence To Attach

- Test class or load script path
- Command used to run the test
- Result summary
- Metrics screenshot or text output
- Known limitation or pending decision
