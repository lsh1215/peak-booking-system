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

This project implements a booking system for accommodation inventory that opens at `00:00` with only `10` available units. The system assumes two or more application server instances and a short burst of high traffic immediately after opening.

The core design goal is not only to process successful bookings, but to prove correctness under pressure:

- no overselling beyond the fixed stock count
- no permanent stock leak after payment failure or system failure
- fair handling of duplicate clicks and retries
- predictable degradation during Redis, DB, or payment instability
- documented trade-offs for Redis admission, DB final consistency, idempotency, fallback, and load testing

## Tech Stack

| Area | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.x |
| Runtime | Spring MVC or WebFlux decision pending design validation |
| Database | MySQL 8 |
| Cache / Admission Control | Redis |
| ORM | Spring Data JPA / Hibernate, pending implementation design |
| Testing | JUnit 5, Spring Boot Test, Testcontainers, load-test tool TBD |
| Documentation | Markdown, ADR-style decision records, source-backed research notes |

## Project Structure

```text
.
├── AGENTS.md
├── README.md
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

The backend source tree has not been generated yet. Once implementation starts, the expected source layout will be added under the project root and reflected here.

## Getting Started

The repository is currently in requirements and design setup phase. Backend bootstrap commands will be added after the Spring Boot project skeleton is generated.

Planned local workflow:

```bash
# 1. Start dependencies
docker compose up -d mysql redis

# 2. Run the application
./gradlew bootRun

# 3. Run tests
./gradlew test

# 4. Run load tests
# TBD after the load-test tool is selected
```

## API Endpoints

Initial API scope:

| Method | Endpoint | Purpose | Status |
|---|---|---|---|
| `GET` | `/api/v1/checkout/{productId}` | Read checkout information such as product, stay dates, price, and user point balance. | Planned |
| `POST` | `/api/v1/bookings` | Validate payment inputs, enforce idempotency, reserve/confirm stock, and create a final booking. | Planned |

Final request/response schemas will be documented after the system design and domain model are accepted.

## Key Documents

| Document | Purpose |
|---|---|
| [Requirements](docs/requirements.md) | Public-safe requirements summary. |
| [Documentation Map](docs/README.md) | Index of project documents. |
| [Decisions](docs/decisions/DECISIONS.md) | Redis, idempotency, fallback, load testing, and other trade-offs. |
| [Source-Backed Research](docs/research/source-backed-research-note.md) | Redis/MySQL/PostgreSQL/idempotency/resilience claims with sources. |
| [Mock Interview Design](docs/system-design/mock-interview.md) | Working system-design discussion document. |
| [Software Design Document](docs/system-design/sdd.md) | Formal SDD working document. |
| [Test-First Scenarios](docs/testing/test-first-scenarios.md) | Failure, race, duplicate, and overload scenarios to pin as tests. |
| [Adversarial Review](docs/reviews/adversarial-review.md) | Critic-mode findings for oversell, undersell, Redis outage, and retry storm risks. |
| [AI Usage](docs/ai/AI_USAGE.md) | Public disclosure of AI usage and human verification boundary. |

## Current Status

- [x] Public-safe requirements extracted
- [x] README initialized
- [x] AI usage, prompt log, and conversation log structure created
- [x] Decision, research, test-first, and adversarial review documents initialized
- [ ] Spring Boot backend skeleton
- [ ] Domain model and API schema
- [ ] Concurrency and idempotency tests
- [ ] Load-test scenario
- [ ] Final design decision review
