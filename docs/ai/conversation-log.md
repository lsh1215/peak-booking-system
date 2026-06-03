# Conversation Log (Curated)

이 문서는 AI와의 전체 원문 대화를 그대로 저장한 raw transcript가 아니다. 제출용으로 의미 있는 설계 흐름, 사람의 승인 지점, AI 리뷰가 남긴 약점만 정리한 curated conversation log다.

원문 로그에서 다음 항목은 의도적으로 제거했다.

- 로컬 절대 경로와 개인 환경 정보
- 내부 프롬프트 템플릿과 UI 보조 자동화 프롬프트
- 중복된 원문 평가 결과
- 에이전트 lane 실행 지시문
- 공개 평가에 필요하지 않은 세션 메타데이터

## 1. Requirements And Invariants

초기 대화에서는 제한 수량 booking/payment 시스템의 핵심 요구사항을 정리했다.

- target product stock은 `10`개로 고정한다.
- 피크 트래픽은 자정 이후 `1~5`분 동안 `500~1000 TPS`까지 증가할 수 있다.
- WAS는 `2+` stateless replicas를 전제로 한다.
- confirmed booking은 어떤 장애 상황에서도 `10`개를 넘으면 안 된다.
- Redis 장애, app instance 장애, duplicate click, retry storm, payment failure/timeout/late success, DB pressure를 모두 고려해야 한다.

AI는 요구사항을 불변식과 failure mode로 나누는 데 사용됐고, 사용자는 “성능보다 oversell 방지가 우선”이라는 기준을 반복적으로 확인했다.

## 2. Redis Role And Fallback Debate

가장 긴 설계 토론은 Redis의 역할과 Redis 장애 fallback 전략이었다.

처음에는 Redis 단일 인스턴스와 bounded DB fallback을 검토했다. 이 방식은 Redis 장애 중에도 제한적으로 admission을 이어갈 수 있지만, DB bulkhead와 connection pool이 좁아지면서 다음 문제가 드러났다.

- DB write 폭증을 막기 위해 permit 수를 줄이면 많은 요청이 admission 전에 탈락한다.
- 앞 순번 사용자가 payment failure를 내면, 더 늦은 요청이 남은 수량을 가져갈 수 있다.
- DB connection 획득, transaction, lock, commit 지연 때문에 “도착 순서 기준 fairness”가 약해진다.
- Redis failover 이후 DB fallback admission 상태와 Redis state를 다시 맞추는 복잡도가 생긴다.

이후 사용자는 실제 서비스 환경, 예를 들어 기존 물리 DB를 공유하는 회사에서 10개 한정 이벤트를 운영하는 상황을 가정했다. 이 조건에서는 한정 이벤트 때문에 기존 서비스 DB가 흔들리면 안 되므로, Redis 장애 중 새 admission을 DB로 보내는 전략은 ROI가 낮다고 판단했다.

최종 대화 흐름은 Redis HA + failover 중 새 admission pause로 수렴했다.

- Redis 정상 시: Redis Lua admission pre-gate가 빠르게 후보를 제한한다.
- Redis failover/장애 중: 새 admission은 `ADMISSION_TEMPORARILY_UNAVAILABLE`과 retry guidance로 응답한다.
- Redis 장애 중에도 기존 후보의 payment, recovery, idempotency 처리는 계속 진행한다.
- MySQL은 final guard로 남기되, Redis 장애 중 새 후보를 받는 fallback queue로 사용하지 않는다.

## 3. Redis HA, WAIT, Persistence, Queue Alternatives

AI와 사용자는 Redis HA에서도 failover window가 완전히 사라지지 않는다는 점을 검토했다.

검토한 대안은 다음과 같다.

- Redis Sentinel/managed HA
- Redis `WAIT`와 `min-replicas-to-write`
- RDB/AOF persistence
- MySQL ticket ledger
- durable waiting room/queue
- RabbitMQ/Kafka 추가
- Redis 이중 cluster

결론은 다음이다.

- `WAIT`와 `min-replicas-to-write`는 write acknowledgement와 split-brain risk를 줄이는 보조 수단이지, primary failover window를 완전히 없애지는 못한다.
- RDB/AOF는 장애 후 복구 가능성을 높이지만, admission ordering을 완전하게 보장하는 영속 queue는 아니다.
- RabbitMQ/Kafka 같은 queue는 fairness를 더 명확히 만들 수 있지만, 10개 한정 이벤트 하나를 위해 운영 복잡도와 장애 대응 면이 커진다.
- 현재 요구사항과 ROI 관점에서는 Redis HA + 짧은 admission pause + MySQL final guard가 더 적절하다고 판단했다.

## 4. Traefik And Infrastructure ROI

Traefik 도입도 별도로 검토했다. 처음에는 WAS 2대 앞의 LB, gateway, rate limiter 역할로 Traefik을 사용했다. 이후 사용자는 회사 또는 cloud provider가 이미 LB를 제공할 수 있다는 점을 지적했다.

정리된 입장은 다음이다.

- Traefik은 core correctness 컴포넌트가 아니다.
- k3s/local deployment에서는 ingress, LB, rate limit, observability integration을 단순화하는 실용적 선택이다.
- production에서는 managed LB/API Gateway/WAF가 이미 있다면 Traefik을 제거하거나 edge gateway가 아니라 internal gateway로 낮출 수 있다.
- Decisions 문서에는 “Traefik이 없으면 안 된다”가 아니라 “현재 local/k3s 검증 환경에서 비용 대비 유효하다”는 식으로 표현해야 한다.

## 5. Payment Extensibility And Failure Handling

결제 요구사항도 AI와 함께 점검했다.

요구사항은 다음과 같다.

- 신용카드, Y Pay, Y Point를 지원한다.
- 신용카드 + 포인트 또는 Y Pay + 포인트 복합 결제가 가능하다.
- 신용카드와 Y Pay는 혼용할 수 없다.
- 한도 초과, timeout, late success 등 결제 실패/불명확 상태에 대응해야 한다.

AI 리뷰를 통해 현재 구현의 강점과 약점을 분리했다.

- `PaymentMethodProcessor` 계열 구조는 결제 수단 확장성에 유리하다.
- idempotency와 recovery worker 설계는 duplicate payment와 late success에 대한 방어를 제공한다.
- 다만 `mockPgScenario`가 public API request body에 남아 있어 production profile에서는 제거하거나 내부 테스트 전용으로 격리해야 한다.

## 6. Implementation And Test-First Work

구현 단계에서는 AI가 Spring Boot 코드와 테스트 작성을 보조했다. 다만 구현은 먼저 논의된 SDD/Decisions/test-first scenarios를 기준으로 이루어졌다.

주요 검증 축은 다음이었다.

- confirmed booking count가 `10`을 넘지 않는지
- duplicate request가 동일 idempotency 결과로 replay되는지
- payment failure가 confirmed booking을 만들지 않는지
- Redis outage/failover 중 oversell이 발생하지 않는지
- retry storm이 degraded dependency를 증폭하지 않는지
- DB write pressure를 bulkhead와 admission gate가 제한하는지

AI 리뷰를 통해 일부 문서와 코드의 불일치도 발견됐다. 특히 Redis admission과 MySQL persistence 사이의 partial failure, public mock PG scenario, Redis failover k6 evidence gap은 제출 전 계속 추적해야 할 항목으로 남겼다.

## 7. Load-Test Evidence And Documentation Cleanup

부하 테스트 결과는 V1/V2로 정리하는 방향을 논의했다.

- V1: Redis 단일 인스턴스 + bounded DB fallback 계열의 historical evidence
- V2: Redis HA + failover pause 중심의 current architecture evidence

AI는 load-test evidence index와 분석 문서를 정리하는 데 사용됐다. 다만 Redis master failover 중 peak load k6 결과는 아직 최종 증거가 없으므로, 완료된 것처럼 주장하지 않고 재실측 필요 항목으로 남겼다.

## 8. AI-Assisted Evaluation

사용자는 평가자가 AI critic 또는 custom agent를 사용해 코드, 문서, AI 사용 기록을 함께 볼 수 있다고 가정했다. 이에 따라 AI에게 다음 관점의 비판적 평가를 요청했다.

- 현재 구조가 요구사항을 만족하는가
- `DECISIONS.md`가 주요 기술 쟁점, 도입 사유, 기각 대안, 비용 대비 효과를 설명하는가
- 설계 문서대로 구현됐는가
- AI 사용 흔적이 투명하면서도 제출 위생을 해치지 않는가

여러 리뷰의 결론은 대체로 일치했다.

- 구조 자체는 요구사항 방향에 맞다.
- Redis HA + failover pause, MySQL final guard, idempotency, payment recovery 설계는 강점이다.
- 최종 제출 전에는 Redis master failover k6 증거, public mock PG scenario 격리, Redis admission/MySQL persistence partial failure 설명을 더 닫아야 한다.

## Human Decisions Captured

- Redis 장애 중 bounded DB fallback은 현재 주 전략에서 제외한다.
- Redis HA와 failover 중 새 admission pause를 선택한다.
- 기존 후보의 payment/recovery/idempotency는 Redis failover 중에도 계속 처리한다.
- Queue/RabbitMQ/Kafka는 현재 요구사항과 ROI 대비 보류한다.
- Traefik은 correctness 필수 요소가 아니라 deployment/gateway/rate-limit 편의 요소로 설명한다.
- AI 로그는 raw transcript가 아니라 curated disclosure로 제출한다.

## Open Items

- Redis master failover 중 k6 peak-load 재실측 결과를 남긴다.
- `mockPgScenario`를 production API에서 제거하거나 내부 테스트 전용으로 격리한다.
- Redis Lua admission 성공 후 MySQL persistence 실패 시 underfill/fairness gap을 문서와 테스트로 계속 추적한다.
- `README-en.md` 등 보조 문서가 최신 Redis HA 정책을 따라가는지 확인한다.
- 공개 제출 전 `.tmp/`와 자동 생성 로컬 상태가 staging되지 않았는지 확인한다.
