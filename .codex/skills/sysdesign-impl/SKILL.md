---
name: sysdesign-impl
description: >-
  Scaffold a minimum-viable implementation of a designed system. Activated
  when the user is ready to write code. Reads sdd.md (or falls back to
  mock-interview.md), determines the smallest component subset that lets the
  design's NFRs be testable (NOT a full production build — single-region,
  in-memory or single-container infra, no CDN, no multi-region), asks the user
  for the language/framework stack (default Spring Boot + PostgreSQL + Kafka
  per project's existing skill set), then scaffolds <topic>/source/ with
  per-service directories, a docker-compose.yml that brings everything up
  locally, and test-results/ scaffolding for functional / load / failure
  tests. The point is verification of design assumptions, not production.
triggers:
  - "구현하자"
  - "구현해보자"
  - "구현 시작"
  - "코드 만들어"
  - "코드 짜보자"
  - "최소 구현"
  - "MVI 만들"
  - "mvi 만들"
  - "minimum viable implementation"
  - "implementation 시작"
  - "implementation 만들"
  - "소스 스캐폴드"
  - "스캐폴딩 해줘"
  - "scaffold"
  - "프로토타입 만들"
  - "prototype 만들"
  - "최소 운영"
  - "동작 가능한 최소"
allowed-tools: Read, Write, Edit, Glob, Grep, Bash
---

# sysdesign-impl — Phase 6 (Minimum-Viable Implementation)

You are scaffolding the **smallest** runnable system that lets the user verify
design decisions from the SDD. Not production. Not 100M-DAU-ready. Just enough
to:

1. Run end-to-end via `docker-compose up`
2. Run functional tests (API behaves as designed)
3. Run small-scale load tests (NFR shape verifiable, even if absolute numbers smaller)
4. Inject one or two failure scenarios

## Operating procedure

### Step 0. Identify active topic

```bash
TOPIC=$(cat .omx/state/active-sysdesign-topic.txt 2>/dev/null)
```

If empty → ask user. Confirm: "현재 활성: `<TOPIC>`. 이 토픽 구현 스캐폴딩한다."

### Step 1. Read the design

Try in order:

1. `<TOPIC>/System-Design-Document/sdd.md` — preferred
2. `<TOPIC>/System-Design-Document/mock-interview.md` — fallback (warn user: "SDD 없이 mock-interview만 보고 진행. ADR / Risk / Rollout 정보 없음. 계속 가도 돼?")
3. Neither → abort: "디자인 문서 없음. sysdesign-design 먼저."

### Step 2. Decide MVI subset

This is the hardest step. The full design has many components; the MVI has
**only what's needed to test the design's most consequential decisions**.

Use this framework to decide what's IN vs OUT:

| Component class | IN MVI | OUT MVI |
|---|---|---|
| Application services | ALL services that own a distinct domain (per design) | none |
| Database | 1 instance of the chosen engine (no replicas) | Replication, sharding, multi-master |
| Cache | 1 Redis (or Memcached) instance — only if cache is a load-bearing decision in the design | Cache cluster, multi-tier, CDN |
| Message queue | 1 Kafka broker (or RabbitMQ) — only if async/event-driven was a load-bearing decision | Multi-broker cluster, multi-region replication |
| Load balancer | nginx in compose (or skip — clients hit services directly for tests) | HA LB pair, GeoDNS |
| API Gateway | Skip unless explicitly tested | (always) |
| CDN | Skip — not testable in compose | (always) |
| Multi-region | Skip — single-region | (always) |
| Service discovery | Skip if ≤3 services (compose hostnames suffice) | ZooKeeper / Consul / etcd |
| Auth/SSO | Stub (hardcoded token) — unless the SDD explicitly designs auth | Production OAuth/OIDC |
| Observability | docker-compose include Prometheus + Grafana **only** if NFR includes specific metric SLOs | Full APM, distributed tracing |
| Background jobs | 1 worker per job type | Auto-scaling, priority queues |

Concrete examples (drawn from `sysdesign-question-bank/problems/`):

- **chat-system** MVI: 2 chat servers (WebSocket) + 1 Kafka + 1 Cassandra (or Postgres for simplicity) + nginx. Skip: presence pub/sub fan-out, push notifications, ZooKeeper.
- **url-shortener** MVI: 1 web service + 1 Postgres + 1 Redis. Skip: replicas, CDN, analytics pipeline.
- **news-feed** MVI: 1 post service + 1 fan-out worker + 1 feed service + 1 Postgres + 1 Redis + 1 Kafka. Skip: graph DB for friends (use Postgres table), CDN for media.
- **rate-limiter** MVI: 1 API gateway service + 1 Redis. Skip: distributed Redis, multi-region.

Propose the MVI to user as a table:

> "SDD에서 설계한 컴포넌트 중 MVI에는 다음만 포함:
>
> | Component | In MVI? | Why / Why not |
> |---|---|---|
> | chat-server-A, chat-server-B | ✓ | 핵심 — WebSocket + 메시지 라우팅 검증 |
> | Kafka (1 broker) | ✓ | 메시지 큐 결정 검증 (async fan-out) |
> | Cassandra | ✓ → Postgres로 대체 추천 | 단일 노드 운영 단순화. Cassandra의 wide-row 특성 검증이 핵심 NFR이면 그대로 가도 됨 |
> | 알림 서비스 | ✗ | offline 푸시는 별도 플로우 — MVI 외 |
> | ZooKeeper | ✗ | 2개 서버는 compose 호스트명으로 충분 |
>
> 이대로 가도 돼? 추가/제외할 거 있어?"

### Step 3. Stack selection

Default stack (per `AGENTS.md` skills bias toward Spring Boot):

- Language/framework: **Spring Boot 3 / Java 21**
- DB: PostgreSQL (or Cassandra/MongoDB if SDD demands it)
- Cache: Redis
- Queue: Kafka
- Container: docker-compose
- Build: Gradle
- Test: JUnit 5 + Testcontainers + k6 (load) + Toxiproxy (failure injection)

Ask user:

> "Stack 기본은 Spring Boot 3 / Postgres / Redis / Kafka / docker-compose / k6.
>  바꿀 거 있어? (Node, Go, Python 가능)"

User may pick stack — adjust scaffolding accordingly. **Do not add stack-specific
scaffolding before user confirms.**

### Step 4. Scaffold

For each IN-MVI service, create `<TOPIC>/source/<service-name>/`:

```
<TOPIC>/source/<service-name>/
├── build.gradle (or package.json / go.mod / pyproject.toml)
├── Dockerfile
├── src/main/...   (entrypoint + minimum API per SDD)
└── src/test/...   (1 unit test, 1 integration test stub)
```

If existing `.codex/skills/new-feature` or `domain-modeling` / `layer-architecture`
skills apply (Spring Boot + DDD + TDD), follow their conventions. Otherwise apply
project-wide conventions from `AGENTS.md`:

- DDD layered: api → application → domain → infra
- TDD: write 1 failing test first (RED), then implement to GREEN
- Domain layer infra-free
- per-topic isolation (no shared code with other topics)
- AI 생성 티 금지 (no TODO comments / boilerplate explanation)

Create `<TOPIC>/source/docker-compose.yml`:

```yaml
version: "3.9"
services:
  service-a:
    build: ./service-a
    ports: ["8080:8080"]
    depends_on: [postgres, kafka]
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: <topic>
      POSTGRES_PASSWORD: dev
    ports: ["5432:5432"]
  kafka:
    image: confluentinc/cp-kafka:latest
    # (minimal single-node config — KRaft mode)
  # ...
```

Create `<TOPIC>/test-results/` scaffolding:

```
<TOPIC>/test-results/
├── functional-test.md (template: API checks)
├── load-test.md (template: k6 script + result summary)
├── failure-test.md (template: chaos scenarios)
└── reports/
```

### Step 5. NFR test mapping

For each NFR in SDD §9, generate a corresponding test stub. Examples:

| NFR | Test type | Stub |
|---|---|---|
| p99 < 100ms | Load test (k6) | `k6 run --vus 100 --duration 60s scripts/p99.js` + assertion `p(99) < 100` |
| 99.99% availability | Failure test | Toxiproxy: kill DB for 30s, verify circuit breaker engages, no 5xx storm |
| 12K RPS sustained | Load test | k6 ramp to 12000 RPS for 5 min |
| Fan-out delivery <1s | Functional test | Post message, assert all subscribers receive within 1s |

Write these as test scripts, not just docs. Place under `<TOPIC>/source/<service>/load-tests/` or `<TOPIC>/test-results/scripts/`.

### Step 6. Smoke run + report

```bash
cd "$TOPIC/source"
docker-compose up -d --build
sleep 10  # wait for services
docker-compose ps
# basic health check
curl -fsS http://localhost:8080/healthz || echo "service not healthy yet"
docker-compose down
```

Report to user:

> "✓ MVI 스캐폴딩 완료.
>  - `<TOPIC>/source/` — {N개 서비스} + docker-compose.yml
>  - `<TOPIC>/test-results/` — functional / load / failure 템플릿
>  - 스모크 결과: 모든 서비스 up, /healthz 200 OK
>
>  다음 단계 (수동):
>  1. `cd <TOPIC>/source && docker-compose up`
>  2. functional 테스트 실행
>  3. k6 load 테스트 — NFR 충족 검증
>  4. 결과 → `<TOPIC>/test-results/{type}.md` 에 기록
>  5. SDD 'Findings vs Assumptions' 섹션 업데이트 (실측이 가정과 다른 부분)"

### Step 7. Mark topic as "shipped"

`source/` 에 실제 파일이 들어갔으니 토픽이 shipped 상태로 자동 분류됩니다
(`cleanup-logs.mjs`가 `source/` 비어있는지로 shipped 판정). conversation-log/
는 다음 SessionStart부터 shipped 보존 정책(3일)이 적용됩니다.

토픽을 다른 토픽으로 갈아탈 준비가 됐다면 `.omx/state/active-sysdesign-topic.txt`
는 사용자가 새 sysdesign-design 호출하면 자동 덮어써집니다 — 별도 액션 불필요.

## Hard rules

| Rule | Why |
|---|---|
| MVI는 production이 아님. CDN / multi-region / replica / HA cluster는 자동 OUT. | 스코프 폭주 방지. 검증 가능한 최소가 목적. |
| Stack은 사용자 확인 후에만 스캐폴딩 시작. | Default 가정으로 코드 수백 줄 쓰면 손실. |
| 모든 NFR은 대응되는 test stub이 있어야 함. NFR 검증 못 하면 MVI 가치 없음. | SDD ↔ test traceability. |
| `<topic>/source/` 외 다른 곳 (예: 다른 토픽 dir, .codex/) 절대 안 건드림. | per-topic isolation. |
| docker-compose가 떠야만 "스캐폴딩 완료". 안 뜨면 in_progress. | "동작 가능한 최소" 정의 충족. |
| 기존 sysdesign-* 외 스킬 (domain-modeling / layer-architecture / tdd-patterns / event-driven / saga-pattern)이 stack에 맞으면 그 컨벤션 따름. | DRY + 프로젝트 일관성. |
