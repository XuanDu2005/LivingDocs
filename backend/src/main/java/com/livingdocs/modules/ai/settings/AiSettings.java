package com.livingdocs.modules.ai.settings;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Workspace AI configuration. The API key is never returned to the
 * frontend — only a boolean indicating whether one is configured.
 *
 * <p>Stored in {@code workspace_settings.extra} as JSON so that the
 * settings table does not need a column for every new field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiSettings(
        AiProvider provider,
        String model,
        String embeddingModel,
        String baseUrl,
        Double temperature,
        Integer maxTokens,
        boolean hasApiKey,
        // Echoed for convenience when the user wants to re-display what
        // they previously entered. The plaintext is only kept client-side
        // (form state) and the server always re-encrypts whatever it gets.
        String apiKeyMasked
) {
    public static AiSettings defaults() {
        return new AiSettings(
                AiProvider.SANDBOX,
                "gpt-4o-mini",
                null,
                null,
                0.2,
                2048,
                false,
                null
        );
    }
}
