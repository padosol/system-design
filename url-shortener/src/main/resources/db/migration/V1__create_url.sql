-- 카운터(전역 시퀀스): nextval → Base62 인코딩 → short_key
CREATE SEQUENCE url_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE url (
    id          BIGINT         PRIMARY KEY,
    short_key   VARCHAR(16)    NOT NULL,
    long_url    VARCHAR(2048)  NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    expire_at   TIMESTAMPTZ,
    CONSTRAINT uq_url_short_key UNIQUE (short_key)
);
