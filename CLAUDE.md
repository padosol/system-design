# CLAUDE.md

이 저장소의 **코드 컨벤션**. (전역 행동 지침은 `~/.claude/CLAUDE.md` 참고)

## 문서 (→ [`docs/`](./docs/README.md))
- **코드 작성 가이드** [`docs/code-guide`](./docs/code-guide/README.md): 신규 코드/시스템이 따르는 6대 원칙 — 헥사고날 · DDD · TDD · RESTful · 로깅 · 에러 중앙화
- **결정 기록(ADR)** [`docs/adr`](./docs/adr/README.md) · **용어집** [`docs/glossary.md`](./docs/glossary.md): 도메인 규칙·아키텍처 결정이나 새 용어가 생기면 갱신

## 스택
- Kotlin + Spring Boot 4.0, Gradle (Kotlin DSL)
- PostgreSQL + Flyway, Spring Data JPA
- 테스트: JUnit5 + Testcontainers

## 구조
- 패키지: `com.padosol.<service>` (예: `urlshortener`, `notification`)
- 신규 시스템/코드는 헥사고날 + DDD(→ [가이드](./docs/code-guide/01-hexagonal-architecture.md)). 기존 `url-shortener`/`notification-system`은 레이어드(Controller → Service → Repository) 유지
- 시스템마다 최상위 디렉터리 1개(`url-shortener/`, `notification-system/`), 설계는 그 안의 `README.md`(7섹션 프레임워크, 루트 `TEMPLATE.md` 복사해 시작)

## 코드
- 들여쓰기: Kotlin 4 스페이스, `*.gradle.kts` 탭
- 엔티티: JPA `@Entity`/`@Table(name = ...)`, 컬럼은 snake_case. 스키마는 Flyway가 관리하고 JPA는 `ddl-auto: validate`(검증만)
- DB 변경: `src/main/resources/db/migration/V{n}__{설명}.sql` 신규 추가 (기존 마이그레이션 수정 금지)
- DTO/응답은 `data class`, 요청 검증은 `jakarta.validation` 애너테이션

## 테스트
- 통합테스트는 Testcontainers(실제 PostgreSQL)로 검증
- 테스트 함수명은 한글 백틱: `` fun `없는 키로 접근하면 404`() ``
- 공유 상태(DB·캐시)는 `@AfterEach`로 정리해 테스트 독립성 보장

## 커밋
- Conventional Commits 한글: `feat:` `fix:` `docs:` `test:` `refactor:` `chore:`
- 커밋·PR 어디에도 트레일러/footer(`Co-Authored-By`, `Generated with` 등) 넣지 않음

## 협업 (→ [`docs/git-and-pr-workflow.md`](./docs/git-and-pr-workflow.md))
- 브랜치 `<type>/<kebab-요약>`, `main` 직접 push 금지. PR은 논리 단위 1개 + [PR 템플릿](./.github/pull_request_template.md), squash 머지
- 리뷰는 코드가이드 6원칙·테스트 동반·마이그레이션 규칙·용어 일치·시크릿 노출을 확인
