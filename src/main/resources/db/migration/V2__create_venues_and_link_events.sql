CREATE TABLE venues (
                        id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        name VARCHAR(200) NOT NULL,
                        city VARCHAR(100) NOT NULL
);

ALTER TABLE events
    ADD COLUMN venue_id BIGINT NOT NULL REFERENCES venues (id);

CREATE INDEX idx_events_venue_id ON events (venue_id);