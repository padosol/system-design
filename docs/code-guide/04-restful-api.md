# 4. RESTful API

> 목표: **리소스 중심**으로 URL을 설계하고, HTTP의 의미(메서드·상태코드)를 정확히 쓴다.

## URL 설계
- **명사·복수형 리소스**: `/urls`, `/notifications`. 동사를 URL에 넣지 않는다(`/createUrl` ❌).
- 계층은 경로로: `/notifications/{id}/deliveries`.
- 필터·정렬·페이지는 쿼리스트링: `/notifications?status=PENDING&page=0&size=20`.
- 소문자 + 하이픈(`kebab-case`) 경로. 식별자는 경로 변수.

## HTTP 메서드
| 메서드 | 용도 | 멱등성 |
|--------|------|--------|
| `GET` | 조회 (부수효과 없음) | O |
| `POST` | 생성 / 비멱등 액션 | X |
| `PUT` | 전체 교체 (멱등) | O |
| `PATCH` | 부분 수정 | △ |
| `DELETE` | 삭제 | O |

> 멱등성은 `notification-system`의 at-least-once·재시도 설계와 직결된다. 생성 API에 `Idempotency-Key` 헤더를 받아 중복 요청을 한 번만 처리하는 패턴을 고려하라.

## 상태 코드
- `200 OK` 조회/수정 성공, `201 Created` 생성(+ `Location` 헤더), `204 No Content` 본문 없는 성공(삭제 등).
- `400` 검증 실패, `401`/`403` 인증·인가, `404` 리소스 없음, `409` 충돌(중복 키 등), `422` 의미적 검증 실패.
- `500` 서버 오류(예상 못한 예외), `503` 의존 시스템 불가.
- **상태코드로 성공/실패를 표현한다.** 200에 `{"success": false}`를 담지 않는다.

## 요청·응답
- 요청/응답 바디는 **`data class`**. 요청 검증은 **`jakarta.validation`** 애너테이션(`@field:NotBlank`, `@field:Size` 등) + 컨트롤러 `@Valid`.
- 컨트롤러는 도메인 객체를 그대로 노출하지 않는다. **응답 DTO로 변환**해 내부 구조 변경이 API 계약을 깨지 않게 한다.
- 시간은 ISO-8601(UTC), 돈/수량 등은 단위를 명확히.

```kotlin
data class CreateUrlRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^https?://.+", message = "http(s) URL이어야 합니다")
    val originalUrl: String,
)

@PostMapping("/urls")
fun create(@Valid @RequestBody request: CreateUrlRequest): ResponseEntity<CreateUrlResponse> {
    val created = createShortUrl.create(request.toCommand())
    return ResponseEntity
        .created(URI.create("/urls/${created.key}"))   // 201 + Location
        .body(CreateUrlResponse.from(created))
}
```

## 페이지네이션·목록
- 목록은 `page`/`size`(또는 커서) + 전체 개수/다음 커서를 응답에 포함.
- 빈 목록은 `200` + `[]`. `404`로 처리하지 않는다.

## 버저닝
- 호환 깨지는 변경은 경로 버전으로: `/v1/urls`. 필드 추가 같은 하위호환 변경은 버전을 올리지 않는다.

## 에러 응답
- 에러 바디는 **공통 포맷**을 쓰고 한 곳에서 생성한다. → [에러 중앙화 가이드](./06-error-handling.md)

## 체크리스트
- [ ] URL이 동사가 아닌 리소스 명사다.
- [ ] 메서드·상태코드가 의미에 맞다(201+Location, 204 등).
- [ ] 요청에 `jakarta.validation` 검증이 붙어 있다.
- [ ] 응답이 도메인 객체가 아닌 DTO다.
- [ ] 에러 응답이 공통 포맷을 따른다.
