---
name: event-driven
description: Kafka topic naming, producer/consumer configuration, outbox pattern, and idempotency strategies. Use when implementing event-driven messaging with Kafka.
triggers:
  - "kafka producer"
  - "kafka consumer"
  - "kafka topic"
  - "outbox pattern"
  - "idempotent consumer"
  - "event driven architecture"
  - "카프카 설정"
  - "이벤트 발행"
---

## 1. Kafka Topic Naming

| Pattern | Example | Usage |
|---------|---------|-------|
| `{domain}.{event-type}` | `order.created` | Domain events |
| `{domain}.{entity}.{action}` | `product.stock.reserved` | Fine-grained events |
| `{source}.{target}.dlq` | `order.payment.dlq` | Dead letter queue |
| `{domain}.retry` | `payment.retry` | Retry topic |

## 2. Producer Configuration

| Property | Value | Reason |
|----------|-------|--------|
| `acks` | `all` | Durability guarantee |
| `retries` | `3` | Transient failure handling |
| `enable.idempotence` | `true` | Exactly-once semantics |
| `key.serializer` | `StringSerializer` | Partition by order ID |
| `value.serializer` | `JsonSerializer` | Event payload |
| `max.in.flight.requests` | `5` (with idempotence) | Ordering guarantee |

## 3. Consumer Configuration

| Property | Value | Reason |
|----------|-------|--------|
| `group.id` | `{service-name}-group` | Consumer group per service |
| `auto.offset.reset` | `earliest` | Don't miss events on new consumer |
| `enable.auto.commit` | `false` | Manual commit after processing |
| `max.poll.records` | `100` | Batch size control |
| `isolation.level` | `read_committed` | Transactional consistency |

## 4. Outbox Pattern

- [ ] `outbox_event` table: `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload (JSONB)`, `created_at`, `published_at`
- [ ] Write to outbox in same transaction as aggregate mutation
- [ ] Polling publisher reads unpublished events, publishes to Kafka, marks as published
- [ ] Alternative: `@TransactionalEventListener` + Kafka producer (simpler, less reliable)
- [ ] Outbox records retained for audit, purged after N days

## 5. Idempotency

| Strategy | How | When |
|----------|-----|------|
| Idempotency key | Store processed event IDs in `processed_events` table | All consumers |
| Natural idempotency | `UPDATE SET status = 'PAID' WHERE status = 'PENDING'` | State transitions |
| Deduplication window | Check event ID against recent N minutes | High-throughput consumers |
