import pytest

AUTH = {"Authorization": "Bearer test_fake_psp_key"}



def create(client, key="order-1", amount=5000, headers=AUTH):
    return client.post(
        "/v1/payments",
        headers={**headers, "Idempotency-Key": key},
        json={"amountCents": amount, "description": "Order 1"},
    )


# ---- API key --------------------------------------------------------------------

def test_missing_api_key_is_rejected(client):
    response = client.post("/v1/payments", json={"amountCents": 5000, "description": "x"})

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"


def test_wrong_api_key_is_rejected(client):
    response = create(client, headers={"Authorization": "Bearer wrong-key"})

    assert response.status_code == 401


def test_health_needs_no_api_key(client):
    assert client.get("/health").status_code == 200


# ---- Creating payments ---------------------------------------------------------

def test_create_returns_open_payment_in_camel_case(client):
    response = create(client)

    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "open"
    assert body["amountCents"] == 5000
    assert body["checkoutUrl"] == f"http://testserver/checkout/{body['id']}"


def test_repeated_key_returns_same_payment_with_200(client):
    first = create(client, key="order-1")
    second = create(client, key="order-1")

    assert second.status_code == 200
    assert second.json()["id"] == first.json()["id"]


def test_reused_key_with_different_request_is_rejected(client):
    create(client, key="order-1", amount=5000)

    response = create(client, key="order-1", amount=9999)

    assert response.status_code == 422


def test_missing_idempotency_key_is_rejected(client):
    response = client.post("/v1/payments", headers=AUTH, json={"amountCents": 5000, "description": "x"})

    assert response.status_code == 422


@pytest.mark.parametrize("body", [
    {"amountCents": 0, "description": "x"},
    {"amountCents": 5000, "description": ""},
    {"description": "missing amount"},
])
def test_invalid_requests_are_rejected(client, body):
    response = client.post("/v1/payments", headers={**AUTH, "Idempotency-Key": "k"}, json=body)

    assert response.status_code == 422


# ---- Getting payments ------------------------------------------------------------

def test_get_returns_the_payment(client):
    payment_id = create(client).json()["id"]

    response = client.get(f"/v1/payments/{payment_id}", headers=AUTH)

    assert response.status_code == 200
    assert response.json()["id"] == payment_id


def test_get_unknown_payment_returns_404(client):
    assert client.get("/v1/payments/tr_unknown", headers=AUTH).status_code == 404


# ---- Canceling payments ----------------------------------------------------------

def test_open_payment_can_be_canceled(client):
    payment_id = create(client).json()["id"]

    response = client.post(f"/v1/payments/{payment_id}/cancel", headers=AUTH)

    assert response.status_code == 200
    assert response.json()["status"] == "canceled"


def test_canceling_twice_is_a_conflict(client):
    payment_id = create(client).json()["id"]
    client.post(f"/v1/payments/{payment_id}/cancel", headers=AUTH)

    response = client.post(f"/v1/payments/{payment_id}/cancel", headers=AUTH)

    assert response.status_code == 409


def test_canceling_unknown_payment_returns_404(client):
    assert client.post("/v1/payments/tr_unknown/cancel", headers=AUTH).status_code == 404