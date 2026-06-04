# URL 단축 서비스 — 부하 테스트 (k6)

설계 문서의 **read-heavy(읽기:쓰기 ≈ 100:1)** 프로파일을 k6로 재현해 동작·지연·캐시 효과를 확인한다.

## 시나리오 ([load-test.js](./load-test.js))

| 시나리오 | exec | 부하 | 설명 |
|----------|------|------|------|
| `redirect` (읽기) | `GET /{key}` | 0→50→0 VU 램프 (60s) | setup에서 시드한 200개 키를 랜덤 조회. 캐시 적중 위주. |
| `create` (쓰기) | `POST /api/v1/urls` | 상시 5 VU (60s) | 매번 고유 URL 단축. |

- **setup()**: 부하 전 단축 URL 200개를 미리 만들어 키 목록 확보.
- **`redirects: 0`**: k6가 302 Location(example.com)을 따라가지 않게 설정 → 외부 호출 방지, 순수 리다이렉트 응답만 측정.

## 실행 방법

```bash
# 1. 인프라 + 앱 기동
docker compose up -d
./gradlew bootJar && java -jar build/libs/url-shortener-0.0.1-SNAPSHOT.jar
#   (8080이 점유 중이면) java -jar ... --server.port=8088

# 2. 부하 테스트 (BASE_URL은 앱 포트에 맞춰)
k6 run -e BASE_URL=http://localhost:8088 load-test/load-test.js
```

## 환경 고정 (재현성 하니스) — `run-load-test.sh`

단일 머신에서 **비교 가능한** 측정을 얻기 위해 1+2+3을 적용한 하니스.

```bash
./load-test/run-load-test.sh    # 워밍업 1 + 측정 3회 → median
# 옵션(env): REPEAT=5 WARMUP=1 SEED_COUNT=200 KEEP_INFRA=0 SKIP_PROMPT=1
```

1. **이웃 정리 안내** — 변동을 키우는 java 프로세스(Kafka·다른 앱 등)를 감지해 경고(죽이진 않음). 최상의 재현성을 위해 테스트 동안 멈출 것을 권장.
2. **방법론 고정** — 첫 런은 워밍업으로 버리고, 매 측정 전 `TRUNCATE url`·시퀀스 리셋·`FLUSHDB`로 동일 상태에서 시작. N회 반복 후 **median** 보고.
3. **CPU 코어 핀** ([docker-compose.loadtest.yml](../docker-compose.loadtest.yml) + `taskset`) — 발생기(k6)와 SUT(앱)가 코어를 안 나누게 분리:

   | 코어 | 용도 | 방식 |
   |------|------|------|
   | 0–1 | OS·이웃 | (미지정) |
   | 2–4 | k6 | `taskset` |
   | 5 | redis | `cpuset` |
   | 6–7 | postgres | `cpuset` |
   | 8–11 | app | `taskset` + `-Xmx512m` |

   > `cpuset`은 내 컨테이너만 그 코어에 묶을 뿐 이웃의 침범은 못 막는다 → 1(이웃 정리)이 함께 필요.

## 결과

> WSL2 단일 머신, 이웃 프로세스 가동 중. 절대 수치보다 **상대·경향**으로 해석.

**고정 하니스 (핀 + 워밍업, median of 3)** — 권장 측정값:

| 지표 | median | 3회 범위 |
|------|--------|----------|
| 처리량 | **≈ 2,199 req/s** | 2,073 ~ 2,418 |
| redirect p95 | **51.9 ms** | 49.5 ~ 60.9 |
| redirect p99 | ~88 ms | 82 ~ 110 |
| create p95 | 58.9 ms | 55.9 ~ 68.6 |
| 실패율 (`http_req_failed`) | **0.00%** | — |

**비고정(참고)** — 핀 없이 같은 머신 단발 측정: 콜드 ≈401 req/s·p95 281ms, 웜 ≈1,147 req/s·p95 96ms.
→ **핀 + 워밍업으로 처리량 ~2배, p95 절반, 런 간 변동 ±8% 수준으로 수렴.**

**캐시 검증**: 부하 후 Redis에 `url:*` 키가 시드 수만큼 적재 — cache-aside가 설계대로 동작(리다이렉트 대부분 캐시 히트).

## 관찰 / 해석

1. **워밍업 효과** — 첫 런(콜드)은 ≈401 req/s·p95 281ms. JVM JIT 콜드 + 캐시 미적재 탓 → 첫 런은 버리고 측정.
2. **발생기 격리 효과** — k6를 전용 코어(2-4)로 분리하자 처리량 1,147→2,199 req/s. 이전엔 부하 발생기가 SUT의 CPU를 뺏고 있었음 (단일 머신 부하테스트의 흔한 함정).
3. **읽기 캐시 효과** — redirect 지연 분포의 하단(캐시 히트, 수 ms)이 지배적 → cache-aside가 효과적.
4. **에러 0%** — 50 VU 동시 부하에서도 실패 없음. 정확성/가용성은 견고.

## 한계 / 다음

- **환경 의존성**: WSL2 + Docker 포트포워딩 + 단일 머신 CPU 경합으로 지연 절대값은 운영 대비 과대. 운영 SLO 목표는 더 타이트(캐시 히트 리다이렉트 p95 < 50ms).
- 임계치(`thresholds`)는 이 dev 환경의 **회귀 가드** 수준으로 설정. 운영에선 전용 인프라·분리된 부하 발생기에서 재측정 필요.
- 확장 시험 거리: 더 높은 VU에서의 한계점(throughput 포화), 캐시 미스 비율을 높였을 때의 DB 부하, 쓰기 폭증 시 시퀀스/커넥션풀 병목.
