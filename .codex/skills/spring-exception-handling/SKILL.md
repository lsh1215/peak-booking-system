---
name: spring-exception-handling
description: Spring Boot 예외 처리 규칙 — try-catch 남용 방지, GlobalExceptionHandler + BusinessException 패턴 강제. spring-boot-expert agent가 부재한 메인 스레드 작업에서도 자동 주입되는 안전망.
triggers:
  - "try catch"
  - "try-catch"
  - "exception handler"
  - "@exceptionhandler"
  - "@restcontrolleradvice"
  - "controlleradvice"
  - "globalexceptionhandler"
  - "businessexception"
  - "throw new"
  - "예외 처리"
  - "익셉션 핸들러"
  - "에러 핸들링"
  - "스프링 예외"
---

# Spring Boot Exception Handling — 핵심 규칙

> 이 스킬은 Spring 코드를 짤 때 가장 흔한 AI 안티패턴(불필요 try-catch)을 방지한다. 메인 스레드에서 작업하더라도 트리거 키워드 발견 시 자동 주입된다. 풀 스펙은 `spring-boot-expert` agent에 있다.

## 한 줄 규칙

**비즈니스 / 컨트롤러 코드에서 `try-catch`로 로직을 감싸지 않는다.** `BusinessException`을 throw하고 `GlobalExceptionHandler`가 HTTP 응답으로 변환한다.

## 결정 플로우

```
이 예외를 호출자(또는 framework)가 알아야 하는가?
├─ YES → throw. try-catch 없음. 끝.
└─ NO ┐
      인프라 경계인가? (Kafka @KafkaListener, @Scheduled, async Runnable)
      ├─ NO → throw. try-catch 없음. 끝.
      └─ YES → catch + log.error + (선택: DLQ / retry). 절대 silent return 금지.
```

## 프로젝트 표준 GlobalExceptionHandler 계약

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCodeBase errorCode = e.getErrorCode();
        log.warn("Business exception: [{}] {}", errorCode.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getStatus()).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) { ... }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("Internal server error"));
    }
}
```

코드를 생성할 때 이 컨트랙트를 따른다. 절대 평행 시스템 만들지 않는다.

## 케이스별 올바른 패턴

| 상황 | ❌ 안티패턴 | ✅ 올바른 패턴 |
|---|---|---|
| 도메인 규칙 위반 | `try { order.cancel(); } catch (Exception e) { log.error(...); return null; }` | `order.cancel();` (내부에서 `throw new BusinessException(...)`) |
| 리소스 없음 | `repo.findById(id).orElse(null)` | `repo.findById(id).orElseThrow(() -> new BusinessException(NOT_FOUND))` |
| 검증 실패 | controller에서 `if (req.getEmail() == null) return badRequest(...)` | request DTO에 `@NotBlank` + `@Valid` (자동 처리됨) |
| Checked exception 만남 | `catch (IOException e) { log.error(e); return null; }` | `catch (IOException e) { throw new BusinessException(IO_ERROR, e); }` (즉시 rethrow) |
| Kafka consumer | (메인 코드와 동일하게 throw) | `try { process(); } catch (Exception e) { log.error(...); deadLetterTopic.send(...); }` (인프라 경계만 허용) |

## "혹시 try-catch 써야 하나?" 자체 점검 5문항

코드에 `try` 키워드를 쓰기 전 다음을 자문:

1. 호출자가 이 실패를 알아야 하는가? → YES면 throw
2. checked exception을 unchecked로 바꿔주는 한 줄 wrapping인가? → YES면 catch + 즉시 rethrow OK
3. 이 메서드가 `@KafkaListener` / `@Scheduled` / async Runnable의 진입점인가? → YES면 catch OK (단 silent return 금지)
4. catch 블록이 `log + return null` 또는 `log + return Optional.empty()`인가? → YES면 **무조건 잘못**. throw로 바꿔라.
5. 위 1–3 어디에도 해당 안 되는데 try를 쓰고 있다면 → **즉시 삭제하고 throw로 대체**.

## 사용자 정의 ErrorCode 확장 패턴

도메인별로 `ErrorCodeBase` enum을 만든다.

```java
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCodeBase {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_404", "주문을 찾을 수 없습니다"),
    INSUFFICIENT_STOCK(HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_422_STOCK", "재고가 부족합니다"),
    CANNOT_CANCEL_CONFIRMED(HttpStatus.CONFLICT, "ORDER_409", "확정된 주문은 취소 불가");

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;
}
```

사용:
```java
throw new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK);
```

## 더 자세히

- 풀 스펙·예시 코드: `spring-boot-expert` agent
- 레이어드 아키텍처 전반: `layer-architecture` skill
- 테스트 패턴: `tdd-patterns` skill
