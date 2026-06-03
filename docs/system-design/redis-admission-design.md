# Redis Admission Design Note

> 이 문서는 Redis를 왜 쓰는지, Redis가 어디까지 판단할 수 있는지, 어떤 상태를 반드시 MySQL에 남겨야 하는지 정리한다.

## 상태

2026-06-03 기준 user가 수용한 Redis HA + failover pause 설계 방향을 정리한 문서다.

이 문서는 `docs/decisions/DECISIONS.md`를 대체하지 않는다. 쟁점 1, 쟁점 2, 쟁점 6과 이후 구현 작업에 필요한 Redis 관련 세부 설계를 보충한다.

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

Redis 처리는 성공했지만 MySQL admission 기록에 실패했다면 클라이언트에 admission 성공을 알려서는 안 된다. 실패 원인에 따라 retry 가능한 실패를 반환하고, Redis/DB 상태가 불명확하면 새 admission을 잠깐 pause한다. 기본 정책은 DB fallback으로 새 후보를 뽑는 것이 아니다.

MySQL admission row에는 순서를 감사하고 복구할 수 있는 정보를 남겨야 한다.

```text
product_id
event_epoch
user_id
gate_mode          -- REDIS or REDIS_FAILOVER_PAUSED
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

Redis failure에는 hard down, command timeout, OOM/write failure, Sentinel failover, `WAIT` timeout, `min-replicas-to-write` 조건 불만족, 더 이상 신뢰할 수 없는 admission state가 포함된다.

기본 장애 대응은 Redis HA다.

```text
Redis HA:
  primary + replica 2대 권장
  Sentinel 3대 또는 managed Redis HA

Redis server:
  min-replicas-to-write 1
  min-replicas-max-lag 1~2s

Application admission:
  Lua admission
  -> 새 admission이면 WAIT 1 short timeout
  -> MySQL official admission ledger 저장
```

Redis failover 또는 replica ACK 불만족이 감지되면 다음 상태로 전환한다.

```text
REDIS -> REDIS_FAILOVER_PAUSED
```

pause 상태에서는 새 admission을 MySQL DB fallback으로 우회하지 않는다. 응답은 retry 가능한 `ADMISSION_TEMPORARILY_UNAVAILABLE + Retry-After`다. 이렇게 해야 failover 중 Redis ordering과 DB ordering이 갈라지는 문제, 그리고 공유 DB로의 직접 부하 폭증을 막을 수 있다.

pause TTL 이후에는 half-open probe를 수행한다. probe는 Redis write + `WAIT`가 성공해야 통과한다. 성공하면 gate를 `REDIS`로 복구하고, 실패하면 Retry-After window 동안 반복 probe를 억제한다.

Redis HA와 `WAIT`는 유실 가능성을 줄이는 완화책이지 durable log와 같은 강한 일관성 보장은 아니다. Redis 장애 중에도 판매를 계속하면서 공식 도착 순서를 절대 유실하지 않아야 한다면 Redis 앞 또는 Redis 옆에 durable admission log를 추가해야 한다.

## TTL, Eviction, Persistence

TTL은 정리 정책이지 correctness 정책이 아니다.

TTL은 비즈니스 복구 window를 기준으로 산정한다.

```text
Redis admission TTL
= max(idempotency replay window, payment reconciliation window)
+ operational buffer
```

초기값은 `24h`다. 쟁점 3의 idempotency record retention과 맞춰 운영 추적과 지연 retry 분석을 쉽게 하고, 쟁점 4의 `5분` 적극 reconciliation window보다 충분히 길게 둔다. 단, Redis TTL은 정리 편의 값이며 correctness나 audit의 원장은 MySQL이다.

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
| Redis command timeout | `REDIS_FAILOVER_PAUSED`로 전환하고 retryable unavailable 응답을 반환한다. |
| Redis OOM/write failure | Redis admission을 unavailable로 보고 failover pause를 적용한다. |
| `WAIT` timeout 또는 replica ACK 부족 | 새 admission을 유효 처리하지 않고 failover pause를 적용한다. |
| `min-replicas-to-write` 조건 불만족 | Redis primary write를 신뢰하지 않고 failover pause를 적용한다. |
| Redis key loss during active epoch | Redis admission state가 손상된 것으로 보고 새 admission을 pause한다. MySQL admission ledger와 inventory guard는 최종 정합성을 계속 보장한다. |
| Redis recovers after pause | half-open probe가 Redis write + WAIT에 성공한 뒤에만 `REDIS`로 복구한다. |
| half-open probe 실패 | Retry-After window 동안 반복 probe를 억제하고 retryable unavailable 응답을 반환한다. |

## 테스트 훅

최소 테스트 후보:

- 동시 Redis admission에서 sequence가 중복되지 않아야 한다.
- 같은 사용자의 반복 요청은 같은 admission sequence를 반환해야 한다.
- candidate limit 초과 요청은 DB admission insert 없이 `BUSY`를 반환해야 한다.
- MySQL row가 없는 Redis seq는 유효 admission으로 보지 않아야 한다.
- Redis down/timeout/OOM/WAIT timeout은 `REDIS_FAILOVER_PAUSED`로 전환되어야 한다.
- failover pause 중 새 admission은 MySQL DB fallback으로 우회하면 안 된다.
- Redis 복구 후 half-open probe가 Redis write + WAIT 성공을 확인해야 admission을 재개한다.
- Redis key loss가 발생해도 MySQL이 최종 source of truth이므로 oversell이 발생하지 않아야 한다.

## 다음 설계 주제

다음 설계 주제는 MySQL admission table과 inventory correctness guard다.

- DB admission sequence와 uniqueness.
- candidate tranche/status transitions.
- reservation/hold model.
- 쟁점 1의 final stock correctness mechanism.
