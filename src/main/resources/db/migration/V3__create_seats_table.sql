CREATE TABLE seats (
                       id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       event_id    BIGINT      NOT NULL REFERENCES events (id),
                       section     VARCHAR(20) NOT NULL,
                       row_label   VARCHAR(20) NOT NULL,
                       seat_number INTEGER     NOT NULL CHECK (seat_number > 0),
                       price_cents INTEGER     NOT NULL CHECK (price_cents >= 0),
                       status      VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
                       UNIQUE (event_id, section, row_label, seat_number)
);