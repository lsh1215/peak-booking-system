# Booking 사용자 흐름 Mermaid

> 이 문서는 사용자의 예약 요청부터 결제 확정/복구까지의 주요 흐름을 Mermaid로 요약한다. 최종 결정의 원문은 `docs/decisions/DECISIONS.md`, 상세 설계 기준은 `docs/system-design/sdd.md`를 따른다.

## 전제

- 정상 admission은 Redis HA의 Lua script가 담당한다.
- Redis failover, `WAIT` timeout, min-replica 조건 불만족 시 새 admission은 DB fallback으로 우회하지 않고 `ADMISSION_TEMPORARILY_UNAVAILABLE + Retry-After`로 짧게 pause한다.
- 최종 재고 정합성은 MySQL inventory guard와 reservation 상태 전이로 보장한다.
- PG confirm timeout/unknown은 즉시 성공/실패로 확정하지 않고 `PAYMENT_UNKNOWN`으로 두되, 재고 점유는 `30s` deadline까지만 허용한다.
- Recovery worker는 기존 WAS 내부에서 작은 thread/batch/concurrency budget으로 실행되며, MySQL lease로 stale `HELD`와 `PAYMENT_UNKNOWN` 중복 처리를 막는다.
- 후순위 `WAITING_CANDIDATE`는 재고를 점유하지 않으며, 사용자-facing 대기 window는 최대 `60s`다.
- candidate pool은 sale event당 `30`으로 고정하며, 추가 tranche는 열지 않는다.
- `PAYMENT_UNKNOWN`이 `30s` 안에 확정되지 않으면 reservation은 `RELEASED/EXPIRED`로 닫고 다음 후보에게 판매 기회를 넘긴다.
- reservation release 이후에도 payment_attempt는 `5분` 동안 status/cancel reconciliation을 계속하며, 끝까지 불명확하면 payment-only `MANUAL_REVIEW_REQUIRED`로 전이한다.

## 1. 정상 상황

```mermaid
sequenceDiagram
    autonumber
    participant U as 사용자
    participant T as Traefik LB / Gateway
    participant A as Spring Boot WAS
    participant R as Redis HA Admission
    participant D as MySQL
    participant P as Mock PG

    U->>T: POST /bookings<br/>예약/결제 요청
    T->>T: route-level rate limit<br/>WAS 보호 목적
    T->>A: 요청 전달

    A->>A: booking_attempt_id / request_hash 확인
    A->>R: Lua admission<br/>duplicate check + seq 발급 + candidate 기록
    A->>R: 새 admission이면 WAIT 1 short timeout
    R-->>A: admitted(seq) 또는 rejected

    alt Redis admission rejected
        A-->>U: 매진/대기 후보 아님 응답
    else Redis admission admitted
        A->>D: reservation 생성 시도<br/>inventory conditional guard
        D-->>A: HELD / WAITING_CANDIDATE / rejected

        alt WAITING_CANDIDATE
            A-->>U: 대기 후보 응답<br/>최대 60s
        else rejected
            A-->>U: 매진 또는 후보 종료 응답
        else HELD reservation 생성
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
                A->>D: HELD -> PAYMENT_UNKNOWN<br/>30s inventory deadline + next_reconcile_at 설정
                D-->>A: unknown 기록 완료
                A-->>U: 결제 확인 중 응답<br/>deadline 이후 재고는 다음 후보로 이동 가능
            end
        end
    end
```

## 2. 사용자가 기다려야 하는 지점

```mermaid
flowchart TD
    Start["사용자가 예약/결제 요청"] --> Admission["Admission 통과 여부 확인"]
    Admission -->|거절| Reject["즉시 실패 응답"]
    Admission -->|통과| Hold["MySQL reservation HELD"]
    Admission -->|후순위 후보<br/>pool 30 안| Candidate["WAITING_CANDIDATE<br/>최대 60s"]
    Admission -->|pool 30 밖| Reject
    Candidate -->|60s 안에 release 발생| Hold
    Candidate -->|60s 초과| WaitExpired["대기 종료 응답"]
    Hold --> Confirm["PG confirm 호출"]
    Confirm -->|성공| Success["예약/결제 확정 응답"]
    Confirm -->|명확한 실패| Fail["결제 실패 응답 + reservation release"]
    Confirm -->|timeout / unknown| Pending["결제 확인 중 응답"]
    Pending --> Wait["사용자는 짧은 확인 상태를 기다림"]
    Pending -->|30s 안에 확정 안 됨| Release["reservation release<br/>다음 후보 승격 가능"]
    Wait --> UserRetry["POST /bookings replay<br/>멱등 재시도/상태 조회"]
    Wait --> Webhook["PG webhook/event 수신"]
    Wait --> Worker["Recovery worker reconciliation"]
    UserRetry --> Final["CONFIRMED / RELEASED"]
    Webhook --> Final
    Worker --> Final
    Release --> PayRecon["payment_attempt reconciliation<br/>cancel/refund/manual review"]
```

## 3. WAS 한 대가 내려간 상황

```mermaid
sequenceDiagram
    autonumber
    participant U as 사용자
    participant T as Traefik LB
    participant A1 as WAS-1
    participant A2 as WAS-2
    participant R as Redis HA
    participant D as MySQL
    participant P as Mock PG

    Note over A1: WAS-1 장애 또는 종료
    U->>T: POST /bookings
    T->>T: health check로 WAS-1 제외
    T->>A2: 살아있는 WAS-2로 전달

    A2->>R: Redis Lua admission + WAIT
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
        Note over A1,A2: 살아있는 WAS의 recovery worker가 lease claim<br/>30s deadline 안에 확정 못 하면 release
        A1-->>D: 복구 후 worker 실행 가능
        A2-->>D: 복구 후 worker 실행 가능
        D-->>A1: 한 worker만 row claim 성공
        A1->>P: payment status query
        A1->>D: deadline 안이면 CONFIRMED 가능<br/>deadline 초과 또는 실패면 RELEASED/EXPIRED
    end
```

## 4. Redis failover 상황

```mermaid
sequenceDiagram
    autonumber
    participant U as 사용자
    participant T as Traefik LB / Gateway
    participant A as Spring Boot WAS
    participant R as Redis HA
    participant D as MySQL

    U->>T: POST /bookings
    T->>A: 요청 전달

    A->>R: Redis Lua admission + WAIT
    R--x A: failover / timeout / replica ACK 부족

    A->>D: gate_mode를 REDIS_FAILOVER_PAUSED로 표시
    A-->>U: ADMISSION_TEMPORARILY_UNAVAILABLE<br/>Retry-After

    Note over A,D: failover 중 새 admission은 MySQL DB fallback으로 우회하지 않음
    Note over A,R: pause TTL 동안 반복 probe 억제

    A->>R: pause TTL 이후 half-open probe<br/>Redis write + WAIT
    alt probe 실패
        R--x A: timeout / ACK 부족
        A-->>U: Retry-After 유지
    else probe 성공
        R-->>A: write + WAIT success
        A->>D: gate_mode REDIS 복구
        A-->>U: 이후 요청부터 정상 admission 재개
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
    S1->>D: due HELD/PAYMENT_UNKNOWN row claim<br/>lease_owner/lease_token/lease_until 갱신
    S2->>D: 같은 row claim 시도
    D-->>S1: claim success
    D-->>S2: claim failed 또는 다른 row

    S1->>P: payment status query

    alt PG status success within inventory deadline
        P-->>S1: approved
        S1->>D: lease_token 일치 + deadline 안이면<br/>HELD/PAYMENT_UNKNOWN -> CONFIRMED
    else PG status failed/not found/expired
        P-->>S1: 명확한 실패/미승인/만료
        S1->>D: lease_token 일치 시<br/>HELD/PAYMENT_UNKNOWN -> RELEASED
    else PG status cancelable
        P-->>S1: 취소 가능한 승인/매입 전 상태
        S1->>P: cancel payment
        P-->>S1: cancel success
        S1->>D: lease_token 일치 시<br/>HELD/PAYMENT_UNKNOWN -> RELEASED
    else still unknown
        P-->>S1: still unknown / timeout
        alt inventory deadline not reached
            S1->>D: next_reconcile_at 갱신<br/>min(backoff, inventory deadline), lease 해제
        else inventory deadline reached
            S1->>D: reservation RELEASED/EXPIRED<br/>다음 후보 승격 가능
            S1->>P: 이후 payment cancel/status reconciliation 계속
        end
    else late success after reservation release
        P-->>S1: approved late
        S1->>P: cancel/refund 시도
        S1->>D: LATE_SUCCESS_CANCEL_PENDING 기록<br/>reservation은 CONFIRMED로 되살리지 않음
    end
```

## 6. 전체 상태 전이 요약

```mermaid
stateDiagram-v2
    [*] --> ADMITTED: Redis admission + MySQL admission ledger 통과
    [*] --> REDIS_FAILOVER_PAUSED: Redis failover / WAIT timeout
    REDIS_FAILOVER_PAUSED --> [*]: Retry-After 응답
    REDIS_FAILOVER_PAUSED --> ADMITTED: half-open probe 성공 후 재시도
    ADMITTED --> HELD: MySQL inventory guard 성공
    ADMITTED --> WAITING_CANDIDATE: candidate pool 30 내 후순위
    ADMITTED --> REJECTED: admission 거절 또는 매진
    WAITING_CANDIDATE --> HELD: 60s 안에 선순위 release
    WAITING_CANDIDATE --> WAITING_EXPIRED: 60s 초과
    HELD --> CONFIRMED: PG confirm success
    HELD --> RELEASED: PG 명확한 실패
    HELD --> PAYMENT_UNKNOWN: PG timeout / 응답 유실
    HELD --> RELEASED: 30s stale HELD expiry
    PAYMENT_UNKNOWN --> CONFIRMED: 30s 안에 성공 확인
    PAYMENT_UNKNOWN --> RELEASED: 실패 또는 취소 확인
    PAYMENT_UNKNOWN --> RELEASED: 30s inventory deadline 초과
    RELEASED --> PAYMENT_RECONCILIATION: payment 상태 계속 조회
    PAYMENT_RECONCILIATION --> LATE_SUCCESS_CANCEL_PENDING: 늦은 PG 성공 확인
    LATE_SUCCESS_CANCEL_PENDING --> CANCELLED_AFTER_RELEASE: cancel/refund 성공
    LATE_SUCCESS_CANCEL_PENDING --> PAYMENT_MANUAL_REVIEW_REQUIRED: cancel/refund 불명확
    PAYMENT_RECONCILIATION --> PAYMENT_MANUAL_REVIEW_REQUIRED: 5분 후에도 payment/cancel 불명확
    CONFIRMED --> [*]
    RELEASED --> [*]
    PAYMENT_MANUAL_REVIEW_REQUIRED --> [*]
    CANCELLED_AFTER_RELEASE --> [*]
    WAITING_EXPIRED --> [*]
    REJECTED --> [*]
```
