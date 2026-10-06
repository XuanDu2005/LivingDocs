"""HTTP endpoints exposed by the LivingDocs AI service."""

from fastapi import APIRouter

from .generate import router as generate_router
from .drift import router as drift_router
from .knowledge import router as knowledge_router
from .ast_endpoints import router as ast_router

api_router = APIRouter()
api_router.include_router(generate_router, prefix="/generate", tags=["generate"])
api_router.include_router(drift_router, prefix="/drift", tags=["drift"])
api_router.include_router(knowledge_router, prefix="/knowledge", tags=["knowledge"])
api_router.include_router(ast_router, prefix="/ast", tags=["ast"])