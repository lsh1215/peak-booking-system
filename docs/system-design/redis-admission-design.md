# Redis Admission Design Note

> This note explains why Redis is used, what Redis is allowed to decide, and what must remain durable in MySQL.

## Status

Draft / Accepted direction by user on 2026-05-31.

This document does not replace `docs/decisions/DECISIONS.md`. It expands the Redis-related decision details for DEC-001, DEC-002, DEC-007, and later implementation work.

## Core Position

Redis is useful in this system only as a **fast admission pre-gate**.

Redis must not be the final inventory correctness guard or the authoritative fairness ledger.

```text
Traefik = first-line WAS protection
Redis = fast admission pre-gate in the normal path
MySQL admission table = durable fairness/audit ledger
MySQL inventory guard = final stock correctness
```

## Why Redis Is Still Worth Using

The hard part of a `10`-stock event is not selling 10 units. The hard part is absorbing tens of thousands of failed, duplicate, or too-late attempts without letting them all hit MySQL.

Redis helps when it rejects most non-candidate traffic before MySQL:

- Duplicate clicks can return the existing admission sequence from Redis.
- Candidate pool overflow can return `BUSY` without a DB write.
- Admission ordering can be assigned cheaply with a Lua script.
- MySQL only receives requests that are inside the candidate pool or require durable recording.

Redis is not useful if most requests still reach MySQL after Redis admission. In that case Redis would add failure modes without reducing DB pressure.

## Data Structures

For each product sale event epoch:

```text
admit:{productId}:{epoch}:seq    -> Redis String counter, incremented with INCR
admit:{productId}:{epoch}:users  -> Redis Hash, userId -> redisSeq
admit:{productId}:{epoch}:queue  -> Redis ZSET, score=redisSeq, member=userId
admit:{productId}:{epoch}:meta   -> Redis Hash, optional gate metadata
```

The counter is called a String counter because Redis has no separate Integer value type. Redis stores the numeric value as a String and atomically increments it with `INCR`.

## Atomic Admission Operation

Normal Redis admission uses a Lua script. ZSET/HASH/String counter define the data model; Lua defines the atomic operation.

Pseudo-flow:

```text
if userId already exists in users:
    return ADMITTED(existingSeq)

if ZCARD(queue) >= candidateLimit:
    return BUSY

seq = INCR(seqKey)
HSET users userId seq
ZADD queue seq userId
EXPIRE keys ttl
return ADMITTED(seq)
```

Redis `MULTI`/`EXEC` transactions and distributed locks are rejected for the default admission path:

- Transactions with `WATCH` add retry and branching complexity under contention.
- Distributed locks are unnecessary because a short Lua script can make the admission decision atomically.
- Redis locks are not the final correctness guard for inventory or fairness.

## Durable Admission Rule

A Redis sequence alone is provisional. A user is considered effectively admitted only after MySQL records the admission row.

```text
Redis seq issued
-> MySQL admission row insert succeeds
-> admission is valid
```

If Redis succeeds but MySQL admission recording fails, the system must not tell the client that admission succeeded. It should either return a retryable failure or switch the event epoch to DB fallback depending on the failure cause.

MySQL admission rows should retain enough information to audit and recover ordering:

```text
product_id
event_epoch
user_id
gate_mode          -- REDIS or DB_FALLBACK
redis_seq          -- nullable
db_admission_seq
status             -- ADMITTED / PROCESSING / SUCCEEDED / FAILED / EXPIRED
created_at
```

`db_admission_seq` is the official ordering value. It should be issued by a short MySQL sequence-counter transaction. The preferred MySQL 8 pattern is:

```sql
UPDATE admission_sequence
SET next_seq = LAST_INSERT_ID(next_seq + 1)
WHERE product_id = ? AND event_epoch = ?;

SELECT LAST_INSERT_ID();
```

This counter row is a deliberate hot row. It is acceptable only because bounded Redis/DB admission prevents total booking traffic from reaching it. The transaction must issue the sequence, insert `booking_admission`, and commit without payment calls, inventory locks, or long business logic.

## Redis Failure And Recovery

Redis failure includes hard down, command timeout, OOM/write failure, unexpected admission key loss, or admission state that can no longer be trusted.

Once Redis admission fails for an event epoch:

```text
NORMAL_REDIS -> DB_FALLBACK
```

The fallback is sticky for that event epoch. Even if Redis later recovers, the same event epoch does not switch back to Redis admission. This avoids merging Redis and DB fallback orderings.

The next event epoch may use Redis again if health checks and startup state are clean.

## TTL, Eviction, And Persistence

TTL is cleanup, not correctness.

The TTL should be derived from business recovery windows:

```text
Redis admission TTL
= max(idempotency replay window, payment reconciliation window)
+ operational buffer
```

Initial candidate: `6h`, subject to DEC-004 and DEC-005 once idempotency and payment reconciliation windows are accepted.

Admission keys must not be treated as ordinary cache keys:

- Active admission keys must not be evicted.
- Redis memory pressure is treated as Redis admission failure.
- Redis persistence can help recovery, but MySQL admission rows are the authoritative ledger.

Recommended operational posture:

```text
admission state: explicit TTL, no eviction during active event
checkout cache: may be evicted because DB can repopulate it
Redis persistence: optional recovery aid, not correctness source
```

## Failure Mode Policy

| Failure | Policy |
|---|---|
| Redis command timeout | Retry only through idempotent user admission lookup; if uncertain, switch to DB_FALLBACK |
| Redis OOM/write failure | Treat Redis admission as unavailable; use bounded DB fallback |
| Redis key loss during active epoch | Treat Redis admission state as corrupted; use MySQL admission ledger and DB_FALLBACK |
| Redis recovers after fallback | Do not switch back during the same epoch |
| DB fallback budget exhausted | Fast fail with retryable busy/unavailable response |

## Test Hooks

Minimum tests:

- Concurrent Redis admission produces no duplicate sequence.
- Same user repeated requests return the same admission sequence.
- Candidate limit overflow returns `BUSY` without DB admission insert.
- Redis seq without MySQL row is not considered valid admission.
- Redis down/timeout/OOM switches to bounded DB fallback.
- Redis recovery does not switch the same epoch back from DB_FALLBACK to Redis.
- Redis key loss does not cause oversell because MySQL remains the final source of truth.

## Next Design Topic

The next design step is the MySQL admission table and inventory correctness guard:

- DB admission sequence and uniqueness.
- Candidate tranche/status transitions.
- Reservation/hold model.
- Final stock correctness mechanism in DEC-003.
