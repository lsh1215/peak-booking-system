---
name: phase-check
description: Validate current project phase deliverables against PRD checklist
argument-hint: "<phase-number or 'current'>"
disable-model-invocation: true
allowed-tools: Read, Glob, Grep, Bash
---

You are validating project deliverables for Phase $ARGUMENTS against the PRD at `docs/PRD.md`.

If `$ARGUMENTS` is `current`, first read `docs/PRD.md` and determine which phase is currently in progress based on what has been implemented.

---

## Step 1: Read PRD Requirements

Read `docs/PRD.md` in full. Extract the deliverables listed for Phase $ARGUMENTS.

If the file does not exist, stop and report: "docs/PRD.md not found. Cannot validate phase deliverables."

---

## Step 2: Phase Deliverable Reference

Use this reference table for each phase's expected deliverables. Cross-check against `docs/PRD.md` — PRD is authoritative; this table is a fallback if PRD is sparse.

| Phase | Expected Deliverables |
|-------|-----------------------|
| 0 | Project skeleton (Gradle multi-module or single module), Docker Compose for PostgreSQL, `.env.example`, `docs/` directory, `CLAUDE.md` with project conventions |
| 1 | Monolith: Order domain (entity + service + controller + tests), Payment domain (entity + service + controller + tests), Product domain (entity + service + controller + tests), Search domain (basic DB query, no ES), all unit + integration tests passing, REST API for all 4 domains |
| 2 | k6 load test scripts in `k6/`, baseline performance measurements recorded in `docs/performance/phase-2-baseline.md` (p50/p95/p99 for each endpoint), identified bottlenecks documented |
| 3 | Kafka (KRaft) running in Docker Compose, order-payment async flow via Kafka, `kafka producer` + `kafka consumer` implementations, outbox table + polling publisher or `@TransactionalEventListener` producer, dead letter queue config, all existing tests still passing |
| 4 | Services split into separate Spring Boot apps (Order, Payment, Product), SAGA orchestration for order placement, `saga_instance` table, compensation flows implemented, service-to-service communication via Kafka only, each service has own database |
| 5 | ElasticSearch + nori tokenizer running in Docker Compose, `ProductDocument` indexed, search API (`/api/v1/search/products`), Kafka -> ES sync consumer, performance comparison `docs/performance/search-comparison.md` |
| 6 | Resilience4j circuit breakers on all inter-service calls, retry + fallback methods, `docs/resilience/chaos-test-results.md`, Actuator endpoints for circuit breaker monitoring, k6 chaos test scripts |
| 7 | Prometheus + Grafana dashboards in Docker Compose, custom metrics for order/payment flows, `docs/monitoring/dashboard-guide.md`, full project retrospective in `docs/retrospective.md` |

---

## Step 3: Codebase Scan

For the target phase, scan the codebase to find evidence of each deliverable:

### Scan strategy

| Check Type | Method |
|-----------|--------|
| File existence | Glob for expected file paths (e.g., `src/**/OrderService.java`) |
| Pattern presence | Grep for expected annotations/keywords (e.g., `@KafkaListener`, `@CircuitBreaker`) |
| Test coverage | Grep for test class names, count `@Test` annotations |
| Config presence | Check `docker-compose.yml` for required services (postgres, kafka, elasticsearch) |
| Docs presence | Check `docs/` for required documentation files |

---

## Step 4: Run Tests

Run the test suite:

```
./gradlew test
```

Capture pass/fail counts. If `./gradlew` is not present, try `./gradlew --version` to confirm Gradle wrapper exists.

---

## Step 5: Gap Analysis Output

Generate a markdown report with this exact structure:

### Feature Status Table

| Feature | Status | Evidence |
|---------|--------|----------|
| {deliverable name} | Done / Partial / Missing | {file path, test name, or "not found"} |

**Status definitions**:
- **Done**: File exists AND tests pass AND implementation is complete
- **Partial**: File exists but tests missing, or implementation incomplete
- **Missing**: No evidence found in codebase

### Summary

| Metric | Count |
|--------|-------|
| Total deliverables checked | N |
| Done | N |
| Partial | N |
| Missing | N |
| Test pass rate | N/N (N%) |

### Action Items for Missing / Partial Items

List each incomplete item with a specific, actionable task:

| Priority | Item | Action Required |
|----------|------|----------------|
| High | {item name} | {specific step: e.g., "Create OrderService.java in application/service/, add @Transactional, implement placeOrder(CreateOrderRequest)"} |
| Medium | {item name} | {specific step} |
| Low | {item name} | {specific step} |

---

## Step 6: Save Report

Save the complete report to:

```
docs/phase-$ARGUMENTS-status.md
```

Create the `docs/` directory if it does not exist.

Use this file header:

```markdown
# Phase $ARGUMENTS Status Report

**Generated**: {current date}
**Phase**: $ARGUMENTS
**Overall Status**: {Done / In Progress / Not Started}

---
```

After saving, print the absolute file path and confirm: "Phase $ARGUMENTS status report saved to docs/phase-$ARGUMENTS-status.md"
