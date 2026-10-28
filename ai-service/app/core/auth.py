"""Internal-authentication guard for backend → AI-service calls.

The AI service is an internal component: it holds no user credentials of
its own but it does hold workspace API keys (forwarded by the backend) and
serves the platform's LLM budget. When ``AI_SERVICE_INTERNAL_TOKEN`` is
configured, every call must present the matching token in the
``X-Ai-Internal-Token`` header.

Leaving the token unset keeps the service open — that is the behaviour of
a local dev setup where the service is not reachable from outside the
compose network. Production deployments should always set it.
"""

from __future__ import annotations

from fastapi import Header, HTTPException, status

from .llm_request_config import (
    HEADER_INTERNAL_TOKEN,
    HEADER_PROVIDER,
    internal_token,
)


def require_internal_token(
    x_ai_internal_token: str | None = Header(default=None, alias=HEADER_INTERNAL_TOKEN),
) -> None:
    """Reject requests that do not carry the configured shared secret."""

    expected = internal_token()
    if not expected:
        # No token configured → the service is running in "open" mode.
        return
    supplied = (x_ai_internal_token or "").strip()
    if not supplied:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing internal authentication token",
        )
    if supplied != expected:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid internal authentication token",
        )
