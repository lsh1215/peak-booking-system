# Booking 사용자 흐름 Mermaid

> 이 문서는 사용자의 예약 요청부터 결제 확정/복구까지의 주요 흐름을 Mermaid로 요약한다. 최종 결정의 원문은 `docs/decisions/DECISIONS.md`, 상세 설계 기준은 `docs/system-design/sdd.md`를 따른다.

## 전제

- 정상 admission은 Redis Lua script가 담당한다.
- Redis 장애 시 같은 `sale_event_id`에서는 DB bounded admission fallback으로 전환한다.
- 최종 재고 정합성은 MySQL inventory guard와 reservation 상태 전이로 보장한다.
- PG confirm timeout/unknown은 즉시 실패로 처리하지 않고 `PAYMENT_UNKNOWN`으로 두며, webhook/user retry/recovery worker가 reconciliation한다.
- Recovery worker는 기존 WAS 내부에서 작은 thread/batch/concurrency budget으로 실행되며, MySQL lease로 중복 처리를 막는다.

## 1. 정상 상황

```mermaid
sequenceDiagram
    autonumber
    participant U as 사용자
    participant T as Traefik LB / Gateway
    participant A as Spring Boot WAS
    participant R as Redis Admission
    participant D as MySQL
    participant P as Mock PG

    U->>T: POST /bookings<br/>예약/결제 요청
    T->>T: route-level rate limit<br/>WAS 보호 목적
    T->>A: 요청 전달

    A->>A: idempotency key / request hash 확인
    A->>R: Lua admission<br/>duplicate check + seq 발급 + candidate 기록
    R-->>A: admitted(seq) 또는 rejected

    alt Redis admission rejected
        A-->>U: 매진/대기 후보 아님 응답
    else Redis admission admitted
        A->>D: reservation 생성 시도<br/>inventory conditional guard
        D-->>A: HELD reservation 생성

        A->>P: confirm payment

        alt PG confirm success
            P-->>A: 승인 성공
            A->>D: HELD -> CONFIRMED<br/>reserved 감소, confirmed 증가
            D-->>A: 확정 완료
            A-->>U: 예약/결제 확정 응답
        else PG business failure
            P-->>A: 잔액 부족/한도 초과 등 명확한 실패
            A->>D: HELD -> RELEASED<br/>reserved 감소
            D-->>A: 해제 완료
            A-->>U: 결제 실패 응답
        else PG timeout / unknown
            P--x A: timeout 또는 응답 유실
            A->>D: HELD -> PAYMENT_UNKNOWN<br/>reserved 유지, next_reconcile_at 설정
            D-->>A: unknown 기록 완료
            A-->>U: 결제 확인 중 응답<br/>사용자는 최종 확정까지 대기하거나 조회/재시도
        end
    end
```

## 2. 사용자가 기다려야 하는 지점

```mermaid
flowchart TD
    Start["사용자가 예약/결제 요청"] --> Admission["Admission 통과 여부 확인"]
    Admission -->|거절| Reject["즉시 실패 응답"]
    Admission -->|통과| Hold["MySQL reservation HELD"]
    Hold --> Confirm["PG confirm 호출"]
    Confirm -->|성공| Success["예약/결제 확정 응답"]
    Confirm -->|명확한 실패| Fail["결제 실패 응답 + reservation release"]
    Confirm -->|timeout / unknown| Pending["결제 확인 중 응답"]
    Pending --> Wait["사용자는 최종 확정 상태를 기다림"]
    Wait --> UserRetry["사용자 상태 조회 또는 멱등 재시도"]
    Wait --> Webhook["PG webhook/event 수신"]
    Wait --> Worker["Recovery worker reconciliation"]
    UserRetry --> Final["CONFIRMED 또는 RELEASED/EXPIRED"]
    Webhook --> Final
    Worker --> Final
```

## 3. WAS 한 대가 내려간 상황

```mermaid
sequenceDiagram
    autonumber
    participant U as 사용자
    participant T as Traefik LB
    participant A1 as WAS-1
    participant A2 as WAS-2
    participant R as Redis
    participant D as MySQL
    participant P as Mock PG

    Note over A1: WAS-1 장애 또는 종료
    U->>T: POST /bookings
    T->>T: health check로 WAS-1 제외
    T->>A2: 살아있는 WAS-2로 전달

    A2->>R: Redis Lua admission
    R-->>A2: admitted(seq)
    A2->>D: reservation 생성 + inventory guard
    D-->>A2: HELD
    A2->>P: confirm payment

    alt WAS-2가 PG 응답 전 정상 유지
        P-->>A2: success/failure/unknown
        A2->>D: 결과에 맞는 상태 전이
        A2-->>U: 최종 또는 pending 응답
    else 요청 처리 중 WAS-2도 장애
        P--x A2: 응답 유실 가능
        Note over D: durable state가 HELD 또는 PAYMENT_UNKNOWN으로 남음
        Note over A1,A2: 이후 살아있는 WAS의 recovery worker가 lease claim
        A1-->>D: 복구 후 worker 실행 가능
        A2-->>D: 복구 후 worker 실행 가능
        D-->>A1: 한 worker만 row claim 성공
        A1->>P: payment status query
        A1->>D: CONFIRMED 또는 RELEASED/EXPIRED 전이
    end
```

## 4. Redis 장애 상황

```mermaid
sequenceDiagram
    autonumber
    participant U as 사용자
    participant T as Traefik LB / Gateway
    participant A as Spring Boot WAS
    participant R as Redis
    participant D as MySQL
    participant P as Mock PG

    U->>T: POST /bookings
    T->>A: 요청 전달

    A->>R: Redis Lua admission
    R--x A: timeout / connection failure

    A->>A: sale_event_id를 DB_FALLBACK으로 전환<br/>같은 sale_event_id에서는 Redis gate로 복귀하지 않음
    A->>D: bounded DB admission<br/>candidate budget + short timeout + bulkhead

    alt DB admission budget 초과 또는 DB 보호 필요
        D-->>A: admission 거절 또는 timeout
        A-->>U: 빠른 실패 응답<br/>DB 보호
    else DB admission accepted
        D-->>A: db_admission_seq 발급 + candidate 기록
        A->>D: reservation 생성 시도<br/>inventory conditional guard
        D-->>A: HELD 또는 sold out

        alt reservation HELD
            A->>P: confirm payment
            P-->>A: success / failure / unknown
            A->>D: 결과에 맞는 상태 전이
            A-->>U: 확정/실패/확인 중 응답
        else inventory guard 실패
            A-->>U: 매진 응답
        end
    end
```

## 5. Recovery worker 흐름

```mermaid
sequenceDiagram
    autonumber
    participant S1 as WAS-1 Scheduler
    participant S2 as WAS-2 Scheduler
    participant D as MySQL
    participant P as Mock PG

    Note over S1,S2: 두 WAS 모두 같은 스케줄러를 가질 수 있음
    S1->>D: due PAYMENT_UNKNOWN row claim<br/>lease_owner/lease_until 갱신
    S2->>D: 같은 row claim 시도
    D-->>S1: claim success
    D-->>S2: claim failed 또는 다른 row

    S1->>P: payment status query

    alt PG status success
        P-->>S1: approved
        S1->>D: PAYMENT_UNKNOWN -> CONFIRMED<br/>reserved 감소, confirmed 증가
    else PG status failed/canceled
        P-->>S1: failed/canceled
        S1->>D: PAYMENT_UNKNOWN -> RELEASED/EXPIRED<br/>reserved 감소
    else still unknown
        P-->>S1: still unknown / timeout
        S1->>D: next_reconcile_at 갱신<br/>backoff + jitter, lease 해제
    end
```

## 6. 전체 상태 전이 요약

```mermaid
stateDiagram-v2
    [*] --> ADMITTED: Redis 또는 DB admission 통과
    ADMITTED --> HELD: MySQL inventory guard 성공
    ADMITTED --> REJECTED: admission 거절 또는 매진
    HELD --> CONFIRMED: PG confirm success
    HELD --> RELEASED: PG 명확한 실패
    HELD --> PAYMENT_UNKNOWN: PG timeout / 응답 유실
    PAYMENT_UNKNOWN --> CONFIRMED: webhook/user retry/worker가 성공 확인
    PAYMENT_UNKNOWN --> RELEASED: 실패 또는 취소 확인
    PAYMENT_UNKNOWN --> EXPIRED: reconciliation window 초과 후 정책에 따른 만료
    CONFIRMED --> [*]
    RELEASED --> [*]
    EXPIRED --> [*]
    REJECTED --> [*]
```
