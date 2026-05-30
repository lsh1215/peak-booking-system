# Source-Backed Research Note

이 문서는 Redis, MySQL/PostgreSQL, idempotency, resilience, PG-like payment flow 관련 설계 주장을 출처와 함께 정리한다.

## Research Rules

- Prefer official documentation and primary engineering sources.
- Mark claims as `Verified`, `Needs validation`, or `Project assumption`.
- Do not turn source guidance into a project decision without recording the trade-off in `docs/decisions/DECISIONS.md`.

## Claims

| Topic | Claim | Status | Source | Project Implication |
|---|---|---|---|---|
| Redis Lua | Redis Lua scripts are useful for combining multiple Redis operations into a single server-side atomic step. | Verified | [Redis Lua scripting docs](https://redis.io/docs/latest/develop/programmability/eval-intro/) | Redis Lua is a candidate for admission/coordination if DEC-002 selects a Redis-backed policy. |
| Redis transactions | Redis transactions execute queued commands in a single serialized step with `MULTI`/`EXEC`, and `WATCH` can make `EXEC` conditional for optimistic locking. | Verified | [Redis Transactions](https://redis.io/docs/latest/develop/using-commands/transactions/) | Transactions are a candidate for simple atomic batches, but admission logic that needs branching across keys may be simpler and safer as a Lua script. |
| Redis sorted sets | Redis sorted sets store members ordered by numeric score and can be queried by rank or score with commands such as `ZADD` and `ZRANGE`. | Verified | [Redis ZADD](https://redis.io/docs/latest/commands/zadd/), [Redis ZRANGE](https://redis.io/docs/latest/commands/zrange/) | Sorted sets are a strong candidate for keeping Redis admission order when the system must promote the next candidate after failures. |
| Redis sets | Redis `SET` supports `NX` and expiry options, and Redis `INCR` provides atomic counters. | Verified | [Redis SET](https://redis.io/docs/latest/commands/set/), [Redis INCR](https://redis.io/docs/latest/commands/incr/) | `SET NX`/sets/counters can support duplicate admission checks, but unordered sets alone cannot represent fair next-candidate ordering. |
| Redis streams | Redis Streams append entries with server-generated ordered IDs and support consumer groups for work-queue style processing. | Verified | [Redis Streams](https://redis.io/docs/latest/develop/data-types/streams/), [Redis XADD](https://redis.io/docs/latest/commands/xadd) | Streams are a candidate if the design needs durable-ish event processing semantics, but may be heavier than a bounded admission gate for 10-stock flash sale flow. |
| Redis locks | Redis documents a distributed lock pattern and the Redlock algorithm, but lock safety depends on precise TTL/value/delete behavior. | Verified | [Redis distributed locks](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/) | Redis lock usage should not be treated as safe by default; if selected, the safety assumptions need explicit tests and a decision record. |
| Redis TTL and eviction | Redis can expire keys, and eviction policy decides what happens when memory exceeds `maxmemory`; policies that evict keys can remove data before TTL if memory pressure occurs. | Verified | [Redis Key eviction](https://redis.io/docs/latest/develop/reference/eviction/), [Redis Data eviction policies](https://redis.io/docs/latest/operate/rc/databases/configuration/data-eviction-policies/) | Admission/fairness keys should not rely on best-effort cache eviction behavior; use explicit TTL for cleanup and avoid eviction policies that can drop live admission state. |
| Redis persistence | Redis persistence options include RDB snapshots and AOF; AOF generally offers stronger durability than snapshots but with resource/recovery trade-offs. | Verified | [Redis OSS persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) | Redis can improve recoverability, but this project should not treat Redis as the final correctness source of truth while Redis failure fallback is required. |
| MySQL locking | InnoDB locking reads such as `SELECT ... FOR UPDATE` lock rows for update and can be used for concurrency control. | Verified | [MySQL InnoDB locking reads](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/innodb-locking-reads.html), [InnoDB locking](https://dev.mysql.com/doc/refman/8.1/en/innodb-locking.html) | If MySQL is accepted as the concrete RDB choice, inventory correctness can use conditional updates or locking reads, but hot-row contention must be load tested. |
| MySQL sequence counter pattern | MySQL documents that `LAST_INSERT_ID(expr)` returns the expression and remembers it as the next value returned by `LAST_INSERT_ID()` for the client connection. | Verified | [MySQL 8.4 Information Functions: LAST_INSERT_ID](https://dev.mysql.com/doc/refman/8.4/en/information-functions.html) | A short `UPDATE ... SET next_seq = LAST_INSERT_ID(next_seq + 1)` pattern is a candidate for issuing DB admission sequence numbers with less lock hold time than an explicit select-then-update sequence. |
| PostgreSQL locking | PostgreSQL supports explicit row locking modes including `FOR UPDATE`. | Verified | [PostgreSQL explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html) | If PostgreSQL is selected later, the same final consistency idea can be mapped to PG transaction/locking semantics. |
| PostgreSQL uniqueness | PostgreSQL partial unique indexes can enforce uniqueness only for rows matching a predicate. | Verified | [PostgreSQL partial indexes](https://www.postgresql.org/docs/18/indexes-partial.html) | Useful as comparison material; current requirements name MySQL/MariaDB, so PostgreSQL is not a current implementation target. |
| Idempotency | Stripe stores the status code/body for the first request made with an idempotency key and returns the same result for later retries. | Verified | [Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests) | Stored response replay and request comparison are candidate policies for DEC-004, not yet accepted requirements. |
| Toss Payments confirm/query/cancel APIs | Toss Payments exposes payment confirmation, lookup by `paymentKey` or `orderId`, and full/partial cancellation APIs. | Verified | [Toss Payments Core API](https://docs.tosspayments.com/reference) | Mock PG should model `confirm`, `get by paymentKey/orderId`, and `cancel` instead of a boolean success/failure stub. |
| Toss Payments payment webhooks | Toss Payments documents payment status and cancellation status webhook event types. | Verified | [Toss Payments webhook guide](https://docs.tosspayments.com/guides/v2/webhook) | Mock PG can simulate status-change callbacks for timeout/unknown and asynchronous cancellation scenarios. |
| Toss Payments payment failure examples | Toss Payments documents card/account rejection errors such as limit exceeded or insufficient balance. | Verified | [Toss Payments error codes](https://docs.tosspayments.com/reference/error-codes) | Payment failure tests should include explicit decline reasons, not only generic exceptions. |
| Toss Payments idempotency guidance | Toss Payments describes idempotency-key behavior with replay, in-progress conflict, and same-key-different-payload rejection scenarios. | Verified | [Toss Payments idempotency blog](https://docs.tosspayments.com/blog/what-is-idempotency) | Useful candidate behavior for DEC-004, but project-level idempotency policy remains a user decision. |
| PortOne payment APIs | PortOne V2 REST API provides payment confirmation, payment lookup, cancellation, transaction lookup, and webhook resend endpoints. | Verified | [PortOne REST API V2 payment](https://developers.portone.io/api/rest-v2/payment?v=v2) | Mock PG can use `paymentId`-based confirm/get/cancel vocabulary as another official PG reference. |
| PortOne payment webhooks | PortOne recommends webhook integration for stable payment result synchronization when client redirect/response handling is interrupted. | Verified | [PortOne webhook help](https://help.portone.io/content/content200004), [Global PortOne payment stabilization](https://docs-global-kr.portone.cloud/integration/direct/end-pay) | Supports treating PG timeout/unknown as a reconciliation design topic rather than a simple immediate failure. |
| Retry safety | AWS warns that retries for side-effecting APIs are unsafe unless the API provides idempotency. | Verified | [AWS Builders Library: timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) | DEC-004/DEC-007 should ensure repeated payment requests do not create duplicate side effects. |
| Retry storm | AWS recommends backoff and jitter to spread retries and reduce congestion. | Verified | [AWS Builders Library: timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) | Retry caps, backoff, jitter, and fast failure are candidate overload controls for DEC-007. |
| Resilience4j | Resilience4j provides decorators/modules for CircuitBreaker, Retry, RateLimiter, Bulkhead, and TimeLimiter. | Verified | [Resilience4j introduction](https://resilience4j.readme.io/docs/getting-started), [Resilience4j Spring Boot docs](https://resilience4j.readme.io/v2.0.0/docs/getting-started-3) | Candidate library for bounded fallback, payment client resilience, and overload protection if library adoption is justified. |
| Flash-sale backpressure | Shopify handled flash-sale checkout pressure by throttling at the edge and sending users to a queue-like page instead of letting checkout writes overwhelm the platform. | Verified | [Shopify Engineering: Surviving Flashes of High-Write Traffic, Part I](https://shopify.engineering/surviving-flashes-of-high-write-traffic-using-scriptable-load-balancers-part-i) | DEC-007 should define how this project prevents collapse under `500~1000 TPS`; admission/queue/fast-failure are candidate approaches. |
| Fair queueing | Shopify's first throttle protected the platform but behaved like random polling; they later added first-attempt timestamp priority to improve fairness. | Verified | [Shopify Engineering: Surviving Flashes of High-Write Traffic, Part II](https://shopify.engineering/surviving-flashes-of-high-write-traffic-using-scriptable-load-balancers-part-ii) | "Fairness" must be defined explicitly. Duplicate polling/retry must not increase a user's chance over earlier arrivals. |
| Inventory reservation source of truth | Shopify moved inventory reservations from Redis to MySQL to keep reservations and ledger updates under ACID guarantees, after observing Redis/MySQL split-state failure modes. | Verified | [Shopify Engineering: We replaced Redis with MySQL for inventory reservations](https://shopify.engineering/scaling-inventory-reservations) | This supports evaluating RDB-backed inventory correctness in DEC-003; it does not by itself decide this project's implementation. |
| DB contention design | Shopify's MySQL reservation design used `SKIP LOCKED`, one row per sellable unit, composite primary keys, consistent lock ordering, and connection visibility to scale under peak checkout load. | Verified | [Shopify Engineering: We replaced Redis with MySQL for inventory reservations](https://shopify.engineering/scaling-inventory-reservations) | If this project chooses DB-first reservation or bounded DB fallback, schema/index/lock ordering/connection hold time are first-class design topics. |
| Waiting room queue policy | Cloudflare Waiting Room supports FIFO, Random, Passthrough, and Reject queueing methods, and FIFO orders users by first entry timestamp. | Verified | [Cloudflare Waiting Room: Queueing methods](https://developers.cloudflare.com/waiting-room/reference/queueing-methods/) | For this project, FIFO vs random admission is a business fairness decision, not just an implementation detail. |
| Waiting room thresholds | Cloudflare recommends queueing when traffic approaches configured active-user/new-user thresholds and setting active users to 75% of origin capacity. | Verified | [Cloudflare Waiting Room: Control waiting room traffic](https://developers.cloudflare.com/waiting-room/how-to/control-waiting-room/), [Cloudflare Waiting Room: Best practices](https://developers.cloudflare.com/waiting-room/reference/best-practices/) | Capacity thresholds should be explicit and measured; admission should be tied to app/DB/payment capacity, not only stock count. |
| Distributed fallback risk | AWS warns that distributed fallback can be hard to test and can worsen outages when fallback bypasses a protective layer and sends direct load to a dependency. | Verified | [AWS Builders Library: Avoiding fallback in distributed systems](https://aws.amazon.com/builders-library/avoiding-fallback-in-distributed-systems/) | DEC-002 should explicitly evaluate the risk of unlimited direct DB fallback during Redis failure. |
| K3s ingress baseline | K3s deploys Traefik as its packaged ingress controller by default, and current K3s docs note Traefik v3 support depending on K3s version. | Verified | [K3s Networking Services](https://docs.k3s.io/networking/networking-services), [K3s Upgrades](https://docs.k3s.io/upgrades) | If the project uses k3s with Traefik, gateway-level middleware can be considered for DEC-007 without assuming a separate commercial gateway product. |
| Traefik rate limiting | Traefik RateLimit is token-bucket based; `average`/`period` define refill rate, `burst` defines bucket size, and Redis-backed configuration can provide distributed rate limiting while in-memory rate limiting is per proxy instance. | Verified | [Traefik v3.5 RateLimit middleware](https://doc.traefik.io/traefik/v3.5/reference/routing-configuration/http/middlewares/ratelimit/) | Traefik can be a first-line overload protection candidate for `POST /bookings`, but it should not be treated as the authoritative fairness ledger. |
| Traefik source criteria | Traefik RateLimit groups requests by a configured source criterion such as request host, request header, or IP strategy. IP-based grouping can be affected by proxy/NAT/header handling. | Verified | [Traefik v3.5 RateLimit sourceCriterion](https://doc.traefik.io/traefik/v3.5/reference/routing-configuration/http/middlewares/ratelimit/) | Gateway rate limiting can protect WAS/DB capacity, but user-level fairness still needs an application/RDB admission policy when authentication is out of scope or user identity is client-supplied. |
| Little's Law | Little's Law relates average number in a stable system to arrival rate and average time in system: `L = lambda * W`. | Verified | [Little, A Proof for the Queuing Formula: L = λW](https://pubsonline.informs.org/doi/10.1287/opre.9.3.383) | Use Little's Law as an initial sizing model for fallback admission concurrency/bulkhead limits, then validate with k6/LGTM. Do not use unmeasured p99 as if it were a stable input. |
| HikariCP pool sizing | HikariCP upstream guidance warns against oversizing connection pools and emphasizes measuring throughput/latency under realistic load. | Verified | [HikariCP About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) | Fallback DB admission should have a small, explicit connection/bulkhead budget; increasing connection count is not a substitute for admission control. |

## Research Sweep: Limited-Stock / Flash-Sale Booking References

Date: 2026-05-30

Priority used for this sweep:

1. Company engineering blogs about real flash-sale, checkout, inventory, or high-concurrency operations.
2. Official product documentation for waiting room, idempotency, retry, fallback, Redis, and MySQL behavior.
3. Personal Medium/system-design posts only as supplemental inspiration; not used as primary evidence in this document.

High-signal references:

| Source | Type | Why it matters for this project |
|---|---|---|
| [Shopify Engineering: Surviving Flashes of High-Write Traffic, Part I](https://shopify.engineering/surviving-flashes-of-high-write-traffic-using-scriptable-load-balancers-part-i) | Company engineering blog | Real flash-sale checkout pressure, write-heavy checkout bottleneck, edge throttle/backpressure, queue UX trade-off. |
| [Shopify Engineering: Surviving Flashes of High-Write Traffic, Part II](https://shopify.engineering/surviving-flashes-of-high-write-traffic-using-scriptable-load-balancers-part-ii) | Company engineering blog | Fairness problem in random polling and timestamp-based stateless queueing. Useful for defining "선착순 공정성". |
| [Shopify Engineering: We replaced Redis with MySQL for inventory reservations](https://shopify.engineering/scaling-inventory-reservations) | Company engineering blog | Directly relevant inventory reservation/oversell/undersell case study with Redis limits, MySQL ACID, `SKIP LOCKED`, lock ordering, and connection bottlenecks. |
| [Cloudflare Waiting Room docs: Queueing methods](https://developers.cloudflare.com/waiting-room/reference/queueing-methods/) | Official docs | FIFO/random/reject/passthrough admission policy vocabulary for future fairness decisions. |
| [Cloudflare Waiting Room docs: Best practices](https://developers.cloudflare.com/waiting-room/reference/best-practices/) | Official docs | Capacity threshold guidance and queue UX caveats during active queueing. |
| [AWS Builders Library: Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) | Company engineering guidance | Retry storm, idempotency requirement for side-effecting APIs, jitter for overload/contention. |
| [AWS Builders Library: Avoiding fallback in distributed systems](https://aws.amazon.com/builders-library/avoiding-fallback-in-distributed-systems/) | Company engineering guidance | Evidence for why unlimited fallback from Redis to DB can expand failures. |
| [Stripe API docs: Idempotent requests](https://docs.stripe.com/api/idempotent_requests) | Official API docs | Request result replay, request parameter comparison, idempotency key TTL guidance. |
| [Toss Payments Core API](https://docs.tosspayments.com/reference) | Official PG docs | Payment confirmation, lookup by payment key/order ID, and cancel API shape for Mock PG assumptions. |
| [Toss Payments Webhook Guide](https://docs.tosspayments.com/guides/v2/webhook) | Official PG docs | Payment and cancellation status-change event vocabulary for callback simulation. |
| [Toss Payments Error Codes](https://docs.tosspayments.com/reference/error-codes) | Official PG docs | Concrete payment decline cases such as limit exceeded or insufficient balance. |
| [PortOne REST API V2 payment](https://developers.portone.io/api/rest-v2/payment?v=v2) | Official PG docs | Payment confirm, lookup, cancel, transaction lookup, and webhook resend endpoints. |
| [PortOne Webhook Help](https://help.portone.io/content/content200004) | Official PG docs | Webhook-based synchronization guidance and retry behavior. |

## Research Sweep: Gateway Rate Limiting And Capacity Sizing

Date: 2026-05-31

Priority used for this sweep:

1. Official K3s and Traefik documentation for gateway/rate-limit behavior.
2. Primary queueing theory and upstream connection-pool guidance for sizing formulas.
3. Project-specific interpretation remains a decision topic for DEC-002 and DEC-007.

High-signal references:

| Source | Type | Why it matters for this project |
|---|---|---|
| [K3s Networking Services](https://docs.k3s.io/networking/networking-services) | Official docs | Establishes Traefik as a packaged K3s ingress controller and points to version-specific packaged behavior. |
| [K3s Upgrades](https://docs.k3s.io/upgrades) | Official docs | Notes that K3s versions differ in bundled Traefik major version; the project must pin/verify the actual local K3s/Traefik version before implementation. |
| [Traefik v3.5 RateLimit middleware](https://doc.traefik.io/traefik/v3.5/reference/routing-configuration/http/middlewares/ratelimit/) | Official docs | Token-bucket `average`/`period`/`burst`, source criteria, and Redis-backed distributed rate limit behavior for gateway-level overload protection. |
| [Little, A Proof for the Queuing Formula: L = λW](https://pubsonline.informs.org/doi/10.1287/opre.9.3.383) | Primary paper | Provides the sizing relationship used to estimate admission concurrency from arrival rate and service time. |
| [HikariCP About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) | Upstream project guidance | Supports keeping DB connection budgets small and load-tested rather than treating a larger pool as a stability fix. |

## Research Sweep: Redis Admission Design

Date: 2026-05-31

Priority used for this sweep:

1. Official Redis command and data-type documentation.
2. Official Redis coordination pattern documentation.
3. Project interpretation remains a DEC-001/DEC-002 design decision until accepted by the user.

High-signal references:

| Source | Type | Why it matters for this project |
|---|---|---|
| [Redis Sorted Sets / ZADD](https://redis.io/docs/latest/commands/zadd/) and [ZRANGE](https://redis.io/docs/latest/commands/zrange/) | Official docs | Candidate structure for ordered admission when later candidates must be promoted after payment failure/timeout. |
| [Redis SET](https://redis.io/docs/latest/commands/set/) and [INCR](https://redis.io/docs/latest/commands/incr/) | Official docs | Candidate primitives for duplicate detection and monotonic sequence assignment. |
| [Redis Transactions](https://redis.io/docs/latest/develop/using-commands/transactions/) | Official docs | Establishes `MULTI`/`EXEC` and `WATCH`; useful comparison against Lua for multi-command admission logic. |
| [Redis Scripting with Lua](https://redis.io/docs/latest/develop/programmability/eval-intro/) | Official docs | Candidate for keeping duplicate check, sequence allocation, candidate pool check, and ordered insert in one server-side atomic operation. |
| [Redis Distributed Locks](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/) | Official docs | Shows lock complexity and why locks should not be the primary inventory/fairness guard here. |
| [Redis Key Eviction](https://redis.io/docs/latest/develop/reference/eviction/) | Official docs | Admission keys must not be silently evicted while the event is active; eviction is a memory-pressure behavior, not a correctness policy. |
| [Redis OSS Persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) | Official docs | Redis persistence can reduce data loss, but final correctness still belongs in MySQL for this project. |

## Open Research Questions

- Which k6 scenarios, LGTM dashboards/queries, and pass/fail metrics should validate the 500~1000 TPS burst.
- Whether "one confirmed booking per user per product" is an actual fairness requirement or only a candidate policy.
- Whether Redis admission should reserve capacity before payment or only gate DB attempts.
- Whether fallback policy should be fail-closed or bounded DB fallback for the first implementation.
- Whether k3s/Traefik should be accepted as a DEC-007 first-line overload defense, and whether its limiter is in-memory, Redis-backed distributed, or only a coarse local/dev safeguard.
