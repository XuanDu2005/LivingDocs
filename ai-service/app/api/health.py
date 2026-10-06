"""Health endpoint router."""

from fastapi import APIRouter

from app.core.config import settings
from app.models.health import HealthResponse

router = APIRouter(tags=["Health"])


@router.get(
    "/health",
    response_model=HealthResponse,
    summary="Service liveness probe",
    description="Returns 200 OK with a static status payload.",
)
def get_health() -> HealthResponse:
    """Return the static UP status for the LivingDocs AI service."""
    return HealthResponse(status="UP", service=settings.service_name)