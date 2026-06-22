-- 단일 채널(푸시) 전환: 멀티채널 breadth 제거. 채널·연락처 컬럼을 걷어낸다.
ALTER TABLE notification_delivery DROP COLUMN channel;
ALTER TABLE outbox             DROP COLUMN channel;
ALTER TABLE template           DROP COLUMN channel;
ALTER TABLE app_user           DROP COLUMN email, DROP COLUMN phone;

-- 수신 설정은 이제 (user, category) 단위.
ALTER TABLE notification_setting DROP CONSTRAINT uq_setting;
ALTER TABLE notification_setting DROP COLUMN channel;
ALTER TABLE notification_setting ADD CONSTRAINT uq_setting UNIQUE (user_id, category);
