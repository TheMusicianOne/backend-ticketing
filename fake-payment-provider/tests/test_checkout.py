import pytest

AUTH = {"Authorization": "Bearer test_fake_psp_key"}


@pytest.fixture(autouse=True)
def no_webhooks(monkeypatch):
    """These tests are about the page, so don't send any webhooks."""
    monkeypatch.setattr("app.checkout.deliver", lambda url, body: None)


def create_payment(client, description="Order 1", amount=9100, key="key-1"):
    response = client.post(
        "/v1/payments",
        headers={**AUTH, "Idempotency-Key": key},
        json={"amountCents": amount, "description": description},
    )
    return response.json()["id"]


def status_of(client, payment_id):
    return client.get(f"/v1/payments/{payment_id}", headers=AUTH).json()["status"]


def test_checkout_page_shows_description_and_amount(client):
    payment_id = create_payment(client)

    response = client.get(f"/checkout/{payment_id}")

    assert response.status_code == 200
    assert "Order 1" in response.text
    assert "€91.00" in response.text
    assert f"/checkout/{payment_id}/approve" in response.text


def test_checkout_page_needs_no_api_key(client):
    payment_id = create_payment(client)

    assert client.get(f"/checkout/{payment_id}").status_code == 200


def test_unknown_payment_returns_404(client):
    assert client.get("/checkout/tr_unknown").status_code == 404
    assert client.post("/checkout/tr_unknown/approve").status_code == 404


def test_description_is_escaped(client):
    payment_id = create_payment(client, description="<script>alert(1)</script>")

    page = client.get(f"/checkout/{payment_id}").text

    assert "<script>alert(1)</script>" not in page
    assert "&lt;script&gt;alert(1)&lt;/script&gt;" in page


def test_approving_marks_the_payment_paid(client):
    payment_id = create_payment(client)

    response = client.post(f"/checkout/{payment_id}/approve")

    assert response.status_code == 200
    assert status_of(client, payment_id) == "paid"


def test_declining_marks_the_payment_failed(client):
    payment_id = create_payment(client)

    client.post(f"/checkout/{payment_id}/decline")

    assert status_of(client, payment_id) == "failed"


def test_paid_payment_cannot_be_declined(client):
    payment_id = create_payment(client)
    client.post(f"/checkout/{payment_id}/approve")

    response = client.post(f"/checkout/{payment_id}/decline")

    assert response.status_code == 409
    assert status_of(client, payment_id) == "paid"


def test_canceled_payment_cannot_be_approved(client):
    payment_id = create_payment(client)
    client.post(f"/v1/payments/{payment_id}/cancel", headers=AUTH)

    response = client.post(f"/checkout/{payment_id}/approve")

    assert response.status_code == 409
    assert status_of(client, payment_id) == "canceled"


def test_finished_payment_page_shows_its_status(client):
    payment_id = create_payment(client)
    client.post(f"/checkout/{payment_id}/approve")

    page = client.get(f"/checkout/{payment_id}").text

    assert "This payment is paid" in page
    assert "/approve" not in page