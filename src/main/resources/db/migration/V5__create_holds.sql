CREATE TABLE holds (
                       id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       user_id    BIGINT      NOT NULL REFERENCES users (id),
                       event_id   BIGINT      NOT NULL REFERENCES events (id),
                       status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'CONVERTED')),
                       expires_at TIMESTAMPTZ NOT NULL,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Find a customer's holds for an event, for the 6-seat limit
CREATE INDEX idx_holds_user_event ON holds (user_id, event_id);

-- Find active holds that have expired, for the expiry job
CREATE INDEX idx_holds_active_expires_at ON holds (expires_at) WHERE status = 'ACTIVE';

ALTER TABLE seats
    ADD COLUMN hold_id BIGINT REFERENCES holds (id),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_seats_hold_id ON seats (hold_id);

ALTER TABLE seats
    ADD CONSTRAINT chk_seats_status
        CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
    ADD CONSTRAINT chk_seats_hold_consistent
        CHECK (
            (status = 'AVAILABLE' AND hold_id IS NULL)
            OR (status = 'HELD' AND hold_id IS NOT NULL)
            OR status = 'SOLD'
        );