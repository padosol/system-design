# 알림 시스템 용어집

`notification-system` 도메인 고유 용어. 아키텍처·운영 공통어(멱등성·fan-out·throttling 등)는 루트
[`docs/glossary.md`](../glossary.md) 참고.

## 채널 (Channel)
알림 전달 경로: 모바일 푸시(APNs/FCM), SMS, 이메일.

## 프로바이더 (Provider)
실제 발송을 수행하는 서드파티(APNs, FCM, SMS 게이트웨이 등). 최종 사용자 도달은 provider에 달려
있어 시스템의 보장 범위 밖.

## 디바이스 토큰 (Device Token)
푸시 발송 대상을 식별하는 단말 토큰. 등록/갱신 대상.

## 수신 거부 (opt-out)
사용자가 채널/카테고리별로 알림을 받지 않도록 설정하는 것.

## 카테고리 (Category)
알림 성격 구분: `transactional`(주문/OTP 등)·`marketing`. 동의·스로틀 판정 근거.

## 아웃박스 (Outbox)
발송 사실을 DB에 먼저 기록해 메시지 유실 없이 비동기 발송하는 패턴의 기록 테이블.

## 전달 (Delivery)
한 알림 요청이 개별 디바이스/채널로 실제 발송되는 단위. 요청 1건이 디바이스 수만큼 delivery로 증폭.

## dedupKey
producer가 부여하는 멱등키. `(producerId, dedupKey)`로 중복 발송을 막는다.
