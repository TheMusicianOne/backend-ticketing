from datetime import datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, HttpUrl
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """Uses camelCase in JSON, like your Java app, while Python code uses snake_case."""
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class PaymentStatus(str, Enum):
    OPEN = "open"
    PAID = "paid"
    FAILED = "failed"
    CANCELED = "canceled"
    EXPIRED = "expired"
    REFUNDED = "refunded"


class CreatePaymentRequest(CamelModel):
    amount_cents: int = Field(gt=0)
    currency: str = Field(default="EUR", min_length=3, max_length=3)
    description: str = Field(min_length=1, max_length=255)
    webhook_url: HttpUrl | None = None


class Payment(CamelModel):
    id: str
    status: PaymentStatus
    amount_cents: int
    currency: str
    description: str
    checkout_url: str
    webhook_url: str | None
    created_at: datetime