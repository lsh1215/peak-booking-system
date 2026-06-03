# AI Usage Disclosure

이 문서는 프로젝트에서 AI를 어디에 사용했고, 사람이 어디까지 검증했는지 공개 가능한 수준으로 요약한다.

## Usage Summary

| Area | AI Used For | Human Verification |
|---|---|---|
| Requirements整理 | 공개 가능한 요구사항 문서 구조화, 민감 맥락 제거, 요구사항 재분류 | 원문 대비 누락/왜곡 여부를 사람이 확인한다. |
| Architecture | Redis admission, DB final consistency, idempotency, fallback, resilience 대안 정리 | 최종 선택은 `docs/decisions/DECISIONS.md`에서 사람이 검토하고 확정한다. |
| Research | Redis/MySQL/PostgreSQL/idempotency/resilience 관련 출처 기반 노트 초안 작성 | 출처 링크와 실제 공식 문서 내용이 주장과 맞는지 사람이 확인한다. |
| Testing | 장애, 경쟁, 중복, retry storm 시나리오 도출 및 테스트 우선순위 제안 | 실행 가능한 테스트 코드와 실패/성공 결과를 사람이 확인한다. |
| Review | oversell, undersell, Redis 장애, 결제 실패, retry storm에 대한 adversarial critique | 지적 사항 중 실제 설계에 반영할 항목을 사람이 선택한다. |
| Documentation | 대화 로그, 프롬프트 로그, 의사결정 문서 초안 유지 | 공개 전 개인정보, 조직 식별 정보, 과장 표현을 사람이 제거한다. |

## Current Verification Boundary

- AI 출력은 설계 보조 자료이며, 최종 요구사항/설계/테스트 결과의 근거로 단독 사용하지 않는다.
- 공식 문서 또는 실행 결과로 확인하지 않은 주장은 `Unverified` 또는 `Needs validation`으로 표시한다.
- 자동 기록되는 `conversation-log.md`와 `prompt-log.md`는 세션별 작업 흔적을 남기기 위한 partial/curated log다. 공개 제출 전에는 사람이 민감 정보, 중복, 오래된 판단을 검토한다.
- 여러 Codex 세션이 동시에 실행될 수 있으므로 로그 훅은 `.ai-log.lock`으로 append를 직렬화한다. 그래도 섹션 순서는 실제 대화 시간과 약간 다를 수 있어 timestamp/session id를 기준으로 검토한다.

## Review Checklist Before Public Submission

- [ ] 원문 요구사항과 `docs/requirements.md`가 충돌하지 않는다.
- [ ] `docs/decisions/DECISIONS.md`의 각 결정에 대안과 기각 이유가 있다.
- [ ] `docs/research/source-backed-research-note.md`의 핵심 주장은 출처 링크가 있다.
- [ ] `docs/testing/test-first-scenarios.md`의 테스트가 실제 코드/결과와 연결된다.
- [ ] `docs/reviews/adversarial-review.md`의 주요 공격 포인트가 처리되었거나 명시적으로 수용되었다.
- [ ] 자동 로그에서 공개하면 안 되는 민감 정보가 제거되었다.
