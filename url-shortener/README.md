# URL 단축 서비스 (URL Shortener)

> 긴 URL을 짧은 키로 변환하고, 짧은 URL 접근 시 원본으로 리다이렉트한다. (예: bit.ly, TinyURL)

---

## 1. 요구사항 정의

### 기능적 요구사항
- 긴 URL을 입력하면 짧은 URL을 생성한다.
- 짧은 URL로 접근하면 원본 URL로 리다이렉트한다.
- (선택) 사용자가 커스텀 별칭(alias)을 지정할 수 있다.
- (선택) 링크에 만료 시간을 둘 수 있다.

### 비기능적 요구사항
- **고가용성**: 리다이렉트가 끊기면 이미 배포된 모든 링크가 죽는다. 가용성이 최우선.
- **낮은 지연시간**: 리다이렉트는 사용자 체감 경로 → 빨라야 한다.
- **유일성**: 짧은 키는 충돌 없이 유일해야 한다.
- **예측 불가능성**(선택): 키가 순차적이면 다른 링크를 추측할 수 있다.

### Out of scope
- 클릭 분석/통계 대시보드 (별도 설계로 분리)
- 사용자 인증/계정 관리

---

## 2. 규모 추정 (Capacity Estimation)

> 가정을 명시하는 것이 핵심. 숫자 자체보다 "어떻게 추정했는가"가 중요하다.

**가정**
- 신규 URL 생성: **1억 건/월**
- 읽기:쓰기 비율: **100 : 1** (단축은 한 번, 클릭은 여러 번)

| 항목 | 계산 | 결과 |
|------|------|------|
| 쓰기 QPS | 1억 / (30 × 24 × 3600초) | **≈ 40 writes/s** |
| 읽기 QPS | 40 × 100 | **≈ 4,000 reads/s** |
| 5년 누적 레코드 | 1억 × 12 × 5 | **60억 건** |
| 레코드당 크기 | short_key + long_url + 메타 ≈ 500 B | |
| 5년 저장 용량 | 60억 × 500 B | **≈ 3 TB** |

**키 길이 산정** — Base62(`a-z A-Z 0-9`) 기준
- 62⁶ ≈ 568억 → 60억 건을 담기엔 빠듯
- 62⁷ ≈ **3.5조** → 충분한 여유
- ➡️ **7자리** 채택

---

## 3. API 설계

```http
POST /v1/urls
  Body: { "longUrl": "https://...", "customAlias": "(선택)", "expireAt": "(선택)" }
  201:  { "shortUrl": "https://sho.rt/abc1234" }

GET /{shortKey}
  301 / 302  Location: <longUrl>
  404        존재하지 않거나 만료됨
```

---

## 4. 데이터 모델

**저장소 선택**: 접근 패턴이 `short_key`로의 단순 조회(point lookup)에 집중됨 →
**Key-Value/NoSQL**이 잘 맞는다. RDB(PK = short_key)도 무방. 관계가 거의 없어 조인이 필요 없다.

```
url
├─ short_key   (PK)   -- 7자리 Base62
├─ long_url
├─ created_at
└─ expire_at  (nullable)
```

샤딩: `short_key` 해시 기반으로 분산 → 핫스팟 없이 균등 분포.

---

## 5. 개략적 설계 (High-level Design)

```
        생성(write)                          리다이렉트(read, 압도적 다수)
 [Client] → [LB] → [App] → [Key Gen]      [Client] → [LB] → [App] → [Cache] --hit--> 301/302
                      │                                          │
                    [DB]                                       miss
                                                                ↓
                                                              [DB] → 캐시에 채움 → 301/302
```

- 읽기가 쓰기의 100배 → **캐시 적중률이 시스템 성능을 좌우**한다.
- 캐시(Redis): `short_key → long_url`, LRU 제거, 인기 링크가 자연히 캐시에 남음.

---

## 6. 상세 설계 (Deep-dive)

### 6-1. 짧은 키 생성 전략 (핵심 난제)

| 전략 | 방식 | 장점 | 단점 |
|------|------|------|------|
| **해시 기반** | `MD5(longUrl)` 앞 7자 | 구현 단순, 같은 URL→같은 키 | **충돌** 발생 → 매번 DB 확인 필요 |
| **카운터 + Base62** | 전역 카운터를 Base62 인코딩 | 충돌 0, 짧음 | 순차적 → **추측 가능**, 카운터가 SPOF/병목 |
| **KGS (키 사전생성)** | 키를 미리 만들어 풀에 저장, 요청 시 발급 | 쓰기 시 충돌검사 없음, 빠름 | 키 풀 관리 복잡, 중복 발급 방지 필요 |
| **Snowflake류 ID** | 분산 유니크 ID 생성 후 인코딩 | 분산 친화 | 비트 길이↑ → 키 길어짐 |

**권장**: 카운터 병목·추측 문제를 피하려면 **KGS** 또는 **분산 카운터(범위 할당)**.
- 분산 카운터: 각 App 서버가 ZooKeeper/티켓서버에서 *번호 구간*(예: 1~1000)을 통째로 받아 로컬에서 소진 → 매 요청마다 중앙 접근 X.
- 추측 방지가 중요하면 카운터 값을 그대로 쓰지 말고 셔플/암호화 후 인코딩.

### 6-2. 캐싱
- 읽기 4,000 QPS의 대부분을 캐시에서 처리 → DB 부하 격감.
- 캐시 미스 시 DB 조회 후 채움(cache-aside).
- 만료된 링크는 캐시에서도 제거(또는 짧은 TTL).

### 6-3. 301 vs 302 리다이렉트
| | 301 Permanent | 302 Found(Temporary) |
|--|--------------|----------------------|
| 브라우저 캐싱 | O (다음부터 서버 안 거침) | X (매번 서버 경유) |
| 서버 부하 | 낮음 | 높음 |
| 클릭 분석 | 불리(요청이 안 옴) | 유리(매번 집계 가능) |

➡️ 트래픽 절감 우선이면 **301**, 클릭 통계가 필요하면 **302**.

---

## 7. 트레이드오프 & 확장

- **키 생성**: 단순함(해시) vs 충돌·보안(카운터/KGS). 요구사항의 "예측 불가능성" 여부가 갈림.
- **301 vs 302**: 부하 vs 분석.
- **확장 포인트**
  - DB는 `short_key` 해시 샤딩으로 수평 확장.
  - 읽기 폭증은 캐시 계층 + 리드 레플리카로 흡수.
  - 글로벌 서비스라면 CDN/지역별 엣지에서 리다이렉트.
- **데이터 정리**: 만료 링크는 배치/TTL로 정리해 저장 비용 관리.

---

## 구현 (Implementation)

설계를 **Kotlin + Spring Boot 4.0**으로 직접 구현했다. 상세 계획은 [IMPLEMENTATION.md](./IMPLEMENTATION.md).

- **스택**: Kotlin, Spring Boot 4.0 (WebMVC, Data JPA), PostgreSQL, Redis, Flyway, Gradle
- **아키텍처**: 레이어드 (Controller → Service → Repository)
- **키 생성**: PostgreSQL 시퀀스 카운터 → Base62 인코딩
- **캐싱**: Redis cache-aside (리다이렉트 경로)

### API
| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/v1/urls` | 긴 URL → 단축 URL (201) |
| GET | `/{shortKey}` | 원본으로 302 리다이렉트 (없으면 404) |

### 실행
```bash
docker compose up -d      # postgres + redis
./gradlew bootRun         # 앱 (http://localhost:8080)
./gradlew test            # 테스트 (Testcontainers, Docker 필요)
```

### 검증 상태
- ✅ **기능 테스트** 23개 통과 — Base62 단위 14 + 통합 8(단축/302/404/400/입력검증) + 컨텍스트 로드 1. Testcontainers로 실제 PostgreSQL·Redis 검증.
- ✅ **부하 테스트** (k6) — 재현성 하니스(CPU 핀 + 워밍업 + median of 3) 기준 ≈2,199 req/s, 에러율 0%, 리다이렉트 p95 ≈52ms, 캐시 적중 확인. 상세: [load-test/LOAD_TEST.md](./load-test/LOAD_TEST.md)

---

## 참고 자료
- Alex Xu, *System Design Interview* — Chapter: Design a URL Shortener
- (추가 학습 자료 링크)
