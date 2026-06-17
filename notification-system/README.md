# 알림 시스템 (Notification System)

> 서버 이벤트를 받아 푸시/SMS/이메일 등 여러 채널로 사용자에게 알림을 안정적으로 전달한다. (예: 주문 완료 푸시, OTP SMS, 마케팅 메일)

---

## 1. 요구사항 정의

### 기능적 요구사항
- 여러 채널을 지원한다: **모바일 푸시(iOS APNs / Android FCM), SMS, 이메일**.
- 다른 서비스(주문, 결제 등)가 보낸 **이벤트**를 받아 알림을 발송한다.
- 사용자는 채널/카테고리별로 **수신 거부(opt-out)** 할 수 있다.
- 디바이스 토큰·연락처를 등록/갱신한다.

### 비기능적 요구사항
- **신뢰성**: 접수된 알림은 **내부적으로 durable하게 기록되고 최소 한 번 발송 시도**된다. (단, 서드파티 이후 *최종 사용자 도달*은 provider에 달려 있어 보장 대상이 아니다 — §6-1.)
- **확장성**: 대규모 fan-out. 캠페인 한 번에 수백만 명 발송을 흡수해야 한다.
- **near real-time**: 소프트 실시간. 약간의 지연은 허용(초~수초).
- **사용자 피로도 제어**: 같은 사람에게 과도하게 보내지 않는다(throttling).
- **격리**: 한 채널/서드파티 장애가 다른 채널을 막지 않는다.

### Out of scope
- 인앱 알림함(inbox/feed) — 읽음 상태·목록 조회는 별도 설계(뉴스피드에 가까움)
- 알림 내용 작성용 어드민 UI
- 정확히-한-번(exactly-once) 보장 — 비용 대비 효과가 낮아 채택 안 함(§7)
- 알림 간 **순서 보장** — 채널·서드파티 경계라 비용이 큼. 필요 시 이벤트에 버전을 실어 수신측이 **최신만 반영**(stale 무시, §7).
- **예약/지연 발송**(delay queue/scheduler) — 별도 컴포넌트라 이번 범위 밖. throttle 초과 건도 "지연"이 아니라 **드롭/이월**로 처리한다(§6-3).

---

## 2. 규모 추정 (Capacity Estimation)

> URL 단축기가 **읽기 중심**이었다면, 알림은 **쓰기·전파(fan-out) 중심**이다. 평균 QPS보다 **버스트(캠페인 폭발)** 와 **내부 쓰기 증폭**이 진짜 병목이다.

**가정**
- 푸시 **1,000만/일**, 이메일 **500만/일**, SMS **100만/일** → 합계 ≈ **1,600만/일**

| 항목 | 계산 | 결과 |
|------|------|------|
| 평균 발송 QPS | 1,600만 / 86,400초 | **≈ 185/s** |
| 피크 QPS (일상 5배) | 185 × 5 | **≈ 900/s** |
| **캠페인 버스트** | 1,000만 명 대상을 10분에 발송 | **≈ 16,000/s** |
| 알림 로그(1건 ≈ 0.5 KB) | 1,600만 × 0.5 KB | **≈ 8 GB/일** |
| 로그 1년 보관 | 8 GB × 365 | **≈ 2.9 TB** |

➡️ **결론 1 — 평균은 쉽다**: 185/s는 서버 한 대로도 처리 가능. 진짜 문제는 캠페인 버스트와 그로 인한 내부 쓰기 증폭이다.

➡️ **결론 2 — provider 쿼터가 실질 상한**: "1,000만/10분 = 16,000/s"는 **provider가 그 속도를 받아줄 때만** 성립한다. APNs/SMS 쿼터가 2,000/s라면 같은 양이 **80분 이상 적체**된다. 따라서 발송 속도가 아니라 **backlog drain time(적체 해소 시간)** 으로 산정해야 한다.

➡️ **결론 3 — 내부 쓰기 증폭을 따로 센다**: 발송 1건은 내부적으로 `request 1 + delivery N(디바이스 수) + outbox N + 재시도 배수`의 쓰기를 만든다. 그래서 위 표의 "8 GB/일·DB write"는 **발송 1건=1행을 가정한 하한**이고, delivery·outbox는 **평균 디바이스 배수(예: ×1.5)** 만큼 더 쌓인다. provider QPS만 보지 말고 **device fan-out 배수 · retry 배수 · 큐 payload 크기 · egress 대역폭 · DB write/s · 인덱스 크기**를 함께 추정한다.

---

## 3. API 설계

발송 진입점을 **두 경로로 분리**한다 — 단건(특정 사용자)과 캠페인(세그먼트 대상)은 처리 방식이 다르기 때문(§5, §6-2).

```http
# 내부 서비스 → 알림 시스템 (서비스 간 인증: mTLS 또는 JWT service identity, §6-5)

# [단건] 특정 사용자에게 — 트랜잭션 알림(주문/OTP 등)
POST /v1/notifications
  Body: {
    "userId": "u_123",
    "channel": "push|sms|email",         // 미지정 시 사용자 설정 따라 멀티채널
    "templateId": "ORDER_SHIPPED",
    "category": "transactional",         // marketing|transactional — 동의·throttle 판정 근거(§6-3)
    "priority": "high",                  // high|normal|bulk — 우선순위 큐 라우팅(§7). producer 권한 검사
    "params": { "orderId": "A1" },
    "dedupKey": "order-A1-shipped"       // (producerId, dedupKey)로 멱등(§6-1)
  }
  202 Accepted: { "requestId": "r_789" }   // 비동기 — 접수만 보장

# [캠페인] 세그먼트 대상 대량 발송 — 사용자 목록은 fan-out 서비스가 전개(§6-2)
POST /v1/campaigns
  Body: { "segmentId": "seg_active_kr", "templateId": "PROMO_618", "channel": "push", "priority": "bulk" }
  202 Accepted: { "campaignId": "c_42" }   // 세그먼트 ID만 접수, 동기 전개 안 함

# [캠페인] 전개 진척·집계 조회 (운영자용, §6-2)
GET /v1/campaigns/{campaignId}
  200: { "total": 10000000, "sent": 4200000, "failed": 1300, "status": "running|done" }

# 발송 상태 조회 — 요청(aggregate) + 채널/디바이스별 delivery 상태를 함께 반환(§4, §6-4)
GET /v1/notifications/{requestId}
  200: {
    "progress": "pending|partial|completed",   // delivery가 terminal에 도달한 '진행도'(성공 아님)
    "deliveries": [                              // 성공/실패는 delivery별 status로 관측(§6-4)
      { "channel": "push", "target": "device_a", "status": "delivered" },
      { "channel": "push", "target": "device_b", "status": "bounced" }
    ]
  }

# 디바이스 토큰 등록/갱신
POST /v1/devices
  Body: { "userId": "u_123", "token": "<APNs/FCM token>", "platform": "ios|android" }

# 수신 설정 변경
PUT /v1/users/{userId}/settings
  Body: { "channel": "push", "category": "marketing", "enabled": false }
```

> `202 Accepted` — 발송을 **약속(접수)** 만 하고 즉시 응답. 실제 전송은 큐 뒤에서 비동기.
> **캠페인은 동기 요청 안에서 사용자 목록을 풀지 않는다** — `segmentId`만 받고 fan-out 서비스가 배치로 전개한다(그렇지 않으면 1,000만 명 전개에 요청 하나가 분 단위로 멈춤).

---

## 4. 데이터 모델

**저장소 선택의 핵심**: 멱등성·outbox·접수 원장은 **반드시 같은 트랜잭션 RDB**에 둔다(원자성). 대용량 분석/감사 로그만 **비동기로 별도 저장소**(NoSQL/시계열)에 적재한다. (접수 원장을 NoSQL로 두면 outbox와 한 트랜잭션으로 못 묶여 원자성이 깨진다.)

```
user                          device  (user 1:N device)
├─ user_id (PK)               ├─ device_id (PK)
├─ email                      ├─ user_id (FK)
└─ phone                      ├─ token        -- APNs/FCM 토큰
                              ├─ platform     -- ios/android
notification_setting          └─ last_seen
├─ user_id, channel,
│  category   (복합키)         template
└─ enabled (bool)             ├─ template_id (PK)
                              ├─ channel
idempotency                   ├─ category, priority   -- 분류·우선순위 기본값
├─ producer_id, dedup_key     ├─ locale, version      -- 다국어·버전
│  (복합 UNIQUE)               └─ subject / body        -- 치환자
└─ request_id
```

**발송 단위 — 요청/전달을 분리한다** (한 요청이 멀티채널·멀티디바이스로 갈라지므로):

```
notification_request          notification_delivery  (request 1:N delivery)
├─ request_id (PK)            ├─ delivery_id (PK)
├─ user_id                    ├─ request_id (FK)
├─ category, priority         ├─ channel, target      -- 디바이스토큰/전화/이메일
└─ created_at                 ├─ status   -- queued/sent/delivered/bounced/expired/suppressed/failed
                              ├─ attempt_count
outbox  (request와 같은 TX)    └─ updated_at
├─ outbox_id (PK)
├─ delivery_id (FK)
├─ payload                    -- 큐로 발행할 메시지(채널·수신처별)
├─ published, published_at    -- 릴레이 발행 표시
└─ lease_owner                -- fencing(중복 발행 방지, §6-1)
```

- **멱등(durable)**: `(producer_id, dedup_key)` UNIQUE를 `notification_request`와 **같은 트랜잭션**에서 잡는다. Redis는 빠른 1차 캐시로만 쓰고 **진실 원장이 아니다**(Redis eviction/failover 시 중복·유실이 생기므로). TTL이 필요하면 일자 파티션으로 정리.
- **`notification_delivery`**: 채널/디바이스별 1행 → 멀티채널·멀티디바이스 상태를 각각 추적. `GET`은 request aggregate + delivery 목록을 함께 반환(§3).
- **`status` 는 채널별 의미가 다르다**(§6-4): push는 보통 `provider_accepted`까지, email은 `delivered/bounced/opened`, SMS는 `delivered/undelivered`.
- **throttle 상태 저장소**: 빈도 한도(피로도)는 **Redis 슬라이딩 윈도우 카운터**로 판정한다 — durable 원장이 아니라 *best-effort*다(멱등성과 달리 정확성보다 비용을 택함). §6-3이 발송 직전 이 카운터를 읽는다.
- 디바이스 토큰 분리: 한 사용자가 여러 기기 → 활성 토큰마다 delivery 1건.

---

## 5. 개략적 설계 (High-level Design)

```
 [단건] 주문/결제 이벤트            [캠페인] POST /v1/campaigns (segmentId)
        │ POST /v1/notifications            │
        ▼                                   ▼
 ┌──────────────────────┐          [Fan-out Service]
 │ Notification Service │          세그먼트 → recipient 배치 전개
 │  ① 인증/권한·멱등확인  │          (checkpoint·backpressure, §6-2)
 │  ② 수신설정·연락처조회 │ ◀────────  request/delivery 생성
 │  ③ 템플릿 렌더링       │
 │  ④ request+delivery   │   ← 한 RDB 트랜잭션으로 원자 커밋(§6-1)
 │     +outbox 원자커밋   │
 └──────────┬───────────┘
            ▼
      [Outbox Relay]  ── outbox 폴링/CDC(+lease/fencing) → 채널 큐로 발행
       ┌────────┼────────┐
       ▼        ▼        ▼
  [Push Q]   [SMS Q]  [Email Q]     ← 버스트 버퍼 + 채널별 격리 (우선순위 분리, §7)
       ▼        ▼        ▼
  [Push W]   [SMS W]  [Email W]     ← 발송 직전 suppression 재검사 + rate-limited 소비
       ▼        ▼        ▼
   APNs/FCM   Twilio  SendGrid      ← 3rd-party (provider idempotency key 전달)
```

> **캠페인 경로 주의**: Fan-out 서비스는 위 ②~④(설정조회·렌더링·request/delivery+outbox 커밋)를 **배치로** 수행한다 — 단건의 동기 파이프라인을 1,000만 번 태우는 게 아니라, 같은 로직을 배치 우회 경로로 처리(§6-2).

**왜 큐를 두는가** (이 설계의 심장)
- **버스트 흡수**: 16,000/s가 몰려도 큐에 쌓고 워커가 일정 속도로 소비 → 서드파티 쿼터 초과/장애 방지.
- **디커플링**: API는 접수 후 즉시 `202` 응답(낮은 지연). 느린 서드파티에 API가 묶이지 않음.
- **채널별 격리**: SMS 업체 장애가 푸시를 막지 않음. 채널별 독립 확장.
- **재시도 분리**: 실패 건만 재시도/DLQ로 빼서 처리.

**큐 메시지에 무엇을 담는가**: **렌더링된 콘텐츠 + 수신처(토큰/전화/이메일) + delivery_id**. 워커는 본문 렌더링을 위해 DB를 다시 조회하지 않는다(무상태). **단, 발송 직전에 opt-out/throttle 만 가볍게 재검사**한다 — 큐 적재 후 사용자가 수신 거부할 수 있기 때문(§6-3). 대용량 첨부는 ID 참조로 둔다.

---

## 6. 상세 설계 (Deep-dive)

### 6-1. 신뢰성 — 보장 범위·멱등·원자성 (핵심 난제)

**먼저, "유실 0"은 과장이다.** Outbox는 *내부 큐 발행 유실*만 막는다. 서드파티에 넘긴 뒤의 토큰 만료·bounce·provider 장애·영수증 유실까지 막지 못한다. 그래서 보장을 이렇게 좁힌다:

> **"접수된 알림은 durable하게 기록되고, 내부적으로 최소 한 번 발송이 시도된다."**
> 최종 도달 여부는 `delivery.status`(sent/delivered/bounced/expired/...)로 **관측**하되 *보장*하지 않는다.

전달 보장 수준:

| 수준 | 구현 | 문제 |
|------|------|------|
| at-most-once | 보내고 잊음 | **유실** — 알림은 잃으면 안 됨 ✗ |
| **at-least-once** | outbox 원자 커밋 → 릴레이 발행 → 워커 ack, 실패 시 재시도 | **중복** 가능 → 멱등으로 흡수 ✓ |
| exactly-once | 분산 트랜잭션/2PC | 비싸고 서드파티 경계라 사실상 불가 ✗ |

**① 멱등 — durable하게, 같은 트랜잭션에서**
- `(producer_id, dedup_key)` UNIQUE를 `notification_request` 저장과 **한 RDB 트랜잭션**에 잡는다.
- ⚠️ Redis `SETNX` 를 **진실 원장으로** 쓰면 안 된다: SETNX 성공 후 DB 트랜잭션이 실패했는데 **Redis 키를 롤백하지 않거나 TTL을 길게 두면** producer 재시도가 24h 막혀 접수가 누락될 수 있고, eviction/failover 시 중복도 난다. 따라서 Redis 키는 **DB 커밋과 생명주기를 맞추고(실패 시 DEL)** *빠른 사전 차단 캐시*로만 쓴다 — 진실은 DB UNIQUE.

**② 원자성 — dual-write 함정 회피 (Transactional Outbox)**
- "DB 저장 → 큐 enqueue" 2단계는 저장 후 enqueue 직전 크래시 시 발송이 누락된다(DB·큐가 한 트랜잭션이 아님).
- 해결: `request + delivery + outbox` 를 **한 트랜잭션으로 커밋**, **Outbox Relay**(폴러/CDC)가 `published=false`를 읽어 큐로 발행.

**③ 릴레이 중복 발행 방지**
- 릴레이가 publish 성공 후 `published=true` 쓰기 전에 죽으면 **중복 발행**된다.
- 완화: outbox에 **lease/fencing token + `published_at`**, delivery 상태 전이는 **CAS**로. 서드파티에는 **provider idempotency key**(지원 채널)를 넘겨 중복을 흡수. 미지원 채널은 중복 가능성을 명시.

**④ 실패 처리**
- 워커는 전송 성공 후에만 ack. 실패/타임아웃 → 지수 백오프 재시도 → N회 초과 시 **DLQ**.
- **Reconciliation**: 오래 `queued`인 건을 스캔해 재발행하되, **delivery/attempt 상태를 보고** 판단(이미 sent면 재발송 금지).

### 6-2. 대량 캠페인 fan-out (내부 쓰기 증폭 관리)
- `POST /v1/campaigns`는 **`segmentId`만 접수**한다. 동기 요청에서 1,000만 명을 풀면 메모리/지연 폭발.
- **단건 API의 인터페이스는 재사용하되 동기 경로를 그대로 태우지 않는다** — 그러면 Service·RDB·outbox·렌더링이 모두 16k/s 쓰기 압력을 받는다.
- 설계 요소:
  - **`campaign_recipient` 테이블 + checkpoint/resume 커서**: 실패 시 중단 지점부터 재개.
  - **segment snapshot**: 전개 중 세그먼트가 바뀌어도 일관된 대상 집합.
  - **per-campaign backpressure + priority별 쿼터**: 캠페인이 OTP 같은 트랜잭션 알림을 굶기지 않게.

### 6-3. 수신 거부·피로도 제어 (suppression & throttling)
- **발송 직전 재검사**: 워커가 큐에서 꺼낸 직후 **opt-out/suppression/빈도 한도**를 다시 확인한다. 접수~발송 사이에 사용자가 수신 거부할 수 있으므로(특히 마케팅). OTP/결제 같은 **필수 알림은 접수 시점 스냅샷 기준**으로 우회.
- **분류 근거 & 우선순위 결정 규칙**: 유효 `category/priority` = **template 기본값을 베이스로, producer가 권한 범위 내에서만 override**한다(권한 밖 값이면 요청 거부, §6-5). 이렇게 확정된 값으로 throttle/우선순위를 판정한다. (예: transactional template에 `category:marketing`을 실어도 권한이 없으면 거부 → §6-3 판정 입력이 항상 확정됨.)
- **throttle 초과 처리**(예약/지연은 범위 밖이므로):
  - 마케팅·비필수 → **드롭 또는 다음 캠페인으로 이월**.
  - OTP·결제 → **throttle 제외**.
  - (진짜 "지연 후 발송"이 필요하면 delay queue를 범위에 추가해야 함.)

### 6-4. 서드파티 피드백 & 채널별 상태
- APNs/FCM가 **invalid token** → 해당 `device`를 stale 처리/삭제(다음부터 제외).
- 영수증 수신 시 `notification_delivery.status` 갱신. **단, "delivered"의 의미는 채널마다 다르다**:
  - **push**: 보통 `provider_accepted`까지만 확인 가능(최종 도달 영수증 미보장).
  - **email**: `delivered / bounced / opened` 구분 가능.
  - **SMS**: provider에 따라 `delivered / undelivered`.
- **서킷브레이커**: 특정 서드파티 오류율이 치솟으면 채널 호출을 일시 차단하고 큐에 백오프로 적체 → 장애 업체에 재시도 폭격 방지, 복구 후 재개.

### 6-5. 보안 & 모니터링 (PII가 핵심)
이 시스템은 **전화번호·이메일·device token·메시지 본문(PII)** 을 다룬다 → appKey/secret 수준으로 부족.
- **인증/인가**: mTLS 또는 OAuth/JWT **service identity**, producer별 **template/category/priority 권한**.
- **데이터 보호**: 저장 PII 암호화, **큐 payload 암호화/마스킹**, 접근 **감사 로그**, opt-out 변경 이력, **secret rotation**.
- **모니터링**: 큐 **backlog 깊이·age**, 채널별 발송/실패율, end-to-end 지연, DLQ 적재 → 백로그 급증이 곧 장애 신호.

---

## 7. 트레이드오프 & 확장

- **전달 보장**: at-least-once(중복 가능, 멱등으로 흡수) 채택. 보장 범위는 "내부 durable 기록 + 최소 한 번 시도"까지로 한정(최종 도달은 provider 의존). exactly-once는 서드파티 경계 때문에 비용 대비 효과 없음.
- **유실 방지 비용**: Transactional Outbox는 쓰기 1회 + 릴레이(폴링/CDC) 인프라가 늘고 발송 지연을 약간 더한다. 알림 도메인에선 그만한 가치가 있다고 판단.
- **순서**: 채널·재시도·멀티워커 때문에 전역 순서는 보장하지 않음. 필요하면 이벤트에 버전을 실어 **수신측이 최신만 표시**.
- **큐 구조 / 우선순위**: 채널별 분리(격리·독립 확장) + **우선순위 큐**로 트랜잭션(OTP/결제)과 벌크(마케팅)를 나눠 캠페인이 OTP를 막지 않게.
- **확장 포인트**
  - 워커는 채널별 수평 확장. 알림 로그는 시간 파티셔닝 + TTL/아카이빙으로 2.9 TB/년 관리.
  - **멀티 리전은 한 줄로 끝낼 수 없다**: 초기엔 **active-passive 또는 region-local sending**만. active-active로 가려면 멱등키의 **글로벌 유일성**, 캠페인 **shard ownership**, preference 일관성, outbox ownership까지 설계해야 함.
- **알려진 한계**: 멱등키 누락 시 중복 노출 가능 → 이벤트 발행 측의 `dedupKey` 부여를 규약화. 미지원 채널은 provider 중복 가능성 잔존.

---

## 구현 (Implementation)

설계의 **기능 1라운드(F1~F8)** 를 **Kotlin + Spring Boot 4.0**으로 구현했다. 정량 완료 조건과 goal prompt는 [GOALS.md](./GOALS.md).

- **스택**: Kotlin, Spring Boot 4.0 (WebMVC, Data JPA), PostgreSQL, Flyway, Gradle
- **아키텍처**: 레이어드(Controller → Service → Repository). 발송 단위는 `notification_request`(접수 원장) → `notification_delivery`(채널·디바이스별)로 분리(설계 §4)
- **발송**: 라운드1은 큐 대신 **동기 디스패치**로 단순화(GOALS 전제). 큐/outbox/재시도/멱등은 라운드B.
- **서드파티**: FCM/Twilio/SendGrid 자리에 **기록형 mock provider 3종**(accept) — 라운드2에서 실 어댑터로 교체.

### API (구현분)
| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/v1/devices` | 디바이스 토큰 등록/갱신(upsert) |
| PUT | `/v1/users/{userId}/settings` | 채널/카테고리별 수신 설정 |
| POST | `/v1/notifications` | 단건 발송 접수 (202 + requestId) |
| GET | `/v1/notifications/{requestId}` | 요청 progress + delivery별 status |

### 실행 / 테스트
```bash
./gradlew test     # Testcontainers(PostgreSQL) 기반 통합테스트, Docker 필요
```

### 검증 상태 (1라운드 · 기능)
- ✅ **기능 테스트 9개 통과** — F1~F8 + 컨텍스트 로드. Testcontainers로 실제 PostgreSQL 검증.
  F1 디바이스 upsert(중복 0) · F2 설정 적용 · F3 발송 202+provider 1회+SENT · F4 템플릿 치환 · F5 멀티채널(push+email=2) · F6 멀티디바이스(2건) · F7 opt-out suppressed(provider 0) · F8 상태조회(progress+delivery).
- ⏭️ **다음 라운드**: 신뢰성(멱등·outbox·DLQ) → 피로도·보안 → 부하, 캠페인 fan-out (GOALS §3).

---

## 참고 자료
- Alex Xu, *System Design Interview* — Chapter: Design a Notification System
- APNs / FCM, Twilio, SendGrid 공식 문서 (전송 쿼터·피드백 API)
- 본 문서는 외부 에이전트(codex, gpt-5.5)의 교차 리뷰로 멱등성·outbox 원자성·멀티채널 상태 모델·suppression 재검사 지점을 보강함.
