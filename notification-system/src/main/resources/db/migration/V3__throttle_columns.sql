-- 라운드 D(피로도): 발송 직전(릴레이) throttle 판정에 쓰도록 outbox 를 self-contained 하게 보강.
-- (설계 §5: 큐 메시지에 user/category 까지 담아 워커가 DB 재조회 없이 판정)
ALTER TABLE outbox
    ADD COLUMN user_id  BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT '';
