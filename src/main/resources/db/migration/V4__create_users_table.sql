CREATE TABLE users (
                       id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       email         VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(email)),
                       password_hash VARCHAR(255) NOT NULL,
                       role          VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER'
                           CHECK (role IN ('CUSTOMER', 'ADMIN')),
                       created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);