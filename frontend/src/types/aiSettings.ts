// Types for the workspace-scoped AI configuration.

export type AiProvider = 'SANDBOX' | 'OPENAI' | 'ANTHROPIC' | 'OLLAMA' | 'CUSTOM';

export interface AiSettings {
  provider: AiProvider;
  model: string;
  embeddingModel?: string | null;
  baseUrl?: string | null;
  temperature?: number | null;
  maxTokens?: number | null;
  hasApiKey: boolean;
  apiKeyMasked?: string | null;
}

export interface UpdateAiSettingsPayload {
  provider?: AiProvider;
  model?: string;
  embeddingModel?: string | null;
  baseUrl?: string | null;
  temperature?: number;
  maxTokens?: number;
  /** Plaintext key — only sent when the user enters a new value. */
  apiKey?: string;
  /** Set true to remove the currently-stored key. */
  clearApiKey?: boolean;
}

export interface ConfidenceThreshold {
  value: number;
}

// Sensible defaults per provider; the backend still validates.
export const PROVIDER_DEFAULTS: Record<AiProvider, { label: string; model: string; baseUrl?: string }> = {
  SANDBOX:   { label: 'Sandbox (mock)',     model: 'gpt-4o-mini' },
  OPENAI:    { label: 'OpenAI',             model: 'gpt-4o-mini', baseUrl: 'https://api.openai.com' },
  ANTHROPIC: { label: 'Anthropic',          model: 'claude-3-5-sonnet-20241022', baseUrl: 'https://api.anthropic.com' },
  OLLAMA:    { label: 'Ollama (local)',     model: 'llama3.1',    baseUrl: 'http://localhost:11434' },
  CUSTOM:    { label: 'Custom endpoint',    model: 'custom-model' },
};
