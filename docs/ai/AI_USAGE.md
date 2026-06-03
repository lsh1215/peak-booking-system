# AI Usage Disclosure

이 문서는 이 프로젝트에서 AI를 어떻게 활용했는지 공개 가능한 수준으로 정리한 disclosure다. 핵심 목적은 AI를 단순 코드 생성 도구로 사용한 것이 아니라, 요구사항 분석, 설계 검증, 구현 보조, 테스트 설계, 비판적 리뷰까지 이어지는 구조화된 workflow로 활용했음을 보여주는 데 있다.

## Summary

이 프로젝트에서 AI는 설계 파트너, 비판적 리뷰어, 구현 보조자, 테스트 시나리오 생성기, 문서 정리 보조자로 사용됐다. 특히 제한 수량 `10`개, 자정 피크 `500~1000 TPS`, Redis 장애, DB 압력, 중복 결제, 결제 실패라는 복합 요구사항을 먼저 분해한 뒤 SDD, Decisions, 테스트 시나리오, 구현 순서로 진행했다.

가장 중요한 활용 방식은 plan-first workflow였다. 구현을 먼저 만든 뒤 문서를 맞춘 것이 아니라, 설계 문서와 의사결정 문서에서 불변식, 실패 모드, 대안, 비용 대비 효과를 먼저 정리하고, 사람이 승인한 선택지만 코드와 테스트로 옮겼다.

## Tools And Workflows

| Tool / Workflow | Used For | Value |
|---|---|---|
| Codex | 저장소 탐색, Spring Boot 구현, 테스트 실행, 문서 수정, diff 검토 | 설계 문서와 실제 구현 사이의 간극을 빠르게 줄이고, 반복 검증을 자동화했다. |
| OMX / oh-my-codex | plan, critic, code-review, adversarial review 성격의 보조 평가 | 한 관점의 답변에 머무르지 않고, 설계자/비판자/리뷰어 관점을 분리해 품질을 높였다. |
| Plan-first workflow | 요구사항 분해, SDD, Decisions, risk register, test-first scenarios 작성 | 구현 전에 문제 구조와 선택지를 명확히 해 재작업 비용을 줄였다. |
| First-principles review | 요구사항을 불변식, 가정, 증거, trade-off로 재분해 | “그럴듯한 구조”가 아니라 실제 요구사항을 만족하는 구조인지 점검했다. |
| Code review / adversarial review | oversell, underfill, Redis outage, duplicate payment, retry storm, DB pressure 공격 | 정상 흐름보다 실패 흐름을 먼저 검토해 설계의 방어력을 높였다. |
| Test-first prompting | 장애/경쟁/중복/결제 실패 시나리오 도출 | 구현 전에 검증해야 할 failure mode를 명확히 했다. |
| k6, Gradle, kustomize | 부하 테스트, 단위/통합 테스트, Kubernetes manifest 검증 | 설계 주장을 실행 가능한 검증 결과와 연결했다. |

## Plan-First Workflow

AI 활용은 단발성 prompt-response가 아니라 단계별 workflow로 진행했다.

1. 요구사항을 불변식과 failure mode로 분해했다.
2. SDD와 Decisions 문서에서 주요 기술 쟁점을 먼저 정리했다.
3. Redis, MySQL, Traefik, queue, payment recovery 같은 선택지를 대안별로 비교했다.
4. 사람이 선택한 방향만 구현 대상으로 확정했다.
5. 구현 후 단위/통합 테스트, k6 결과, 문서 리뷰로 다시 검증했다.
6. AI critic과 code review를 반복 실행해 설계와 구현의 빈틈을 점검했다.

이 방식은 AI를 “빠르게 코드를 뽑는 도구”가 아니라 “설계 사고를 확장하고 검증 루프를 촘촘하게 만드는 도구”로 사용한 사례다.

## How AI Improved The Design

### Requirements Structuring

AI는 요구사항을 기능 목록이 아니라 시스템 불변식으로 재구성하는 데 도움을 줬다.

- confirmed booking은 target product 기준 `10`개를 넘지 않아야 한다.
- Redis 장애가 oversell로 이어지면 안 된다.
- duplicate click, retry, payment timeout, late payment success가 중복 booking/payment 효과를 만들면 안 된다.
- DB는 최종 정합성 guard이며, Redis는 admission/fairness/fast-fail을 위한 선행 gate다.
- DB write 폭증이 기존 서비스 DB까지 끌고 내려가면 안 된다.

### Architecture Trade-Off Analysis

AI는 Redis admission, Redis HA, bounded DB fallback, failover 중 admission pause, Traefik 도입, queue/RabbitMQ/Kafka 대안, MySQL final guard, payment recovery를 비교하는 데 활용됐다.

특히 Redis 장애 대응에서는 “서비스를 계속 받는 것처럼 보이는 fallback”과 “공정성/DB 보호를 우선하는 fail-safe admission control”을 분리해 판단했다. 그 결과 현재 설계는 Redis HA와 failover 중 새 admission pause를 중심으로 정리됐다.

### Implementation Support

AI는 설계 문서에 기록된 선택을 코드로 옮기는 과정에서 생산성을 높였다.

- Redis Lua 기반 admission pre-gate
- MySQL booking/admission/payment final guard
- idempotency key/hash/replay 정책
- payment method processor 구조
- payment failure, timeout, late success recovery
- Resilience4j bulkhead/circuit breaker/retry cap
- Testcontainers 기반 MySQL/Redis integration test
- k6와 load-test evidence 문서 정리

## Human Approval And Verification

AI가 제안한 내용은 자동으로 채택하지 않았다. 사용자가 요구사항, 비용 대비 효과, 구현 복잡도, 평가 가능성을 기준으로 주요 결정을 승인했다.

- Redis 단일 인스턴스 + bounded DB fallback은 load-test와 fairness 토론 후 현재 주 전략에서 제외했다.
- Redis HA + failover pause는 DB 보호와 공정성 요구사항을 우선하는 방향으로 채택했다.
- queue/RabbitMQ/Kafka는 추가 인프라 ROI와 운영 복잡도 대비 현재 요구사항에는 보류했다.
- Traefik은 correctness 필수 요소가 아니라 k3s/local edge, LB, gateway, rate-limit 보호 계층으로 문서 표현을 조정했다.
- AI 리뷰 결과는 그대로 반영하지 않고, 파일 근거와 요구사항 충족 여부를 기준으로 선별했다.

## Distinctive Usage Patterns

이 프로젝트의 AI 활용 방식에서 특히 강점으로 볼 수 있는 부분은 다음이다.

- 설계 문서에 긴 시간을 투자한 뒤 구현했다. `ralplan` 같은 단일 도구 실행보다 넓은 의미의 plan-first workflow를 프로젝트 전반에 적용했다.
- AI를 정답 생성기보다 비판자와 설계 파트너로 사용했다. Redis 장애, DB fallback, fairness, payment recovery를 여러 번 반대로 공격하게 했다.
- 단일 세션의 답변에 의존하지 않고, Codex와 OMX 기반 리뷰를 조합해 architecture review, code review, adversarial review 관점을 분리했다.
- 부하 테스트와 테스트 결과를 설계 문서의 주장과 연결해, 문서가 구현과 검증 결과를 따라가도록 관리했다.
- AI 사용 흔적도 원문 로그를 그대로 방치하지 않고, 공개 가능한 curated disclosure와 prompt theme으로 정리했다.

## What This Demonstrates

이 프로젝트의 AI 사용 방식은 다음 역량을 보여준다.

- 복잡한 요구사항을 불변식과 failure mode로 구조화하는 능력
- AI 제안을 그대로 따르지 않고 trade-off를 검증하는 능력
- 설계 문서, 결정 기록, 테스트, 구현을 하나의 흐름으로 연결하는 능력
- 코드 생성보다 리뷰와 검증에 AI를 적극 활용하는 능력
- 평가자가 확인할 수 있는 형태로 AI 사용 과정을 문서화하는 능력

## Public Submission Checklist

- [x] AI 사용 목적을 도구별/단계별로 설명했다.
- [x] plan-first workflow와 사람 승인 gate를 명확히 기록했다.
- [x] Codex, OMX, review skills의 역할을 구분했다.
- [x] 설계, 구현, 테스트, 문서화에 AI가 기여한 방식을 연결했다.
- [x] raw log 대신 curated log로 AI 활용 과정을 설명했다.
