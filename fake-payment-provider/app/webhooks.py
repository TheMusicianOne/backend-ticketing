import hashlib
import hmac
import json
import logging
import secrets
import time
from datetime import datetime, timezone
from app.chaos import chaos
import httpx

from app.config import settings
from app.models import Payment

log = logging.getLogger("webhooks")

RETRY_DELAYS_SECONDS = [1, 2, 4]


def sign(body: bytes, timestamp: int, secret: str) -> str:
    signed_payload = f"{timestamp}.".encode() + body
    digest = hmac.new(secret.encode(), signed_payload, hashlib.sha256).hexdigest()
    return f"t={timestamp},v1={digest}"


def build_event(payment: Payment) -> bytes:
    event = {
        "eventId": "evt_" + secrets.token_hex(8),
        "paymentId": payment.id,
        "status": payment.status.value,
        "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    # Compact JSON, so the bytes are predictable
    return json.dumps(event, separators=(",", ":")).encode()





def deliver(url: str, body: bytes) -> None:
    """Sends one event, possibly late, lost, or twice, depending on the chaos settings."""
    current = chaos.current()

    if current.webhook_delay_ms:
        time.sleep(current.webhook_delay_ms / 1000)

    if chaos.happens(current.webhook_drop_rate):
        log.info("Simulated a lost webhook to %s", url)
        return

    copies = 2 if chaos.happens(current.webhook_duplicate_rate) else 1
    for _ in range(copies):
        _send_with_retries(url, body)


def _send_with_retries(url: str, body: bytes) -> None:
    """Sends one event, retrying on failure. Runs in the background."""
    for attempt, delay in enumerate([0, *RETRY_DELAYS_SECONDS], start=1):
        if delay:
            time.sleep(delay)

        # Sign each attempt with a fresh timestamp, like real PSPs do
        timestamp = int(time.time())
        headers = {
            "Content-Type": "application/json",
            "Payment-Signature": sign(body, timestamp, settings.webhook_secret),
        }
        try:
            response = httpx.post(url, content=body, headers=headers, timeout=5.0)
            if 200 <= response.status_code < 300:
                log.info("Webhook delivered to %s on attempt %d", url, attempt)
                return
            log.warning("Webhook to %s got %d on attempt %d", url, response.status_code, attempt)
        except httpx.HTTPError as error:
            log.warning("Webhook to %s failed on attempt %d: %s", url, attempt, error)

    log.error("Giving up on webhook to %s", url)