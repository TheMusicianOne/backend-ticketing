import pytest
from fastapi.testclient import TestClient

from app.chaos import ChaosSettings, chaos
from app.dependencies import get_store
from app.main import app
from app.store import PaymentStore


@pytest.fixture
def store():
    return PaymentStore("http://testserver")


@pytest.fixture
def client(store):
    """A test client whose endpoints all use the test's own empty store."""
    app.dependency_overrides[get_store] = lambda: store
    yield TestClient(app)
    app.dependency_overrides.clear()


@pytest.fixture(autouse=True)
def reset_chaos():
    """Chaos settings are shared, so every test starts and ends with them off."""
    chaos.update(ChaosSettings())
    yield
    chaos.update(ChaosSettings())