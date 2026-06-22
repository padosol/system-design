# 프로젝트 문서

저장소 전반에 걸친 규약·결정·용어. 특정 시스템에만 해당하는 설계는 각 시스템의 `README.md`에 있다.

### 공통 (전 서비스)
| 문서 | 내용 |
|------|------|
| [code-guide/](./code-guide/README.md) | 코드 작성 6대 원칙(헥사고날·DDD·TDD·RESTful·로깅·에러중앙화) |
| [git-and-pr-workflow.md](./git-and-pr-workflow.md) | 브랜치·PR·리뷰·머지 규칙 |
| [adr/](./adr/README.md) | 시스템 ADR(여러 서비스에 걸친 결정) |
| [glossary.md](./glossary.md) | 공통 용어집(아키텍처·운영) |

### 서비스별
| 서비스 | 문서 |
|------|------|
| url-shortener | [용어집](./url-shortener/glossary.md) · [ADR](./url-shortener/adr/README.md) |
| notification-system | [용어집](./notification-system/glossary.md) · [ADR](./notification-system/adr/README.md) |

> 협업 규칙: 코드 변경 시 [PR 템플릿](../.github/pull_request_template.md)의 "문서 영향" 체크리스트로
> ADR·용어집·CLAUDE.md 동기화를 강제한다.
