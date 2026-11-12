package io.github.themusicianone.ticketing_backend.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PaymentWebhookEventRepository {

    private final JdbcTemplate jdbc;

    PaymentWebhookEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true if this event is new, false if it was already recorded. */
    boolean recordIfNew(String eventId, String paymentId) {
        int inserted = jdbc.update("""
                INSERT INTO payment_webhook_events (event_id, payment_id)
                VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, paymentId);
        return inserted == 1;
    }
}
