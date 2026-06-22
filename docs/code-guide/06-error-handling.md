# 6. 에러 중앙화

> 목표: 예외는 **도메인/애플리케이션에서 의미 있게 던지고**, HTTP로의 변환은 **단 한 곳**에서 한다.

## 구성
1. **에러 코드 enum** — 코드·메시지·HTTP 상태를 한곳에서 관리.
2. **예외 계층** — 도메인/애플리케이션이 던지는 공통 베이스 예외.
3. **전역 핸들러** — `@RestControllerAdvice`가 예외 → 공통 응답으로 변환.
4. **공통 에러 응답 DTO** — 모든 에러가 같은 모양.

## 에러 코드
```kotlin
enum class ErrorCode(val status: HttpStatus, val code: String, val message: String) {
    URL_NOT_FOUND(HttpStatus.NOT_FOUND, "URL_001", "단축 URL을 찾을 수 없습니다"),
    DUPLICATE_KEY(HttpStatus.CONFLICT, "URL_002", "이미 존재하는 키입니다"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_001", "잘못된 요청입니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 오류가 발생했습니다"),
}
```

## 예외 계층
```kotlin
// application 또는 공통 모듈
open class BusinessException(val errorCode: ErrorCode) : RuntimeException(errorCode.message)

class UrlNotFoundException : BusinessException(ErrorCode.URL_NOT_FOUND)
class DuplicateKeyException : BusinessException(ErrorCode.DUPLICATE_KEY)
```
- 도메인 불변식 위반은 도메인이 `require`/`check`(IllegalArgument/IllegalState) 또는 도메인 예외로 던지고, 애플리케이션 경계에서 `BusinessException`으로 매핑하거나 핸들러에서 처리한다.
- **예외를 삼키지 않는다**(`catch {}` 후 무시 금지). 복구 못 하면 던지거나 의미 있는 예외로 감싼다.

## 전역 핸들러 (단일 변환 지점)
```kotlin
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ErrorResponse> {
        log.warn("business error code={} msg={}", e.errorCode.code, e.message)
        return ErrorResponse.of(e.errorCode)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)   // @Valid 실패
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val detail = e.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        return ErrorResponse.of(ErrorCode.INVALID_REQUEST, detail)
    }

    @ExceptionHandler(Exception::class)                         // 예상 못한 모든 것
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("unexpected error", e)                       // 스택트레이스는 여기서 한 번만
        return ErrorResponse.of(ErrorCode.INTERNAL_ERROR)
    }
}
```
- **로깅은 핸들러에서 한 번만.** 각 층에서 잡아 다시 로깅하지 않는다. → [로깅 가이드](./05-logging.md)
- 컨트롤러에 `try/catch`를 흩뿌리지 않는다. 변환은 advice에 모은다.

## 공통 에러 응답
```kotlin
data class ErrorResponse(val code: String, val message: String, val detail: String? = null) {
    companion object {
        fun of(ec: ErrorCode, detail: String? = null) =
            ResponseEntity.status(ec.status).body(ErrorResponse(ec.code, ec.message, detail))
    }
}
```
```json
{ "code": "URL_001", "message": "단축 URL을 찾을 수 없습니다", "detail": null }
```
- 모든 4xx/5xx가 이 포맷. 상태코드 사용 기준은 [RESTful 가이드](./04-restful-api.md) 참고.
- **내부 구현 노출 금지**: 스택트레이스·SQL·내부 클래스명을 응답 바디에 담지 않는다(로그에만).

## 체크리스트
- [ ] 새 에러 상황마다 `ErrorCode`에 항목을 추가했다.
- [ ] 컨트롤러/서비스에 흩어진 `try/catch`로 HTTP 응답을 만들지 않는다.
- [ ] 모든 에러 응답이 공통 `ErrorResponse` 포맷이다.
- [ ] 예외 로깅이 핸들러에서 한 번만 일어난다.
- [ ] 응답 바디에 내부 정보(스택트레이스 등)가 없다.
