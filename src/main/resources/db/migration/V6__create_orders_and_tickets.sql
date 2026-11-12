-- Holds can now be frozen while their payment is in progress
ALTER TABLE holds DROP CONSTRAINT holds_status_check;
ALTER TABLE holds ADD CONSTRAINT holds_status_check
    CHECK (status IN ('ACTIVE', 'PAYMENT_PENDING', 'RELEASED', 'EXPIRED', 'CONVERTED'));

CREATE TABLE orders (
                        id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        user_id          BIGINT       NOT NULL REFERENCES users (id),
                        event_id         BIGINT       NOT NULL REFERENCES events (id),
                        hold_id          BIGINT       NOT NULL UNIQUE REFERENCES holds (id),
                        status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'EXPIRED', 'REFUNDED')),
                        total_cents      INTEGER      NOT NULL CHECK (total_cents >= 0),
                        idempotency_key  VARCHAR(100) NOT NULL,
                        payment_id       VARCHAR(100) UNIQUE,
                        checkout_url     VARCHAR(500),
                        payment_deadline TIMESTAMPTZ  NOT NULL,
                        created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        UNIQUE (user_id, idempotency_key)
);

-- For the reconciliation and abandoned-payment jobs
CREATE INDEX idx_orders_pending_created_at ON orders (created_at) WHERE status = 'PENDING';

-- For "my orders"
CREATE INDEX idx_orders_user_created_at ON orders (user_id, created_at DESC);

CREATE TABLE tickets (
                         id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                         order_id  BIGINT      NOT NULL REFERENCES orders (id),
                         seat_id   BIGINT      NOT NULL UNIQUE REFERENCES seats (id),
                         code      VARCHAR(64) NOT NULL UNIQUE,
                         issued_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tickets_order_id ON tickets (order_id);

-- Every webhook the payment provider has sent, so duplicates can be ignored
CREATE TABLE payment_webhook_events (
                                        event_id    VARCHAR(100) PRIMARY KEY,
                                        payment_id  VARCHAR(100) NOT NULL,
                                        received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);