"""Runtime configuration for the LivingDocs AI service."""

from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime settings for the LivingDocs AI service.

    Values are read from environment variables (or a .env file at the
    project root) so that the container can be configured without code
    changes.
    """

    service_name: str = "livingdocs-ai-service"
    api_v1_prefix: str = "/api/v1"
    host: str = "0.0.0.0"
    port: int = 8000
    debug: bool = False

    # -- LLM configuration --------------------------------------------------
    llm_provider: str = Field(default="mock", description="openai | anthropic | ollama | mock")
    llm_api_key: str = Field(default="", description="API key for the chosen provider")
    llm_api_base: str = Field(default="", description="Override the provider base URL")
    llm_model: str = Field(default="gpt-4o-mini", description="Default chat / completion model")
    llm_max_tokens: int = Field(default=2048)
    llm_temperature: float = Field(default=0.2)
    llm_timeout_seconds: float = Field(default=60.0)

    # -- Embedding model ----------------------------------------------------
    embedding_provider: str = Field(default="mock", description="openai | ollama | mock")
    embedding_model: str = Field(default="text-embedding-3-small")
    embedding_dimensions: int = Field(default=1536)

    # -- Drift detector thresholds -----------------------------------------
    confidence_threshold: float = Field(default=0.65)
    referential_severity_default: str = Field(default="HIGH")

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )


settings = Settings()