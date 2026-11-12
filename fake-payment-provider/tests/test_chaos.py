import pytest

from app import payments, webhooks
from app.chaos import Chaos, ChaosSettings, chaos

AUTH = {"Authorization": "Bearer test_fake_psp_key"}
WEBHOOK_URL = "http://merchant.test/webhooks/payments"


def set_chaos(**settings):
    chaos.update(ChaosSettings(**settings))


def create(client, key="key-1"):
    return client.post(
        "/v1/payments",
        headers={**AUTH, "Idempotency-Key": key},
        json={"amountCents": 5000, "description": "Order 1"},
    )


# ---- The admin endpoints ---------------------------------------------------------

def test_everything_is_off_by_default(client):
    response = client.get("/admin/chaos", headers=AUTH)

    assert response.status_code == 200
    assert all(value == 0 for value in response.json().values())


def test_settings_can_be_changed(client):
    response = client.put("/admin/chaos", headers=AUTH, json={"webhookDropRate": 0.5})

    assert response.status_code == 200
    assert client.get("/admin/chaos", headers=AUTH).json()["webhookDropRate"] == 0.5


def test_settings_left_out_go_back_to_their_defaults(client):
    client.put("/admin/chaos", headers=AUTH, json={"webhookDropRate": 0.5})

    client.put("/admin/chaos", headers=AUTH, json={"createErrorRate": 0.2})

    settings = client.get("/admin/chaos", headers=AUTH).json()
    assert settings["createErrorRate"] == 0.2
    assert settings["webhookDropRate"] == 0.0


@pytest.mark.parametrize("body", [
    {"createErrorRate": 1.5},
    {"webhookDropRate": -0.1},
    {"createLatencyMs": -1},
    {"webhookDelayMs": 120_000},
])
def test_out_of_range_settings_are_rejected(client, body):
    assert client.put("/admin/chaos", headers=AUTH, json=body).status_code == 422


def test_admin_endpoints_need_the_api_key(client):
    assert client.get("/admin/chaos").status_code == 401
    assert client.put("/admin/chaos", json={}).status_code == 401


# ---- Creating payments -------------------------------------------------------------

def test_provider_error_creates_nothing(client):
    set_chaos(create_error_rate=1.0)

    assert create(client, key="key-1").status_code == 500

    # The failure happened before creating, so a retry creates a brand-new payment
    set_chaos()
    assert create(client, key="key-1").status_code == 201


def test_lost_response_still_creates_the_payment(client):
    set_chaos(create_lost_response_rate=1.0)

    assert create(client, key="key-1").status_code == 503

    # The payment exists, so a retry with the same key gets it back with 200
    set_chaos()
    assert create(client, key="key-1").status_code == 200


def test_latency_slows_down_creation(client, monkeypatch):
    sleeps = []
    monkeypatch.setattr(payments.time, "sleep", sleeps.append)
    set_chaos(create_latency_ms=250)

    create(client)

    assert sleeps == [0.25]


def test_no_chaos_means_normal_behavior(client, monkeypatch):
    sleeps = []
    monkeypatch.setattr(payments.time, "sleep", sleeps.append)

    assert create(client).status_code == 201
    assert sleeps == []


# ---- Webhooks ----------------------------------------------------------------------

@pytest.fixture
def sends(monkeypatch):
    """Records each send instead of making HTTP calls, and skips real sleeping."""
    calls = []
    monkeypatch.setattr(webhooks, "_send_with_retries", lambda url, body: calls.append((url, body)))
    return calls


@pytest.fixture
def sleeps(monkeypatch):
    calls = []
    monkeypatch.setattr(webhooks.time, "sleep", calls.append)
    return calls


def test_webhook_is_sent_once_normally(sends, sleeps):
    webhooks.deliver(WEBHOOK_URL, b'{"eventId":"evt_1"}')

    assert sends == [(WEBHOOK_URL, b'{"eventId":"evt_1"}')]
    assert sleeps == []


def test_dropped_webhook_is_never_sent(sends, sleeps):
    set_chaos(webhook_drop_rate=1.0)

    webhooks.deliver(WEBHOOK_URL, b'{"eventId":"evt_1"}')

    assert sends == []


def test_duplicated_webhook_is_sent_twice_with_the_same_body(sends, sleeps):
    set_chaos(webhook_duplicate_rate=1.0)

    webhooks.deliver(WEBHOOK_URL, b'{"eventId":"evt_1"}')

    assert sends == [(WEBHOOK_URL, b'{"eventId":"evt_1"}')] * 2


def test_delayed_webhook_waits_first(sends, sleeps):
    set_chaos(webhook_delay_ms=500)

    webhooks.deliver(WEBHOOK_URL, b'{"eventId":"evt_1"}')

    assert sleeps == [0.5]
    assert len(sends) == 1


# ---- The dice --------------------------------------------------------------------

def test_rate_zero_never_happens():
    assert not any(Chaos.happens(0.0) for _ in range(1000))


def test_rate_one_always_happens():
    assert all(Chaos.happens(1.0) for _ in range(1000))