# Next Session Prompt

아래 프롬프트를 다음 Codex 세션 시작 메시지로 붙여 넣는다.

```md
우리는 `/Users/leesanghun/My_Project/peak-booking-system`에서 `00시`에 오픈되는 `10개 한정` 초특가 숙소 상품의 선착순 예약/결제 backend를 설계하고 있다. 앞으로 나는 설계 결정을 너와 티키타카하면서 확정할 것이다. 기술 결정의 최종 권한자는 나다. 요구사항에 없는 내용을 네가 임의로 확정하지 말고, 후보/트레이드오프/권장안을 분리해서 제시해줘.

먼저 아래 순서로 프로젝트를 파악해줘.

1. `AGENTS.md`
   - 프로젝트 운영 규칙, 불변식, 커밋/테스트/문서화 규칙을 확인한다.
   - `docs/ai/conversation-log.md`, `docs/ai/prompt-log.md`는 자동 관리 로그이므로 직접 편집하지 않는다.
2. `docs/requirements.md`
   - 현재 원문 기반 요구사항을 최우선 source of truth로 읽는다.
   - 특히 `10개 한정`, `2대 이상 app server`, `50 TPS -> 500~1000 TPS for 1~5분`, `Scale-up/out 제한`, `Y페이/Y포인트`, `실제 PG 생략 + Mock PG 흐름`, `인증/로그인 보안 제외`를 확인한다.
3. `docs/decisions/DECISIONS.md`
   - DEC-000은 이미 Accepted다: Java 21, Spring Boot 3.x, MySQL 8, Redis, k6, LGTM.
   - Open인 DEC-001~008만 앞으로 나와 함께 결정한다.
4. `docs/research/source-backed-research-note.md`
   - Shopify, AWS, Cloudflare, Stripe, Toss Payments, PortOne 공식/회사 자료를 근거로 어떤 주장이 source-backed인지 확인한다.
5. `docs/testing/test-first-scenarios.md`
   - 구현 전에 고정해야 할 경쟁/중복/장애/부하 테스트 후보를 확인한다.
6. `docs/system-design/mock-interview.md`
   - 쉬운 설계 설명 문서다. 대화 중 결정이 생기면 이 문서도 사용자가 이해하기 쉽게 갱신한다.
7. `docs/system-design/sdd.md`
   - 상세 설계 문서다. 실제 구현자가 참고할 수 있도록 결정/근거/리스크/추적성을 정리한다.

현재 결정해야 할 핵심 쟁점은 다음이다.

1. DEC-001 Stock model and fairness policy
   - 재고 수량은 `10개`로 확정이다.
   - 아직 결정할 것: "모든 사용자에게 동등한 확률"을 FIFO, random, first-attempt timestamp, 사용자당 제한 중 무엇으로 테스트 가능하게 정의할지.
   - 주의: 사용자당 1회 예약 제한은 현재 요구사항에 직접 쓰여 있지 않으므로 후보로만 다룬다.

2. DEC-002 Redis failure fallback policy
   - Redis 장애 시 Booking write path를 fail-closed로 막을지, bounded DB fallback을 허용할지, checkout-only/read-only degraded mode로 둘지 결정해야 한다.
   - unlimited DB fallback은 DB 붕괴와 공정성 훼손 위험이 크므로 매우 조심해서 다룬다.

3. DEC-003 RDB inventory correctness guard
   - MySQL 8이 baseline이다.
   - count row conditional update, per-unit inventory row, reservation table + expiry/release 중 어떤 모델로 `confirmed <= 10`과 결제 실패 후 재고 회복을 보장할지 결정해야 한다.

4. DEC-004 Idempotency policy
   - 짧은 간격의 연속 결제 요청이 중복 처리되지 않아야 한다.
   - client-provided idempotency key, server-generated attempt token, request hash comparison, stored response replay, processing 상태 복구, TTL을 정해야 한다.

5. DEC-005 Payment failure and PG abstraction
   - 실제 PG 연동은 생략하지만 Mock PG는 실제 PG처럼 `confirm`, `query/status`, `cancel`, `status changed webhook/event`, `timeout/unknown`을 표현하는 후보가 문서화되어 있다.
   - 결정할 것: DB transaction 안에서 PG mock을 호출할지, DB state 저장 후 PG interface를 분리할지, timeout/unknown을 recovery/reconciliation 대상으로 볼지.
   - recovery worker/scheduler 도입 여부도 여기서 결정해야 한다.

6. DEC-006 Payment method extensibility
   - 요구사항: 신용카드, Y페이, Y포인트 지원. 허용 조합은 `신용카드 + Y포인트`, `Y페이 + Y포인트`. 신용카드와 Y페이는 혼용 불가.
   - 결정할 것: strategy, combination policy object, registry 등으로 Booking API 핵심 로직 수정을 최소화할 구조.

7. DEC-007 HA, load shedding, and backpressure
   - 인프라 증설이 제한된 상태에서 `500~1000 TPS` 피크를 받아야 한다.
   - 결정할 것: 시스템 붕괴 기준, fast reject/load shedding 기준, pool/bulkhead/timeout/retry cap.

8. DEC-008 Test, load-test, and observability strategy
   - k6/LGTM은 이미 Accepted다.
   - 결정할 것: 50 TPS baseline, 500~1000 TPS peak, Redis 장애, PG timeout, duplicate click 등 시나리오와 pass/fail 기준, LGTM에서 볼 지표.

대화 방식:

- 내가 특정 쟁점을 고르면, 먼저 요구사항에서 확정된 사실과 미결정 사항을 분리해줘.
- 그 다음 선택지를 2~4개로 정리하고, 각 선택지의 장점/단점/테스트 방법/문서 반영 위치를 말해줘.
- 네 권장안은 줘도 되지만, 최종 결정으로 쓰기 전에는 반드시 내가 동의해야 한다.
- 내가 결정하면 `docs/decisions/DECISIONS.md`, `docs/system-design/mock-interview.md`, `docs/system-design/sdd.md`, 필요하면 `docs/testing/test-first-scenarios.md`와 `docs/research/source-backed-research-note.md`를 즉시 갱신해줘.
- 다이어그램은 Mermaid로 유지해줘.
- 설계 문서 작업 전후에는 `git status --short --branch`를 확인하고, 자동 로그 파일은 스테이징하지 마.
```
