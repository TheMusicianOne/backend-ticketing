from fastapi import APIRouter, Depends, Header, HTTPException, Response, status

from app.dependencies import get_store
from app.models import CreatePaymentRequest, Payment, PaymentStatus
from app.security import require_api_key
from app.store import IdempotencyConflict, PaymentStore
from fastapi import BackgroundTasks
import time
from app.chaos import chaos
from app.webhooks import build_event, deliver
router = APIRouter(prefix="/v1/payments", dependencies=[Depends(require_api_key)])


@router.post("", response_model=Payment, status_code=status.HTTP_201_CREATED)
def create_payment(
    request: CreatePaymentRequest,
    response: Response,
    idempotency_key: str = Header(alias="Idempotency-Key", min_length=1, max_length=100),
    store: PaymentStore = Depends(get_store),
):
    settings = chaos.current()

    if settings.create_latency_ms:
        time.sleep(settings.create_latency_ms / 1000)

    if chaos.happens(settings.create_error_rate):
        raise HTTPException(status_code=500, detail="Simulated provider error")

    try:
        payment, created = store.create(request, idempotency_key)
    except IdempotencyConflict:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Idempotency key was already used for a different request",
        )

    if chaos.happens(settings.create_lost_response_rate):
        # The payment exists, but the caller is told something went wrong
        raise HTTPException(status_code=503, detail="Simulated lost response")

    if not created:
        response.status_code = status.HTTP_200_OK
    return payment


@router.get("/{payment_id}", response_model=Payment)
def get_payment(payment_id: str, store: PaymentStore = Depends(get_store)):
    payment = store.get(payment_id)
    if payment is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Payment not found")
    return payment


@router.post("/{payment_id}/cancel", response_model=Payment)
def cancel_payment(payment_id: str, background: BackgroundTasks, store: PaymentStore = Depends(get_store)):
    canceled = store.transition(payment_id, {PaymentStatus.OPEN}, PaymentStatus.CANCELED)
    if canceled is not None:
        if canceled.webhook_url:
            background.add_task(deliver, canceled.webhook_url, build_event(canceled))
        return canceled
    

    if store.get(payment_id) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Payment not found")
    raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Only open payments can be canceled")

