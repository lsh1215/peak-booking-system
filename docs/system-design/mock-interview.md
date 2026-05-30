# {시스템 이름} — System Design (Mock Interview Style)

> **문서 목적**
> 시스템 디자인 모의 인터뷰(45–60분) 흐름으로 이 프로젝트의 설계 판단을 채워나가는 작업 문서다. ByteByteGo / Hello Interview / Donne Martin system-design-primer가 공통적으로 권장하는 3단계(Clarifying → High Level → Drill Down) + 선택적 Wrap-Up으로 구성한다.

---

## 0. 메타데이터 (선택)

| 항목 | 값 |
|---|---|
| Topic | {예: URL Shortener} |
| Interviewer Persona | {예: Big Tech L5 / Staff Engineer} |
| Time Budget | 45 / 60 min |
| Date | YYYY-MM-DD |

---

## 1. Clarifying Questions

> _Why this section exists_
> 문제 범위를 좁히고 인터뷰어와 공통 전제를 맞춘다. **너무 빨리 설계로 넘어가는 것이 가장 흔한 실패 원인.** 5분 이내, 3–5개 질문이 적정.

### 1.1 Functional Requirements
> _What goes here_
> "유저는 …할 수 있어야 한다" 형태로 핵심 기능 3개에 집중. 부가 기능은 나중에.

- [ ] FR-1: {예: 긴 URL을 짧은 URL로 변환할 수 있다}
- [ ] FR-2: {예: 짧은 URL로 접속하면 원본으로 리다이렉트된다}
- [ ] FR-3: {예: 클릭 통계를 조회할 수 있다}

### 1.2 Non-Functional Requirements (정량화 필수)
> _What goes here_
> "낮은 지연" 같은 형용사가 아니라 숫자로. availability / consistency / latency / scalability / durability 중 시스템에 맞는 top 3–5개를 골라 수치 명시.

- [ ] NFR-1: {예: 리다이렉트 p99 < 100ms}
- [ ] NFR-2: {예: 99.99% availability (월 4분 다운타임 허용)}
- [ ] NFR-3: {예: write-after-read consistency 불필요, eventual consistency OK}

### 1.3 Scale & Constraints
> _What goes here_
> 트래픽 규모와 데이터 특성. 이 숫자가 뒤에 나올 추정·샤딩·캐시 결정의 근거가 된다.

| 항목 | 값 | 근거 |
|---|---|---|
| DAU | {1M} | {가정} |
| 읽기:쓰기 비율 | {100:1} | {링크 1개 만들고 100번 클릭} |
| 데이터 보존 기간 | {5년} | |
| 글로벌 여부 | {US-only / Global} | |
| 평균 페이로드 크기 | {200B 원본 URL} | |

### 1.4 Out of Scope (명시적 제외)
> _Why_
> 인터뷰어와 합의해 시간을 사야 한다. "인증/결제/관리자 도구는 다음 단계로 두자"고 못 박는다.

- [ ] {예: 사용자 인증 — 이미 OAuth로 해결됐다고 가정}
- [ ] {예: 결제 / 요금제}
- [ ] {예: 관리자 대시보드}

---

## 2. High Level Design

> _Why this section exists_
> 전체 시스템의 뼈대를 스케치하고 인터뷰어의 동의를 얻는다. 박스/화살표가 없으면 안 된다 — 말로만 설명하지 말 것.

### 2.1 Back-of-the-Envelope Estimation
> _What goes here_
> 설계 결정을 정당화하는 숫자. 5분 초과 금지. 단위 표기 필수.
>
> 공식
> - QPS = (DAU × 사용자당 일일 요청 수) ÷ 86,400
> - Peak QPS ≈ 평균 × 10
> - Storage = 레코드 수 × 레코드 크기 × 복제 계수
> - Bandwidth = QPS × 평균 응답 크기 × 8 (bps)

| Metric | 계산 | 값 |
|---|---|---|
| Write QPS (avg) | {1M DAU × 1 write/day ÷ 86,400} | {≈ 12} |
| Write QPS (peak) | {avg × 10} | {≈ 120} |
| Read QPS (peak) | {write × 100} | {≈ 12,000} |
| 5년 누적 레코드 수 | {12 × 86,400 × 365 × 5} | {≈ 1.9B} |
| Storage | {1.9B × 500B × 3} | {≈ 2.8 TB} |
| Egress Bandwidth | {12,000 × 1KB × 8} | {≈ 96 Mbps} |

### 2.2 Core Entities (Data Model 초안)
> _What goes here_
> 핵심 명사만 나열. 컬럼은 5개 이내로 시작. 풀 스키마는 Drill Down에서.

- **User**: id, email, created_at
- **{Entity2}**: ...
- **{Entity3}**: ...

### 2.3 API Contract
> _What goes here_
> Functional Requirement 1:1 매핑. REST 기본. 인증 토큰은 헤더.

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `POST` | `/api/v1/{resource}` | {FR-1} | Bearer |
| `GET` | `/api/v1/{resource}/{id}` | {FR-2} | - |
| `GET` | `/api/v1/{resource}/{id}/stats` | {FR-3} | Bearer |

### 2.4 Architecture Diagram
> _What goes here_
> 클라이언트 → LB → API → 캐시/DB/큐의 박스+화살표. ASCII가 가장 빠르다.

```
┌────────┐   ┌─────┐   ┌──────────┐   ┌────────┐
│ Client │ → │ LB  │ → │ API Tier │ → │ Cache  │
└────────┘   └─────┘   └────┬─────┘   └────┬───┘
                            │              │ miss
                            ▼              ▼
                       ┌────────┐    ┌─────────┐
                       │  Queue │    │  Primary│
                       └───┬────┘    │   DB    │
                           ▼         └─────────┘
                       ┌────────┐
                       │ Worker │
                       └────────┘
```

---

## 3. Drill Down

> _Why this section exists_
> Non-functional requirement 중 High Level이 아직 충족 못한 것을 메우는 단계. **시니어는 인터뷰어 힌트를 기다리지 않고 직접 병목을 찾아 제안한다.** 모든 영역을 고르게 파지 말고 가장 중요한 1–2개를 깊게.

### 3.1 Database
> _Decisions to make_
> SQL vs NoSQL, 샤딩 전략, 복제 토폴로지, 인덱스, 파티셔닝.

- **선택**: {예: PostgreSQL (강한 일관성 + 단순한 access pattern)}
- **선택 근거**: {access pattern, 트랜잭션 필요 여부, 스키마 진화 빈도}
- **샤딩 키**: {예: hash(short_code) — 핫스팟 방지}
- **복제**: {예: 1 primary + 2 read replica, async replication}
- **인덱스**: {예: short_code unique, user_id + created_at composite}

### 3.2 Cache
> _Decisions to make_
> 어디에(앱 / CDN / DB), 무엇을(자주 읽고 안 바뀌는 것), 어떤 전략(cache-aside / write-through / write-around), eviction(LRU + TTL).

- **레이어**: {예: Redis 클러스터 (앱 레이어), CDN (정적 자산)}
- **캐싱 대상**: {예: short_code → original_url 매핑, 사용자 통계 집계}
- **전략**: {예: cache-aside (lazy load), TTL 24h}
- **Eviction**: {예: LRU + TTL}
- **Invalidation**: {예: 원본 URL은 immutable이므로 invalidation 불필요}

### 3.3 Load Balancer
> _Decisions to make_
> L4(빠름) vs L7(라우팅 규칙), sticky session 필요성, health check.

- **계층**: {L4 / L7}
- **알고리즘**: {round-robin / least-conn / consistent hash}
- **Sticky session**: {불필요 — stateless 설계}
- **Health check**: {`/healthz` 200, 5s 간격}

### 3.4 Bottlenecks & Mitigations
> _Decisions to make_
> 어디가 먼저 깨질지 식별하고 완화책 제시.

| Bottleneck | 식별 근거 | 완화 |
|---|---|---|
| {DB 읽기 과부하} | {12K read QPS, 단일 노드 한계} | {Redis 캐시 + read replica} |
| {핫 short_code} | {바이럴 링크는 1개가 전체 트래픽 대부분} | {consistent hashing + 노드별 로컬 캐시} |
| {ID 생성 경합} | {단일 시퀀스} | {snowflake / base62 random + 충돌 재시도} |

### 3.5 Failure Modes
> _Decisions to make_
> SPOF, 장애 전파, graceful degradation.

- **SPOF**: {예: primary DB → 자동 failover with Patroni}
- **Cache 다운**: {예: DB 직접 조회 fallback, latency 증가는 감수}
- **재시도**: {지수 백오프 + jitter, 최대 3회}
- **서킷 브레이커**: {downstream 5xx 50% 초과 시 30s open}

### 3.6 Monitoring & Observability
> _Decisions to make_
> 무엇을 보고 무엇에 알람을 걸 것인가.

- **Metrics**: QPS, p50/p99 latency, 에러율, 캐시 hit ratio, DB connection pool
- **Tracing**: 분산 추적 (OpenTelemetry) — 리다이렉트 → DB 조회까지
- **Alerts**: p99 > 200ms / 5min, error rate > 1% / 5min, cache hit < 80%
- **Logs**: 구조화 JSON, request_id correlation

### 3.7 Security (선택)
> _Decisions to make_
> 인증/인가, rate limiting, 입력 검증, abuse 방지.

- {예: per-user rate limit 100 req/min, per-IP 1000 req/min}
- {예: malicious URL blocklist (Google Safe Browsing)}
- {예: HTTPS only, HSTS}

---

## 4. Wrap-Up

> _Why this section exists_
> Alex Xu 4단계의 마지막. 3–5분 이내로 압축.

### 4.1 트레이드오프 요약
- {선택한 길과 선택하지 않은 길, 그 이유 1줄씩}

### 4.2 운영 고려사항
- {배포 전략, 마이그레이션, 롤백}

### 4.3 다음 단계 / 확장 방향
- {예: 글로벌 확장 시 멀티 리전 + GeoDNS}
- {예: 분석 파이프라인 → 데이터 웨어하우스}
- {예: ML 기반 abuse detection}

---

## 부록: 자주 빠뜨리는 것 체크리스트

- [ ] Non-functional requirement를 **숫자로** 적었는가
- [ ] QPS 추정에 **peak 배수**(×10)를 곱했는가
- [ ] Architecture diagram에 **데이터 흐름 방향**이 있는가
- [ ] DB 선택을 **access pattern**으로 정당화했는가
- [ ] Cache의 **eviction 정책**을 명시했는가
- [ ] **SPOF**를 적어도 1개 식별했는가
- [ ] 모니터링에 **알람 임계값**이 있는가
- [ ] Wrap-up에서 **트레이드오프**를 1번 더 환기했는가

---

## 참고 자료

- [ByteByteGo — A Framework for System Design Interviews](https://bytebytego.com/courses/system-design-interview/a-framework-for-system-design-interviews)
- [Hello Interview — Delivery Framework](https://www.hellointerview.com/learn/system-design/in-a-hurry/delivery)
- [Donne Martin — system-design-primer](https://github.com/donnemartin/system-design-primer)
- [Avinash Billakurthi — Designing a Scalable URL Shortener](https://www.linkedin.com/pulse/designing-scalable-efficient-url-shortener-system-avinash-billakurthi-kwroe/)
- [interviewing.io — 3-Step Framework](https://interviewing.io/guides/system-design-interview/part-three)
