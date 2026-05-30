# Peak Booking System

[한국어](README.md) | [English](README-en.md)

![Java](https://img.shields.io/badge/Java_21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.5.x-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL_8-4479A1?style=flat-square&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis&logoColor=white)

Limited-stock booking and payment backend for a midnight flash-sale scenario.

## Table of Contents

- [Project Overview](#project-overview)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [API Endpoints](#api-endpoints)
- [Key Documents](#key-documents)
- [Current Status](#current-status)

## Project Overview

This project implements a booking system for a `10-unit` limited accommodation deal that opens at `00:00`. The system assumes two or more application server instances, a short burst of high traffic immediately after opening, and constrained scale-up/out during the promotion.

The core design goal is not only to process successful bookings, but to prove correctness under pressure:

- no overselling beyond the fixed `10-unit` stock count
- no permanent stock leak after payment failure or system failure
- fair handling of duplicate clicks and retries
- predictable degradation during Redis, DB, or payment instability
- documented trade-offs for Redis admission, DB final consistency, idempotency, fallback, and load testing

## Tech Stack

The stack below reflects the current `origin/main` bootstrap and is accepted as the project baseline in DEC-000 of [DECISIONS.md](docs/decisions/DECISIONS.md).

| Area | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.x |
| Runtime | Spring MVC or WebFlux decision pending design validation |
| Database | MySQL 8 |
| Cache / Admission Control | Redis |
| ORM | Spring Data JPA / Hibernate |
| Load Testing | k6 |
| Observability | LGTM stack, Micrometer Prometheus, OpenTelemetry Java agent |
| Documentation | Markdown, ADR-style decision records, source-backed research notes |

## Project Structure

```text
.
├── AGENTS.md
├── README.md
├── README-en.md
├── backend
│   ├── build.gradle
│   ├── settings.gradle
│   ├── src
│   └── Dockerfile
├── docker-compose.yml
├── infra
│   └── observability
├── k6
├── k8s
├── docs
│   ├── requirements.md
│   ├── ai
│   ├── decisions
│   ├── research
│   ├── reviews
│   ├── system-design
│   └── testing
└── .codex
    ├── agents
    ├── hooks
    └── skills
```

The backend now starts as a single Spring Boot application. The application stays stateless, but peak-time correctness and collapse prevention must be explained through admission control, backpressure, and the final RDB correctness guard rather than relying only on adding replicas.

- `backend/src/main/java/com/peakbooking`: single Spring Boot application entrypoint
- `backend/src/main/java/com/peakbooking/common`: common response/exception/JPA auditing/CORS/OpenAPI configuration
- `backend/src/main/java/com/peakbooking/booking`: package for booking-domain APIs and the initial health-check API
- `infra/observability`: local LGTM observability configuration and Grafana dashboard provisioning
- `k6`: health-check smoke load test
- `k8s`: Kubernetes kustomize manifests

## Getting Started

Local execution uses Docker Compose for MySQL, Redis, LGTM, and the application.

```bash
# 1. Compile and test
cd backend
./gradlew compileJava test --no-daemon
cd ..

# 2. Start the local stack
docker compose up -d mysql redis lgtm booking-service

# 3. Health check
curl http://localhost:8080/api/v1/health

# 4. k6 smoke load test
docker compose run --rm -e RATE=20 -e DURATION=10s k6

# 5. Stop
docker compose down
```

Grafana is available at `http://localhost:3000`. The provisioned default dashboard is Grafana dashboard template `4701 JVM (Micrometer)`, adapted for this project.

Render the Kubernetes manifests with:

```bash
kubectl kustomize k8s/base
kubectl kustomize k8s/local
```

## API Endpoints

Initial API scope:

| Method | Endpoint | Purpose | Status |
|---|---|---|---|
| `GET` | `/api/v1/checkout/{productId}` | Read checkout information such as product, stay dates, price, and user Y-point balance. | Planned |
| `POST` | `/api/v1/bookings` | Validate payment inputs, enforce idempotency, reserve/confirm stock, and create a final booking. | Planned |
| `GET` | `/api/v1/health` | Service startup and smoke/load-test health check. | Implemented |

Final request/response schemas will be documented after the system design and domain model are accepted.

## Key Documents

| Document | Purpose |
|---|---|
| [Requirements](docs/requirements.md) | Public-safe requirements summary. |
| [Documentation Map](docs/README.md) | Index of project documents. |
| [Decisions](docs/decisions/DECISIONS.md) | Redis, idempotency, fallback, load testing, and other trade-offs. |
| [Source-Backed Research](docs/research/source-backed-research-note.md) | Redis/MySQL/idempotency/resilience/PG Mock claims with sources. |
| [Mock Interview Design](docs/system-design/mock-interview.md) | Working system-design discussion document. |
| [Software Design Document](docs/system-design/sdd.md) | Formal SDD working document. |
| [Test-First Scenarios](docs/testing/test-first-scenarios.md) | Failure, race, duplicate, and overload scenarios to pin as tests. |
| [Bootstrap Verification](docs/testing/bootstrap-verification.md) | Backend, local deployment, observability, and k6 verification evidence. |
| [Adversarial Review](docs/reviews/adversarial-review.md) | Critic-mode findings for oversell, undersell, Redis outage, and retry storm risks. |
| [AI Usage](docs/ai/AI_USAGE.md) | Public disclosure of AI usage and human verification boundary. |

## Current Status

- [x] Public-safe requirements extracted
- [x] README initialized
- [x] AI usage, prompt log, and conversation log structure created
- [x] Decision, research, test-first, and adversarial review documents initialized
- [x] Spring Boot backend skeleton
- [ ] Domain model and API schema
- [ ] Concurrency and idempotency tests
- [x] Initial k6 health-check load-test scenario
- [ ] Final design decision review
