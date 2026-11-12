import secrets
import threading
from datetime import datetime, timezone

from app.models import CreatePaymentRequest, Payment, PaymentStatus


class IdempotencyConflict(Exception):
    """The same idempotency key was reused with a different request."""


class PaymentStore:
    def __init__(self, checkout_base_url: str):
        self._checkout_base_url = checkout_base_url
        self._payments: dict[str, Payment] = {}
        self._by_key: dict[str, tuple[str, CreatePaymentRequest]] = {}
        self._lock = threading.Lock()

    def create(self, request: CreatePaymentRequest, idempotency_key: str) -> tuple[Payment, bool]:
        """Returns (payment, created). A repeated key returns the original payment."""
        with self._lock:
            if idempotency_key in self._by_key:
                payment_id, original = self._by_key[idempotency_key]
                if original != request:
                    raise IdempotencyConflict(idempotency_key)
                return self._payments[payment_id], False

            payment_id = "tr_" + secrets.token_hex(8)
            payment = Payment(
                id=payment_id,
                status=PaymentStatus.OPEN,
                amount_cents=request.amount_cents,
                currency=request.currency,
                description=request.description,
                checkout_url=f"{self._checkout_base_url}/checkout/{payment_id}",
                webhook_url=str(request.webhook_url) if request.webhook_url else None,
                created_at=datetime.now(timezone.utc),
            )
            self._payments[payment_id] = payment
            self._by_key[idempotency_key] = (payment_id, request)
            return payment, True

    def get(self, payment_id: str) -> Payment | None:
        return self._payments.get(payment_id)

    def transition(self, payment_id: str, allowed_from: set[PaymentStatus],
                   new_status: PaymentStatus) -> Payment | None:
        """Changes the status only if the current one is allowed. Returns None if not."""
        with self._lock:
            payment = self._payments.get(payment_id)
            if payment is None or payment.status not in allowed_from:
                return None
            updated = payment.model_copy(update={"status": new_status})
            self._payments[payment_id] = updated
            return updated