"""Pydantic models for the AI service.

This package will house request/response models for future AI
capabilities (code analysis, document generation, etc.). Only the
health response model exists today.
"""

from app.models.health import HealthResponse

__all__ = ["HealthResponse"]