# Redis Admission Design Note

> 이 문서는 Redis를 왜 쓰는지, Redis가 어디까지 판단할 수 있는지, 어떤 상태를 반드시 MySQL에 남겨야 하는지 정리한다.

## 상태

2026-05-31 기준 user가 수용한 설계 방향을 정리한 초안이다.

이 문서는 `docs/decisions/DECISIONS.md`를 대체하지 않는다. DEC-001, DEC-002, DEC-007과 이후 구현 작업에 필요한 Redis 관련 세부 설계를 보충한다.

## 핵심 입장

이 시스템에서 Redis의 가치는 **fast admission pre-gate** 역할에 있다.

Redis는 최종 재고 정합성 guard나 공정성 원장이 아니다.

```text
Traefik = 1차 WAS 보호
Redis = 정상 경로의 빠른 admission pre-gate
MySQL admission table = durable fairness/audit ledger
MySQL inventory guard = final stock correctness
```

## Redis를 계속 사용할 이유

`10개 한정` 이벤트에서 어려운 부분은 10개를 판매하는 행위 자체가 아니다. 진짜 어려운 부분은 수만 건의 실패 요청, 중복 요청, 너무 늦은 요청이 모두 MySQL에 도달하지 않도록 흡수하는 것이다.

Redis는 MySQL 앞에서 후보가 아닌 트래픽 대부분을 빠르게 거절할 때 의미가 있다.

- 중복 클릭은 Redis에 저장된 기존 admission sequence를 반환할 수 있다.
- candidate pool이 가득 찬 요청은 DB write 없이 `BUSY`로 응답할 수 있다.
- Lua script로 admission 순서를 저렴하게 부여할 수 있다.
- MySQL은 candidate pool 안에 들어왔거나 durable 기록이 필요한 요청만 받는다.

Redis admission 이후에도 대부분의 요청이 MySQL에 도달한다면 Redis는 유용하지 않다. 그런 설계에서는 Redis가 DB 부하를 줄이지 못하고 장애 지점만 추가한다.

## 자료구조

상품 판매 이벤트 epoch마다 다음 key를 사용한다.

```text
admit:{productId}:{epoch}:seq    -> Redis String counter, INCR로 증가
admit:{productId}:{epoch}:users  -> Redis Hash, userId -> redisSeq
admit:{productId}:{epoch}:queue  -> Redis ZSET, score=redisSeq, member=userId
admit:{productId}:{epoch}:meta   -> Redis Hash, optional gate metadata
```

여기서 counter를 String counter라고 부르는 이유는 Redis에 별도 Integer value type이 없기 때문이다. Redis는 숫자 값을 String으로 저장하고 `INCR`로 원자적으로 증가시킨다.

## 원자적 Admission Operation

정상 Redis admission은 Lua script를 사용한다. ZSET/HASH/String counter는 데이터 모델이고, Lua script는 여러 명령을 하나의 원자적 operation으로 묶는 수단이다.

의사 흐름:

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

기본 admission 경로에서는 Redis `MULTI`/`EXEC` transaction과 distributed lock을 사용하지 않는다.

- `WATCH` 기반 transaction은 경합 상황에서 재시도와 분기 처리가 복잡해진다.
- 짧은 Lua script 하나로 admission 판단을 원자 처리할 수 있으므로 distributed lock이 필요하지 않다.
- Redis lock은 inventory나 fairness의 최종 correctness guard가 아니다.

## Durable Admission 규칙

Redis sequence만으로는 provisional 상태다. 사용자는 MySQL에 admission row가 기록된 뒤에만 유효하게 admission된 것으로 본다.

```text
Redis seq issued
-> MySQL admission row insert succeeds
-> admission is valid
```

Redis 처리는 성공했지만 MySQL admission 기록에 실패했다면 클라이언트에 admission 성공을 알려서는 안 된다. 실패 원인에 따라 retry 가능한 실패를 반환하거나 해당 event epoch을 DB fallback으로 전환한다.

MySQL admission row에는 순서를 감사하고 복구할 수 있는 정보를 남겨야 한다.

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

`db_admission_seq`가 공식 ordering 값이다. 이 값은 짧은 MySQL sequence-counter transaction으로 발급한다. MySQL 8에서 우선 고려할 패턴은 다음과 같다.

```sql
UPDATE admission_sequence
SET next_seq = LAST_INSERT_ID(next_seq + 1)
WHERE product_id = ? AND event_epoch = ?;

SELECT LAST_INSERT_ID();
```

이 counter row는 의도적인 hot row다. 다만 bounded Redis/DB admission으로 전체 Booking 트래픽이 이 row에 직접 도달하지 않도록 제한하기 때문에 허용 가능한 병목으로 본다. transaction은 sequence 발급, `booking_admission` insert, commit까지만 수행해야 하며 payment call, inventory lock, 긴 business logic을 포함하면 안 된다.

## Redis 장애와 복구

Redis failure에는 hard down, command timeout, OOM/write failure, 예기치 않은 admission key loss, 더 이상 신뢰할 수 없는 admission state가 포함된다.

특정 event epoch에서 Redis admission failure가 감지되면 다음 mode로 전환한다.

```text
NORMAL_REDIS -> DB_FALLBACK
```

fallback은 해당 event epoch 동안 sticky하게 유지한다. Redis가 나중에 복구되어도 같은 event epoch 안에서는 Redis admission으로 되돌아가지 않는다. 이렇게 해야 Redis ordering과 DB fallback ordering을 병합하면서 생기는 공정성 문제를 피할 수 있다.

다음 event epoch에서는 health check와 시작 상태가 정상일 때 Redis admission을 다시 사용할 수 있다.

## TTL, Eviction, Persistence

TTL은 정리 정책이지 correctness 정책이 아니다.

TTL은 비즈니스 복구 window를 기준으로 산정한다.

```text
Redis admission TTL
= max(idempotency replay window, payment reconciliation window)
+ operational buffer
```

초기값은 `24h`다. DEC-004의 idempotency record retention과 맞춰 운영 추적과 지연 retry 분석을 쉽게 하고, DEC-005의 `5분` 적극 reconciliation window보다 충분히 길게 둔다. 단, Redis TTL은 정리 편의 값이며 correctness나 audit의 원장은 MySQL이다.

Admission key는 일반 cache key처럼 취급하면 안 된다.

- Active admission key는 eviction 대상이 되면 안 된다.
- Redis memory pressure는 Redis admission failure로 취급한다.
- Redis persistence는 복구 보조 수단일 뿐이며, MySQL admission row가 authoritative ledger다.

권장 운영 방향:

```text
admission state: explicit TTL, active event 중 eviction 금지
checkout cache: DB에서 재조회 가능하므로 eviction 허용 가능
Redis persistence: optional recovery aid, correctness source 아님
```

## 실패 모드 정책

| 실패 상황 | 정책 |
|---|---|
| Redis command timeout | idempotent user admission lookup으로만 재시도한다. 불확실하면 DB_FALLBACK으로 전환한다. |
| Redis OOM/write failure | Redis admission을 unavailable로 보고 bounded DB fallback을 사용한다. |
| Redis key loss during active epoch | Redis admission state가 손상된 것으로 보고 MySQL admission ledger와 DB_FALLBACK을 사용한다. |
| Redis recovers after fallback | 같은 epoch 안에서는 Redis admission으로 되돌아가지 않는다. |
| DB fallback budget exhausted | retry 가능한 busy/unavailable 응답으로 fast fail한다. |

## 테스트 훅

최소 테스트 후보:

- 동시 Redis admission에서 sequence가 중복되지 않아야 한다.
- 같은 사용자의 반복 요청은 같은 admission sequence를 반환해야 한다.
- candidate limit 초과 요청은 DB admission insert 없이 `BUSY`를 반환해야 한다.
- MySQL row가 없는 Redis seq는 유효 admission으로 보지 않아야 한다.
- Redis down/timeout/OOM은 bounded DB fallback으로 전환되어야 한다.
- Redis 복구 후에도 같은 epoch은 DB_FALLBACK에서 Redis admission으로 되돌아가지 않아야 한다.
- Redis key loss가 발생해도 MySQL이 최종 source of truth이므로 oversell이 발생하지 않아야 한다.

## 다음 설계 주제

다음 설계 주제는 MySQL admission table과 inventory correctness guard다.

- DB admission sequence와 uniqueness.
- candidate tranche/status transitions.
- reservation/hold model.
- DEC-003의 final stock correctness mechanism.
