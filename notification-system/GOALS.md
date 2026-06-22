# 알림 시스템 — 정량 완료 조건 & Goal Prompt

> [README.md](./README.md) 설계의 **기능(happy-path)** 을 구현했을 때 "완료"로 인정하는 정량 기준(DoD)과
> 그대로 에이전트에 넘길 goal prompt. **범위를 좁혀 1라운드는 기능에 집중**한다 — 신뢰성·성능·보안은 §3으로 분리.

## 전제 (1라운드)

- **스택**: Kotlin/Spring Boot + PostgreSQL + Redis. 큐는 테스트에서 embedded/Testcontainers Kafka(또는 동기 처리로 단순화) — 목적은 *기능* 검증.
- **서드파티**(FCM/Twilio/SendGrid)는 **mock/stub** — 이번 라운드는 `accept`만(실패·지연·invalid-token 주입은 다음 라운드).
- **검증**: **Testcontainers 통합테스트만**(부하/k6 없음). 모든 항목은 자동 검증 가능해야 한다.

---

## 1. 정량 완료 조건 — 기능 (Definition of Done · Functional)

> F1~F8 **전부 PASS = 1라운드 완료**. 수동 확인 금지(통합테스트로 측정).

| ID | 완료 조건 (정량) | 근거 | 검증 |
|----|------------------|------|------|
| F1 | `POST /v1/devices` 등록/갱신 — 저장되고, **같은 token 재등록은 upsert**(중복 행 0) | §3, §4 | 통합테스트 |
| F2 | `PUT /v1/users/{id}/settings` — `notification_setting` 반영되고 **후속 발송 판정에 적용** | §3, §4 | 통합테스트 |
| F3 | `POST /v1/notifications` → **202 + requestId**, 비동기로 발송돼 **mock provider 1회 호출**, `delivery.status=sent` | §1, §3 | 통합테스트 |
| F4 | 템플릿 렌더링 — `templateId` + `params` → **치환된 본문**이 provider로 전달 | §4 | 통합테스트 |
| F5 | 멀티채널 — `channel` 미지정 시 사용자 **enabled 채널마다 각각 발송**(예: push+email → delivery 2건) | §3 | 통합테스트 |
| F6 | 멀티디바이스 — 한 user에 device 2개 → **push delivery 2건** 각각 발송 | §4 | 통합테스트 |
| F7 | opt-out suppression — 거부된 채널/카테고리는 `status=suppressed`, **provider 호출 0** | §1, §6-3 | 통합테스트 |
| F8 | 상태 조회 — `GET /v1/notifications/{requestId}` → `progress`(pending/partial/completed) + **delivery별 status 배열** | §3 | 통합테스트 |

**게이트**: F1~F8 통합테스트 전부 PASS. (url-shortener의 "기능 테스트 N개 통과" 형식과 동일.)

---

## 1-B. 정량 완료 조건 — 신뢰성 (라운드 B)

> 멱등 · Transactional Outbox · 재시도/DLQ · 중복발행 방지. 동기 디스패치 → **outbox + 릴레이**로 전환(설계 §6-1).

| ID | 완료 조건 (정량) | 근거 | 검증 |
|----|------------------|------|------|
| B1 | 같은 `(producerId, dedupKey)` 재접수(**동시 50 포함**) → `notification_request` **1건**, 실발송 **1회** | §6-1 | 통합(순차+동시) |
| B2 | 인라인 발행 없이 접수된 outbox **50건**을 릴레이가 전부 회수 → 실발송 50, **유실 0** | §6-1 | 통합 |
| B3 | 발행 실패 주입 시 outbox **PENDING 잔존**(미발행), 회복 후 회수 | §6-1 | 통합(실패 주입) |
| B4 | 영속 실패 → **maxRetry=5 후 DLQ** + delivery FAILED, 백오프 지수 증가 | §6-1 | 통합 + 단위(Backoff) |
| B5 | 크래시 재발행 시 provider idempotency key 로 **실발송 중복 0** | §6-1 | 통합 |

**게이트**: B1~B5 통합 + Backoff 단위 전부 PASS.

---

## 2. Goal Prompt (그대로 복사해 사용)

```text
목표: notification-system/README.md 설계의 '기능(happy-path)'을 구현하고,
아래 기능 완료 조건(F1~F8)을 전부 PASS 시켜라. (신뢰성·부하·보안은 이번 범위 밖.)

스택: Kotlin/Spring Boot + PostgreSQL + Redis. 서드파티(FCM/Twilio/SendGrid)는 mock(accept만).
검증: Testcontainers 통합테스트 (부하/k6 없음).

작업 방식 (loop until verified — F1~F8이 전부 green 될 때까지 멈추지 마라):
1. 각 항목마다 먼저 실패하는 통합테스트를 쓴다 (red).
2. 최소 구현으로 통과시킨다 (green) → 리팩터.
3. 전체 테스트를 돌려 미통과가 있으면 고치고 반복.

완료 조건 (전부 PASS = 완료):
[F1] POST /v1/devices 등록/갱신 — 같은 token 재등록은 upsert(중복 행 0)
[F2] PUT settings — opt-out 저장되고 후속 발송 판정에 적용
[F3] POST /v1/notifications → 202+requestId, 비동기 발송, mock provider 1회 호출, delivery.status=sent
[F4] templateId+params → 치환된 본문이 provider로 전달
[F5] channel 미지정 → enabled 채널마다 각각 발송 (예: push+email = delivery 2건)
[F6] user 2-device → push delivery 2건 각각 발송
[F7] opt-out된 채널/카테고리 → status=suppressed, provider 호출 0
[F8] GET /v1/notifications/{requestId} → progress + delivery별 status 반환

산출물: 구현 코드 + 통합테스트.
완료 시 README "검증 상태"에 'F1~F8 통과 N개'를 url-shortener 형식으로 기록.
```

---

## 3. 다음 라운드

게이트는 **B → D → C 순**으로 덧붙인다(부하 C는 기능·신뢰성이 선통과해야 의미 있음).

- ✅ **B. 신뢰성** — 멱등 `(producerId,dedupKey)`, Transactional Outbox·유실0, 재시도/DLQ, 릴레이 중복발행 방지 (§6-1) → **완료(§1-B)**
- ⬜ **D. 피로도·보안** — throttle 빈도 한도, 인증(mTLS/JWT)·PII 암호화·모니터링 (§6-3, §6-5)
- ⬜ **C. 성능·부하** — 피크 900/s·캠페인 16,000/s·`drain_time`·p95, k6 재현성 하니스(median of 3) (§2)
- ⬜ **캠페인 fan-out** — `POST /v1/campaigns` 전개·checkpoint·backpressure (§6-2)
