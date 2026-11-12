import random
import threading

from fastapi import APIRouter, Depends
from pydantic import Field

from app.models import CamelModel
from app.security import require_api_key


class ChaosSettings(CamelModel):
    create_latency_ms: int = Field(default=0, ge=0, le=60_000)
    create_error_rate: float = Field(default=0.0, ge=0.0, le=1.0)
    create_lost_response_rate: float = Field(default=0.0, ge=0.0, le=1.0)
    webhook_delay_ms: int = Field(default=0, ge=0, le=60_000)
    webhook_drop_rate: float = Field(default=0.0, ge=0.0, le=1.0)
    webhook_duplicate_rate: float = Field(default=0.0, ge=0.0, le=1.0)


class Chaos:
    """Holds the current settings. Shared by every request, so changes are locked."""

    def __init__(self):
        self._settings = ChaosSettings()
        self._lock = threading.Lock()

    def current(self) -> ChaosSettings:
        with self._lock:
            return self._settings

    def update(self, settings: ChaosSettings) -> None:
        with self._lock:
            self._settings = settings

    @staticmethod
    def happens(rate: float) -> bool:
        return random.random() < rate


chaos = Chaos()

router = APIRouter(prefix="/admin/chaos", dependencies=[Depends(require_api_key)])


@router.get("", response_model=ChaosSettings)
def get_chaos():
    return chaos.current()


@router.put("", response_model=ChaosSettings)
def set_chaos(settings: ChaosSettings):
    chaos.update(settings)
    return settings