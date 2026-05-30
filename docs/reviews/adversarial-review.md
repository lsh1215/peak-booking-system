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
| ADV-001 | Redis outage | High | Booking write path must not switch to unlimited DB fallback. | Define fail-closed or bounded DB fallback. | Open |
| ADV-002 | Oversell | Critical | Redis admission alone is not the final consistency guard. | Add DB conditional update/unique constraints. | Open |
| ADV-003 | Undersell | High | Payment failure or crash can leak reserved stock. | Define TTL/release/recovery policy. | Open |
| ADV-004 | Retry storm | High | Client retries can amplify a degraded dependency. | Cap retries, add backoff+jitter, enforce idempotency. | Open |

## Review Cadence

- Run before accepting each major decision.
- Run again after test-first scenarios are implemented.
- Close a finding only when it is covered by code, test, or an explicit accepted trade-off.
