---
name: resilience
description: Resilience4j circuit breaker, retry, rate limiter, and bulkhead patterns with testing strategies. Use when implementing fault tolerance between services.
triggers:
  - "circuit breaker"
  - "resilience4j"
  - "retry pattern"
  - "fallback method"
  - "rate limiter"
  - "bulkhead pattern"
  - "서킷 브레이커"
  - "장애 대응"
---

## 1. Resilience4j Configuration

| Pattern | Config Key | Default Value | Purpose |
|---------|-----------|---------------|---------|
| Circuit Breaker | `failureRateThreshold` | `50` (%) | Open circuit when 50% of calls fail |
| Circuit Breaker | `slidingWindowSize` | `10` | Number of calls to evaluate |
| Circuit Breaker | `waitDurationInOpenState` | `10s` | Time before half-open |
| Retry | `maxAttempts` | `3` | Total attempts including initial |
| Retry | `waitDuration` | `500ms` | Wait between retries |
| Rate Limiter | `limitForPeriod` | `100` | Max calls per period |
| Bulkhead | `maxConcurrentCalls` | `25` | Max concurrent calls |

## 2. Pattern Application Map

| Service Call | Pattern(s) | Fallback |
|-------------|-----------|----------|
| Payment -> External gateway | CircuitBreaker + Retry | Queue for later processing |
| Order -> Payment service | CircuitBreaker | Accept order, process payment async |
| Order -> Product service | CircuitBreaker + Retry | Return cached stock status |
| API Gateway -> any service | Rate Limiter + Bulkhead | 429 Too Many Requests |

## 3. Implementation Checklist

- [ ] `@CircuitBreaker(name = "...", fallbackMethod = "...")` on service methods
- [ ] `@Retry(name = "...")` on idempotent operations only
- [ ] Fallback method has same signature + `Throwable` parameter
- [ ] Config in `application.yml` under `resilience4j.*`
- [ ] Actuator endpoints exposed for monitoring (`/actuator/circuitbreakers`)
- [ ] Circuit breaker state changes logged

## 4. Testing Resilience

- [ ] Unit test: mock service to throw exception, verify fallback invoked
- [ ] Integration test: stop dependent container, verify circuit opens
- [ ] k6 test: measure response time with circuit open vs closed
- [ ] Chaos test: kill payment-service container during load test
