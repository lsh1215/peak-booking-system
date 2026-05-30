# Peak Booking System

[한국어](README.md) | [English](README-en.md)

![Java](https://img.shields.io/badge/Java_21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.5.x-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL_8-4479A1?style=flat-square&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis&logoColor=white)

`00:00`에 오픈되는 한정 재고 숙소 상품을 위한 예약/결제 백엔드 프로젝트입니다.

## 목차

- [프로젝트 개요](#프로젝트-개요)
- [기술 스택](#기술-스택)
- [프로젝트 구조](#프로젝트-구조)
- [시작 방식](#시작-방식)
- [API 엔드포인트](#api-엔드포인트)
- [주요 문서](#주요-문서)
- [현재 상태](#현재-상태)

## 프로젝트 개요

이 프로젝트는 `00:00`에 오픈되는 한정 수량 숙소 상품 예약 시스템을 구현합니다. 대상 상품 재고는 `10개`로 제한되며, 오픈 직후 짧은 시간 동안 높은 트래픽이 몰리는 상황을 가정합니다.

핵심 목표는 단순히 성공 예약을 처리하는 것이 아니라, 압박 상황에서도 정합성을 증명하는 것입니다.

- 고정 재고 수량을 초과하는 초과 판매 방지
- 결제 실패 또는 시스템 장애 후 재고가 영구히 잠기는 문제 방지
- 중복 클릭과 재시도에 대한 공정하고 멱등적인 처리
- Redis, DB, 결제 경로 장애 시 예측 가능한 degraded behavior
- Redis admission, DB 최종 정합성, 멱등성, fallback, 부하 테스트에 대한 의사결정 기록

## 기술 스택

| 영역 | 선택 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.x |
| Runtime | Spring MVC 또는 WebFlux 중 설계 검증 후 결정 |
| Database | MySQL 8 |
| Cache / Admission Control | Redis |
| ORM | Spring Data JPA / Hibernate |
| Load Testing | k6 |
| Observability | LGTM stack, Micrometer Prometheus, OpenTelemetry Java agent |
| Documentation | Markdown, ADR-style decision records, source-backed research notes |

## 프로젝트 구조

```text
.
├── AGENTS.md
├── README.md
├── README-en.md
├── build.gradle
├── settings.gradle
├── common
├── service-booking
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

현재 백엔드는 Spring Boot 멀티모듈 구조로 시작합니다.

- `common`: shared kernel, 공통 응답/예외/JPA auditing/CORS/OpenAPI/Actuator 설정
- `service-booking`: 예약 서비스 애플리케이션과 초기 헬스체크 API
- `infra/observability`: LGTM 로컬 관측 스택 설정과 Grafana dashboard provisioning
- `k6`: 헬스체크 smoke 부하 테스트
- `k8s`: Kubernetes 배포용 kustomize manifests

## 시작 방식

로컬은 Docker Compose 기준으로 MySQL, Redis, LGTM, 애플리케이션을 함께 실행합니다.

```bash
# 1. 컴파일/테스트
./gradlew compileJava test --no-daemon

# 2. 로컬 전체 스택 실행
docker compose up -d mysql redis lgtm booking-service

# 3. 헬스체크
curl http://localhost:8080/api/v1/health

# 4. k6 smoke 부하 테스트
docker compose run --rm -e RATE=20 -e DURATION=10s k6

# 5. 종료
docker compose down
```

Grafana는 `http://localhost:3000`에서 확인할 수 있습니다. 기본 dashboard는 Grafana dashboard template `4701 JVM (Micrometer)`를 프로젝트용으로 provisioning합니다.

Kubernetes manifest는 다음처럼 렌더링할 수 있습니다.

```bash
kubectl kustomize k8s/base
kubectl kustomize k8s/local
```

## API 엔드포인트

초기 API 범위:

| Method | Endpoint | 목적 | 상태 |
|---|---|---|---|
| `GET` | `/api/v1/checkout/{productId}` | 상품, 숙박 기간, 가격, 사용자 포인트 등 주문서 진입 정보를 조회합니다. | 예정 |
| `POST` | `/api/v1/bookings` | 결제 입력 검증, 멱등성 처리, 재고 선점/확정, 최종 예약 생성을 수행합니다. | 예정 |
| `GET` | `/api/v1/health` | 서비스 기동 및 smoke/load-test용 헬스체크입니다. | 구현 |

최종 요청/응답 스키마는 시스템 설계와 도메인 모델이 확정된 뒤 문서화합니다.

## 주요 문서

| 문서 | 목적 |
|---|---|
| [요구사항](docs/requirements.md) | 공개 가능한 요구사항 요약 |
| [문서 지도](docs/README.md) | 프로젝트 문서 인덱스 |
| [의사결정 기록](docs/decisions/DECISIONS.md) | Redis, 멱등성, fallback, 부하 테스트 등 주요 trade-off |
| [출처 기반 리서치](docs/research/source-backed-research-note.md) | Redis/MySQL/PostgreSQL/idempotency/resilience 관련 주장과 출처 |
| [Mock Interview Design](docs/system-design/mock-interview.md) | 시스템 설계 사고 흐름 문서 |
| [Software Design Document](docs/system-design/sdd.md) | 정식 SDD 작업 문서 |
| [Test-First Scenarios](docs/testing/test-first-scenarios.md) | 장애, 경쟁, 중복, 과부하 시나리오를 테스트로 고정하기 위한 문서 |
| [Bootstrap Verification](docs/testing/bootstrap-verification.md) | 백엔드/로컬 배포/관측/k6 세팅 검증 결과 |
| [Adversarial Review](docs/reviews/adversarial-review.md) | oversell, undersell, Redis 장애, retry storm 등에 대한 critic 결과 |
| [AI Usage](docs/ai/AI_USAGE.md) | AI 사용 범위와 사람 검증 경계 공개 문서 |

## 현재 상태

- [x] 공개 안전 요구사항 정리
- [x] README 초기화
- [x] AI usage, prompt log, conversation log 구조 생성
- [x] Decision, research, test-first, adversarial review 문서 생성
- [x] Spring Boot backend skeleton
- [ ] Domain model and API schema
- [ ] Concurrency and idempotency tests
- [x] Initial k6 health-check load-test scenario
- [ ] Final design decision review
