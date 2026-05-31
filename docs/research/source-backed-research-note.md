# Source-Backed Research Note

이 문서는 Redis, MySQL/PostgreSQL, idempotency, resilience, PG-like payment flow 관련 설계 주장을 출처와 함께 정리한다.

## 리서치 규칙

- 공식 문서와 주요 engineering source를 우선한다.
- 각 주장은 `검증됨`, `검증 필요`, `프로젝트 가정` 중 하나로 표시한다.
- 출처의 가이드를 프로젝트 결정으로 바꿀 때는 반드시 trade-off를 `docs/decisions/DECISIONS.md`에 기록한다.

## 근거 기반 주장

| 주제 | 주장 | 상태 | 출처 | 프로젝트 적용 의미 |
|---|---|---|---|---|
| Redis Lua | Redis Lua script는 여러 Redis operation을 하나의 server-side atomic step으로 묶는 데 유용하다. | 검증됨 | [Redis Lua scripting docs](https://redis.io/docs/latest/develop/programmability/eval-intro/) | DEC-002가 Redis-backed 정책을 선택할 경우 admission/coordination 후보가 된다. |
| Redis transactions | Redis transaction은 `MULTI`/`EXEC`로 queue에 쌓인 command를 한 번의 serialized step으로 실행하며, `WATCH`를 사용하면 optimistic locking 형태로 `EXEC`를 조건부 실행할 수 있다. | 검증됨 | [Redis Transactions](https://redis.io/docs/latest/develop/using-commands/transactions/) | 단순 atomic batch 후보이지만, 여러 key에 걸친 branching admission logic은 Lua script가 더 단순하고 안전할 수 있다. |
| Redis sorted sets | Redis sorted set은 member를 numeric score 기준으로 정렬해 저장하고 `ZADD`, `ZRANGE` 같은 command로 rank/score 조회가 가능하다. | 검증됨 | [Redis ZADD](https://redis.io/docs/latest/commands/zadd/), [Redis ZRANGE](https://redis.io/docs/latest/commands/zrange/) | 결제 실패 뒤 다음 candidate를 승격해야 하는 경우 Redis admission order를 유지하기 좋은 후보이다. |
| Redis sets | Redis `SET`은 `NX`와 expiry option을 지원하고, Redis `INCR`는 atomic counter를 제공한다. | 검증됨 | [Redis SET](https://redis.io/docs/latest/commands/set/), [Redis INCR](https://redis.io/docs/latest/commands/incr/) | `SET NX`/set/counter는 중복 admission check에 쓸 수 있지만, unordered set만으로는 fair next-candidate ordering을 표현할 수 없다. |
| Redis streams | Redis Streams는 server-generated ordered ID로 entry를 append하고 consumer group 기반 work-queue 처리를 지원한다. | 검증됨 | [Redis Streams](https://redis.io/docs/latest/develop/data-types/streams/), [Redis XADD](https://redis.io/docs/latest/commands/xadd) | durable-ish event processing semantics가 필요하면 후보가 될 수 있지만, 10개 한정 flash-sale의 bounded admission gate에는 다소 무거울 수 있다. |
| Redis locks | Redis는 distributed lock pattern과 Redlock algorithm을 설명하지만, lock safety는 정확한 TTL/value/delete 동작에 의존한다. | 검증됨 | [Redis distributed locks](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/) | Redis lock을 기본적으로 안전하다고 간주하면 안 된다. 선택한다면 safety assumption과 테스트를 decision record에 명시해야 한다. |
| Redis TTL and eviction | Redis는 key expiration을 지원하고, eviction policy는 memory가 `maxmemory`를 넘을 때 어떤 key를 제거할지 결정한다. eviction policy에 따라 TTL 전에 key가 제거될 수 있다. | 검증됨 | [Redis Key eviction](https://redis.io/docs/latest/develop/reference/eviction/), [Redis Data eviction policies](https://redis.io/docs/latest/operate/rc/databases/configuration/data-eviction-policies/) | admission/fairness key는 best-effort cache eviction에 기대면 안 된다. cleanup에는 explicit TTL을 쓰고, live admission state가 제거될 수 있는 eviction policy는 피해야 한다. |
| Redis persistence | Redis persistence option에는 RDB snapshot과 AOF가 있으며, 일반적으로 AOF가 snapshot보다 강한 durability를 제공하지만 resource/recovery trade-off가 있다. | 검증됨 | [Redis OSS persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) | Redis는 recoverability를 높일 수 있지만, Redis failure fallback이 요구되는 이 프로젝트에서 final correctness source of truth로 두면 안 된다. |
| MySQL locking | `SELECT ... FOR UPDATE` 같은 InnoDB locking read는 update 대상 row를 잠그며 concurrency control에 사용할 수 있다. | 검증됨 | [MySQL InnoDB locking reads](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/innodb-locking-reads.html), [InnoDB locking](https://dev.mysql.com/doc/refman/8.1/en/innodb-locking.html) | MySQL을 concrete RDB로 수용했으므로 inventory correctness는 conditional update나 locking read로 구현할 수 있지만, hot-row contention은 load test로 확인해야 한다. |
| MySQL sequence counter pattern | MySQL은 `LAST_INSERT_ID(expr)`가 expression 값을 반환하고 해당 client connection의 다음 `LAST_INSERT_ID()` 값으로 기억한다고 설명한다. | 검증됨 | [MySQL 8.4 Information Functions: LAST_INSERT_ID](https://dev.mysql.com/doc/refman/8.4/en/information-functions.html) | `UPDATE ... SET next_seq = LAST_INSERT_ID(next_seq + 1)` 패턴은 explicit select-then-update sequence보다 lock hold time을 줄이며 DB admission sequence를 발급하는 후보가 된다. |
| PostgreSQL locking | PostgreSQL은 `FOR UPDATE`를 포함한 explicit row locking mode를 지원한다. | 검증됨 | [PostgreSQL explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html) | 향후 PostgreSQL을 선택한다면 같은 final consistency 개념을 PG transaction/locking semantics에 맞춰 옮길 수 있다. |
| PostgreSQL uniqueness | PostgreSQL partial unique index는 predicate에 매칭되는 row에 대해서만 uniqueness를 강제할 수 있다. | 검증됨 | [PostgreSQL partial indexes](https://www.postgresql.org/docs/18/indexes-partial.html) | 비교 자료로는 유용하지만 현재 baseline은 MySQL 8이므로 현 구현 대상은 아니다. |
| Idempotency | Stripe는 idempotency key로 들어온 첫 요청의 status code/body를 저장하고 이후 retry에 같은 결과를 반환한다. | 검증됨 | [Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests) | Stored response replay와 request comparison은 DEC-004 후보 정책이며, 아직 수용된 요구사항은 아니다. |
| Toss Payments confirm/query/cancel APIs | Toss Payments는 payment confirmation, `paymentKey` 또는 `orderId` 기반 조회, 전체/부분 취소 API를 제공한다. | 검증됨 | [Toss Payments Core API](https://docs.tosspayments.com/reference) | Mock PG는 boolean success/failure stub이 아니라 `confirm`, `get by paymentKey/orderId`, `cancel`을 모델링해야 한다. |
| Toss Payments payment webhooks | Toss Payments는 payment status와 cancellation status webhook event type을 문서화한다. | 검증됨 | [Toss Payments webhook guide](https://docs.tosspayments.com/guides/v2/webhook) | Mock PG는 timeout/unknown 및 비동기 cancellation scenario를 위해 status-change callback을 시뮬레이션할 수 있다. |
| Toss Payments payment failure examples | Toss Payments는 한도 초과, 잔액 부족 같은 card/account rejection error를 문서화한다. | 검증됨 | [Toss Payments error codes](https://docs.tosspayments.com/reference/error-codes) | Payment failure test는 generic exception만이 아니라 구체 decline reason을 포함해야 한다. |
| Toss Payments idempotency guidance | Toss Payments는 idempotency-key의 replay, in-progress conflict, same-key-different-payload rejection scenario를 설명한다. | 검증됨 | [Toss Payments idempotency blog](https://docs.tosspayments.com/blog/what-is-idempotency) | DEC-004 후보 동작으로 유용하지만, 프로젝트 idempotency 정책은 여전히 user decision이다. |
| PortOne payment APIs | PortOne V2 REST API는 payment confirmation, payment lookup, cancellation, transaction lookup, webhook resend endpoint를 제공한다. | 검증됨 | [PortOne REST API V2 payment](https://developers.portone.io/api/rest-v2/payment?v=v2) | Mock PG는 또 다른 공식 PG 참고 모델로 `paymentId` 기반 confirm/get/cancel vocabulary를 사용할 수 있다. |
| PortOne payment webhooks | PortOne은 client redirect/response 처리가 중단되는 경우 안정적인 payment result synchronization을 위해 webhook integration을 권장한다. | 검증됨 | [PortOne webhook help](https://help.portone.io/content/content200004), [Global PortOne payment stabilization](https://docs-global-kr.portone.cloud/integration/direct/end-pay) | PG timeout/unknown을 단순 즉시 실패가 아니라 reconciliation design topic으로 다뤄야 한다는 근거가 된다. |
| Retry safety | AWS는 side-effecting API retry가 idempotency 없이는 안전하지 않다고 경고한다. | 검증됨 | [AWS Builders Library: timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) | DEC-004/DEC-007은 반복 payment request가 duplicate side effect를 만들지 않도록 해야 한다. |
| Retry storm | AWS는 retry를 분산하고 congestion을 줄이기 위해 backoff와 jitter를 권장한다. | 검증됨 | [AWS Builders Library: timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) | Retry cap, backoff, jitter, fast failure는 DEC-007 overload control 후보이다. |
| Resilience4j | Resilience4j는 CircuitBreaker, Retry, RateLimiter, Bulkhead, TimeLimiter decorator/module을 제공한다. | 검증됨 | [Resilience4j introduction](https://resilience4j.readme.io/docs/getting-started), [Resilience4j Spring Boot docs](https://resilience4j.readme.io/v2.0.0/docs/getting-started-3) | library adoption이 정당화된다면 bounded fallback, payment client resilience, overload protection 후보가 된다. |
| Flash-sale backpressure | Shopify는 flash-sale checkout 압력을 edge throttling과 queue-like page로 처리해 checkout write가 platform을 압도하지 않게 했다. | 검증됨 | [Shopify Engineering: Surviving Flashes of High-Write Traffic, Part I](https://shopify.engineering/surviving-flashes-of-high-write-traffic-using-scriptable-load-balancers-part-i) | DEC-007은 `500~1000 TPS`에서 collapse를 막는 방식을 정의해야 하며 admission/queue/fast-failure가 후보이다. |
| Fair queueing | Shopify의 첫 throttle은 platform은 보호했지만 random polling처럼 동작했고, 이후 first-attempt timestamp priority를 추가해 fairness를 개선했다. | 검증됨 | [Shopify Engineering: Surviving Flashes of High-Write Traffic, Part II](https://shopify.engineering/surviving-flashes-of-high-write-traffic-using-scriptable-load-balancers-part-ii) | "공정성"은 명시적으로 정의해야 한다. 중복 polling/retry가 앞서 도착한 사용자보다 chance를 높이면 안 된다. |
| Inventory reservation source of truth | Shopify는 Redis/MySQL split-state failure mode를 겪은 뒤 inventory reservation과 ledger update를 ACID guarantee 아래 두기 위해 reservation을 Redis에서 MySQL로 옮겼다. | 검증됨 | [Shopify Engineering: We replaced Redis with MySQL for inventory reservations](https://shopify.engineering/scaling-inventory-reservations) | DEC-003에서 RDB-backed inventory correctness를 평가하는 근거가 된다. 다만 이 자료만으로 프로젝트 구현이 결정되는 것은 아니다. |
| DB contention design | Shopify의 MySQL reservation 설계는 `SKIP LOCKED`, sellable unit당 한 row, composite primary key, consistent lock ordering, connection visibility를 사용해 peak checkout load를 처리했다. | 검증됨 | [Shopify Engineering: We replaced Redis with MySQL for inventory reservations](https://shopify.engineering/scaling-inventory-reservations) | DB-first reservation이나 bounded DB fallback을 선택하면 schema/index/lock ordering/connection hold time이 일급 설계 주제가 된다. |
| Waiting room queue policy | Cloudflare Waiting Room은 FIFO, Random, Passthrough, Reject queueing method를 지원하며, FIFO는 first entry timestamp로 user를 정렬한다. | 검증됨 | [Cloudflare Waiting Room: Queueing methods](https://developers.cloudflare.com/waiting-room/reference/queueing-methods/) | 이 프로젝트에서 FIFO와 random admission 중 무엇을 택할지는 구현 세부가 아니라 business fairness decision이다. |
| Waiting room thresholds | Cloudflare는 traffic이 configured active-user/new-user threshold에 가까워질 때 queueing하고, active users를 origin capacity의 75%로 잡는 것을 권장한다. | 검증됨 | [Cloudflare Waiting Room: Control waiting room traffic](https://developers.cloudflare.com/waiting-room/how-to/control-waiting-room/), [Cloudflare Waiting Room: Best practices](https://developers.cloudflare.com/waiting-room/reference/best-practices/) | Capacity threshold는 명시적으로 측정해야 하며, admission은 stock count뿐 아니라 app/DB/payment capacity와 연결해야 한다. |
| Distributed fallback risk | AWS는 distributed fallback이 테스트하기 어렵고, 보호 계층을 우회해 dependency로 direct load를 보내면 outage를 악화시킬 수 있다고 경고한다. | 검증됨 | [AWS Builders Library: Avoiding fallback in distributed systems](https://aws.amazon.com/builders-library/avoiding-fallback-in-distributed-systems/) | DEC-002는 Redis failure 중 unlimited direct DB fallback 위험을 명시적으로 평가해야 한다. |
| K3s ingress baseline | K3s는 기본 packaged ingress controller로 Traefik을 배포하며, 현재 K3s docs는 K3s version에 따라 Traefik v3 지원 여부가 달라질 수 있음을 설명한다. | 검증됨 | [K3s Networking Services](https://docs.k3s.io/networking/networking-services), [K3s Upgrades](https://docs.k3s.io/upgrades) | k3s + Traefik을 쓴다면 별도 commercial gateway product를 가정하지 않고 DEC-007에서 gateway-level middleware를 검토할 수 있다. |
| Traefik rate limiting | Traefik RateLimit은 token-bucket 기반이며, `average`/`period`는 refill rate를, `burst`는 bucket size를 정의한다. Redis-backed configuration은 distributed rate limiting을 제공할 수 있고, in-memory rate limiting은 proxy instance별로 동작한다. | 검증됨 | [Traefik v3.5 RateLimit middleware](https://doc.traefik.io/traefik/v3.5/reference/routing-configuration/http/middlewares/ratelimit/) | Traefik은 `POST /bookings`의 1차 overload protection 후보지만 authoritative fairness ledger로 취급하면 안 된다. |
| Traefik source criteria | Traefik RateLimit은 request host, request header, IP strategy 같은 configured source criterion으로 request를 그룹화한다. IP 기반 grouping은 proxy/NAT/header 처리에 영향을 받을 수 있다. | 검증됨 | [Traefik v3.5 RateLimit sourceCriterion](https://doc.traefik.io/traefik/v3.5/reference/routing-configuration/http/middlewares/ratelimit/) | Gateway rate limiting은 WAS/DB capacity를 보호할 수 있지만, authentication이 범위 밖이거나 user identity가 client-supplied인 경우 user-level fairness에는 application/RDB admission policy가 필요하다. |
| Little's Law | Little's Law는 stable system의 평균 체류 수, arrival rate, 평균 체류 시간 사이의 관계를 `L = lambda * W`로 설명한다. | 검증됨 | [Little, A Proof for the Queuing Formula: L = λW](https://pubsonline.informs.org/doi/10.1287/opre.9.3.383) | fallback admission concurrency/bulkhead limit의 초기 sizing model로 사용하고, 이후 k6/LGTM으로 검증한다. 측정되지 않은 p99를 stable input처럼 쓰면 안 된다. |
| HikariCP pool sizing | HikariCP upstream guidance는 connection pool 과대 설정을 경고하고 realistic load에서 throughput/latency를 측정하라고 강조한다. | 검증됨 | [HikariCP About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) | Fallback DB admission에는 작고 명시적인 connection/bulkhead budget이 필요하다. connection count 증가는 admission control의 대체물이 아니다. |
| HikariCP connection timeout | HikariCP `connectionTimeout`은 pool에서 connection을 기다리는 최대 시간이며, 최저 허용값은 `250ms`, 기본값은 `30000ms`다. | 검증됨 | [HikariCP README configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby) | Booking write path에서는 pool 고갈을 30초 동안 끌지 않도록 짧은 timeout을 초기값으로 둔다. |
| Dependency isolation | AWS는 느린 dependency가 concurrency를 증가시켜 thread, memory, socket, connection 같은 제한 자원을 고갈시킬 수 있으므로 bulkhead/concurrency limit로 blast radius를 제한하라고 설명한다. | 검증됨 | [AWS Builders Library: Dependency isolation](https://aws.amazon.com/builders-library/dependency-isolation/) | DB fallback, PG confirm, recovery worker는 같은 WAS 안에 있어도 별도 concurrency budget을 가져야 한다. |
| k6 constant arrival rate | k6 `constant-arrival-rate` executor는 응답 시간과 독립적으로 지정한 rate로 iteration을 시작하는 open-model executor다. | 검증됨 | [Grafana k6 constant arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/) | `500~1000 TPS` burst를 closed-loop VU 수가 아니라 도착률 기준으로 재현하는 데 사용한다. |
| k6 metrics and thresholds | k6 `http_reqs`는 생성된 HTTP 요청 수를 세고 `http_req_duration`은 요청 처리 시간을 측정한다. threshold는 metric/tag별 pass/fail 기준으로 사용할 수 있다. | 검증됨 | [Grafana k6 built-in metrics](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/), [Grafana k6 thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/) | Fast fail도 HTTP 요청 수에는 포함된다. 따라서 controlled rejection과 technical failure를 분리해 custom metric/tag로 측정해야 한다. |
| SLI/SLO selection | Google SRE는 user-facing serving system에서 availability, latency, throughput을 대표 SLI로 보며, correctness도 system health indicator로 추적해야 한다고 설명한다. | 검증됨 | [Google SRE Book: Service Level Objectives](https://sre.google/sre-book/service-level-objectives/) | DEC-008은 단순 성공률이 아니라 correctness invariant, latency, throughput, recovery 상태를 함께 pass/fail 기준으로 둔다. |

## Research Sweep: DEC-007/DEC-008 Initial Budgets

날짜: 2026-05-31

이번 sweep의 우선순위:

1. 초기 concurrency/connection/timeout 산정에 필요한 queueing, latency, pool sizing 근거.
2. Traefik/k6의 공식 동작과 metric semantics.
3. 숫자는 최종 운영값이 아니라 `2`개 WAS, Mock PG `100ms`, stock `10` 기준 starting point로만 기록한다.

주요 참고 자료:

| 출처 | 유형 | 이 프로젝트에서 중요한 이유 |
|---|---|---|
| [AWS Builders Library: Dependency isolation](https://aws.amazon.com/builders-library/dependency-isolation/) | 회사 기술 가이드 | Little's Law를 concurrency overload 판단에 연결하고, dependency별 bulkhead가 필요한 이유를 설명한다. |
| [AWS Builders Library: Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) | 회사 기술 가이드 | Timeout 선택, retry storm, capped backoff, jitter, side-effecting retry의 idempotency 필요성을 제공한다. |
| [HikariCP About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) | upstream 프로젝트 가이드 | DB connection pool은 작게 시작하고 load test로 조정해야 한다는 근거다. |
| [HikariCP README configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby) | upstream 프로젝트 문서 | `connectionTimeout`의 의미, 최저 허용값, 기본값을 제공한다. |
| [Traefik RateLimit middleware](https://doc.traefik.io/traefik/v3.3/middlewares/http/ratelimit/) | 공식 문서 | Token bucket `average`, `period`, `burst` 동작을 확인한다. |
| [Grafana k6 constant arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/) | 공식 문서 | Open-model arrival rate로 peak traffic을 재현하는 근거다. |
| [Grafana k6 built-in metrics](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/) | 공식 문서 | `http_reqs`, `http_req_duration`, `http_req_failed` 의미를 확인한다. |
| [Grafana k6 thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/) | 공식 문서 | Metric/tag 기반 pass/fail threshold 정의 방식을 제공한다. |
| [Google SRE Book: Service Level Objectives](https://sre.google/sre-book/service-level-objectives/) | SRE reference | user-facing system의 latency/throughput/availability/correctness SLI 선택 근거다. |

## Research Sweep: Limited-Stock / Flash-Sale Booking References

날짜: 2026-05-30

이번 sweep의 우선순위:

1. 실제 flash-sale, checkout, inventory, high-concurrency operation을 다룬 company engineering blog.
2. waiting room, idempotency, retry, fallback, Redis, MySQL behavior에 대한 official product documentation.
3. 개인 Medium/system-design 글은 보조 아이디어로만 사용하고, 이 문서의 primary evidence로 쓰지 않는다.

주요 참고 자료:

| 출처 | 유형 | 이 프로젝트에서 중요한 이유 |
|---|---|---|
| [Shopify Engineering: Surviving Flashes of High-Write Traffic, Part I](https://shopify.engineering/surviving-flashes-of-high-write-traffic-using-scriptable-load-balancers-part-i) | 회사 기술 블로그 | 실제 flash-sale checkout pressure, write-heavy checkout bottleneck, edge throttle/backpressure, queue UX trade-off를 보여준다. |
| [Shopify Engineering: Surviving Flashes of High-Write Traffic, Part II](https://shopify.engineering/surviving-flashes-of-high-write-traffic-using-scriptable-load-balancers-part-ii) | 회사 기술 블로그 | random polling의 fairness 문제와 timestamp-based stateless queueing을 다룬다. "선착순 공정성" 정의에 유용하다. |
| [Shopify Engineering: We replaced Redis with MySQL for inventory reservations](https://shopify.engineering/scaling-inventory-reservations) | 회사 기술 블로그 | Redis 한계, MySQL ACID, `SKIP LOCKED`, lock ordering, connection bottleneck을 포함한 inventory reservation/oversell/undersell case study다. |
| [Cloudflare Waiting Room docs: Queueing methods](https://developers.cloudflare.com/waiting-room/reference/queueing-methods/) | 공식 문서 | 향후 fairness decision에 필요한 FIFO/random/reject/passthrough admission policy vocabulary를 제공한다. |
| [Cloudflare Waiting Room docs: Best practices](https://developers.cloudflare.com/waiting-room/reference/best-practices/) | 공식 문서 | active queueing 중 capacity threshold guidance와 queue UX caveat를 제공한다. |
| [AWS Builders Library: Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) | 회사 기술 가이드 | Retry storm, side-effecting API의 idempotency requirement, overload/contention 완화를 위한 jitter 근거다. |
| [AWS Builders Library: Avoiding fallback in distributed systems](https://aws.amazon.com/builders-library/avoiding-fallback-in-distributed-systems/) | 회사 기술 가이드 | Redis에서 DB로 가는 unlimited fallback이 failure를 확장할 수 있는 이유를 설명하는 근거다. |
| [Stripe API docs: Idempotent requests](https://docs.stripe.com/api/idempotent_requests) | 공식 API 문서 | Request result replay, request parameter comparison, idempotency key TTL guidance를 제공한다. |
| [Toss Payments Core API](https://docs.tosspayments.com/reference) | 공식 PG 문서 | Mock PG assumption을 위한 payment confirmation, payment key/order ID lookup, cancel API shape를 제공한다. |
| [Toss Payments Webhook Guide](https://docs.tosspayments.com/guides/v2/webhook) | 공식 PG 문서 | callback simulation에 필요한 payment/cancellation status-change event vocabulary를 제공한다. |
| [Toss Payments Error Codes](https://docs.tosspayments.com/reference/error-codes) | 공식 PG 문서 | 한도 초과, 잔액 부족 같은 구체 payment decline case를 제공한다. |
| [PortOne REST API V2 payment](https://developers.portone.io/api/rest-v2/payment?v=v2) | 공식 PG 문서 | Payment confirm, lookup, cancel, transaction lookup, webhook resend endpoint를 제공한다. |
| [PortOne Webhook Help](https://help.portone.io/content/content200004) | 공식 PG 문서 | Webhook-based synchronization guidance와 retry behavior를 제공한다. |

## Research Sweep: Gateway Rate Limiting And Capacity Sizing

날짜: 2026-05-31

이번 sweep의 우선순위:

1. gateway/rate-limit behavior에 대한 공식 K3s 및 Traefik documentation.
2. sizing formula를 위한 primary queueing theory와 upstream connection-pool guidance.
3. 프로젝트별 해석은 DEC-002와 DEC-007의 decision topic으로 남긴다.

주요 참고 자료:

| 출처 | 유형 | 이 프로젝트에서 중요한 이유 |
|---|---|---|
| [K3s Networking Services](https://docs.k3s.io/networking/networking-services) | 공식 문서 | Traefik이 K3s packaged ingress controller임을 확인하고, version-specific packaged behavior를 확인해야 함을 보여준다. |
| [K3s Upgrades](https://docs.k3s.io/upgrades) | 공식 문서 | K3s version에 따라 bundled Traefik major version이 달라질 수 있으므로 구현 전 local K3s/Traefik version을 pin/verify해야 함을 보여준다. |
| [Traefik v3.5 RateLimit middleware](https://doc.traefik.io/traefik/v3.5/reference/routing-configuration/http/middlewares/ratelimit/) | 공식 문서 | gateway-level overload protection을 위한 token-bucket `average`/`period`/`burst`, source criteria, Redis-backed distributed rate limit behavior 근거다. |
| [Little, A Proof for the Queuing Formula: L = λW](https://pubsonline.informs.org/doi/10.1287/opre.9.3.383) | 원 논문 | arrival rate와 service time으로 admission concurrency를 추정하는 sizing relationship을 제공한다. |
| [HikariCP About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) | upstream 프로젝트 가이드 | 큰 pool을 stability fix로 보는 대신 DB connection budget을 작고 명시적으로 유지하고 load test해야 한다는 근거다. |

## Research Sweep: Redis Admission Design

날짜: 2026-05-31

이번 sweep의 우선순위:

1. 공식 Redis command 및 data-type documentation.
2. 공식 Redis coordination pattern documentation.
3. 프로젝트 해석은 user가 수용하기 전까지 DEC-001/DEC-002 design decision으로 남긴다.

주요 참고 자료:

| 출처 | 유형 | 이 프로젝트에서 중요한 이유 |
|---|---|---|
| [Redis Sorted Sets / ZADD](https://redis.io/docs/latest/commands/zadd/) and [ZRANGE](https://redis.io/docs/latest/commands/zrange/) | 공식 문서 | 결제 실패/timeout 이후 later candidate를 승격해야 할 때 ordered admission을 표현할 수 있는 후보 구조다. |
| [Redis SET](https://redis.io/docs/latest/commands/set/) and [INCR](https://redis.io/docs/latest/commands/incr/) | 공식 문서 | duplicate detection과 monotonic sequence assignment에 필요한 primitive 후보를 제공한다. |
| [Redis Transactions](https://redis.io/docs/latest/develop/using-commands/transactions/) | 공식 문서 | `MULTI`/`EXEC`와 `WATCH`를 정리해 multi-command admission logic에서 Lua와 비교할 기준을 제공한다. |
| [Redis Scripting with Lua](https://redis.io/docs/latest/develop/programmability/eval-intro/) | 공식 문서 | duplicate check, sequence allocation, candidate pool check, ordered insert를 하나의 server-side atomic operation으로 묶는 후보 근거다. |
| [Redis Distributed Locks](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/) | 공식 문서 | lock complexity를 보여주며, 이 설계에서 lock이 primary inventory/fairness guard가 되면 안 되는 이유를 보강한다. |
| [Redis Key Eviction](https://redis.io/docs/latest/develop/reference/eviction/) | 공식 문서 | event active 중 admission key가 조용히 evict되면 안 된다. eviction은 correctness policy가 아니라 memory-pressure behavior다. |
| [Redis OSS Persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) | 공식 문서 | Redis persistence는 data loss를 줄일 수 있지만, 이 프로젝트의 final correctness는 여전히 MySQL에 둬야 한다. |

## 미해결 리서치 질문

- DEC-008 초기 k6 scenario와 pass/fail metric은 정리되었지만, 구현 후 concrete LGTM dashboard/query와 metric name을 확정해야 한다.
- "user/product당 confirmed booking 1건"이 실제 fairness requirement인지, 아니면 candidate policy인지.
- Redis admission이 payment 전에 capacity를 reserve해야 하는지, 아니면 DB attempt만 gate해야 하는지.
- 첫 구현에서 fallback policy를 fail-closed로 둘지 bounded DB fallback으로 둘지.
- k3s/Traefik을 DEC-007의 first-line overload defense로 수용할지, 그리고 limiter를 in-memory로 둘지 Redis-backed distributed로 둘지, 아니면 local/dev coarse safeguard로만 둘지.
