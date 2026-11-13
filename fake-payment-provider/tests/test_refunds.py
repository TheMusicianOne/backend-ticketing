import json

import pytest

AUTH = {"Authorization": "Bearer test_fake_psp_key"}
WEBHOOK_URL = "http://merchant.test/webhooks/payments"


@pytest.fixture
def sent(monkeypatch):
    """Records webhooks instead of sending them."""
    calls = []
    record = lambda url, body: calls.append(json.loads(body))
    monkeypatch.setattr("app.checkout.deliver", record)
    monkeypatch.setattr("app.payments.deliver", record)
    return calls


def create_payment(client, key="key-1"):
    response = client.post(
        "/v1/payments",
        headers={**AUTH, "Idempotency-Key": key},
        json={"amountCents": 9100, "description": "Order 1", "webhookUrl": WEBHOOK_URL},
    )
    return response.json()["id"]


def paid_payment(client):
    payment_id = create_payment(client)
    client.post(f"/checkout/{payment_id}/approve")
    return payment_id


def refund(client, payment_id, key="refund-1", headers=None):
    return client.post(
        f"/v1/payments/{payment_id}/refunds",
        headers=headers if headers is not None else {**AUTH, "Idempotency-Key": key},
    )


def status_of(client, payment_id):
    return client.get(f"/v1/payments/{payment_id}", headers=AUTH).json()["status"]


# ---- Refunding ---------------------------------------------------------------------

def test_paid_payment_can_be_refunded(client, sent):
    payment_id = paid_payment(client)

    response = refund(client, payment_id)

    assert response.status_code == 200
    assert response.json()["status"] == "refunded"
    assert status_of(client, payment_id) == "refunded"


def test_refunding_sends_a_refunded_webhook(client, sent):
    payment_id = paid_payment(client)
    sent.clear()  # forget the "paid" webhook from approving

    refund(client, payment_id)

    assert [event["status"] for event in sent] == ["refunded"]
    assert sent[0]["paymentId"] == payment_id


# ---- Refunding twice --------------------------------------------------------------

def test_refunding_twice_succeeds_both_times(client, sent):
    payment_id = paid_payment(client)

    first = refund(client, payment_id)
    second = refund(client, payment_id)

    # A retried refund must look successful, so the caller can safely try again
    assert first.status_code == 200
    assert second.status_code == 200
    assert second.json()["status"] == "refunded"


def test_refunding_twice_sends_one_webhook(client, sent):
    payment_id = paid_payment(client)
    sent.clear()

    refund(client, payment_id)
    refund(client, payment_id)

    assert [event["status"] for event in sent] == ["refunded"]


# ---- Payments that can't be refunded -----------------------------------------------

def test_open_payment_cannot_be_refunded(client, sent):
    payment_id = create_payment(client)

    response = refund(client, payment_id)

    assert response.status_code == 409
    assert status_of(client, payment_id) == "open"


def test_failed_payment_cannot_be_refunded(client, sent):
    payment_id = create_payment(client)
    client.post(f"/checkout/{payment_id}/decline")

    response = refund(client, payment_id)

    assert response.status_code == 409
    assert status_of(client, payment_id) == "failed"


def test_canceled_payment_cannot_be_refunded(client, sent):
    payment_id = create_payment(client)
    client.post(f"/v1/payments/{payment_id}/cancel", headers=AUTH)

    response = refund(client, payment_id)

    assert response.status_code == 409


def test_unknown_payment_returns_404(client, sent):
    assert refund(client, "tr_unknown").status_code == 404


def test_refunded_payment_cannot_be_approved_again(client, sent):
    payment_id = paid_payment(client)
    refund(client, payment_id)

    response = client.post(f"/checkout/{payment_id}/approve")

    assert response.status_code == 409
    assert status_of(client, payment_id) == "refunded"


# ---- Request checks ------------------------------------------------------------------

def test_refund_needs_an_idempotency_key(client, sent):
    payment_id = paid_payment(client)

    response = refund(client, payment_id, headers=AUTH)

    assert response.status_code == 422
    assert status_of(client, payment_id) == "paid"


def test_refund_needs_the_api_key(client, sent):
    payment_id = paid_payment(client)

    response = refund(client, payment_id, headers={"Idempotency-Key": "refund-1"})

    assert response.status_code == 401
    assert status_of(client, payment_id) == "paid"