# spring-bootstrap templates

이 디렉토리는 **검증된 Spring Boot 멀티모듈 골격의 정적 스냅샷**이다. 직접 수정하지 말 것 — 사용자가 `spring-bootstrap` 스킬을 호출하면 이 트리가 통째로 `<topic>/source/`로 복사되고 그 후 사용자 환경에 맞게 치환된다.

## 들어 있는 파일 (12 Java + 10 build/ops = 22)

| 파일 | 역할 | 필수 / 선택 |
|---|---|---|
| `build.gradle` | 루트 — Java 21, Spring Boot 3.5.11, Lombok, JUnit 5 | 필수 |
| `settings.gradle` | 멀티모듈 include 선언 | 필수 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.14.4 | 필수 (jar는 부트스트랩 단계에서 생성) |
| `Dockerfile` | 4-stage build + OTel agent attachment | 선택 (k8s/Docker 배포 시) |
| `entrypoint.sh` | 런타임 시 OTel agent 조건부 attach | Dockerfile과 한 쌍 |
| `.dockerignore` | Docker context 최소화 | Dockerfile과 한 쌍 |
| `.gitignore` | Gradle / IDE 무시 | 필수 |
| `common/build.gradle` | shared kernel 의존성 | 필수 |
| `common/src/main/java/com/ecommerce/common/exception/` | 5개 — 예외 처리 표준 | 필수 (전체 구현 기반) |
| `common/src/main/java/com/ecommerce/common/dto/` | `ApiResponse<T>`, `PageResponse<T>` | 필수 |
| `common/src/main/java/com/ecommerce/common/entity/BaseEntity.java` | id + createdAt + updatedAt | 필수 (모든 엔티티의 부모) |
| `common/src/main/java/com/ecommerce/common/config/CorsConfig.java` | CORS | 권장 |
| `common/src/main/java/com/ecommerce/common/config/JpaAuditingConfig.java` | `@CreatedDate`/`@LastModifiedDate` 활성화 | 필수 (BaseEntity가 의존) |
| `common/src/main/java/com/ecommerce/common/config/{OpenApiGroup,Swagger}Config.java` | OpenAPI 그룹 + Swagger | 권장 |
| `common/src/main/resources/application-common.yml` | 로깅 패턴 (trace_id 포함) + actuator/prometheus + jackson SNAKE_CASE | 필수 |

## 의도적으로 빠진 것 — llm-wiki에서 별도 관리

| 패턴 | 어디서 가져올지 | 언제 필요한지 |
|---|---|---|
| Kafka producer/consumer/topics | llm-wiki | 마이크로서비스 간 비동기 메시징이 필요할 때 |
| Outbox 패턴 | llm-wiki | "DB 저장 + 메시지 발행" 원자성이 필요할 때 |
| Idempotency 헬퍼 | llm-wiki | Kafka consumer가 중복 메시지 받을 가능성이 있을 때 |
| QueryDSL | llm-wiki | 동적 쿼리(조건부 WHERE)가 많을 때 |
| DomainEvent abstract base | llm-wiki | 이벤트 직렬화·트레이싱 표준화가 필요할 때 |
| SAGA 오케스트레이션 | llm-wiki | 분산 트랜잭션이 필요할 때 |
| Spring Security | 도메인별 결정 | 인증/인가 도입 시점 |
| 데이터베이스 드라이버 | 서비스 모듈별 | 서비스가 실제 DB와 연동할 때 |
| Resilience4j / Circuit Breaker | 도메인별 결정 | 외부 호출 안정화가 필요할 때 |
| Flyway / Liquibase | 도메인별 결정 | 스키마 마이그레이션이 필요할 때 |

부트스트랩 골격은 **"거의 항상 필요한 최소 셋"**만 포함한다. 도메인·아키텍처 결정에 따라 선택되는 패턴은 의도적으로 분리해서 llm-wiki로 빼둔다 — 잘 안 쓰는 게 박혀 있으면 검토 비용만 늘어남.

## 패키지 치환 규칙

모든 Java 파일 / yml / build.gradle 안의:

- `com.ecommerce` → 사용자 base package (예: `com.shorten`, `com.myorg.system`)
- `com/ecommerce` (디렉토리 경로) → 사용자 base package 경로 (예: `com/shorten`)

`settings.gradle`의 `rootProject.name = 'ecommerce-v3'` → 사용자 프로젝트명.
`build.gradle`의 `group = 'com.ecommerce'` → 사용자 base package.

자세한 단계는 `../SKILL.md` 참고.

## 의도적 결정

이 골격에 **들어 있는** 것들의 이유:

- **Micrometer + Prometheus 기본 노출**: actuator의 `/actuator/prometheus`로 즉시 노출. JVM/HTTP/JPA 메트릭은 표준 이름이라 grafana.com/dashboards 4701, 10939와 호환. 운영성을 처음부터 챙기는 것은 비용이 거의 없으면서 효과가 큼.
- **Logging pattern에 trace_id 슬롯**: OTel agent가 attach되면 자동 채워짐. 안 attach하면 빈 값으로 깔끔하게 처리. 미리 박아두면 나중에 모든 서비스 로그 패턴 일괄 변경할 일 없음.
- **OTel agent 조건부 attach**: `OTEL_EXPORTER_OTLP_ENDPOINT`가 없으면 attach 안 함. 로컬 / 단순 docker run 환경에서 collector 없이도 동작.
- **Lombok subprojects 일괄 적용**: 개별 모듈마다 의존성 선언 반복할 일 없음. root `build.gradle`의 `subprojects` 블록 한 곳에서 관리.
- **OpenAPI 기본 포함**: 거의 항상 유용하고 의존성 비용 저렴. API 없는 서비스가 드물어서 디폴트 ON이 더 자연스러움.

## 버전 매트릭스 (snapshot 시점)

| 항목 | 버전 |
|---|---|
| Java | 21 (toolchain) |
| Spring Boot | 3.5.11 |
| Spring Dependency Management | 1.1.7 |
| Gradle | 8.14.4 |
| springdoc-openapi | 2.8.16 |
| OTel Java agent | 2.20.1 (Dockerfile build arg) |
| JUnit | 5.11.4 |

새 프로젝트에서 버전을 올리려면 `build.gradle`의 `ext { ... }` 블록에서 한 곳만 수정.
