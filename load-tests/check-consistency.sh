#!/usr/bin/env bash
# Checks that seats, holds, orders and tickets agree with each other for one event.
# Every count must be 0. Usage: ./check-consistency.sh <event-id> [compose-profile-args]
set -euo pipefail

EVENT_ID="${1:?Pass the event ID}"

docker compose exec -T postgres psql -U ticketing -d ticketing -v event_id="$EVENT_ID" <<'SQL'
SELECT 'held seat whose hold is no longer active' AS problem, count(*)
  FROM seats s JOIN holds h ON s.hold_id = h.id
  WHERE s.event_id = :event_id AND s.status = 'HELD' AND h.status NOT IN ('ACTIVE', 'PAYMENT_PENDING')
UNION ALL
SELECT 'sold seat without a ticket', count(*)
  FROM seats s LEFT JOIN tickets t ON t.seat_id = s.id
  WHERE s.event_id = :event_id AND s.status = 'SOLD' AND t.id IS NULL
UNION ALL
SELECT 'ticket for a seat that is not sold', count(*)
  FROM tickets t JOIN seats s ON t.seat_id = s.id
  WHERE s.event_id = :event_id AND s.status <> 'SOLD'
UNION ALL
SELECT 'confirmed order without tickets', count(*)
  FROM orders o
  WHERE o.event_id = :event_id AND o.status = 'CONFIRMED'
    AND NOT EXISTS (SELECT 1 FROM tickets t WHERE t.order_id = o.id)
UNION ALL
SELECT 'ticket on an order that is not confirmed', count(*)
  FROM tickets t JOIN orders o ON t.order_id = o.id
  WHERE o.event_id = :event_id AND o.status <> 'CONFIRMED';
SQL