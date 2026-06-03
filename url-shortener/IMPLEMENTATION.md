# URL 단축 서비스 — 구현 계획

설계 문서([README.md](./README.md))를 Kotlin + Spring Boot로 직접 구현한다.

## 기술 스택

| 영역 | 선택 |
|------|------|
| 언어/런타임 | Kotlin, Java 21 |
| 프레임워크 | Spring Boot 4.0 (WebMVC, Data JPA) |
| 빌드 | Gradle (Kotlin DSL) |
| DB | PostgreSQL 16 |
| 캐시 | Redis 7 (cache-aside) |
| 마이그레이션 | Flyway |
| 인프라 | docker-compose (postgres, redis) |
| 테스트 | JUnit 5 + Testcontainers (실제 PG/Redis) |

> 아키텍처는 **레이어드**(Controller → Service → Repository). URL 단축은 단순한 도메인이라 과설계를 피한다.

## 핵심 설계 결정

1. **키 생성 — 카운터 + Base62**
   PostgreSQL 시퀀스 `nextval` → 숫자 id → Base62 인코딩 → `short_key`(유니크 인덱스).
   순차 추측 방지(오프셋/셔플)는 후속 과제. v1은 단순 카운터.
2. **캐싱 — 명시적 cache-aside**
   `@Cacheable`로 숨기지 않고 `RedisTemplate`으로 직접 구현해 캐시 동작을 체감.
   리다이렉트: Redis 조회 → miss면 DB → 캐시 채움(TTL).
3. **리다이렉트 — 기본 302** (추후 클릭 통계 여지). 설정 한 줄로 301 전환 가능.

## 단계별 마일스톤 (각 단계 = 검증 게이트)

| # | 작업 | 검증 |
|---|------|------|
| 0 | 스캐폴딩 + docker-compose + 부팅 | 컴파일 통과, `/actuator/health` 200 |
| 1 | 엔티티 + Repository + Flyway | Testcontainers 저장/조회 통합테스트 |
| 2 | Base62 인코더 | 단위테스트(왕복 변환, 경계값) |
| 3 | 단축 API (POST /api/v1/urls) | 긴 URL → shortUrl 반환 |
| 4 | 리다이렉트 API (GET /{key}) | 302 + Location, 없는 키 404 |
| 5 | Redis 캐시 (cache-aside) | 2회 호출 시 DB 1회만 조회 |
| 6 | 입력 검증 + 실행 가이드 | 잘못된 URL 400, README 갱신 |

진행: 가능한 범위에서 테스트 먼저 → 구현 → 통과 확인 (TDD 지향).

## 실행 방법

```bash
# 1. 인프라 기동
docker compose up -d

# 2. 앱 실행
./gradlew bootRun

# 3. 테스트 (Testcontainers — Docker 필요)
./gradlew test
```
