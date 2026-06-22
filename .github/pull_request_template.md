## 요약
<!-- 무엇을, 왜 바꿨는지 1~3줄 -->

## 변경 사항
-

## 테스트
- [ ] 단위/통합 테스트 추가·통과 (통합은 Testcontainers 실제 PostgreSQL)
- [ ] 로컬에서 동작 확인

## 코드 가이드 self-check (신규 코드에 한함)
<!-- docs/code-guide 참조. 기존 레이어드 시스템 수정이면 생략 가능 -->
- [ ] 도메인 모델이 프레임워크/JPA/웹 타입에 의존하지 않음
- [ ] 비즈니스 불변식이 도메인 객체 안에서 강제됨
- [ ] API: 리소스 URL·표준 상태코드·`jakarta.validation`, 응답은 DTO
- [ ] 예외는 `@RestControllerAdvice` 한 곳에서 HTTP로 변환
- [ ] `println`/`printStackTrace` 없음, 민감정보 마스킹

## 문서 영향
- [ ] 도메인 규칙/아키텍처 결정 변경 → ADR 추가·수정 (시스템: `docs/adr/`, 서비스: `docs/<서비스>/adr/`)
- [ ] 새 용어 도입 → 용어집 갱신 (공통: `docs/glossary.md`, 도메인: `docs/<서비스>/glossary.md`)
- [ ] 규약 변경 → 관련 `CLAUDE.md` 갱신

## DB
- [ ] 마이그레이션 추가 시 `V{n}` 번호 충돌 확인 (기존 마이그레이션 수정 금지)
