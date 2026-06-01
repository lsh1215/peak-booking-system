# Adversarial Review Log

이 문서는 AI critic 모드가 설계를 공격한 결과와 처리 상태를 기록한다.

## Attack Surface

- Redis outage
- Oversell
- Undersell
- Duplicate booking
- Duplicate payment
- Retry storm
- Payment timeout and ambiguous outcome
- Application instance crash
- DB hot row contention
- Connection pool exhaustion
- Incomplete observability

## Findings

| ID | Attack | Severity | Finding | Required Response | Status |
|---|---|---|---|---|---|
| ADV-001 | Redis outage | High | Booking write path must not switch to unlimited DB fallback. | DEC-002 accepted bounded DB fallback, sticky `DB_FALLBACK`, candidate pool `30`, app/DB bulkhead. | Decision recorded / Test pending |
| ADV-002 | Oversell | Critical | Redis admission alone is not the final consistency guard. | DEC-003 accepted MySQL reservation row + atomic counter guard with `HELD + PAYMENT_UNKNOWN + CONFIRMED <= total_stock`. | Decision recorded / Test pending |
| ADV-003 | Undersell | High | Payment failure, PG unknown, or crash can leak reserved stock. | DEC-005 now covers stale `HELD`, `PAYMENT_UNKNOWN` `30s` inventory deadline, release to next candidate, and payment-only reconciliation/manual review. | Decision recorded / Test pending |
| ADV-004 | Retry storm | High | Client retries can amplify a degraded dependency. | DEC-004 accepted server-issued `booking_attempt_id`, request hash, replay, `24h` retention; DEC-007/008 cap overload and test failure mix. | Decision recorded / Test pending |
| ADV-005 | Waiting expiry ambiguity | Medium | `WAITING_EXPIRED` plus user unique admission can be inconsistent. | DEC-005 now states same `sale_event_id + product_id + user_id` gets no new admission chance after waiting expiry; replay/sold-out response only. | Decision recorded / Test pending |
| ADV-006 | Late PG success after inventory release | Critical | A late payment success could revive an expired reservation and oversell. | DEC-005 forbids `RELEASED/EXPIRED -> CONFIRMED`; late success becomes `LATE_SUCCESS_CANCEL_PENDING` and cancel/refund compensation. | Decision recorded / Test pending |

## Review Cadence

- Run before accepting each major decision.
- Run again after test-first scenarios are implemented.
- Use `Decision recorded / Test pending` when the design decision exists but code/k6/Testcontainers evidence is not implemented yet.
- Close a finding only when it is covered by code, test, or an explicit accepted trade-off.
