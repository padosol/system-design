# 코드 작성 가이드

이 디렉터리는 이 저장소에서 **새로 작성하는 코드가 따라야 할 설계 원칙**을 모은다.
루트 [`CLAUDE.md`](../../CLAUDE.md)의 코드 컨벤션을 보강하며, CLAUDE.md는 이 인덱스를 참조한다.

## 적용 범위
- **신규 코드/시스템부터 적용한다.** 새 최상위 시스템(`*/`)이나 새 기능은 아래 6개 원칙을 따른다.
- 기존 `url-shortener/`, `notification-system/`은 레이어드(Controller → Service → Repository) 구조를 **그대로 둔다.** 큰 기능을 새로 붙일 때 점진적으로 이관해도 좋지만 강제는 아니다.
- 충돌 시 우선순위: 루트 `CLAUDE.md`의 불변 규칙(Flyway 스키마 관리, `ddl-auto: validate`, 커밋 컨벤션 등) > 이 가이드.

## 6대 원칙
| # | 가이드 | 한 줄 요약 |
|---|--------|-----------|
| 1 | [헥사고날 아키텍처](./01-hexagonal-architecture.md) | 도메인을 안쪽에 두고, 웹·DB·외부연동을 바깥 어댑터로 분리한다 |
| 2 | [DDD](./02-ddd.md) | 도메인 언어로 모델링하고 불변식을 도메인 안에 강제한다 |
| 3 | [TDD](./03-tdd.md) | 실패 테스트 → 통과 → 리팩터, 도메인부터 |
| 4 | [RESTful API](./04-restful-api.md) | 리소스 중심 URL, 올바른 HTTP 메서드·상태코드 |
| 5 | [로깅](./05-logging.md) | 구조화 로그, 레벨 기준, 민감정보 마스킹, 추적 ID |
| 6 | [에러 중앙화](./06-error-handling.md) | 예외 계층 + `@RestControllerAdvice`로 응답 일원화 |

## 최소 준수 사항 (반드시)
- 도메인 모델은 프레임워크/JPA/웹 타입에 의존하지 않는다. → [1](./01-hexagonal-architecture.md), [2](./02-ddd.md)
- 비즈니스 불변식은 도메인 객체 안에서 강제한다. 의미 없는 `var`/setter 남발 금지. → [2](./02-ddd.md)
- 새 기능은 도메인 단위 테스트 → 통합 테스트(Testcontainers) 순으로 작성한다. → [3](./03-tdd.md)
- API는 리소스 명사 + 표준 상태코드. 에러 바디는 공통 포맷. → [4](./04-restful-api.md), [6](./06-error-handling.md)
- 예외는 도메인/애플리케이션에서 던지고, **단 한 곳**(`@RestControllerAdvice`)에서 HTTP로 변환한다. → [6](./06-error-handling.md)
- 로그는 `logger`로만 남긴다. `println`/`printStackTrace` 금지, 민감정보 마스킹. → [5](./05-logging.md)
