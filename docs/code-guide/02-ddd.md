# 2. 도메인 주도 설계 (DDD)

> 목표: **비즈니스 규칙을 도메인 객체 안에** 둔다. 서비스는 빈약(anemic)하지 않게, 도메인은 풍부(rich)하게.

## 전략적 설계 (가볍게)
- **바운디드 컨텍스트 = 최상위 시스템 1개**(`url-shortener/`, `notification-system/`). 컨텍스트마다 용어가 다를 수 있고, 모델을 공유하지 않는다.
- 컨텍스트 간 통신은 명시적 계약(API/이벤트)으로만. 한쪽 JPA 엔티티를 다른 쪽이 직접 참조하지 않는다.

## 전술적 패턴
| 패턴 | 정의 | 이 레포에서의 위치 |
|------|------|----------------------|
| **엔티티(Entity)** | 식별자로 구분되는 객체(생애 동안 식별자 유지) | `domain/` |
| **값 객체(VO)** | 식별자 없이 값으로 동등성 판단, 불변 | `domain/`, 코틀린 `data class` + `init` 검증 |
| **애그리거트(Aggregate)** | 함께 변경되는 엔티티/VO 묶음, 일관성 경계 | `domain/` |
| **애그리거트 루트** | 애그리거트의 유일한 진입점, 불변식 책임 | `domain/` |
| **리포지토리(Repository)** | 애그리거트 단위 영속화 추상화 | 인터페이스는 `application/port/out`, 구현은 `adapter/out/persistence` |
| **도메인 서비스** | 한 엔티티에 담기 애매한 도메인 로직 | `domain/` (순수). 외부 의존이 필요하면 애플리케이션 서비스로 |
| **도메인 이벤트** | "무슨 일이 일어났다"는 과거형 사실 | `domain/`, 발행은 애플리케이션 서비스 |

## 규칙
1. **불변식은 도메인 안에서 강제.** 생성·상태 전이는 의미 있는 메서드로만 노출하고, 무분별한 `var`/setter를 두지 않는다.
2. **VO로 원시 타입 집착(primitive obsession)을 줄인다.** `String url` 대신 `OriginalUrl`, `String key` 대신 `ShortKey`.
3. **애그리거트는 작게.** 트랜잭션은 애그리거트 하나만 수정하는 것을 기본으로 한다. 다른 애그리거트는 식별자로 참조.
4. **유비쿼터스 언어**: 코드 식별자 = 도메인 용어. 설계 `README.md`/용어집과 같은 단어를 쓴다.

## 예시

값 객체 — 생성 시점에 불변식 검증:
```kotlin
// domain/Recipient.kt
@JvmInline
value class Email(val value: String) {
    init { require(EMAIL_REGEX.matches(value)) { "잘못된 이메일: $value" } }
}
```

애그리거트 루트 — 상태 전이를 메서드로만:
```kotlin
// domain/Notification.kt
class Notification private constructor(
    val id: NotificationId,
    val recipient: Email,
    private var status: Status,   // 외부에 setter 노출 X
) {
    fun markSent() {
        check(status == Status.PENDING) { "PENDING 상태에서만 발송 처리 가능" }
        status = Status.SENT
    }

    companion object {
        fun pending(id: NotificationId, recipient: Email) =
            Notification(id, recipient, Status.PENDING)
    }
}
```

빈약한 도메인 안티패턴 (지양):
```kotlin
// ❌ 모든 필드가 public var, 규칙은 서비스에 흩어짐
class Notification { var status: String = "PENDING" }
class NotificationService { fun send(n: Notification) { n.status = "SENT" /* 검증 없음 */ } }
```

## 애플리케이션 서비스 vs 도메인
- **도메인 서비스**: 순수 계산/규칙(예: 두 애그리거트 간 정책). 외부 의존 없음.
- **애플리케이션 서비스**: 트랜잭션, 포트 호출, 이벤트 발행, 오케스트레이션. 비즈니스 규칙 자체는 도메인에 위임. → [헥사고날 가이드](./01-hexagonal-architecture.md)

## 체크리스트
- [ ] 도메인 객체가 자신의 불변식을 스스로 보장한다(잘못된 상태로 만들 수 없다).
- [ ] 상태 전이가 의미 있는 메서드(`markSent()` 등)로 표현된다.
- [ ] 원시 타입 대신 VO를 도입할 곳을 검토했다.
- [ ] 한 트랜잭션이 애그리거트 하나만 변경한다.
