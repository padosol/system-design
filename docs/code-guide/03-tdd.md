# 3. 테스트 주도 개발 (TDD)

> 목표: **테스트로 설계를 끌어낸다.** 실패하는 테스트 없이 프로덕션 코드를 쓰지 않는다.

## Red → Green → Refactor
1. **Red**: 원하는 동작을 표현하는 **실패하는** 테스트를 먼저 쓴다. (컴파일 에러도 Red다)
2. **Green**: 테스트를 통과시키는 **최소한의** 코드를 쓴다. 과한 일반화 금지.
3. **Refactor**: 테스트가 초록인 상태로 중복 제거·이름 개선. 동작은 그대로.

한 번에 한 가지만. 다음 Red로 넘어가기 전에 항상 초록을 본다.

## 테스트 피라미드 (안쪽부터)
| 종류 | 대상 | 도구 | 속도 |
|------|------|------|------|
| **도메인 단위** | 애그리거트·VO·도메인 서비스 | JUnit5 (스프링 컨텍스트 X) | 매우 빠름 |
| **애플리케이션** | UseCase/서비스 (포트는 페이크/목) | JUnit5 + MockK 또는 손수 만든 페이크 | 빠름 |
| **통합** | 어댑터·실제 DB·전체 흐름 | JUnit5 + **Testcontainers(실제 PostgreSQL)** | 느림 |

> [헥사고날 구조](./01-hexagonal-architecture.md) 덕분에 대부분의 비즈니스 규칙을 **스프링 없이** 빠르게 테스트할 수 있다. 도메인부터 안에서 밖으로 테스트하라.

## 이 레포의 테스트 규칙 (루트 CLAUDE.md)
- 통합 테스트는 **Testcontainers로 실제 PostgreSQL**을 띄워 검증한다. H2로 대체하지 않는다.
- 테스트 함수명은 **한글 백틱**: `` fun `없는 키로 접근하면 404`() ``
- 공유 상태(DB·캐시)는 `@AfterEach`로 정리해 **테스트 독립성**을 보장한다.

## 예시

도메인 단위 (Red 먼저):
```kotlin
class NotificationTest {
    @Test
    fun `PENDING이 아니면 발송 처리하면 예외`() {
        val n = Notification.pending(NotificationId("1"), Email("a@b.com"))
        n.markSent()
        assertThrows<IllegalStateException> { n.markSent() }  // 이미 SENT
    }
}
```

통합 (Testcontainers, AfterEach 정리):
```kotlin
@SpringBootTest
@Testcontainers
class CreateShortUrlIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16")
        @JvmStatic @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", postgres::getJdbcUrl)
            r.add("spring.datasource.username", postgres::getUsername)
            r.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var repository: ShortUrlJpaRepository

    @AfterEach
    fun clean() = repository.deleteAll()

    @Test
    fun `단축 URL을 저장하면 키로 조회된다`() { /* ... */ }
}
```

## 버그 수정도 TDD로
"고쳐줘"는 먼저 **버그를 재현하는 실패 테스트**로 바꾼 뒤 통과시킨다. (전역 지침 §4)

## 하지 말 것
- 구현부터 쓰고 나중에 테스트 끼워 맞추기.
- 통과시키려고 단언(assert)을 약화시키기.
- 한 테스트에서 여러 동작을 한꺼번에 검증해 실패 원인을 흐리기.

## 체크리스트
- [ ] 새 동작마다 먼저 실패하는 테스트가 있었다.
- [ ] 비즈니스 규칙은 스프링 없는 빠른 단위 테스트로 덮였다.
- [ ] DB가 걸린 동작은 Testcontainers 통합 테스트로 덮였다.
- [ ] 테스트가 공유 상태를 `@AfterEach`로 정리한다.
