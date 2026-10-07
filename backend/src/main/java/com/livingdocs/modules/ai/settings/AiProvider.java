package com.livingdocs.modules.ai.settings;

/**
 * Supported AI providers for workspace-scoped documentation generation.
 * The list is intentionally short — adding a new one is a matter of
 * implementing the matching HTTP call inside {@code AiServiceClient}.
 */
public enum AiProvider {
    SANDBOX,   // mock provider used in dev (no network call)
    OPENAI,    // https://api.openai.com
    ANTHROPIC, // https://api.anthropic.com
    OLLAMA,    // local server (default http://localhost:11434)
    CUSTOM     // user-supplied base URL + model
}
