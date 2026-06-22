# 5. 로깅

> 목표: **운영에서 추적 가능하고, 민감정보가 새지 않는** 로그. Spring Boot 기본 SLF4J + Logback을 쓴다.

## 기본 규칙
- 로거는 클래스마다 하나: `private val log = LoggerFactory.getLogger(javaClass)`.
- **`println` / `System.out` / `e.printStackTrace()` 금지.** 항상 로거로.
- 메시지는 한국어 또는 영어로 일관되게. 문자열 연결 대신 **플레이스홀더**: `log.info("url 생성 key={}", key)` (지연 평가 + 가독성).
- 예외는 마지막 인자로 넘겨 스택트레이스를 남긴다: `log.error("저장 실패 key={}", key, e)`.

## 로그 레벨 기준
| 레벨 | 언제 |
|------|------|
| `ERROR` | 처리 실패로 사용자/시스템에 영향. 즉시 조치 후보. |
| `WARN` | 비정상이지만 처리는 계속됨(재시도, 폴백, 임박한 한계). |
| `INFO` | 비즈니스적으로 의미 있는 사건(생성/발송/상태 전이). 운영 기본 레벨. |
| `DEBUG` | 개발·디버깅용 상세. 운영에선 끈다. |
| `TRACE` | 매우 상세(페이로드 덤프 등). 평소 끔. |

- 정상 흐름을 ERROR/WARN으로 남기지 않는다(알림 피로).
- 루프 안에서 INFO 폭주 금지 — 집계해서 한 줄로.

## 구조화 & 추적
- **key=value** 형태로 검색 가능하게 남긴다: `log.info("notification sent id={} channel={} latencyMs={}", id, channel, ms)`.
- 요청 단위 추적은 **MDC**에 추적 ID를 넣고, 필터에서 설정/정리한다.
```kotlin
// adapter/in/web 의 필터 등
MDC.put("traceId", request.getHeader("X-Trace-Id") ?: UUID.randomUUID().toString())
try { chain.doFilter(req, res) } finally { MDC.clear() }
```
패턴에 `%X{traceId}`를 넣으면 모든 로그에 자동으로 붙는다.

## 민감정보 (반드시)
- 이메일·전화번호·토큰·비밀번호·인증헤더를 **평문으로 남기지 않는다.** 마스킹하거나 생략.
  - 예: `a***@b.com`, 전화 `010-****-1234`.
- 외부 요청/응답 바디 전체 덤프 금지. 필요한 식별자만.

## 어디서 로그를 남기나
- **경계에서**: 인바운드(요청 수신/결과), 아웃바운드(외부 호출 시작/실패), 상태 전이.
- 도메인 객체 내부에는 로깅을 넣지 않는다(순수 유지). 로깅은 애플리케이션 서비스/어댑터에서. → [헥사고날 가이드](./01-hexagonal-architecture.md)
- 예외를 잡아 **다시 던질 거면 거기서 로깅하지 않는다.** 최종 처리부(에러 핸들러)에서 한 번만 남겨 중복을 막는다. → [에러 중앙화 가이드](./06-error-handling.md)

## 체크리스트
- [ ] `println`/`printStackTrace`가 없다.
- [ ] 레벨이 기준에 맞다(정상 흐름이 ERROR/WARN 아님).
- [ ] 민감정보가 평문으로 찍히지 않는다.
- [ ] 메시지가 key=value로 검색 가능하다.
- [ ] 같은 예외를 여러 층에서 중복 로깅하지 않는다.
