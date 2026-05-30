---
name: domain-modeling
description: >-
  Applies Eric Evans' Domain-Driven Design patterns to design and review domain model classes.
  Covers tactical patterns (Entity, Value Object, Aggregate, Service, Repository, Factory, Domain Event)
  and strategic patterns (Bounded Context, Context Map, Anti-Corruption Layer, Core Domain, Distillation).
  Use when designing JPA entities, defining aggregate boundaries, identifying value objects,
  mapping bounded contexts, creating domain events, or reviewing domain model code.
  Also use when asked about ubiquitous language, context maps, or anti-corruption layers.
triggers:
  - "jpa entity"
  - "aggregate root"
  - "value object"
  - "@embeddable"
  - "엔티티 설계"
  - "도메인 모델"
  - "bounded context"
  - "domain event"
  - "도메인 주도"
  - "ubiquitous language"
  - "context map"
  - "anti-corruption layer"
  - "core domain"
  - "domain service"
  - "specification pattern"
---

# Domain-Driven Design Patterns

> Reference: Eric Evans, "Domain-Driven Design: Tackling Complexity in the Heart of Software"

## Foundational Principles

| Principle | What it means | Violation signal |
|-----------|--------------|-----------------|
| **Ubiquitous Language** | One shared vocabulary in code, docs, and conversation | Same concept has different names in different places |
| **Model-Driven Design** | Code IS the model; they evolve together | Analysis diagrams that don't match the code |
| **Hands-On Modeler** | Modelers write code; coders participate in modeling | "Ivory tower" architects disconnected from implementation |
| **Knowledge Crunching** | Iterative domain expert ↔ developer collaboration | Building features without domain expert input |

## Tactical Patterns

### Entity (Ch. 5)

Objects defined by **identity and continuity**, not attributes. Two entities with identical fields are still different if their identities differ.

| Aspect | Rule |
|--------|------|
| Identity | `Long id` (DB) + `String publicId` (ULID, external API) |
| Equality | Override `equals`/`hashCode` on id or business key |
| Constructor | Protected no-arg + static factory method |
| Behavior | Entity owns its domain logic; avoid anemic getters-only |
| Lifecycle | Track through state transitions; state is part of the model |

### Value Object (Ch. 5)

Objects defined by **attributes only**, with no identity. Immutable. Two VOs with same values are interchangeable.

| Aspect | Rule |
|--------|------|
| Implementation | `@Embeddable` (JPA) or `record` (DTO) |
| Immutability | Final fields, no setters; replace whole object on change |
| Conceptual wholeness | Group related attrs (Address = street+city+zip, not loose fields) |
| Side-effect-free | Methods return new instances |
| Validation | Self-validating constructor (fail-fast) |

**Key question**: "Do I need to distinguish between two instances with the same values?" If no → Value Object.

### Aggregate (Ch. 6)

A cluster of entities and VOs treated as a **single unit for transactions**.

| Rule | Rationale |
|------|-----------|
| Only Root has a Repository | Preserves invariants; external access through root only |
| Children accessed through Root | Root enforces all invariants |
| Cross-aggregate refs by ID only | Prevents coupling; enables separate transactions |
| One transaction = one aggregate | Aggregate IS the consistency boundary |
| Keep aggregates small | Large aggregates → concurrency contention |
| Eventual consistency between aggregates | Use domain events, not distributed transactions |

**Checklist for identifying aggregates:**
- [ ] Independent lifecycle?
- [ ] Invariants spanning multiple entities?
- [ ] Natural loading unit?
- [ ] Small enough to minimize lock contention?

### Service (Ch. 5)

Stateless operations that don't belong to any Entity or Value Object. Named as **verbs/activities** from the Ubiquitous Language.

| Layer | Responsibility | Naming |
|-------|---------------|--------|
| Domain Service | Cross-entity domain logic | Verb from domain: `ValidateAllocation`, `CalculateExchangeRate` |
| Application Service | Use case orchestration, transactions | `CreateOrderUseCase`, `ProcessPaymentUseCase` |
| Infrastructure Service | Technical: DB, messaging, APIs | `ProductClient`, `OrderEventProducer` |

### Repository (Ch. 6)

| Rule | Detail |
|------|--------|
| One per Aggregate Root | Never for child entities |
| Interface in `domain.repository` | Implementation in `infra.persistence` |
| Query methods express domain concepts | `findByPublicId()` not `findByField1AndField2()` |
| Returns complete aggregates | Not raw data or projections |

### Factory (Ch. 6)

Use when object creation is complex, requires knowledge outside the created object, or must enforce invariants at birth.

```java
public static Orders create(String customerId, List<OrderItem> items,
                             Money totalAmount, String idempotencyKey) {
    if (items.isEmpty()) throw new BusinessException(...);
    return new Orders(customerId, items, totalAmount, idempotencyKey);
}
```

### Domain Event (Ch. 8+)

Something that happened in the domain that other parts of the system need to know about.

| Rule | Detail |
|------|--------|
| Immutable records | Never modify after publication |
| IDs, not objects | Consumer fetches current state if needed |
| Past tense naming | `OrderCreatedEvent`, not `CreateOrderEvent` |
| Minimal payload | Only what consumers need to react |

## Strategic Patterns

### Bounded Context (Ch. 14)

A boundary within which a model is consistent. Same word can mean different things in different contexts.

| Pattern | When | Example |
|---------|------|---------|
| **Shared Kernel** | Two teams share a small model subset | `common` module: BaseEntity, ErrorCode |
| **Customer-Supplier** | Upstream serves downstream | Order → Inventory |
| **Conformist** | Downstream accepts upstream's model | Drop → Product (variant refs) |
| **Anti-Corruption Layer** | Protect model from external influence | Order → Product via RestClient + DTO translation |
| **Published Language** | Standardized interchange format | Domain events as JSON records |
| **Separate Ways** | No integration | Customer ↔ Product |

### Distillation (Ch. 15)

| Concept | What | Decision |
|---------|------|----------|
| **Core Domain** | Competitive advantage; invest most here | Where the best developers work |
| **Generic Subdomain** | Solved problems; buy or use standard solutions | Use libraries, minimal custom code |
| **Supporting Subdomain** | Necessary but not differentiating | Good enough; don't over-engineer |
| **Domain Vision Statement** | Short doc articulating the core domain's value | Keep updated as understanding evolves |

### Specification Pattern (Ch. 9)

Encapsulate business rules for selection, validation, or construction as combinable objects:

```java
public interface Specification<T> {
    boolean isSatisfiedBy(T candidate);
    Specification<T> and(Specification<T> other);
    Specification<T> or(Specification<T> other);
}
```

### Strategy / Policy (Ch. 12)

Extract varying business rules into interchangeable objects:

```java
public interface ShippingPolicy {
    Money calculate(Order order, Address destination);
}
```

## Layered Architecture (Ch. 4)

```
┌─────────────────────────────┐
│  API (Controllers, DTOs)    │  api.controller, api.dto.request/response
├─────────────────────────────┤
│  Application (Use Cases)    │  application.service, application.usecase
├─────────────────────────────┤
│  Domain (THE MODEL)         │  domain.model, domain.event, domain.repository, domain.service
├─────────────────────────────┤
│  Infrastructure (Tech)      │  infra.persistence, infra.client, infra.kafka
└─────────────────────────────┘
```

**Dependencies point downward only. Domain layer has ZERO dependencies on infrastructure.**

## JPA Conventions

| Mapping | Convention |
|---------|-----------|
| Table naming | Singular snake_case: `order_item` |
| Enum mapping | `@Enumerated(EnumType.STRING)` always |
| Lazy loading | `@ManyToOne(fetch = LAZY)` default |
| Cascade | Root → child only: `CascadeType.ALL` + `orphanRemoval` |
| Optimistic locking | `@Version` on concurrent aggregates |

## Domain Document Checklist

When doing domain design work, produce these artifacts (see `docs/domain/`):

1. **Domain Vision Statement** — Core domain's value proposition
2. **Ubiquitous Language glossary** — Term definitions per Bounded Context
3. **Bounded Context Map** — Contexts, relationships, integration patterns
4. **Use Cases** — Actor, preconditions, main flow, alternatives, postconditions
5. **Business Requirements Conversation** — Domain expert ↔ developer dialogue
6. **Aggregate Design** — Boundaries, invariants, ID references, concurrency

