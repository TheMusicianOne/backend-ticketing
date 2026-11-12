import hashlib
import hmac
import json

import httpx
import pytest

from app import webhooks
from app.config import settings
from app.models import CreatePaymentRequest, PaymentStatus
from app.webhooks import build_event, deliver, sign

AUTH = {"Authorization": "Bearer test_fake_psp_key"}
WEBHOOK_URL = "http://merchant.test/webhooks/payments"


def verify(header: str, body: bytes, secret: str) -> bool:
    """What the receiving side does: recompute the signature and compare."""
    parts = dict(part.split("=", 1) for part in header.split(","))
    expected = hmac.new(secret.encode(), f"{parts['t']}.".encode() + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, parts["v1"])


# ---- Signing ---------------------------------------------------------------------

def test_signature_has_timestamp_and_hmac():
    header = sign(b'{"a":1}', 1762958899, "secret")

    assert header.startswith("t=1762958899,v1=")
    assert verify(header, b'{"a":1}', "secret")


def test_signature_matches_a_manual_hmac():
    body = b'{"paymentId":"tr_1","status":"paid"}'
    expected = hmac.new(b"secret", b"1000." + body, hashlib.sha256).hexdigest()

    assert sign(body, 1000, "secret") == f"t=1000,v1={expected}"


def test_changed_body_fails_verification():
    header = sign(b'{"status":"failed"}', 1000, "secret")

    assert not verify(header, b'{"status":"paid"}', "secret")


def test_wrong_secret_fails_verification():
    header = sign(b'{"a":1}', 1000, "secret")

    assert not verify(header, b'{"a":1}', "another-secret")


def test_changed_timestamp_fails_verification():
    header = sign(b'{"a":1}', 1000, "secret").replace("t=1000", "t=2000")

    assert not verify(header, b'{"a":1}', "secret")


# ---- Building events ---------------------------------------------------------------

def make_paid_payment(store):
    payment, _ = store.create(
        CreatePaymentRequest(amount_cents=5000, description="Order 1", webhook_url=WEBHOOK_URL), "key-1")
    return store.transition(payment.id, {PaymentStatus.OPEN}, PaymentStatus.PAID)


def test_event_contains_payment_id_and_status(store):
    payment = make_paid_payment(store)

    event = json.loads(build_event(payment))

    assert event["paymentId"] == payment.id
    assert event["status"] == "paid"
    assert event["eventId"].startswith("evt_")
    assert event["createdAt"].endswith("Z")


def test_event_json_is_compact(store):
    body = build_event(make_paid_payment(store))

    assert b" " not in body


def test_each_event_gets_a_new_id(store):
    payment = make_paid_payment(store)

    first = json.loads(build_event(payment))["eventId"]
    second = json.loads(build_event(payment))["eventId"]

    assert first != second


# ---- Delivering ------------------------------------------------------------------

class FakeHttp:
    """Replaces httpx.post, answering with the given outcomes in order."""

    def __init__(self, *outcomes):
        self.outcomes = list(outcomes)
        self.calls = []

    def __call__(self, url, content, headers, timeout):
        self.calls.append({"url": url, "body": content, "headers": headers})
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return httpx.Response(outcome)


@pytest.fixture
def no_sleep(monkeypatch):
    """Skips the real retry delays, but records them."""
    delays = []
    monkeypatch.setattr(webhooks.time, "sleep", delays.append)
    return delays


def test_successful_delivery_sends_once(monkeypatch, no_sleep):
    http = FakeHttp(200)
    monkeypatch.setattr(webhooks.httpx, "post", http)

    deliver(WEBHOOK_URL, b'{"a":1}')

    assert len(http.calls) == 1
    assert http.calls[0]["url"] == WEBHOOK_URL
    assert http.calls[0]["body"] == b'{"a":1}'
    assert verify(http.calls[0]["headers"]["Payment-Signature"], b'{"a":1}', settings.webhook_secret)
    assert no_sleep == []


def test_failed_delivery_is_retried_until_it_succeeds(monkeypatch, no_sleep):
    http = FakeHttp(500, httpx.ConnectError("refused"), 200)
    monkeypatch.setattr(webhooks.httpx, "post", http)

    deliver(WEBHOOK_URL, b'{"a":1}')

    assert len(http.calls) == 3
    assert no_sleep == [1, 2]


def test_every_retry_sends_the_same_body_with_a_valid_signature(monkeypatch, no_sleep):
    http = FakeHttp(500, 503, 200)
    monkeypatch.setattr(webhooks.httpx, "post", http)

    deliver(WEBHOOK_URL, b'{"eventId":"evt_1"}')

    for call in http.calls:
        assert call["body"] == b'{"eventId":"evt_1"}'
        assert verify(call["headers"]["Payment-Signature"], call["body"], settings.webhook_secret)


def test_delivery_gives_up_after_four_attempts(monkeypatch, no_sleep):
    http = FakeHttp(500, 500, 500, 500)
    monkeypatch.setattr(webhooks.httpx, "post", http)

    deliver(WEBHOOK_URL, b'{"a":1}')

    assert len(http.calls) == 4
    assert no_sleep == [1, 2, 4]


# ---- Which actions send webhooks --------------------------------------------------

@pytest.fixture
def sent(monkeypatch):
    """Records webhooks instead of sending them."""
    calls = []
    record = lambda url, body: calls.append({"url": url, "event": json.loads(body)})
    monkeypatch.setattr("app.checkout.deliver", record)
    monkeypatch.setattr("app.payments.deliver", record)
    return calls


def create_payment(client, webhook_url=WEBHOOK_URL, key="key-1"):
    body = {"amountCents": 5000, "description": "Order 1"}
    if webhook_url:
        body["webhookUrl"] = webhook_url
    return client.post("/v1/payments", headers={**AUTH, "Idempotency-Key": key}, json=body).json()["id"]


def test_approving_sends_a_paid_webhook(client, sent):
    payment_id = create_payment(client)

    client.post(f"/checkout/{payment_id}/approve")

    assert len(sent) == 1
    assert sent[0]["url"] == WEBHOOK_URL
    assert sent[0]["event"]["paymentId"] == payment_id
    assert sent[0]["event"]["status"] == "paid"


def test_declining_sends_a_failed_webhook(client, sent):
    payment_id = create_payment(client)

    client.post(f"/checkout/{payment_id}/decline")

    assert [s["event"]["status"] for s in sent] == ["failed"]


def test_canceling_sends_a_canceled_webhook(client, sent):
    payment_id = create_payment(client)

    client.post(f"/v1/payments/{payment_id}/cancel", headers=AUTH)

    assert [s["event"]["status"] for s in sent] == ["canceled"]


def test_approving_twice_sends_one_webhook(client, sent):
    payment_id = create_payment(client)

    client.post(f"/checkout/{payment_id}/approve")
    client.post(f"/checkout/{payment_id}/approve")

    assert len(sent) == 1


def test_no_webhook_without_a_webhook_url(client, sent):
    payment_id = create_payment(client, webhook_url=None)

    client.post(f"/checkout/{payment_id}/approve")

    assert sent == []