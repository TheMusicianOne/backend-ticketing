from app.config import settings
from app.store import PaymentStore

_store = PaymentStore(checkout_base_url=settings.public_base_url)


def get_store() -> PaymentStore:
    return _store