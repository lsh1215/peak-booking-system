# Peak Booking System Decisions

이 문서는 `00시` 오픈, `10개 한정` 초특가 숙소 상품의 선착순 예약/결제 backend에서 요구사항을 만족하기 위해 내린 **핵심 기술 의사결정을** 기록한다.

기술 스택 자체를 단순 나열하는 것은 이 문서의 중심 주제가 아니다. 여기서는 요구사항이 직접 요구하는 **재고 정합성, 공정성, Redis 장애 대응, 멱등성, 결제 실패 처리, 결제 확장성, 과부하 방어 판단을** 다룬다. 라이브러리와 외부 구성요소는 **"어떤 문제를 해결하기 위해 도입했는가"가** 분명한 경우에만 별도 쟁점으로 기록한다.

## 목차

1. [재고 정합성과 공정성 기준](#쟁점-1-재고-정합성과-공정성-기준)
2. [Redis 장애 대응과 고가용성](#쟁점-2-redis-장애-대응과-고가용성)
3. [멱등성 처리](#쟁점-3-멱등성-처리)
4. [결제 실패, timeout, unknown 처리](#쟁점-4-결제-실패-timeout-unknown-처리)
5. [결제 수단 확장성](#쟁점-5-결제-수단-확장성)
6. [피크 트래픽 방어와 rate limiter](#쟁점-6-피크-트래픽-방어와-rate-limiter)
7. [테스트와 관측 기준](#쟁점-7-테스트와-관측-기준)
8. [라이브러리와 외부 구성요소 도입 범위](#쟁점-8-라이브러리와-외부-구성요소-도입-범위)

---

## 쟁점 1. 재고 정합성과 공정성 기준

### 상황

대상 상품은 `00시`에 열리는 `10개 한정` 상품이다. 피크 구간에는 `500~1000 TPS`가 `1~5분` 동안 몰릴 수 있고, 사용자는 중복 클릭하거나 재시도할 수 있다.

요구사항은 두 가지를 동시에 요구한다.

- 초과판매가 절대 발생하면 안 된다.
- 모든 사용자가 동등한 확률로 상품을 구매할 수 있는 구조를 고민해야 한다.

클라이언트가 먼저 클릭한 시각은 사용자의 네트워크, 단말, 브라우저, clock drift 영향을 받으므로 서버가 검증 가능한 공정성 기준으로 쓰기 어렵다.

### 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| 클라이언트 클릭 시각 기준 | 사용자 체감과 가까워 보임 | 조작/오차/네트워크 차이를 검증하기 어렵고 서버 감사가 어렵다 | 거절 |
| Redis sequence를 최종 순서로 사용 | 빠르고 구현이 단순함 | Redis 장애/replication lag/failover 시 유실 가능성이 있어 최종 원장으로 부족하다 | 단독 기준으로 거절 |
| MySQL admission ledger sequence를 공식 순서로 사용 | durable하고 감사 가능하며 Redis 장애 후에도 기준이 남는다 | DB write가 필요하고 hot row/candidate pool 설계가 필요하다 | 수용 |
| 재고 1개당 row를 만들고 lock으로 점유 | 단위 재고 추적이 명확함 | 현재 `10개` 이벤트와 결제 unknown/recovery 모델에 비해 schema/state complexity가 크다 | 기본안으로 거절 |

### 왜 그렇게 판단했는지

공정성은 **"누가 실제로 더 빨리 클릭했는가"가** 아니라 **"오픈 이후 유효한 첫 예약 시도가 권위 있는 서버 admission gate에 어떤 순서로 기록되었는가"로** 정의한다.

최종 채택 구조는 다음과 같다.

- Redis는 정상 경로에서 빠르게 중복 사용자, candidate limit, provisional sequence를 판단한다.
- Redis admission이 성공해도 **MySQL `booking_admission` row가 저장되기 전까지는 유효 admission으로 보지 않는다**.
- **공식 순서는 MySQL `db_admission_seq`다**.
- `(sale_event_id, product_id, user_id)` unique constraint로 같은 사용자의 중복 클릭이 구매 확률을 높이지 못하게 한다.
- candidate pool은 sale event당 `30`으로 제한한다.
- 재고 점유는 **`reservation` row와 MySQL atomic counter guard로만 확정한다**.

최종 재고 불변식은 아래와 같다.

```text
HELD + PAYMENT_UNKNOWN + CONFIRMED <= total_stock
total_stock = 10
```

초기 재고 점유는 조건부 update로 처리한다.

```sql
UPDATE sale_inventory
SET reserved_count = reserved_count + 1
WHERE sale_event_id = ?
  AND product_id = ?
  AND reserved_count + payment_unknown_count + confirmed_count < total_count;
```

이 update가 성공한 transaction 안에서만 `reservation.HELD` row를 만든다. PG 호출은 이 transaction 안에서 하지 않는다.

---

## 쟁점 2. Redis 장애 대응과 고가용성

### 상황

요구사항은 Redis 장애 시 fallback 전략과 근거를 요구한다. 동시에 이 시스템은 `500~1000 TPS` 피크에서 기존 서비스 DB를 터뜨리면 안 된다.

처음에는 Redis 장애 시 bounded DB admission fallback을 고려했다. 하지만 단일 Redis를 완전히 내린 상태에서 `500 RPS` 이상을 DB fallback으로 바로 받아보면 두 문제가 생긴다.

- DB fallback concurrency를 작게 잡으면 대부분 요청이 거절되어 "Redis 장애 중에도 제한적으로 판매한다"는 목표를 달성하기 어렵다.
- DB fallback concurrency를 크게 잡으면 공유 DB의 connection pool, lock wait, latency가 급격히 나빠질 수 있다.

따라서 Redis 장애 상황에서 **request thread가 DB를 새 admission 원장으로 직접 때리는 방식은 ROI가 낮다**. 다만 완전 fail-fast만 선택하면 판매 UX가 너무 급격히 나빠지고, Redis failover 동안 유입된 사용자가 모두 이탈한다. 현재 판단은 공정성을 일부 포기하더라도 시스템 전체를 살리고 제한적인 비즈니스 연속성을 확보하는 쪽으로 바뀌었다.

### 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| Redis 장애 시 fail-closed | 가장 단순하고 초과판매 위험이 낮다 | Redis 장애 동안 판매가 멈춘다 | 단독 기본안으로는 부족 |
| Redis 장애 시 bounded DB fallback | Redis down 중에도 일부 판매 가능성이 있다 | 피크에서 DB 보호와 공정성을 동시에 만족시키기 어렵고, DB가 기존 서비스와 공유되면 위험하다 | 기존 후보에서 거절 |
| Redis HA + failover pause | Redis 단일 장애 시간을 줄이고, failover 중 DB 폭주를 막으며 공정성 기준을 갈라놓지 않는다 | failover 중 판매가 멈춘다 | 이전 기본안, 현재는 보조 회복 구조 |
| WAS-local bounded in-memory queue | Kafka/MQ 없이 장애 중 요청을 202로 받아 UX를 유지하고, worker가 DB write 속도를 제한한다 | replica 간 전역 FIFO가 아니며 WAS crash 시 해당 로컬 큐 요청이 유실될 수 있다 | 현재 fallback으로 수용 |
| Durable admission log 추가 | Redis 장애 중에도 공식 도착 순서를 계속 보존할 수 있다 | Queue/Kafka/PubSub HA, consumer, 운영 복잡도가 크게 늘어난다 | 현재 범위에서는 보류 |

### 왜 그렇게 판단했는지

Redis 장애 대응의 핵심은 **"Redis가 죽어도 DB를 무제한으로 때리지 않는다"와** **"장애 중 유입된 요청을 bounded queue와 throttled worker로만 DB에 흘린다"다**. 기존의 전역 공정성 기준은 약해진다. Redis 장애 중에는 WAS replica별 로컬 큐 순서가 사용자 경험상 대기 순서가 되며, 전역 FIFO는 보장하지 않는다.

최종 채택 구조는 다음과 같다.

```text
Redis HA:
  primary + replica 2대 권장
  Sentinel 3대 또는 managed Redis HA

Redis config:
  min-replicas-to-write 1
  min-replicas-max-lag 1~2s

Admission path:
  Redis Lua admission
  -> 새 admission write인 경우 WAIT 1, short timeout(예: 20~50ms)
  -> MySQL official admission ledger write

Failure policy:
  min-replicas 조건 불만족
  WAIT timeout
  Sentinel failover 감지
  Redis command timeout
  -> request thread는 DB admission을 직접 수행하지 않음
  -> bounded WAS-local queue에 offer
  -> 성공 시 202 LOCAL_QUEUE_ACCEPTED + booking_attempt_id
  -> queue full 시 429 LOCAL_QUEUE_FULL + Retry-After

Local queue policy:
  ArrayBlockingQueue(capacity 예: WAS당 2,000)
  booking_attempt_id + request_hash로 로컬 dedupe/conflict 방지
  worker는 fixedDelay/batchSize로 DB admission을 throttling
  DB admission은 LOCAL_QUEUE gate_mode로 MySQL official ledger에 기록
  confirmed 수는 MySQL inventory guard로 <= 10 보장
  client는 GET /bookings/status/{booking_attempt_id} 또는 POST replay로 polling

Recovery policy:
  pause TTL 이후 half-open probe 수행
  probe는 Redis write + WAIT가 성공해야 통과
  probe가 성공해도 local queue active_count가 0이 될 때까지 새 외부 요청은 로컬 큐에 유지
  probe 성공 시점부터 local queue drain-grace(예: 30s)가 지나면 남은 queue는 worker가 계속 처리하고 새 요청은 REDIS 경로 재개 가능
  실패하면 Retry-After window 동안 반복 probe를 억제
```

이 구조는 Redis replication이나 WAS local queue가 강한 전역 공정성을 보장한다고 가정하지 않는다. Redis primary가 sequence를 부여했지만 MySQL admission row 저장 전에 죽는 짧은 구간은 이론적으로 남는다. 로컬 큐도 JVM 메모리이므로 해당 WAS가 crash되면 아직 DB에 남지 않은 queue entry는 유실될 수 있다. 이를 완화하기 위해 `WAIT`, `min-replicas-to-write`, bounded queue, idempotency key, throttled worker를 사용하지만, 이것이 durable log와 같은 보장을 주지는 않는다.

따라서 Redis 장애 중에도 판매를 계속하면서 공식 도착 순서까지 보존해야 하는 요구가 추가되면, Redis 앞 또는 Redis 옆에 durable admission log를 추가해야 한다. 현재 요구사항과 비용 대비 효과를 기준으로는 **Redis HA + WAS-local bounded queue + throttled DB admission**이 더 적절하다.

현재 repository에 남기는 증거는 두 층으로 분리한다.

| 증거 | 확인 내용 | 현재 상태 |
|---|---|---|
| unit/integration test | Redis failover pause, half-open probe, local queue bounded/dedupe/drain-grace, worker batch 제한, DB fallback 무제한 우회 금지를 코드 수준에서 검증 | repository에 포함 |
| k6 isolated suite | 정상 peak, 중복 클릭, PG timeout, WAS 1대 down, Redis hard-down, mixed, shared DB pressure를 분리 측정 | 원시 결과는 `loadtest-results/`에 보존하되 Git 추적 제외 |
| Redis master failover k6 | Sentinel failover 중 local queue accepted/full 비율, drain 시간, DB pressure, half-open recovery를 실측해야 함 | 최신 원시 결과를 제출 증거로 다시 남겨야 함 |

따라서 이 결정의 핵심 주장은 **"failover 중에도 모든 요청을 성공시킨다"가** 아니다. 핵심은 **"Redis HA로 장애 시간을 줄이고, failover 중에는 request thread의 직접 DB fallback을 막으며, bounded local queue와 throttled worker로만 제한적으로 판매를 이어가고, Redis half-open probe 성공 후에도 local queue가 비거나 복구 시점부터 drain-grace가 지날 때까지 새 요청을 로컬 큐에 유지한다"는** 것이다. Redis master failover의 최종 성능 수치는 [부하 테스트 증거 인덱스](../testing/loadtest-evidence-index.md)에 최신 실행 결과를 연결해 갱신한다.

---

## 쟁점 3. 멱등성 처리

### 상황

요구사항은 주문서에서 아주 짧은 간격으로 연속 결제 요청이 발생해도 중복 처리되지 않아야 한다고 한다.

중복 클릭은 단순히 같은 HTTP 요청이 여러 번 들어오는 문제가 아니다. 아래 문제가 함께 생긴다.

- 같은 사용자가 같은 결제 버튼을 여러 번 누른다.
- 같은 `booking_attempt_id`로 동시에 요청이 들어온다.
- 이전 요청은 PG confirm 중인데 다음 요청이 다시 PG confirm에 진입한다.
- payment timeout/unknown 상태에서 사용자가 재시도한다.
- replay 응답이 원래 terminal 응답과 다르면 client가 상태를 오해한다.

### 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| `user_id` 단독을 멱등성 key로 사용 | 단순함 | 같은 사용자의 다른 이벤트/상품/시도를 구분하지 못한다 | 거절 |
| 클라이언트가 `Idempotency-Key` 생성 | 범용 API 패턴과 유사함 | 현재 인증/클라이언트 신뢰가 범위 밖이고 key 오염 가능성이 있다 | 거절 |
| 서버가 checkout 진입 시 `booking_attempt_id` 발급 | 주문서 진입과 결제 시도를 하나의 논리 시도로 묶기 쉽다 | GET이 token 발급 상태를 만들 수 있어 API semantics를 조심해야 한다 | 수용 |
| DB unique constraint만 사용 | 일부 중복 insert는 막을 수 있음 | replay 응답, in-progress 상태, request body 변경 검증이 약하다 | 단독으로 거절 |

### 왜 그렇게 판단했는지

최종 멱등성 기준은 **서버 발급 `booking_attempt_id`다**. 사용자는 `GET /checkout/{productId}`에서 주문서 정보와 함께 attempt token을 받고, `POST /bookings`에서 같은 token을 사용한다.

정책은 다음과 같다.

- `booking_attempt_id`는 서버가 발급한다.
- `request_hash`는 side-effect에 영향을 주는 필드만 정규화해 만든다.
- 같은 `booking_attempt_id`와 같은 `request_hash`의 terminal replay는 저장된 logical response를 반환한다.
- 같은 `booking_attempt_id`지만 `request_hash`가 다르면 conflict로 거절한다.
- in-progress 또는 `PAYMENT_UNKNOWN` replay는 **새 PG confirm을 만들지 않고 현재 상태를 반환한다**.
- 같은 attempt에서 PG confirm owner는 조건부 상태 전이로 1개 요청만 획득한다.
- idempotency record retention은 `24h`다. 이 값은 **재고 점유 시간이 아니라 운영 추적, 지연 retry, recovery/webhook 확인 buffer다**.

`request_hash`에 포함하는 필드는 다음이다.

- `sale_event_id`
- `product_id`
- 인증된 `user_id`
- `booking_attempt_id`
- 결제 수단 조합
- 수단별 금액
- 포인트 사용액
- PG 승인 대상 금액
- `total_amount`
- `currency`
- `payment_policy_version`

`request_hash`에서 제외하는 필드는 다음이다.

- 요청 시각
- User-Agent
- client IP
- trace id
- retry count
- header 순서
- 화면 표시용 문자열

---

## 쟁점 4. 결제 실패, timeout, unknown 처리

### 상황

요구사항은 한도 초과 같은 결제 실패 케이스에 대한 대응 로직을 요구한다. 실제 PG 연동은 범위 밖이지만, Mock PG는 실제 PG처럼 승인, 조회, 취소, 상태 변경 이벤트 흐름을 구조적으로 가져야 한다.

`mock_pg_scenario`는 이 흐름을 검증하기 위한 local/test/load-test profile 전용 장애 주입 값이다. production API 계약에서는 사용자가 `SUCCESS`, `FAILURE`, `TIMEOUT`, `LATE_SUCCESS` 같은 결제 결과를 선택하는 형태로 노출하면 안 된다. 실제 운영에서는 PG adapter나 테스트 전용 내부 endpoint/header가 같은 역할을 맡아야 한다.

결제 처리에서 가장 위험한 지점은 timeout/응답 유실이다.

- PG confirm 호출이 성공했지만 WAS가 응답을 받기 전에 죽을 수 있다.
- PG confirm 중 timeout이 났지만 실제 PG에서는 승인되었을 수 있다.
- PG 성공 여부가 불명확한 상태에서 재고를 무기한 잡으면 미달 판매가 발생한다.
- 불명확하다고 재고를 바로 풀었는데 나중에 PG 성공이 확인되면 초과판매/중복 확정 위험이 생긴다.

### 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| PG timeout을 즉시 실패로 처리 | 구현이 단순함 | 실제 승인 후 응답 유실이면 미정산 charge가 생길 수 있다 | 거절 |
| PG confirm을 DB transaction 안에서 호출 | 코드 흐름이 직관적임 | 외부 지연이 DB lock/connection을 오래 점유한다 | 거절 |
| `HELD` commit 후 PG confirm을 transaction 밖에서 호출 | DB 자원 점유를 줄이고 crash recovery 지점이 명확하다 | 상태 전이와 recovery worker가 필요하다 | 수용 |
| `PAYMENT_UNKNOWN`을 재고에 무기한 묶기 | oversell 방어가 강하다 | 10개 한정 상품에서 미달 판매 가능성이 커진다 | 거절 |
| `PAYMENT_UNKNOWN` 재고 점유를 deadline으로 제한 | oversell 방어와 미달 판매 방지를 균형 있게 다룬다 | 늦은 PG 성공 cancel/refund 비용이 생길 수 있다 | 수용 |

### 왜 그렇게 판단했는지

#### 결론

이 정책의 핵심은 "실패한 결제는 예약으로 확정하지 않고, 결과가 불명확한 결제는 짧게만 기다린 뒤 정리한다"이다.

사용자가 한도 초과, 카드 거절, 포인트 부족처럼 명확한 실패를 받으면 예약은 실패로 끝난다. 이때 시스템은 잡아두었던 재고와 Y포인트 hold를 즉시 반환하고, 해당 요청을 다시 확정 예약으로 되살리지 않는다.

PG timeout, bulkhead full, 응답 유실처럼 성공인지 실패인지 알 수 없는 경우에는 바로 실패로 단정하지 않는다. 실제 PG에서는 이미 승인됐을 수 있기 때문이다. 대신 예약을 `PAYMENT_UNKNOWN`으로 표시하고, 최대 `30s` 동안만 재고를 임시 점유한다. `30s` 안에 PG 성공이 확인되면 예약을 확정하고, 확인하지 못하면 재고를 풀어 다음 후보에게 기회를 넘긴다.

deadline 이후 늦게 PG 성공이 확인되어도 예약을 다시 `CONFIRMED`로 되살리지 않는다. 이미 풀린 재고가 다른 후보에게 넘어갔을 수 있으므로, 이 경우는 예약 확정이 아니라 PG 취소, 환불, 또는 manual review 대상으로 처리한다.

#### 사용자 기준 흐름

| 상황 | 사용자에게 보이는 결과 | 재고 처리 | 다음 후보 영향 |
|---|---|---|---|
| PG 승인 성공 | 예약 성공 | `10개` 재고 중 1개 확정 사용 | 없음 |
| 한도 초과, 카드 거절, 포인트 부족 | 결제 실패 | 잡아둔 재고를 즉시 반환 | 대기 후보가 다음 기회를 받을 수 있음 |
| PG timeout, 응답 유실 | 결제 확인 중 | 최대 `30s` 동안 임시 점유 | 아직 다음 후보에게 넘기지 않음 |
| `30s` 안에 PG 성공 확인 | 예약 성공 | 확정 재고로 전환 | 없음 |
| `30s` 안에 PG 실패 확인 | 결제 실패 | 재고 반환 | 대기 후보가 다음 기회를 받을 수 있음 |
| `30s` 이후 PG 성공 확인 | 예약 실패 유지, 보상 처리 | 이미 반환한 재고를 되돌리지 않음 | 기존 다음 후보 처리를 우선함 |

#### 내부 처리 순서

예약과 결제는 하나의 긴 DB transaction으로 묶지 않는다. 외부 PG 지연이 DB connection과 lock을 오래 붙잡으면, 00:00 spike에서 DB가 먼저 병목이 될 수 있기 때문이다.

```text
1. MySQL transaction으로 reservation.HELD를 먼저 만든다.
2. DB transaction 밖에서 PG confirm을 호출한다.
3. PG 성공이면 짧은 transaction으로 HELD -> CONFIRMED 처리한다.
4. PG 명시 실패이면 HELD -> RELEASED 처리하고 Y포인트 hold를 반환한다.
5. PG timeout/unknown이면 HELD -> PAYMENT_UNKNOWN으로 전이한다.
6. PAYMENT_UNKNOWN은 최초 unknown 이후 30s까지만 재고를 점유한다.
7. 30s 안에 PG 성공을 확인하지 못하면 reservation을 반환/만료 처리한다.
8. deadline 이후 늦은 PG 성공은 예약 확정이 아니라 cancel/refund/manual review로 정리한다.
```

이 구조는 초과판매를 막기 위해 `HELD`, `PAYMENT_UNKNOWN`, `CONFIRMED`를 모두 재고 점유 상태로 계산한다. 동시에 `PAYMENT_UNKNOWN`에는 deadline을 두어, 10개 한정 상품이 timeout 몇 건 때문에 오래 미달 판매로 남는 상황을 줄인다.

#### 다음 후보 기회 승계

결제 실패 후속 처리는 두 단계로 나눈다.

1. 실패한 예약을 확정하지 않는다.
2. 풀린 재고를 다음 후보가 가져갈 수 있게 한다.

명확한 결제 실패가 확정되면 해당 admission은 실패로 닫고, 잡아두었던 `HELD` 재고를 즉시 반환한다. 그러면 대기 중인 다음 후보가 기회를 받을 수 있다.

다만 이것은 서버가 다음 후보의 결제를 백그라운드에서 자동 실행한다는 뜻이 아니다. 결제 side effect는 사용자의 명시적인 booking request 안에서만 발생해야 한다. 따라서 다음 후보는 자신이 받은 `202 WAITING_CANDIDATE + RETRY_POST_BOOKINGS` 안내에 따라 짧게 retry/polling한다. 그 재요청 시점에 선착순/대기열 정책상 가장 앞선 유효 후보이고 waiting window 안에 있을 때만 `HELD`로 승격된다.

```text
A candidate:
  ADMITTED -> HELD -> PAYMENT_FAILED
  reservation RELEASED
  admission FAILED
  idempotency terminal snapshot 저장

B waiting candidate:
  기존 응답: 202 WAITING_CANDIDATE + RETRY_POST_BOOKINGS
  A 실패 후 B가 재요청:
    waiting window 안의 earliest valid candidate인지 확인
    -> HELD reservation 생성
    -> B 요청 흐름 안에서 payment confirm 진행
```

즉, 결제 실패는 다음 후보에게 기회를 열어주는 것이지, 다음 후보의 구매 성공을 보장하거나 자동 결제를 실행하는 것이 아니다.

#### Recovery worker는 안전망이다

Recovery worker는 위 사용자-facing 흐름에서 정리되지 못한 상태를 뒤에서 보정하는 안전망이다. 기존 WAS 내부에서 bounded budget으로 동작한다. 별도 worker pod를 필수로 두지 않는 이유는 인프라 증설이 제한적인 상황을 가정하고, 현재 범위에서는 기존 WAS 안의 scheduler와 MySQL lease로 충분히 중복 실행을 막을 수 있기 때문이다.

Recovery worker 대상은 다음이다.

- PG 호출 전/중 WAS crash로 남은 stale `HELD`
- `PAYMENT_UNKNOWN`
- release 이후 늦은 PG 성공 가능성이 있는 `RECONCILING_AFTER_RELEASE`
- cancel/refund가 필요한 `LATE_SUCCESS_CANCEL_PENDING`

재고 점유 deadline과 payment reconciliation window는 분리한다.

| 항목 | 값 | 의미 |
|---|---:|---|
| `hold_expires_at` | 30s | PG 호출 전/중 남은 stale `HELD` 재고 점유 제한 |
| `unknown_inventory_deadline_at` | 30s | `PAYMENT_UNKNOWN` 재고 점유 제한 |
| waiting candidate 사용자-facing window | 60s | 후순위 후보가 대기할 수 있는 최대 시간 |
| payment reconciliation 적극 window | 5분 | 재고 release 이후에도 payment/cancel 상태를 정리하는 운영 window |

이 선택은 PG 취소 수수료, 환불 비용, CS 비용을 accepted compensation cost로 둔다. 요구사항상 더 중요한 것은 초과판매 방지와 결제 실패/장애 후 재고 누수 방지다.

---

## 쟁점 5. 결제 수단 확장성

### 상황

요구사항은 신용카드, Y페이, Y포인트를 지원해야 한다고 한다. 복합 결제는 `(신용카드 + 포인트)` 또는 `(Y페이 + 포인트)`만 허용하며, 신용카드와 Y페이 혼용은 금지한다.

또한 향후 새로운 결제 수단이 추가되어도 Booking API의 비즈니스 로직 수정을 최소화해야 한다.

### 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| Booking service에 결제 수단 if/else를 직접 작성 | 빠르게 구현 가능 | 수단이 늘 때 Booking API 핵심 로직이 계속 바뀐다 | 거절 |
| 결제 수단별 microservice 분리 | 수단별 독립 배포가 가능함 | 현재 요구사항 대비 운영 복잡도가 크다 | 거절 |
| `PaymentPlan` + `CombinationPolicy` + `PaymentProcessor` | 조합 검증과 수단별 실행 책임을 분리한다 | 초기 class 수가 약간 늘어난다 | 수용 |

### 왜 그렇게 판단했는지

최종 구조의 핵심은 **Booking API가 결제 수단을 직접 알지 않게 하는 것**이다. Booking Application Service는 예약 흐름을 조정하고, 결제 조합의 허용 여부와 결제 수단별 실행 방식은 결제 domain policy/strategy로 분리한다.

적용한 설계 개념은 다음과 같다.

| 개념 | 적용 |
|---|---|
| DDD Value Object | `PaymentPlan`으로 결제 수단과 금액을 정규화한다 |
| Domain Policy | `CombinationPolicy`가 신용카드+Y페이 혼용 금지, 금액 일치 같은 조합 규칙을 검증한다 |
| Strategy Pattern | `PaymentMethodProcessor` 구현체가 신용카드, Y페이, Y포인트의 실행 방식 차이를 담당한다 |
| Registry / Factory | `PaymentProcessorRegistry`가 결제 수단에 맞는 processor를 선택해 `PaymentExecutionPlan`을 만든다 |
| Port / Adapter | `PaymentProvider` interface 뒤로 실제 PG/Mock PG 연동을 숨긴다 |

이 구조는 SOLID 관점에서 SRP와 OCP를 우선한 선택이다. 조합 검증, 수단 선택, 외부 PG 호출, 예약 흐름을 분리하고, 새 결제 수단이 추가되어도 Booking Application Service의 핵심 예약 흐름에 수단별 `if/else`가 늘어나지 않게 한다.

**Y포인트는 단순 차감 값이 아니라 내부 원장 결제 수단으로 본다.** 따라서 Y포인트도 `hold -> capture -> release` 상태를 가진다. 신용카드와 Y페이는 외부 provider 결제 수단이고, Y포인트는 내부 ledger 결제 수단이다. 복합 결제는 이 모델에서 "외부 provider 결제 최대 1개 + 내부 원장 결제"로 표현된다.

- 결제 시작 시 포인트를 `hold`한다.
- PG 성공이 reservation deadline 안에 확인되면 `capture`한다.
- PG 명확한 실패 또는 reservation deadline release면 `release`한다.
- 늦은 외부 PG 성공은 booking 확정이 아니라 cancel/refund/reconciliation 대상이다.

새 결제 수단이 추가되면 `PaymentMethodType`, 해당 `PaymentMethodProcessor`, `CombinationPolicy`, 필요 시 `PaymentProvider` adapter와 테스트를 추가한다. 완전한 OCP를 주장하지는 않는다. 새 수단은 실제 domain rule 변경이므로 정책은 바뀌어야 한다. 이 결정의 목표는 변경을 없애는 것이 아니라, 변경이 **Booking API 핵심 예약 흐름으로 번지지 않고 결제 domain의 policy/strategy 경계 안에 머물게 하는 것**이다.

---

## 쟁점 6. 피크 트래픽 방어와 rate limiter

### 상황

요구사항은 평시 `50 TPS`, `00시` 이후 `1~5분` 동안 `500~1000 TPS` 급증을 고려하라고 한다. 또한 인프라 증설이 제한적인 상황을 가정한다.

이 시스템에서 대부분의 요청은 결국 재고 `10개`를 얻지 못한다. 따라서 모든 요청을 WAS와 DB 깊은 경로로 들여보내면 성공 예약 수는 10개뿐인데, 실패 요청이 기존 서비스 DB를 압박할 수 있다.

### 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| 별도 gateway rate limit 없이 WAS만으로 방어 | 구성요소가 적다 | spike가 WAS thread/connection을 먼저 압박한다 | 거절 |
| Traefik route-level rate limit | WAS 앞에서 1차 shedding 가능 | 공정성/중복 방지 원장은 될 수 없다 | 수용 |
| Traefik Redis-backed distributed rate limit | gateway replica 간 전역 rate limit 가능 | Redis 장애 시 gateway 방어까지 Redis에 결합된다 | 거절 |
| user-level gateway rate limit | 중복 사용자 제어에 좋아 보임 | 현재 인증/인가가 out of scope이고 user header를 gateway가 신뢰하기 어렵다 | 현재 범위에서 거절 |
| app 내부 bulkhead/semaphore | DB/PG별 blast radius를 제한할 수 있다 | 너무 앞단에 두면 Redis admission 전에 공정성을 해칠 수 있다 | 수용하되 위치 제한 |

### 왜 그렇게 판단했는지

Traefik은 k3s 환경에서 2개 이상 WAS replica 앞단의 LB/API gateway 역할을 한다. 여기서 route-level rate limit은 **WAS가 터지는 것을 막는 1차 방어 수단이다**.

하지만 **선착순 공정성이나 사용자 중복 방지는 Traefik에서 판단하지 않는다**.

최종 정책은 다음과 같다.

- Traefik rate limit은 `POST /bookings` route 보호용이다.
- Traefik은 Redis-backed distributed limiter로 구성하지 않는다.
- Traefik은 user별 중복 방지나 공정성 원장이 아니다.
- 사용자 중복 방지는 app/MySQL의 `(sale_event_id, product_id, user_id)` unique와 idempotency policy가 담당한다.
- DB write, PG confirm, recovery worker는 별도 concurrency budget을 둔다.
- DB write bulkhead는 **Redis admission을 통과한 후보를 MySQL official admission ledger에 저장할 수 있는지를** 보호하는 좁은 구간에 둔다.
- 이 순서는 "Redis가 모든 요청의 최종 순서를 먼저 정한다"는 뜻이 아니다. **DB에 durable admission row를 남길 수 없는 요청은 Redis candidate sequence도 소비하지 않게 하려는 trade-off다**.
- **request thread의 직접 DB fallback admission은 기본 경로에서 제거한다**.
- **Redis failover 중 새 admission은 WAS-local bounded queue로만 받고, throttled worker가 DB admission을 수행한다**.
- **Redis가 복구되어도 local queue active_count가 0이 되거나 half-open probe 성공 시점부터 drain-grace가 지날 때까지 새 요청은 로컬 큐에 유지한다**.

초기 수치는 Little's Law, HikariCP pool sizing guidance, Mock PG normal delay, k6/LGTM 실측으로 산정하고 보정한다. 단, **초과판매 금지, 중복 결제 금지, 재고 불변식 같은 hard correctness 기준은 수치 보정으로 완화하지 않는다**.

---

## 쟁점 7. 테스트와 관측 기준

### 상황

문서 설계만으로는 Redis HA, 결제 unknown, 멱등성, DB 보호가 실제로 동작한다고 주장할 수 없다. 특히 이 시스템은 **"정상 부하에서 빠르다"보다** **"장애와 spike가 겹쳐도 초과판매/중복결제/DB collapse가 없다"가** 더 중요하다.

### 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| unit test 중심 검증 | 빠르고 개발 중 피드백이 좋다 | Redis/DB/PG 장애, load shedding은 검증이 부족하다 | 단독으로 부족 |
| 구현 후 k6만 실행 | 실제 환경 부하를 볼 수 있다 | domain invariant가 어디서 깨지는지 늦게 알 수 있다 | 단독으로 부족 |
| TDD + integration + k6 resilience + LGTM | 작은 규칙부터 운영 지표까지 검증 가능 | 준비 비용이 더 든다 | 수용 |

### 왜 그렇게 판단했는지

구현은 작은 단위에서 큰 단위로 간다.

1. Unit test: 결제 조합, request hash canonicalization, 상태 전이.
2. Slice test: controller validation, repository query/mapping.
3. Integration/acceptance: MySQL, Redis, Mock PG를 포함한 예약/결제 흐름.
4. k6 smoke/load/resilience: 정상, Redis failover, WAS 1대 down, PG timeout, duplicate, mixed.
5. LGTM/Grafana: DB pressure, Redis pressure, app latency, Hikari, JVM, gateway shedding 확인.

부하 테스트 원시 결과는 크고 환경 의존적이므로 `loadtest-results/` 아래에 로컬 보존하고 Git에는 올리지 않는다. 대신 제출 문서에는 [부하 테스트 증거 인덱스](../testing/loadtest-evidence-index.md)를 두어 **어떤 주장을 어떤 테스트/파일/상태가 뒷받침하는지를** 추적한다.

Hard correctness fail은 아래와 같다.

- confirmed booking이 10을 초과한다.
- `HELD + PAYMENT_UNKNOWN + CONFIRMED <= 10`이 깨진다.
- 같은 user/event/product에서 confirmed가 중복된다.
- 같은 `booking_attempt_id`에서 PG confirm side effect가 2회 이상 발생한다.
- Redis failover 중 새 admission이 DB fallback으로 무제한 우회한다.
- deadline 이후 release된 reservation이 늦은 PG success로 다시 confirmed가 된다.

**Latency/resource 기준은 실측으로 보정할 수 있지만, 위 correctness 기준은 완화하지 않는다.**

---

## 쟁점 8. 라이브러리와 외부 구성요소 도입 범위

### 상황

요구사항은 동작 가능한 소스 코드와 실행 방법을 요구한다. 동시에 추가 인프라를 사용했다면 이유와 비용 대비 효과를 설명해야 한다.

이 시스템은 `10개` 한정 상품을 다루므로, 기능 구현만 보면 단순 CRUD처럼 보일 수 있다. 하지만 실제 문제는 **중복 클릭, 결제 timeout, Redis 장애, 피크 트래픽, DB 압박이 겹칠 때 정합성을 지키는 것이다**. 따라서 라이브러리와 외부 구성요소는 **"있으면 좋아 보이는 기술"이 아니라 "요구사항의 특정 위험을 낮추는 도구"일 때만 도입한다**.

### 선택지

| 선택지 | 장점 | 단점 | 판단 |
|---|---|---|---|
| Spring Boot + MySQL만 사용 | 구성요소가 가장 적다 | 피크 admission을 모두 DB write로 받아야 해서 공유 DB 보호가 어렵다 | 단독으로 거절 |
| Redis 단일 인스턴스만 사용 | admission이 빠르고 구현이 쉽다 | Redis 장애 시 failover/fairness 문제가 커진다 | 단독으로 거절 |
| Redis HA + MySQL final guard | 정상 경로는 빠르게 거르고, Redis 단일 장애 시간을 줄이며, 최종 정합성은 DB가 보장한다 | Redis Sentinel/replica 운영 복잡도가 추가된다 | 수용 |
| Kafka/Queue 기반 durable admission log 추가 | Redis 장애 중에도 공식 도착 순서를 계속 저장할 수 있다 | 현재 요구사항 대비 운영 비용과 구현 범위가 크다 | 보류 |
| full microservice/payment service 분리 | 독립 배포와 조직 확장에 유리하다 | 현재 과제의 핵심 위험보다 운영 복잡도가 먼저 커진다 | 거절 |

### 왜 그렇게 판단했는지

| 구성요소 | 도입 이유 | 거절한 단순안 | 문제 해결 전략 |
|---|---|---|---|
| Spring Boot 3.x | Java 21 기반 REST API, validation, scheduling, metrics를 빠르게 구성한다 | 직접 HTTP 서버 구현 | application service boundary에 transaction을 두고, controller는 요청/응답 변환에 집중한다 |
| Spring Data JPA / Hibernate | domain entity와 repository를 명확히 두고 MySQL schema와 매핑한다 | JDBC string query 중심 구현 | 단, hot-path atomic update와 lease claim은 repository query로 명확히 표현한다 |
| MySQL 8 | 최종 재고 원장, admission ledger, idempotency, payment/recovery state를 durable하게 저장한다 | Redis를 최종 원장으로 사용 | `reservation` + atomic counter + DB constraint로 `<= 10`을 보장한다 |
| Redis HA | 정상 경로 admission을 빠르게 처리하고 Redis 단일 장애 시간을 줄인다 | 단일 Redis 또는 DB fallback only | Lua admission + `WAIT` + Sentinel failover + half-open probe를 사용한다 |
| WAS-local queue | Redis 장애 중 request thread의 DB 폭주 없이 제한적 판매 연속성을 제공한다 | Kafka/MQ durable queue | bounded in-memory queue + worker throttling + polling status를 사용한다 |
| Traefik | k3s 환경에서 2개 이상 WAS 앞단의 LB와 1차 route-level shedding을 담당한다 | app 내부 방어만 사용 | gateway는 WAS 보호만 담당하고, 공정성/중복 방지는 app/DB가 담당한다 |
| k6 | 피크, 장애, 중복 클릭, mixed 시나리오를 재현한다 | 수동 curl 또는 smoke test만 사용 | 정상/Redis failover/WAS down/PG timeout/mixed를 분리해 검증한다 |
| LGTM + Micrometer | p95/p99, Hikari pressure, Redis 상태, JVM 지표를 관측한다 | 로그만 확인 | 성능 실패와 정합성 실패를 분리해서 판단한다 |
| Testcontainers | MySQL/Redis 통합 동작을 테스트에서 고정한다 | in-memory DB로 대체 | DB constraint, Redis admission, recovery query가 실제 의존성 위에서 동작하는지 검증한다 |

추가 인프라의 ROI는 다음 기준으로 판단한다.

- **Redis HA는 Redis 장애 요구사항을 직접 다루므로 수용한다**.
- Traefik은 `2+` stateless WAS 앞단의 LB가 필요하고, WAS 보호용 rate limit을 제공하므로 수용한다.
- LGTM과 k6는 설계가 실제로 버티는지 증명하는 검증 도구이므로 수용한다.
- Kafka/Queue는 Redis 장애 중에도 전역 FIFO와 durable 대기열을 보장해야 하는 더 강한 요구가 생기면 검토한다. 현재는 **Redis HA + WAS-local bounded queue**가 비용 대비 효과가 더 크다.
