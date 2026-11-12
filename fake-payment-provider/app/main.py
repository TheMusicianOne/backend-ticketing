import logging

from fastapi import FastAPI

from app.chaos import router as chaos_router
from app.checkout import router as checkout_router
from app.payments import router as payments_router

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="Fake Payment Provider")
app.include_router(payments_router)
app.include_router(checkout_router)
app.include_router(chaos_router)


@app.get("/health")
def health():
    return {"status": "UP"}