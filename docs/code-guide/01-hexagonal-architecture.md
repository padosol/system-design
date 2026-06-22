# 1. 헥사고날 아키텍처 (포트 & 어댑터)

> 목표: **도메인이 프레임워크·DB·웹을 모른다.** 의존성은 항상 바깥에서 안(도메인)으로 향한다.

## 핵심 규칙
- **의존성 방향**: `adapter → application → domain`. 도메인은 그 무엇도 import 하지 않는다(코틀린 표준 라이브러리 제외).
- **포트(인터페이스)는 애플리케이션이 소유**하고, **어댑터가 구현**한다.
  - 인바운드 포트(`port.in`): 외부가 도메인을 호출하는 진입점(UseCase).
  - 아웃바운드 포트(`port.out`): 도메인이 외부에 요청하는 출구(저장소·외부 연동).
- **Spring 애너테이션은 어댑터와 애플리케이션 서비스에만.** 도메인 모델에는 `@Entity`, `@Component`, `@Autowired` 등을 붙이지 않는다.

## 패키지 구조 (`com.padosol.<service>`)
```
com.padosol.<service>
├── domain/                     # 순수 도메인 — 프레임워크 의존 0
│   ├── <Aggregate>.kt          #   애그리거트 루트, 엔티티, VO
│   └── ...
├── application/
│   ├── port/
│   │   ├── in/                 # 인바운드 포트 (UseCase 인터페이스)
│   │   └── out/                # 아웃바운드 포트 (저장소/외부연동 인터페이스)
│   └── service/                # UseCase 구현 (애플리케이션 서비스, @Service)
└── adapter/
    ├── in/web/                 # Controller, Request/Response DTO
    └── out/
        ├── persistence/        # JPA Entity, Spring Data Repository, 매퍼
        └── <external>/         # 외부 API/메시지 큐 등 연동
```
> 어디에 어떤 도메인 개념(엔티티/VO/도메인 서비스)을 두는지는 [DDD 가이드](./02-ddd.md) 참고.

## 흐름
```
HTTP 요청
  → [adapter.in.web] Controller            (요청 DTO ↔ 도메인 명령 변환)
  → [application.port.in] UseCase
  → [application.service] 서비스           (트랜잭션·오케스트레이션)
  → [domain] 애그리거트                     (불변식·비즈니스 규칙)
  → [application.port.out] 아웃바운드 포트
  → [adapter.out.persistence] 어댑터        (도메인 ↔ JPA 엔티티 매핑)
```

## 예시

도메인(순수):
```kotlin
// domain/ShortUrl.kt
class ShortUrl private constructor(
    val key: String,
    val originalUrl: String,
) {
    companion object {
        fun create(key: String, originalUrl: String): ShortUrl {
            require(originalUrl.startsWith("http")) { "URL은 http(s)로 시작해야 한다" }
            return ShortUrl(key, originalUrl)
        }
    }
}
```

포트:
```kotlin
// application/port/in/CreateShortUrlUseCase.kt
fun interface CreateShortUrlUseCase {
    fun create(command: CreateShortUrlCommand): ShortUrl
}

// application/port/out/SaveShortUrlPort.kt
interface SaveShortUrlPort {
    fun save(shortUrl: ShortUrl): ShortUrl
}
```

애플리케이션 서비스:
```kotlin
// application/service/CreateShortUrlService.kt
@Service
class CreateShortUrlService(
    private val savePort: SaveShortUrlPort,
) : CreateShortUrlUseCase {
    @Transactional
    override fun create(command: CreateShortUrlCommand): ShortUrl {
        val shortUrl = ShortUrl.create(command.key, command.originalUrl)
        return savePort.save(shortUrl)
    }
}
```

영속성 어댑터 — **도메인 모델과 JPA 엔티티를 분리**한다(루트 규칙: 스키마는 Flyway, `ddl-auto: validate`):
```kotlin
// adapter/out/persistence/ShortUrlJpaEntity.kt
@Entity
@Table(name = "short_url")
class ShortUrlJpaEntity(
    @Id @Column(name = "url_key") val key: String,
    @Column(name = "original_url") val originalUrl: String,
)

// adapter/out/persistence/ShortUrlPersistenceAdapter.kt
@Component
class ShortUrlPersistenceAdapter(
    private val repository: ShortUrlJpaRepository,
) : SaveShortUrlPort {
    override fun save(shortUrl: ShortUrl): ShortUrl =
        repository.save(shortUrl.toJpaEntity()).toDomain()
}
```

## 트레이드오프 (의도적으로 알고 쓴다)
- **도메인 ↔ JPA 분리는 매핑 보일러플레이트를 만든다.** 기본은 분리. 단, 로직이 거의 없는 단순 CRUD 애그리거트는 한 클래스로 합치고 주석으로 사유를 남겨도 된다(예외이지 기본 아님).
- 기존 `url-shortener`/`notification-system`은 레이어드 그대로 둔다. 이 구조는 **신규부터** 적용.

## 체크리스트
- [ ] `domain/` 안의 파일에 Spring/JPA/jakarta import가 없다.
- [ ] 애플리케이션 서비스는 포트 인터페이스에만 의존하고, 어댑터 구현체를 직접 참조하지 않는다.
- [ ] 컨트롤러는 도메인 객체를 그대로 반환하지 않고 응답 DTO로 변환한다.
- [ ] 트랜잭션 경계(`@Transactional`)는 애플리케이션 서비스에 있다.
