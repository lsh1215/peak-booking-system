---
name: tdd-patterns
description: TDD workflow, per-layer test types, naming conventions, and test fixture patterns for Spring Boot. Use when writing or reviewing tests.
triggers:
  - "tdd"
  - "test first"
  - "red green refactor"
  - "@test"
  - "junit5"
  - "mockito mock"
  - "testcontainers"
  - "단위 테스트"
  - "통합 테스트"
---

> Note: The `tdd` keyword may also trigger OMX/Codex's built-in TDD guidance. This overlap is acceptable — the built-in guidance provides general TDD workflow while this skill provides project-specific layer test patterns and fixture conventions.

## 1. TDD Workflow

- [ ] RED: Write failing test FIRST (compile error is OK)
- [ ] GREEN: Write minimum code to pass
- [ ] REFACTOR: Clean up, extract, rename
- [ ] Repeat for next behavior

## 2. Test Types by Layer

| Layer | Test Type | Framework | What to Test | What to Mock |
|-------|-----------|-----------|-------------|-------------|
| Entity/Domain | Unit | JUnit 5 | Invariants, validation, state transitions, equality | Nothing |
| Domain Service | Unit | JUnit 5 + Mockito | Business logic, cross-aggregate rules | Repository interfaces |
| Application Service | Unit | JUnit 5 + Mockito | Orchestration, DTO conversion, event publishing | Repository, domain services, infra |
| Application Service | Integration | `@SpringBootTest` + Testcontainers | Full flow with real DB | External APIs only |
| Controller | Slice | `@WebMvcTest` | Request mapping, validation, serialization | Application service |
| Repository | Slice | `@DataJpaTest` + Testcontainers | Custom queries, projections | Nothing |

## 3. Test Naming Convention

Pattern: `should_{expected_behavior}_when_{condition}`

| Example |
|---------|
| `should_create_order_when_valid_request` |
| `should_throw_exception_when_insufficient_stock` |
| `should_return_empty_when_product_not_found` |
| `should_publish_event_when_order_placed` |

## 4. Test Fixture Patterns

| Pattern | When | How |
|---------|------|-----|
| Builder/Factory | Domain objects | `OrderFixture.aValidOrder().withStatus(PENDING).build()` |
| `@Sql` | DB state setup | `@Sql("/test-data/orders.sql")` |
| Testcontainers | Integration tests needing Kafka/PostgreSQL/ES | `@Container static PostgreSQLContainer<?>` |
| `@MockBean` | Spring slice tests | Mock application service in controller test |
| ArgumentCaptor | Verify event publishing | `ArgumentCaptor<OrderCreatedEvent>` |

## 5. Test Organization

- [ ] Test class mirrors source class: `OrderService` -> `OrderServiceTest`
- [ ] `src/test/java` mirrors `src/main/java` package structure
- [ ] Integration tests annotated `@Tag("integration")`
- [ ] Unit tests run in < 1 second each
- [ ] `@Nested` for grouping related scenarios
