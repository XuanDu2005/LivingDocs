"""LivingDocs AI service entry point."""

from __future__ import annotations

from fastapi import FastAPI

from app.api import health_router, v1_router
from app.core.config import settings

app = FastAPI(
    title="LivingDocs AI Service",
    description="AI-assisted documentation maintenance — AI microservice.",
    version="0.3.0",
    openapi_url="/openapi.json",
    docs_url="/docs",
    redoc_url="/redoc",
)

app.include_router(health_router, prefix=settings.api_v1_prefix)
app.include_router(v1_router, prefix=settings.api_v1_prefix)


@app.get("/", include_in_schema=False)
def root() -> dict[str, str]:
    """Redirect hint for human visitors."""
    return {
        "service": settings.service_name,
        "docs": "/docs",
        "openapi": "/openapi.json",
        "health": f"{settings.api_v1_prefix}/health",
        "generate": f"{settings.api_v1_prefix}/generate",
        "drift": f"{settings.api_v1_prefix}/drift",
        "knowledge": f"{settings.api_v1_prefix}/knowledge",
        "ast": f"{settings.api_v1_prefix}/ast",
    }