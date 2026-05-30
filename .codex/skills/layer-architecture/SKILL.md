---
name: layer-architecture
description: Layered architecture conventions for service types, transaction boundaries, REST API design, DTO patterns, and error handling. Use when implementing or reviewing service/controller layer code.
triggers:
  - "@restcontroller"
  - "controller layer"
  - "service layer"
  - "rest api"
  - "@transactional"
  - "api endpoint"
  - "dto pattern"
  - "레이어 아키텍처"
  - "api 설계"
---

## 1. Service Type Distinction

| Type | Responsibility | Annotation | Transaction | Example |
|------|---------------|------------|-------------|---------|
| Application Service | Use-case orchestration, DTO conversion, event dispatch | `@Service` | `@Transactional` | `OrderService.placeOrder(dto)` |
| Domain Service | Cross-aggregate business logic, no infra dependency | `@Component` | None (caller manages) | `PricingService.calculate(order, coupon)` |
| Infrastructure Service | External system calls, messaging | `@Component` | Per-method | `PaymentGateway.charge()` |

## 2. Transaction Boundary Rules

- [ ] `@Transactional` on application service public methods only
- [ ] Read-only operations: `@Transactional(readOnly = true)`
- [ ] Never nest `@Transactional` across service calls (use `REQUIRES_NEW` sparingly)
- [ ] Event publishing AFTER transaction commit (`@TransactionalEventListener(phase = AFTER_COMMIT)`)
- [ ] Catch and translate infrastructure exceptions at service boundary

## 3. REST API Design Conventions

| Resource | Method | URI | Request Body | Response |
|----------|--------|-----|-------------|----------|
| Create | POST | `/api/v1/{resource}` | CreateRequest DTO | 201 + created DTO |
| Get one | GET | `/api/v1/{resource}/{id}` | -- | 200 + DTO |
| List | GET | `/api/v1/{resource}?page=&size=&sort=` | -- | 200 + Page wrapper |
| Update | PUT/PATCH | `/api/v1/{resource}/{id}` | UpdateRequest DTO | 200 + updated DTO |
| Delete | DELETE | `/api/v1/{resource}/{id}` | -- | 204 No Content |

## 4. Controller Rules

- [ ] `@RestController` + `@RequestMapping("/api/v1/{resource}")`
- [ ] No business logic in controller (delegate to application service)
- [ ] `@Valid` on all `@RequestBody` parameters
- [ ] Return `ResponseEntity<T>` with appropriate status codes
- [ ] `@PathVariable` for resource IDs, `@RequestParam` for filters
- [ ] No entity references in controller layer (DTO only)

## 5. DTO Pattern and Location

| DTO Type | Naming | Location | Purpose |
|----------|--------|----------|---------|
| Create Request | `Create{Resource}Request` | `api/dto/request/` | Controller input; has `@Valid` annotations |
| Update Request | `Update{Resource}Request` | `api/dto/request/` | Controller input; partial update fields |
| Response | `{Resource}Response` | `api/dto/response/` | Controller output; no validation annotations |
| Summary (list) | `{Resource}Summary` | `api/dto/response/` | Subset of fields for list views |
| Page wrapper | `PageResponse<T>` | `api/dto/response/` | Generic wrapper with pagination metadata |
| Internal DTO | `{Feature}Command` / `{Feature}Result` | `application/dto/` | Between application and domain layers |

**Rule**: Request/Response DTOs (API contract) always go in `api/dto/`. Internal DTOs passed between services or layers go in `application/dto/`. Never mix the two locations.

## 6. Error Handling Standard

| Exception Type | HTTP Status | Response Body |
|---------------|-------------|---------------|
| `EntityNotFoundException` | 404 | `{ code, message, timestamp }` |
| `IllegalArgumentException` / Validation | 400 | `{ code, message, fieldErrors[] }` |
| `DuplicateKeyException` | 409 | `{ code, message }` |
| `AccessDeniedException` | 403 | `{ code, message }` |
| Business rule violation | 422 | `{ code, message }` |
| Unhandled | 500 | `{ code, message }` (no stack trace) |

Structure: `@RestControllerAdvice` with `@ExceptionHandler` methods returning `ErrorResponse` record.

## 7. Event Publishing Pattern

| Phase | Pattern | When |
|-------|---------|------|
| Monolith (Phase 1-2) | `ApplicationEventPublisher` + `@EventListener` | Synchronous in-process |
| Kafka intro (Phase 3) | `ApplicationEventPublisher` + `@TransactionalEventListener` -> Kafka producer | After commit, async |
| MSA (Phase 4+) | Outbox table + Kafka Connect or polling publisher | Guaranteed delivery |
