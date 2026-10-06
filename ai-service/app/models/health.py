"""Health response model."""

from pydantic import BaseModel


class HealthResponse(BaseModel):
    """Schema returned by the health endpoint."""

    status: str
    service: str