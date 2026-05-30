---
name: saga-pattern
description: SAGA orchestration pattern, state machine, compensation transactions, and error handling for distributed transactions. Use when implementing order processing across services.
triggers:
  - "saga pattern"
  - "saga orchestration"
  - "compensation transaction"
  - "distributed transaction"
  - "choreography pattern"
  - "보상 트랜잭션"
  - "사가 패턴"
---

## 1. Orchestration vs Choreography

| Aspect | Orchestration (chosen) | Choreography |
|--------|----------------------|--------------|
| Control | Central orchestrator (OrderSaga) | Each service reacts to events |
| Visibility | Single place to see full flow | Scattered across services |
| Coupling | Orchestrator knows all steps | Services know next step |
| Debugging | Easy (single state machine) | Hard (event chain tracing) |
| This project | Phase 4+ (recommended) | Not used |

## 2. Order SAGA Steps

| Step | Service | Action | Compensation |
|------|---------|--------|-------------|
| 1 | Product | Reserve stock | Release stock |
| 2 | Payment | Process payment | Refund payment |
| 3 | Order | Confirm order | Cancel order |

## 3. SAGA State Machine

| State | On Success -> | On Failure -> |
|-------|-------------|---------------|
| `ORDER_CREATED` | `STOCK_RESERVING` | `ORDER_REJECTED` |
| `STOCK_RESERVING` | `PAYMENT_PROCESSING` | `ORDER_CANCELLED` |
| `PAYMENT_PROCESSING` | `ORDER_CONFIRMED` | `STOCK_RELEASING` -> `ORDER_CANCELLED` |
| `STOCK_RELEASING` | `ORDER_CANCELLED` | `ORDER_CANCELLED` (log error) |

## 4. Implementation Checklist

- [ ] `OrderSaga` class manages state transitions
- [ ] SAGA state persisted in `saga_instance` table (`id`, `order_id`, `state`, `step_data`, `created_at`, `updated_at`)
- [ ] Each step sends command via Kafka, waits for reply event
- [ ] Timeout handling: if no reply within N seconds, trigger compensation
- [ ] Compensation runs in reverse order
- [ ] All steps are idempotent

## 5. Error Handling

| Error Type | Handling |
|-----------|---------|
| Transient (network, timeout) | Retry with backoff (max 3 attempts) |
| Business (insufficient stock) | Compensate immediately |
| System (service down) | Circuit breaker + retry after recovery |
| Compensation failure | Log + alert + manual intervention queue |
