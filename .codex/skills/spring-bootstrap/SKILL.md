---
name: spring-bootstrap
description: 새 Spring Boot 멀티모듈 프로젝트의 기초 세팅(common shared kernel + Gradle/Docker/CI 골격)을 깔아주는 스킬. 시스템 설계 문서 기반 구현 시작 시 가장 먼저 발동. 핵심 골격만 포함 — `BusinessException`/`ErrorCodeBase`/`ApiResponse`/`GlobalExceptionHandler`/`BaseEntity` + JPA/CORS/OpenAPI configs. Kafka/Outbox/QueryDSL 같은 도메인 특화 패턴은 별도 llm-wiki에서 끌어다 쓴다.
triggers:
  - "spring boot 세팅"
  - "spring 프로젝트 세팅"
  - "spring boot 부트스트랩"
  - "spring 부트스트랩"
  - "bootstrap spring"
  - "scaffold spring boot"
  - "scaffold spring"
  - "스프링 부트 시작"
  - "스프링 초기 세팅"
  - "스프링 프로젝트 만들"
  - "common 모듈"
  - "common module"
  - "shared kernel"
  - "spring 멀티모듈"
  - "gradle 멀티모듈"
  - "design doc 구현"
  - "설계 기반 구현"
  - "설계 문서 기반 구현"
---

# Spring Boot Bootstrap

새 Spring Boot 멀티모듈 프로젝트를 시작할 때 사용하는 스킬. 이 스킬의 `templates/` 디렉토리에 **검증된 shared kernel + 빌드 인프라 전체**가 들어 있다. 새 주제(`<topic>/source/`)에서 Spring 구현을 시작하면 가장 먼저 이 템플릿을 복사·치환해서 깔고 그 위에 서비스 모듈을 추가한다.

## 언제 발동하나

- 시스템 설계 문서(SDD)를 입력으로 "이 설계대로 구현해줘" 요청을 받았고, 대상 stack이 Spring Boot일 때 → 코드 작성 전에 **반드시 먼저** 이 스킬의 부트스트랩 단계 수행
- `<topic>/source/`가 비어 있거나 `build.gradle`이 없으면 부트스트랩이 필요한 상태
- 이미 부트스트랩되어 있다면(루트 `build.gradle` + `common/` 존재) 이 스킬은 건너뛰고 서비스 모듈 추가로 진행

## 결과로 깔리는 것

```
<topic>/source/
├── build.gradle                           # Java 21 + Spring Boot 3.5 + Lombok + JUnit 5
├── settings.gradle                        # 멀티모듈 include
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew, gradlew.bat                   # ※ 별도 단계로 생성 (아래 Step 5 참고)
├── Dockerfile                             # 4-stage: deps cache → build → extract layered jar → runtime + OTel agent
├── entrypoint.sh                          # OTel agent 조건부 attach
├── .dockerignore
├── .gitignore
└── common/                                # Shared Kernel (모든 서비스가 의존)
    ├── build.gradle
    └── src/main/
        ├── java/{base.package}/common/
        │   ├── exception/                 # BusinessException, ErrorCodeBase, GlobalExceptionHandler, CommonErrorCode, EntityNotFoundException
        │   ├── dto/                       # ApiResponse<T>, PageResponse<T>
        │   ├── entity/                    # BaseEntity (id + createdAt + updatedAt with auditing)
        │   └── config/                    # CorsConfig, JpaAuditingConfig, OpenApiGroupConfig, SwaggerConfig
        └── resources/
            └── application-common.yml     # logging pattern (with trace_id), actuator/prometheus, jackson SNAKE_CASE
```

**의도적으로 포함되지 않은 것**: Kafka producer/consumer/topics, Outbox 패턴, Idempotency 헬퍼, QueryDSL, DomainEvent abstract base, Scheduling thread pool. 이들은 도메인·아키텍처 결정에 따라 선택적으로 도입되는 패턴이라 부트스트랩 골격에 박지 않았다. 필요할 때 별도 llm-wiki에서 가져다 붙이는 방식.

## 부트스트랩 단계 (순서대로)

### Step 1: 사용자에게 3가지 질문 (AskUserQuestion 권장)

| 질문 | 예시 답 |
|---|---|
| Project name (settings.gradle `rootProject.name`) | `url-shortener` |
| Base package (Java root package) | `com.shorten` |
| 서비스 모듈 목록 (멀티모듈인 경우) | `service-shortener`, `service-analytics` (단일 모듈이면 빈 리스트) |

**기본값**: 답이 모호하면 `com.{project}` 단일 패키지 + 단일 모듈로 진행. Swagger/OpenAPI는 기본 포함(거의 항상 유용).

### Step 2: 템플릿 복사

`{SKILL_DIR}/templates/`의 모든 파일을 `<topic>/source/`로 복사. 디렉토리 구조 유지.

```bash
SKILL_DIR=.codex/skills/spring-bootstrap
TARGET=<topic>/source
cp -r "$SKILL_DIR/templates/." "$TARGET/"
```

### Step 3: 패키지 치환

템플릿은 `com.ecommerce`(Java 패키지) / `com/ecommerce`(파일 경로)로 작성되어 있다. 사용자 답에 맞게 일괄 치환.

**Bash 스크립트** (예: base package = `com.shorten`):

```bash
TARGET=<topic>/source
NEW_PKG_DOT=com.shorten
NEW_PKG_PATH=com/shorten

# 1) Java/yml/gradle/Dockerfile 등 텍스트 안의 패키지명 치환
find "$TARGET" -type f \( -name "*.java" -o -name "*.yml" -o -name "*.gradle" -o -name "Dockerfile" \) \
    -exec sed -i '' -e "s/com\.ecommerce/$NEW_PKG_DOT/g" {} +

# 2) JsonDeserializer TRUSTED_PACKAGES wildcard 치환 (KafkaConsumerConfig.java 안에 com.ecommerce.* 가 있음)
# — 위 sed가 처리함

# 3) 디렉토리 경로 이동: java/com/ecommerce → java/{NEW_PKG_PATH}
mkdir -p "$TARGET/common/src/main/java/$NEW_PKG_PATH"
mv "$TARGET/common/src/main/java/com/ecommerce/common" \
   "$TARGET/common/src/main/java/$NEW_PKG_PATH/common"
# 빈 com/ 디렉토리 정리
[ -d "$TARGET/common/src/main/java/com/ecommerce" ] && rmdir "$TARGET/common/src/main/java/com/ecommerce"
[ -d "$TARGET/common/src/main/java/com" ] && rmdir "$TARGET/common/src/main/java/com" 2>/dev/null
```

### Step 4: 프로젝트명 / 서비스 모듈 치환

`settings.gradle`을 수정:

```bash
# rootProject.name = 'ecommerce-v3'  →  '{project-name}'
sed -i '' "s/rootProject\.name = 'ecommerce-v3'/rootProject.name = '$PROJECT_NAME'/" \
    "$TARGET/settings.gradle"
```

서비스 모듈 부분(`include 'service-product'` 등)은 **수동으로 사용자 답에 맞춰 수정**. 단일 모듈이면 모두 제거하고 `include 'common'`만 남김.

`build.gradle`의 `group = 'com.ecommerce'`도 사용자 base package로 치환:

```bash
sed -i '' "s/group = 'com\.ecommerce'/group = '$NEW_PKG_DOT'/" "$TARGET/build.gradle"
```

### Step 5: Gradle wrapper jar 생성

템플릿에 `.jar` 바이너리는 포함되어 있지 않다. `.properties`만 있는 상태에서 시스템 gradle로 wrapper를 생성:

```bash
cd "$TARGET"
# 시스템에 gradle이 설치되어 있으면:
gradle wrapper --gradle-version 8.14.4
# 없으면 사용자에게 brew install gradle 안내 후 재실행, 또는
# 동일 버전 wrapper를 다른 프로젝트에서 복사
```

### Step 6: (선택) 추가 모듈 제거

기본 골격은 거의 모든 Spring Boot 프로젝트에 필요한 최소 셋이라 보통 그대로 둔다. 단, 다음은 도메인에 따라 빼도 됨:

| 빼는 것 | 삭제할 파일 |
|---|---|
| OpenAPI / Swagger 안 씀 | `config/SwaggerConfig.java`, `config/OpenApiGroupConfig.java`, `common/build.gradle`의 `springdoc-openapi-starter-webmvc-ui` 라인, `build.gradle` 루트의 `springdocVersion` 변수 |
| CORS 안 씀 (서버-사이드 only) | `config/CorsConfig.java` |

**Kafka, Outbox, Idempotency, QueryDSL, DomainEvent**가 필요하면 부트스트랩 후에 llm-wiki에서 끌어다 붙여라. 이 스킬에는 의도적으로 포함하지 않음.

### Step 7: 검증

```bash
cd "$TARGET"
./gradlew compileJava --no-daemon
```

컴파일 통과해야 부트스트랩 완료. 실패 시:
- 패키지 치환 누락 (`com.ecommerce` 잔재) — `grep -r "com\.ecommerce" .`로 확인
- 의존성 누락 — `common/build.gradle` 확인
- Gradle 버전 불일치 — `gradle/wrapper/gradle-wrapper.properties` 확인

### Step 8: 새 서비스 모듈 추가 (필요 시)

`<topic>/source/service-{name}/`을 생성하고:

```
service-{name}/
├── build.gradle           # `dependencies { implementation project(':common') }` + spring-boot-starter
└── src/main/
    ├── java/{base.package}/{name}/
    │   └── {Name}Application.java   # @SpringBootApplication
    └── resources/
        └── application.yml
```

`settings.gradle`에 `include 'service-{name}'` 추가.

이 단계부터는 `spring-boot-expert` agent에 위임 권장.

---

## ⚠️ Hard Rules — 부트스트랩 시 절대 지킬 것

1. **템플릿의 `BusinessException`/`ErrorCodeBase`/`ApiResponse`/`GlobalExceptionHandler`를 그대로 사용**. 새로 만들지 마라. 평행 시스템 금지.
2. **Java 21 + Spring Boot 3.5 + Gradle 8.14**. 버전 임의 변경 금지 (build.gradle의 `springBootVersion` 등 변수 통해서만).
3. **모든 서비스 모듈은 `common`에 의존** — `dependencies { implementation project(':common') }`.
4. **Lombok은 root build.gradle의 subprojects 블록에서 일괄 적용**. 개별 모듈에서 다시 선언하지 마라.
5. **CORS / Auditing / Prometheus / Logging**은 `application-common.yml` + `config/`로 이미 처리됨. 서비스별 `application.yml`에서 `spring.profiles.include: common`만 추가.

## 인접 스킬 / Agent

- 부트스트랩 후 도메인 모델링 → `domain-modeling` skill
- 첫 feature 구현 → `new-feature` slash command (TDD 순서) 또는 `spring-boot-expert` agent
- 예외 처리 패턴 보강 → `spring-exception-handling` skill (자동 주입됨)
- Kafka / Outbox / Idempotency / SAGA 등 이벤트 패턴 도입 시 → llm-wiki에서 가져옴 (이 스킬 범위 밖)

## 참고 — 템플릿 출처

`templates/`는 `ecommerce-microservices/backend-v2/`의 검증된 구조를 캡처한 것이다 (시점: 2026-05). 원본 레포가 사라져도 이 스킬만으로 자족적으로 동일한 부트스트랩이 가능하다. 템플릿 자체에 대한 자세한 안내는 `templates/README.md` 참고.
