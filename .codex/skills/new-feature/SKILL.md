---
name: new-feature
description: Scaffold a new domain feature with TDD order (entity -> test -> service -> test -> controller -> test)
argument-hint: "<feature description>"
disable-model-invocation: true
allowed-tools: Read, Glob, Grep, Edit, Write, Bash
---

You are implementing a new domain feature for the E-Commerce Order Platform following TDD + DDD + Layered Architecture conventions.

**Feature to implement**: $ARGUMENTS

---

## Step 1: Parse Feature

Before generating any code, identify:

| Question | Answer (fill in) |
|----------|-----------------|
| Domain context | Which bounded context owns this feature? (Order / Payment / Product / Search) |
| Aggregate root | Which entity is the aggregate root? |
| Value objects | What value objects are needed? |
| Domain events | What events does this feature publish? |
| Service package | `com.ecommerce.{service}` |

---

## Step 2: Implementation Order (STRICT — do not skip ahead)

Implement in this exact sequence. Complete each item before starting the next.

| Step | Artifact | Package | File Naming |
|------|----------|---------|-------------|
| 1 | Domain entity (aggregate root) | `domain/model/` | `{Entity}.java` |
| 2 | Value objects | `domain/model/` | `{ValueObject}.java` (use `@Embeddable` record or class) |
| 3 | Domain event records | `domain/event/` | `{Entity}{Action}Event.java` |
| 4 | **Entity unit tests** (RED -> GREEN) | `test/.../domain/model/` | `{Entity}Test.java` |
| 5 | Repository interface | `domain/repository/` | `{Entity}Repository.java` |
| 6 | **Repository slice tests** | `test/.../infra/persistence/` | `{Entity}RepositoryTest.java` using `@DataJpaTest` + Testcontainers |
| 7 | Application service | `application/service/` | `{Feature}Service.java` |
| 8 | **Service unit tests** (RED -> GREEN) | `test/.../application/service/` | `{Feature}ServiceTest.java` |
| 9 | **Service integration tests** | `test/.../application/service/` | `{Feature}ServiceIntegrationTest.java` using `@SpringBootTest` + Testcontainers |
| 10 | Request DTOs | `api/dto/request/` | `Create{Entity}Request.java`, `Update{Entity}Request.java` |
| 11 | Response DTOs | `api/dto/response/` | `{Entity}Response.java`, `{Entity}Summary.java` |
| 12 | Controller | `api/controller/` | `{Entity}Controller.java` |
| 13 | **Controller slice tests** (RED -> GREEN) | `test/.../api/controller/` | `{Entity}ControllerTest.java` using `@WebMvcTest` |

---

## Step 3: Package Structure

All code must follow this package layout (from `AGENTS.md`):

```
com.ecommerce.{service}/
├── api/
│   ├── controller/
│   └── dto/
│       ├── request/    (CreateXxxRequest, UpdateXxxRequest — API contract only)
│       └── response/   (XxxResponse, XxxSummary, PageResponse<T> — API contract only)
├── application/
│   ├── service/
│   └── dto/            (XxxCommand, XxxResult — internal between layers only)
├── domain/
│   ├── model/          (entities, value objects)
│   ├── event/          (domain event records)
│   ├── repository/     (interfaces only — NO implementations)
│   └── service/        (domain services — cross-aggregate logic only)
└── infra/
    ├── persistence/    (JPA repository implementations)
    ├── kafka/          (producer/consumer)
    └── config/         (Spring configuration)
```

**DTO Location Rule**: Request/Response DTOs (API contract) go in `api/dto/`. Internal DTOs passed between application and domain layers go in `application/dto/`. Never mix locations.

---

## Step 4: Naming Conventions

| Artifact | Naming Rule | Example |
|----------|------------|---------|
| Entity | Singular PascalCase | `Order`, `Product`, `Payment` |
| Value Object | Noun PascalCase | `Money`, `OrderStatus`, `StockQuantity` |
| Domain Event | `{Entity}{PastTenseVerb}Event` | `OrderCreatedEvent`, `StockReservedEvent` |
| Repository | `{Entity}Repository` | `OrderRepository` |
| Application Service | `{Feature}Service` | `OrderService`, `PaymentProcessingService` |
| Create Request DTO | `Create{Entity}Request` | `CreateOrderRequest` |
| Update Request DTO | `Update{Entity}Request` | `UpdateOrderRequest` |
| Response DTO | `{Entity}Response` | `OrderResponse` |
| Summary DTO (list) | `{Entity}Summary` | `OrderSummary` |
| Test class | `{Subject}Test` | `OrderServiceTest` |

---

## Step 5: Entity Design Rules (from `.codex/skills/domain-modeling.md`)

Apply these rules to every entity:

| Rule | Requirement |
|------|-------------|
| Identity | `Long id` with `@GeneratedValue(strategy = GenerationType.IDENTITY)` |
| Constructor | Protected no-arg constructor + static factory method or builder |
| Field access | `@Access(AccessType.FIELD)` |
| Collections | Expose unmodifiable view; mutate via domain methods |
| Enums | `@Enumerated(EnumType.STRING)` always |
| Lazy loading | `@ManyToOne(fetch = FetchType.LAZY)` always |
| Timestamps | `@CreatedDate`, `@LastModifiedDate` via `@EntityListeners(AuditingEntityListener.class)` |
| Cascade | `CascadeType.ALL` + `orphanRemoval = true` on aggregate root -> owned children only |
| Cross-aggregate | Reference by ID only (Long field), never object reference |
| Table naming | Singular snake_case: `order_item`, not `OrderItems` |

---

## Step 6: Test Conventions (from `.codex/skills/tdd-patterns.md`)

### TDD Cycle per artifact

For each test file:
1. RED: Write the failing test first (compile error is acceptable)
2. GREEN: Write minimum code to pass
3. REFACTOR: Clean up, extract, rename

### Test naming pattern
`should_{expected_behavior}_when_{condition}`

Examples:
- `should_create_order_when_valid_request`
- `should_throw_exception_when_insufficient_stock`
- `should_return_empty_when_product_not_found`
- `should_publish_event_when_order_placed`

### Test type per layer

| Layer | Annotation | Mock Strategy |
|-------|-----------|--------------|
| Entity unit test | `@ExtendWith(MockitoExtension.class)` | Nothing — pure Java |
| Repository slice | `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + Testcontainers PostgreSQL | Nothing |
| Service unit test | `@ExtendWith(MockitoExtension.class)` | Mock repositories, domain services |
| Service integration | `@SpringBootTest` + Testcontainers PostgreSQL | External APIs only |
| Controller slice | `@WebMvcTest({Entity}Controller.class)` | `@MockBean` application service |

---

## Step 7: Controller and Layer Rules (from `.codex/skills/layer-architecture.md`)

### Controller checklist
- `@RestController` + `@RequestMapping("/api/v1/{resource}")`
- No business logic in controller — delegate entirely to application service
- `@Valid` on all `@RequestBody` parameters
- Return `ResponseEntity<T>` with correct HTTP status codes
- No entity references — DTO only at controller boundary

### REST URI pattern

| Operation | Method | URI | Response Status |
|-----------|--------|-----|----------------|
| Create | POST | `/api/v1/{resource}` | 201 Created |
| Get one | GET | `/api/v1/{resource}/{id}` | 200 OK |
| List | GET | `/api/v1/{resource}?page=&size=&sort=` | 200 OK |
| Update | PUT | `/api/v1/{resource}/{id}` | 200 OK |
| Delete | DELETE | `/api/v1/{resource}/{id}` | 204 No Content |

### Transaction boundaries
- `@Transactional` on application service public methods only
- Read-only queries: `@Transactional(readOnly = true)`
- Publish domain events after commit: `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`

---

## Step 8: Output Summary

After completing all 13 steps, print this summary table:

| Step | File Path | Status |
|------|-----------|--------|
| 1 | `src/main/java/com/ecommerce/{service}/domain/model/{Entity}.java` | Created |
| 2 | `src/main/java/com/ecommerce/{service}/domain/model/{ValueObject}.java` | Created |
| 3 | `src/main/java/com/ecommerce/{service}/domain/event/{Entity}{Action}Event.java` | Created |
| 4 | `src/test/java/com/ecommerce/{service}/domain/model/{Entity}Test.java` | Created |
| 5 | `src/main/java/com/ecommerce/{service}/domain/repository/{Entity}Repository.java` | Created |
| 6 | `src/test/java/com/ecommerce/{service}/infra/persistence/{Entity}RepositoryTest.java` | Created |
| 7 | `src/main/java/com/ecommerce/{service}/application/service/{Feature}Service.java` | Created |
| 8 | `src/test/java/com/ecommerce/{service}/application/service/{Feature}ServiceTest.java` | Created |
| 9 | `src/test/java/com/ecommerce/{service}/application/service/{Feature}ServiceIntegrationTest.java` | Created |
| 10 | `src/main/java/com/ecommerce/{service}/api/dto/request/Create{Entity}Request.java` | Created |
| 11 | `src/main/java/com/ecommerce/{service}/api/dto/response/{Entity}Response.java` | Created |
| 12 | `src/main/java/com/ecommerce/{service}/api/controller/{Entity}Controller.java` | Created |
| 13 | `src/test/java/com/ecommerce/{service}/api/controller/{Entity}ControllerTest.java` | Created |

Then run `./gradlew test` and report the result. If any tests fail, fix them before declaring completion.
