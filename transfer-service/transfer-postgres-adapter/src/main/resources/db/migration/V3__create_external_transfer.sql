CREATE TABLE EXTERNAL_TRANSFER (
                           id           VARCHAR(36)    PRIMARY KEY,
                           source_iban  VARCHAR(27)    NOT NULL REFERENCES account (iban),
                           dest_iban    VARCHAR(27)    NOT NULL,
                           amount       NUMERIC(19, 4) NOT NULL,
                           currency     VARCHAR(3)     NOT NULL,
                           status       VARCHAR(20)    NOT NULL,
                           reason       VARCHAR(500),
                           created_at   TIMESTAMP      NOT NULL DEFAULT now()
);
