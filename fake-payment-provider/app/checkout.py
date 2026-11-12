from html import escape

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, status
from fastapi.responses import HTMLResponse

from app.dependencies import get_store
from app.models import Payment, PaymentStatus
from app.store import PaymentStore
from app.webhooks import build_event, deliver

router = APIRouter(prefix="/checkout")


def page(title: str, body: str, status_code: int = 200) -> HTMLResponse:
    html = f"""<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{escape(title)}</title>
  <style>
    body {{ font-family: system-ui, sans-serif; max-width: 420px; margin: 48px auto; padding: 0 16px; }}
    .amount {{ font-size: 2rem; font-weight: 600; margin: 8px 0 24px; }}
    form {{ display: inline; }}
    button {{ font-size: 1rem; padding: 10px 20px; margin-right: 8px; cursor: pointer; }}
    .approve {{ background: #1a7f37; color: white; border: none; }}
    .decline {{ background: #cf222e; color: white; border: none; }}
    .note {{ color: #666; font-size: 0.9rem; margin-top: 32px; }}
  </style>
</head>
<body>
  {body}
  <p class="note">Fake Payment Provider. No real money is involved.</p>
</body>
</html>"""
    return HTMLResponse(html, status_code=status_code)


def find_payment(store: PaymentStore, payment_id: str) -> Payment:
    payment = store.get(payment_id)
    if payment is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Payment not found")
    return payment


@router.get("/{payment_id}", response_class=HTMLResponse)
def show_checkout(payment_id: str, store: PaymentStore = Depends(get_store)):
    payment = find_payment(store, payment_id)
    amount = f"€{payment.amount_cents / 100:.2f}"

    if payment.status != PaymentStatus.OPEN:
        return page("Payment", f"<h1>This payment is {escape(payment.status.value)}</h1>")

    return page("Pay", f"""
      <h1>{escape(payment.description)}</h1>
      <div class="amount">{amount}</div>
      <form method="post" action="/checkout/{escape(payment.id)}/approve">
        <button class="approve">Approve</button>
      </form>
      <form method="post" action="/checkout/{escape(payment.id)}/decline">
        <button class="decline">Decline</button>
      </form>""")


@router.post("/{payment_id}/approve", response_class=HTMLResponse)
def approve(payment_id: str, background: BackgroundTasks, store: PaymentStore = Depends(get_store)):
    return finish(store, background, payment_id, PaymentStatus.PAID, "Payment approved")


@router.post("/{payment_id}/decline", response_class=HTMLResponse)
def decline(payment_id: str, background: BackgroundTasks, store: PaymentStore = Depends(get_store)):
    return finish(store, background, payment_id, PaymentStatus.FAILED, "Payment declined")


def finish(store: PaymentStore, background: BackgroundTasks, payment_id: str,
           new_status: PaymentStatus, message: str) -> HTMLResponse:
    find_payment(store, payment_id)
    updated = store.transition(payment_id, {PaymentStatus.OPEN}, new_status)
    if updated is None:
        return page("Payment", "<h1>This payment is no longer open</h1>", status.HTTP_409_CONFLICT)

    if updated.webhook_url:
        background.add_task(deliver, updated.webhook_url, build_event(updated))

    return page("Payment", f"<h1>{escape(message)}</h1><p>You can close this page.</p>")