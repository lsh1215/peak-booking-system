# 처리율 제한 장치의 설계 (Rate Limiter)

**Book chapter:** Alex Xu Vol.1 Ch.4 (pp.51–75)
**Slug:** rate-limiter
**Scale class:** medium (API-gateway scale, millions of users)
**Type:** Mock-ready (warm-up)

## Canned numbers (book's starting assumptions — Clarifying starting point)

Not explicitly given — chapter focuses on algorithm comparison, not scale estimation.
Typical framing: "≤5 posts/sec per user", millions of users, API gateway level.

## Functional requirements (book's defaults)

- Accurately limit excessive requests at a configurable rate (e.g., ≤5 posts/sec per user) [p.52]
- Return HTTP 429 (Too Many Requests) when limit exceeded
- Low latency — must not slow down HTTP response time
- Support distributed rate limiting across multiple servers
- Support throttling rules per user, IP, or API endpoint [p.52]

## Non-functional requirements (book's defaults)

- Low latency
- As little memory as possible
- Distributed: sharable across multiple servers or processes
- Exception handling: clearly show users when throttled
- High fault tolerance: if rate limiter crashes, system continues

## Core decisions

| Decision | Options | Winner / Notes |
|---|---|---|
| Placement | Client-side vs server-side vs middleware/API gateway | API gateway (middleware) — server-side preferred [p.53] |
| Algorithm | Token Bucket, Leaking Bucket, Fixed Window Counter, Sliding Window Log, Sliding Window Counter | Depends on use case — Token Bucket common for bursts [pp.55–63] |
| Counter storage | DB vs Redis (in-memory) | Redis — fast, atomic INCR + EXPIRE [p.63] |
| Distributed race condition | Naive read-increment vs atomic ops | Lua script or sorted set in Redis [pp.67–69] |
| Distributed sync | Sticky sessions vs centralized store | Centralized data store (Redis) [p.69] |

## Key components

- API Gateway (or rate limiter middleware)
- Redis (counter storage with TTL)
- Rules engine (configurable throttle rules)
- Response headers: `X-Ratelimit-Remaining`, `X-Ratelimit-Limit`, `X-Ratelimit-Retry-After`

## Common traps

- Naive in-process counters don't work in distributed setting without shared store [p.63]
- Fixed window boundary burst: 2x traffic can sneak through at window edges [p.58]
- Race condition: two requests read same counter simultaneously without atomic ops [p.67]
- Sticky sessions for distributed sync — violates scalability [p.69]

## Deep-dive topics

- Five algorithm comparison: Token Bucket, Leaking Bucket, Fixed Window Counter, Sliding Window Log, Sliding Window Counter [pp.55–63]
- Distributed rate limiting: race condition + synchronization [pp.67–69]
- Rate limiter placement: L3/L4 vs L7 [p.54]
- Hard vs soft throttling [p.70]

## Key design dimensions

Algorithm selection and tradeoffs, Distributed systems (shared state, race conditions), Caching/in-memory storage, API gateway design

## Cross-references in sysdesign-frameworks

- `building-blocks.md` (cache section — Redis as shared counter store)
- `scalability-patterns.md` (stateless vs stateful tier; why centralized store matters)

**Source:** alex-xu-vol2-problems §rate-limiter, Alex Xu Vol.1 Ch.4 (pp.51–75)
