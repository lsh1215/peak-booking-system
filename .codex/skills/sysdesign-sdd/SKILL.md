---
name: sysdesign-sdd
description: >-
  Promote a filled mock-interview.md into a formal Software Design Document
  (sdd.md) following the IEEE 1016 / Atlassian / Google design-doc style.
  Activated when the user says they are ready to write the SDD. Reads the
  topic's mock-interview.md AND the topic's conversation log files (which
  capture the back-and-forth detail not preserved in the structured
  mock-interview), then asks the user the SDD-specific questions that
  mock-interview doesn't cover (Constraints, ADRs, Risk Register, Rollout,
  Testing strategy). Writes sdd.md incrementally as decisions land.
triggers:
  - "SDD 작성"
  - "sdd 작성"
  - "sdd 만들"
  - "sdd로 넘어가"
  - "SDD로 넘어가"
  - "정식 설계 문서"
  - "정식 설계 문서 만들"
  - "software design document"
  - "설계 문서 정리"
  - "설계 문서 작성"
  - "design document 작성"
  - "design doc 만들"
allowed-tools: Read, Write, Edit, Glob, Grep, Bash
---

# sysdesign-sdd — Phase 5 (Formal SDD)

You are promoting a working mock-interview into a formal SDD that another
engineer (or future-you) could pick up six months later. The mock-interview
captures decisions; the SDD captures **decisions + their rationale + risks +
rollout + traceability**.

## Operating procedure

### Step 0. Identify active topic

```bash
TOPIC=$(cat .omx/state/active-sysdesign-topic.txt 2>/dev/null)
```

If empty → ask user which topic. If non-empty, confirm: "현재 활성 토픽:
`<TOPIC>`. 이 토픽의 SDD 작성한다. 맞지?"

### Step 1. Inventory inputs

For the active topic, locate and read **in this order**:

1. `<TOPIC>/System-Design-Document/mock-interview.md` — primary structured input.
   If missing, abort: "mock-interview.md가 없어. 먼저 sysdesign-design으로 채우자."

2. **Conversation logs** — `<TOPIC>/conversation-log/*.log` (glob all available).
   The retention policy means at most ~14 days of history; report what's available:

   ```bash
   ls -la "$TOPIC/conversation-log/" 2>/dev/null
   ```

   Tell user: "사용 가능한 로그: {N개 파일, {oldest_date} ~ {newest_date}}.
   이 범위 외 작업이 더 있었다면 알려줘 — 백업본 위치 등."

3. `docs/system-design/sdd.md` — the project SDD skeleton and current working
   structure.

### Step 2. Bootstrap sdd.md

If `<TOPIC>/System-Design-Document/sdd.md` does NOT exist, copy the project
working SDD skeleton:

```bash
cp docs/system-design/sdd.md \
   "$TOPIC/System-Design-Document/sdd.md"
```

If it exists, the user is **resuming** an SDD draft — read its current state
and pick up from the first un-filled `{...}` placeholder.

### Step 3. Migrate from mock-interview.md (auto-fill what you can)

Read mock-interview.md and conversation logs together. **Bulk-fill** the SDD
sections that have a 1:1 source:

| SDD section | Source |
|---|---|
| §1.1 Purpose / §1.2 Scope | mock-interview §1 (Functional + Out-of-Scope) |
| §2 System Overview | mock-interview §2.4 (Architecture Diagram) + intro paragraph from logs |
| §3.1 Goals | mock-interview §1.2 (NFRs as quantified goals — "p99 < 100ms" → "G-1: API p99 latency < 100ms") |
| §5 System Architecture | mock-interview §2.4 + logs (architectural style: monolith vs microservices — infer from logs or ask) |
| §6 Data Design | mock-interview §2.2 (Core Entities) + §3.1 (DB choice + sharding + replication) |
| §7 Component Design | mock-interview §3.x (each component) — flesh out Inputs / Outputs / Dependencies / Core Logic |
| §8 Interface Design | mock-interview §2.3 (API Contract) — add Request/Response/Errors columns |
| §9 NFRs | mock-interview §1.2 — add Acceptance Criteria column |
| §10 Cross-Cutting | mock-interview §3.5 / §3.6 / §3.7 (Failure Modes / Monitoring / Security) |

After bulk fill, surface to user: "기본 골격 채웠어. 이제 SDD에만 있는 항목 차례로 물어볼게."

### Step 4. Ask SDD-only questions (one section at a time)

Walk the user through the SDD-specific sections. **Don't dump 20 questions at
once** — one section per turn, wait for answer, fill, move on.

Order (matches `software-design-document-style.md`):

1. **§3.2 Non-Goals** — "이 SDD에서 명시적으로 안 다룰 거 뭐가 있어? (예: 멀티
   리전, 자체 ML)"
2. **§4 Constraints** — Technical / Organizational / Regulatory
   - "회사 표준 스택 강제? (예: Java 21, Spring Boot)"
   - "팀 규모 / 예산 상한?"
   - "GDPR / PCI / HIPAA 같은 규제 적용?"
3. **§5.3 Deployment Topology** — "Runtime은? K8s? ECS? Lambda? 단일 리전?"
4. **§6.3 Data Lifecycle** — Creation / Retention / Archival / Deletion 4단계
5. **§11 ADRs** — 가장 중요한 결정 3–5개. mock-interview에서 "선택 근거"가 적힌
   곳을 ADR로 변환하자고 제안:
   - "mock-interview에서 'PostgreSQL (강한 일관성 + 단순 access pattern)' 같은
      결정이 있었어. 이거 ADR-001로 만들까? Status / Context / Decision /
      Consequences 형식으로 정리할게."
6. **§12 Alternatives Considered** — 거절된 대안들. 로그에서 후보 추출 가능한
   경우 자동 채우고 사용자 확인.
7. **§13 Risk Register** (선택) — "운영 위험 식별할까? (likelihood/impact/mitigation/owner)"
8. **§14 Requirements Traceability** (선택) — FR/NFR ↔ §7/§8 + Test Case ID 매핑
9. **§15 Testing Strategy** — Unit / Integration / E2E / Load / Chaos / Acceptance.
   sysdesign-impl 단계에서 어떤 테스트만 우선 자동화할지도 같이 정함.
10. **§16 Rollout** — Phased rollout / Feature flags / Data migration / Rollback
    트리거. **단순 연습 프로젝트면 "skip"으로 합의 가능 — 사용자 의사 확인.**
11. **§17 Glossary** — 도메인 약어 / 용어. 로그에서 빈출 약어 자동 추출 후 정의 요청.

### Step 5. Update Revision History

매 turn 끝에 §0.1 Revision History 업데이트:

```
| 0.1 | 2026-05-04 | (사용자 이름 또는 'collab') | Initial draft from mock-interview migration |
| 0.2 | 2026-05-04 | collab | ADR-001 to ADR-003 added |
| ... |
```

### Step 6. Sign-off

When all sections (or all the sections the user wants) are filled, run the SDD
checklist from `software-design-document-style.md` 부록 and report:

> "✓ SDD 초안 완료. `<TOPIC>/System-Design-Document/sdd.md`. 부록 체크리스트
>  결과:
>  - [x] Goals 측정 가능
>  - [x] Non-Goals/Constraints 분리
>  - [ ] Risk Register — skipped (사용자 합의)
>  - ...
>
>  검토해서 수정/추가하고 싶은 거 있으면 알려줘. 구현으로 넘어가려면 '구현하자'
>  / '최소 구현 만들자' 라고 하면 sysdesign-impl로 진입."

## Hard rules

| Rule | Why |
|---|---|
| Bulk-fill first (Step 3), then ask only what mock-interview doesn't have. | 사용자가 같은 정보 두 번 말하면 안 됨. |
| One SDD-only section per turn (Step 4). | Q 20개 한 번에 던지면 답변 품질 떨어짐. |
| Always check log files for context — they have the rationale that mock-interview's bullet form lost. | mock-interview는 "결정 결과" / 로그는 "왜 그렇게 결정했는지". |
| Update §0.1 Revision History every turn. | SDD가 팀 자산이 되려면 변경 추적 필수. |
| Never reduce mock-interview content. SDD is **augmentation**. | Loss of decisions = bug. |
| If a log file referenced earlier is now missing (3-day catchall window passed before topic was set), surface the gap explicitly. | User must know what's reconstructed vs documented. |
| ADRs cite mock-interview turn or log timestamp as source. | Traceability. |
