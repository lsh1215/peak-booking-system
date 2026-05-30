# {시스템 이름} — Software Design Document

> **문서 목적**
> 이 프로젝트의 Software Design Document(SDD)를 채워나가는 작업 문서다. IEEE 1016-2009 학술 구조 + Atlassian 실무 가이드 + CMS 엔터프라이즈 항목 + Notion 모던 메타데이터를 통합한 형태로, **결정의 근거와 추적 가능성**을 남기는 데 목적이 있다.

---

## 0. Document Metadata

> _Why_
> 변경 이력과 책임자를 명시해 문서를 "팀 자산"으로 만든다. 메타데이터가 없는 SDD는 시간이 지나면 신뢰도를 잃는다.

| 항목 | 값 |
|---|---|
| Document Title | {시스템 이름} SDD |
| Version | 0.1 (Draft) |
| Status | Draft / Proposed / Accepted / Deprecated |
| Author(s) | {이름} |
| Reviewer(s) | {이름} |
| Last Updated | YYYY-MM-DD |
| Related Documents | {PRD, ADR-001, etc.} |

### 0.1 Revision History

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | YYYY-MM-DD | {이름} | Initial draft |

---

## 1. Introduction

> _Why_
> 독자가 본문을 읽기 전에 "이 문서가 무엇이고 왜 존재하는지"를 30초 안에 파악하게 한다. PRD가 "무엇을 만들 것인가"라면 SDD는 "어떻게 만들 것인가"의 시작점.

### 1.1 Purpose
> _What goes here_
> 이 SDD가 다루는 시스템 / 모듈, 작성 목적, 대상 독자 (예: backend 팀, SRE, 보안 검토자).

{이 문서는 ... 시스템의 설계를 기술한다. 대상 독자는 ...}

### 1.2 Scope
> _What goes here_
> 문서가 커버하는 범위. "이 SDD는 X를 다루며, Y는 별도 문서에서 다룬다."

- **In scope**: {모듈 / 컴포넌트 / 인터페이스}
- **Out of scope**: {별도 문서로 분리된 영역}

### 1.3 References
> _What goes here_
> PRD, ADR, 외부 표준, 관련 RFC.

- [PRD-{n}] {링크}
- [ADR-{n}] {링크}
- [{외부 표준}] {링크}

---

## 2. System Overview

> _Why_
> 본격 설계 진입 전 "시스템이 무엇을 하고 어디에 위치하는지" 2–3문단으로 압축. 다이어그램 1개 권장 (context diagram).

{본 시스템은 ... 역할을 수행한다. 외부적으로는 ... 시스템과 상호작용하며, 내부적으로는 ... 컴포넌트로 구성된다.}

```
┌─ External ─────────┐    ┌─ This System ─┐    ┌─ External ─┐
│ {업스트림}          │ →  │   {시스템명}    │ →  │ {다운스트림}│
└────────────────────┘    └────────────────┘    └────────────┘
```

---

## 3. Goals and Non-Goals

> _Why_
> Atlassian / Notion / Google 디자인 독의 공통 항목. **무엇을 하지 않을지 명시하는 것이 무엇을 할지 명시하는 것만큼 중요하다.**

### 3.1 Goals
> _What goes here_
> 측정 가능한 목표. "빠르게" 같은 형용사 대신 "p99 < 100ms"처럼.

- G-1: {예: API p99 latency < 100ms 달성}
- G-2: {예: 99.99% availability}
- G-3: {예: 2026년 Q3까지 첫 배포}

### 3.2 Non-Goals
> _What goes here_
> 명시적으로 다루지 않을 것. 스코프 크리프 방지.

- NG-1: {예: 멀티 리전 active-active — 단일 리전으로 시작}
- NG-2: {예: 자체 ML 추천 — 외부 API 사용}

---

## 4. Constraints

> _Why_
> 설계 자유도를 제한하는 외부 요인. Constraints는 "선택 사항이 아닌 것"이고 Non-Goals는 "선택했지만 안 하는 것" — 혼동 금지.

### 4.1 Technical Constraints
- {예: 회사 표준 스택은 Java 21 / Spring Boot 3 / PostgreSQL 16}
- {예: 레거시 인증 시스템과 SAML 연동 필수}

### 4.2 Organizational / Business Constraints
- {예: 예산 상한 $X / month}
- {예: 팀 규모 backend 2명 + frontend 1명}

### 4.3 Regulatory / Compliance Constraints
- {예: GDPR — EU 사용자 데이터는 EU 리전 보관}
- {예: PCI-DSS — 결제 데이터 직접 저장 금지}

---

## 5. System Architecture

> _Why_
> 시스템의 뼈대. 컴포넌트 분해, 통신 방식, 배포 토폴로지를 한눈에 보이게 한다. 다이어그램 없이는 의미 없음.

### 5.1 Architectural Style
{예: 마이크로서비스 (도메인별 분리, 독립 배포). 동기 통신은 gRPC, 비동기 이벤트는 Kafka.}

선택 근거: {왜 monolith가 아닌가, 왜 event-driven인가}

### 5.2 Component Diagram
```
{박스/화살표 다이어그램. Mermaid / ASCII / PlantUML 권장 — 이미지보다 diff 가능.}
```

### 5.3 Deployment Topology
- **Runtime**: {Kubernetes, ECS, Lambda, ...}
- **Region**: {us-east-1 single-region with multi-AZ}
- **Network**: {VPC / subnet / security group 개략}

---

## 6. Data Design

> _Why_
> 데이터 모델은 시스템의 척추. 스키마뿐 아니라 **데이터 라이프사이클**(생성 → 보관 → 삭제)까지 포함.

### 6.1 Data Model / ERD
```
{ER 다이어그램 또는 표. 핵심 엔티티, 관계, 카디널리티.}
```

### 6.2 Data Dictionary
| Entity | Field | Type | Constraint | Description |
|---|---|---|---|---|
| User | id | UUID | PK | |
| User | email | VARCHAR(255) | UNIQUE NOT NULL | |
| ... | | | | |

### 6.3 Data Lifecycle
- **Creation**: {언제, 누가, 어떤 트리거로}
- **Retention**: {보관 기간, 정책}
- **Archival**: {cold storage 이전 기준}
- **Deletion**: {hard delete vs soft delete, 주기}

### 6.4 Data Flow
```
{DFD 또는 sequence diagram. 주요 시나리오 1–3개.}
```

---

## 7. Component Design

> _Why_
> 각 모듈을 구현자가 그대로 코드로 옮길 수 있는 수준까지 명세. 추상적이면 가치 없음.

### 7.1 Component {Name}
- **Responsibility**: {한 문장}
- **Inputs**: {API / 이벤트 / 큐 메시지 형식}
- **Outputs**: {반환값 / 발행 이벤트}
- **Dependencies**: {다른 컴포넌트, 외부 서비스, DB}
- **Core Logic**: {핵심 알고리즘 / 상태 머신 / 의사 코드}

### 7.2 Component {Name 2}
{...}

---

## 8. Interface Design

> _Why_
> 컴포넌트의 외부 노출면. 한 번 공개되면 변경 비용이 큼 — SDD 단계에서 신중히.

### 8.1 External APIs
| Method | Path | Request | Response | Errors |
|---|---|---|---|---|
| `POST` | `/api/v1/...` | `{...}` | `200 {...}` | `400, 401, 409` |

### 8.2 Internal Service Interfaces
{gRPC proto / 내부 REST / 메시지 큐 토픽 + payload}

### 8.3 UI Flow (해당 시)
{wireframe, sequence, 상태 다이어그램}

---

## 9. Non-Functional Requirements

> _Why_
> ISO 25010 품질 모델 기반 분류. 인터뷰 스타일과 달리 **각 항목이 검증 가능한 acceptance criteria**를 가져야 한다.

| Category | Requirement | Acceptance Criteria |
|---|---|---|
| Performance | 응답 지연 | p99 < 100ms |
| Scalability | 처리량 | 12K req/s sustained |
| Availability | 가용성 | 99.99% (월 4분 다운타임) |
| Reliability | 데이터 내구성 | 11-9 durability |
| Security | 인증 | OAuth 2.1 + PKCE |
| Maintainability | 배포 빈도 | weekly canary |
| Observability | 추적성 | 100% request 분산 추적 |
| Portability | 환경 | dev / staging / prod 동일 image |

---

## 10. Cross-Cutting Concerns

> _Why_
> 어느 한 컴포넌트의 책임이 아니라 시스템 전체에 걸쳐 있는 관심사. 한 곳에 모아 두지 않으면 누락되기 쉽다.

### 10.1 Security
- 인증 / 인가 모델 (RBAC / ABAC)
- 비밀 관리 (KMS, Vault)
- 입력 검증 / 출력 인코딩
- 전송 암호화 (TLS), 저장 암호화 (at rest)

### 10.2 Observability
- Metrics (RED: Rate / Error / Duration, USE: Utilization / Saturation / Errors)
- Logs (구조화 JSON, correlation ID)
- Traces (OpenTelemetry, sampling 전략)
- Alerts (SLO 기반 burn-rate)

### 10.3 Resilience
- 재시도 / 지수 백오프 / jitter
- 서킷 브레이커
- Bulkhead / 격리
- Graceful degradation 시나리오

### 10.4 Privacy
- PII 식별 및 분류
- 데이터 최소화 (collection minimization)
- 사용자 권리(GDPR right-to-erasure 등) 처리 흐름

---

## 11. Architecture Decisions (ADR)

> _Why_
> Michael Nygard ADR 형식. **"왜 이렇게 했나"가 코드보다 빠르게 잊힌다.** 결정 1건당 1 ADR.

### ADR-001: {결정 제목}
- **Status**: Proposed / Accepted / Deprecated / Superseded by ADR-XXX
- **Context**: {어떤 상황과 제약 아래에서 결정해야 했는가}
- **Decision**: {무엇을 선택했는가}
- **Consequences**:
  - Positive: {얻는 것}
  - Negative: {잃는 것 / 새로 생기는 위험}
  - Neutral: {중립적 영향}

### ADR-002: {...}
{...}

---

## 12. Alternatives Considered

> _Why_
> 채택하지 않은 대안과 그 기각 이유. 6개월 뒤 누군가 "왜 X를 안 썼지?"라고 물을 때 답이 되는 섹션.

| Alternative | Pros | Cons | Why Rejected |
|---|---|---|---|
| {대안 1: monolith} | {배포 단순} | {팀 독립성 낮음} | {팀 4개로 곧 분리 예정} |
| {대안 2: DynamoDB} | {수평 확장} | {조인 / 트랜잭션 한계} | {강한 일관성 트랜잭션 필요} |

---

## 13. Risk Register (선택)

> _Why_
> 엔터프라이즈 SDD의 핵심. 각 위험에 등급과 완화 전략을 매핑.

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| R-1 | {DB failover 시 30초 다운타임} | Medium | High | {Patroni 자동 failover + read replica fallback} | {SRE} |
| R-2 | {써드파티 API rate limit 초과} | High | Medium | {로컬 캐시 + exponential backoff} | {Backend} |

---

## 14. Requirements Traceability (선택)

> _Why_
> IEEE 1016 핵심 항목. 요구사항 번호 ↔ 설계 섹션 매핑으로 커버리지를 증명.

| Req ID | Requirement | Design Section | Test Case |
|---|---|---|---|
| FR-1 | {기능 설명} | §7.1, §8.1 | TC-001 |
| NFR-1 | {p99 < 100ms} | §5, §10.2 | LT-005 |

---

## 15. Testing Strategy

> _Why_
> 무엇을 어떤 깊이로 검증할 것인지를 SDD에서 미리 합의. 구현 후 "그게 테스트 가능했나"를 막는다.

- **Unit**: {커버리지 목표, 핵심 모듈 우선순위}
- **Integration**: {DB / 외부 API mocking 정책, contract test}
- **E2E**: {핵심 사용자 플로우 N개}
- **Load / Performance**: {NFR 검증 시나리오 — k6 / Locust 스크립트}
- **Chaos / Failure Injection**: {network partition, pod kill, DB failover 시나리오}
- **Acceptance**: {PRD 수용 기준 → 자동화 매핑}

---

## 16. Rollout / Deployment Plan

> _Why_
> 인터뷰 스타일과 가장 큰 차이점. 시스템은 코드가 아니라 운영되는 동안에만 가치 — 배포 / 마이그레이션 / 롤백을 설계의 일부로.

### 16.1 Phased Rollout
- Phase 1: {dev 환경 검증 — Day 0}
- Phase 2: {internal canary 1% — Day 7}
- Phase 3: {staged rollout 10% → 50% → 100% — Day 14–21}

### 16.2 Feature Flags
| Flag | Default | Removal Criteria |
|---|---|---|
| `new_engine_enabled` | off | {2주간 100% 트래픽에서 안정 후 제거} |

### 16.3 Data Migration
- 마이그레이션 단계: {dual-write → backfill → cutover → cleanup}
- 롤백 가능 시점: {cutover 이전까지}

### 16.4 Rollback Plan
- 트리거: {error rate > 5% / 5min, p99 > 2× baseline}
- 절차: {flag off → traffic shift → 사후 분석}

---

## 17. Glossary

> _Why_
> 도메인 / 약어 통일. 문서 끝이 아니라 처음에 두는 팀도 많음.

| Term | Definition |
|---|---|
| {QPS} | Queries per second |
| {SLO} | Service Level Objective |
| {도메인 용어} | {정의} |

---

## 18. Appendix (선택)

- A. 다이어그램 원본 (PlantUML / Excalidraw 소스)
- B. 외부 참조 자료 / 벤치마크
- C. Capacity 추정 상세 계산
- D. 미해결 질문 (Open Questions)

---

## 부록: 작성 체크리스트

- [ ] **Goals**가 측정 가능한 숫자로 적혔는가
- [ ] **Non-Goals**와 **Constraints**를 분리했는가
- [ ] Architecture diagram이 텍스트 기반(diff 가능)인가
- [ ] Data Lifecycle (생성 → 삭제)이 명시되었는가
- [ ] **Component**가 구현자가 코드로 옮길 수준까지 구체적인가
- [ ] **NFR** 각 항목에 acceptance criteria가 있는가
- [ ] Cross-cutting concerns에 **Observability**가 포함되었는가
- [ ] 주요 결정마다 **ADR**이 1건씩 있는가
- [ ] **Alternatives Considered**에 기각 이유가 적혔는가
- [ ] **Rollout Plan**에 롤백 트리거가 있는가
- [ ] Revision History가 갱신되었는가

---

## 참고 자료

- [Bellevue College — SDD RoadTrip Example (IEEE 1016)](https://www.bellevuecollege.edu/wp-content/uploads/sites/135/2019/04/SDD_RoadTrip.pdf)
- [Atlassian — Software Design Document Guide](https://www.atlassian.com/work-management/knowledge-sharing/documentation/software-design-document)
- [CMS — System Design Document Template](https://www.cms.gov/files/document/sddpdf)
- [Notion — Design Document Template](https://www.notion.com/blog/design-document-template)
- [Design Docs at Google — Industrial Empathy](https://www.industrialempathy.com/posts/design-docs-at-google/)
- [Michael Nygard — Documenting Architecture Decisions](https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
- [IEEE 1016-2009](https://standards.ieee.org/ieee/1016/4502/)
- [ISO/IEC 25010 Quality Model](https://iso25000.com/index.php/en/iso-25000-standards/iso-25010)
