CREATE TABLE OUTBOX (
                        id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        transfer_id VARCHAR(36)  NOT NULL,
                        payload     TEXT         NOT NULL,
                        created_at  TIMESTAMP    NOT NULL DEFAULT now()
);