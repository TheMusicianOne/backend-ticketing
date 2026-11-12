from concurrent.futures import ThreadPoolExecutor

import pytest
from pydantic import ValidationError

from app.models import CreatePaymentRequest, PaymentStatus
from app.store import IdempotencyConflict, PaymentStore


@pytest.fixture
def store():
    return PaymentStore(checkout_base_url="http://localhost:8081")


def make_request(amount_cents=5000, description="Order 1"):
    return CreatePaymentRequest(amount_cents=amount_cents, description=description)


# ---- Creating payments ----------------------------------------------------

def test_new_payment_is_open_with_checkout_url(store):
    payment, created = store.create(make_request(), "key-1")

    assert created is True
    assert payment.status == PaymentStatus.OPEN
    assert payment.id.startswith("tr_")
    assert payment.checkout_url == f"http://localhost:8081/checkout/{payment.id}"
    assert payment.amount_cents == 5000


def test_different_keys_create_different_payments(store):
    first, _ = store.create(make_request(), "key-1")
    second, _ = store.create(make_request(), "key-2")

    assert first.id != second.id


# ---- Idempotency ------------------------------------------------------------

def test_repeated_key_returns_the_same_payment(store):
    first, _ = store.create(make_request(), "key-1")
    second, created = store.create(make_request(), "key-1")

    assert created is False
    assert second.id == first.id


def test_reusing_a_key_for_a_different_request_is_rejected(store):
    store.create(make_request(amount_cents=5000), "key-1")

    with pytest.raises(IdempotencyConflict):
        store.create(make_request(amount_cents=9999), "key-1")


def test_simultaneous_requests_with_one_key_create_one_payment(store):
    def create(_):
        payment, _ = store.create(make_request(), "same-key")
        return payment.id

    with ThreadPoolExecutor(max_workers=50) as pool:
        ids = set(pool.map(create, range(200)))

    assert len(ids) == 1


# ---- Status changes -------------------------------------------------------

def test_open_payment_can_be_paid(store):
    payment, _ = store.create(make_request(), "key-1")

    updated = store.transition(payment.id, {PaymentStatus.OPEN}, PaymentStatus.PAID)

    assert updated.status == PaymentStatus.PAID
    assert store.get(payment.id).status == PaymentStatus.PAID


def test_paid_payment_cannot_change_again(store):
    payment, _ = store.create(make_request(), "key-1")
    store.transition(payment.id, {PaymentStatus.OPEN}, PaymentStatus.PAID)

    result = store.transition(payment.id, {PaymentStatus.OPEN}, PaymentStatus.FAILED)

    assert result is None
    assert store.get(payment.id).status == PaymentStatus.PAID


def test_unknown_payment_cannot_change(store):
    assert store.transition("tr_unknown", {PaymentStatus.OPEN}, PaymentStatus.PAID) is None


# ---- Request validation ---------------------------------------------------

def test_request_is_read_from_camel_case_json():
    request = CreatePaymentRequest.model_validate(
        {"amountCents": 1500, "description": "Order 7", "webhookUrl": "http://localhost:8080/webhooks/payments"}
    )

    assert request.amount_cents == 1500
    assert str(request.webhook_url) == "http://localhost:8080/webhooks/payments"


@pytest.mark.parametrize("amount", [0, -100])
def test_amount_must_be_positive(amount):
    with pytest.raises(ValidationError):
        make_request(amount_cents=amount)


def test_description_cannot_be_empty():
    with pytest.raises(ValidationError):
        make_request(description="")