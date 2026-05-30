# Source-Backed Research Note

이 문서는 Redis, MySQL/PostgreSQL, idempotency, resilience 관련 설계 주장을 출처와 함께 정리한다.

## Research Rules

- Prefer official documentation and primary engineering sources.
- Mark claims as `Verified`, `Needs validation`, or `Project assumption`.
- Do not turn source guidance into a project decision without recording the trade-off in `docs/decisions/DECISIONS.md`.

## Claims

| Topic | Claim | Status | Source | Project Implication |
|---|---|---|---|---|
| Redis Lua | Redis Lua scripts are useful for combining multiple Redis operations into a single server-side atomic step. | Verified | [Redis Lua scripting docs](https://redis.io/docs/latest/develop/programmability/eval-intro/) | Redis admission can use Lua for check-and-decrement style logic, but DB must still be final source of truth. |
| Redis locks | Redis documents a distributed lock pattern and the Redlock algorithm, but lock safety depends on precise TTL/value/delete behavior. | Verified | [Redis distributed locks](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/) | Avoid casual Redis lock usage for final booking correctness unless the safety assumptions are explicitly tested. |
| MySQL locking | InnoDB locking reads such as `SELECT ... FOR UPDATE` lock rows for update and can be used for concurrency control. | Verified | [MySQL InnoDB locking reads](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/innodb-locking-reads.html), [InnoDB locking](https://dev.mysql.com/doc/refman/8.1/en/innodb-locking.html) | DB final stock guard can use conditional updates or locking reads, but hot-row contention must be load tested. |
| PostgreSQL locking | PostgreSQL supports explicit row locking modes including `FOR UPDATE`. | Verified | [PostgreSQL explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html) | If PostgreSQL is selected later, the same final consistency idea can be mapped to PG transaction/locking semantics. |
| PostgreSQL uniqueness | PostgreSQL partial unique indexes can enforce uniqueness only for rows matching a predicate. | Verified | [PostgreSQL partial indexes](https://www.postgresql.org/docs/18/indexes-partial.html) | Useful if only confirmed bookings should be unique, but this project currently targets MySQL-family RDBMS. |
| Idempotency | Stripe stores the status code/body for the first request made with an idempotency key and returns the same result for later retries. | Verified | [Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests) | Booking API should store request hash and logical result so retries do not duplicate side effects. |
| Retry safety | AWS warns that retries for side-effecting APIs are unsafe unless the API provides idempotency. | Verified | [AWS Builders Library: timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) | Booking/payment retries require idempotency keys and bounded retry policy. |
| Retry storm | AWS recommends backoff and jitter to spread retries and reduce congestion. | Verified | [AWS Builders Library: timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) | Use retry caps, exponential backoff, jitter, and fast failure under overload. |
| Resilience4j | Resilience4j provides decorators/modules for CircuitBreaker, Retry, RateLimiter, Bulkhead, and TimeLimiter. | Verified | [Resilience4j introduction](https://resilience4j.readme.io/docs/getting-started), [Resilience4j Spring Boot docs](https://resilience4j.readme.io/v2.0.0/docs/getting-started-3) | Candidate library for bounded fallback, payment client resilience, and overload protection if library adoption is justified. |

## Open Research Questions

- MySQL-specific unique index strategy for "one confirmed booking per user per product" when statuses change.
- Whether Redis admission should reserve capacity before payment or only gate DB attempts.
- Whether fallback policy should be fail-closed or bounded DB fallback for the first implementation.
- Exact load-test tool and metrics to use for 500~1000 TPS burst.
