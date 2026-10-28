package com.livingdocs.modules.ai.settings;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Workspace AI configuration. The API key is never returned to the
 * frontend — only a boolean indicating whether one is configured.
 *
 * <p>Stored in {@code workspace_settings.extra} as JSON so that the
 * settings table does not need a column for every new field.
 *
 * <p>{@code apiKey} holds the <em>decrypted</em> key and is used only
 * when talking to the AI service (which forwards it to the LLM
 * vendor). It is marked {@link JsonIgnore} so it can never leak into
 * an HTTP response — the REST layer uses a separate DTO.
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
        String apiKeyMasked,
        @JsonIgnore String apiKey) {

    public static AiSettings defaults() {
        return new AiSettings(
                AiProvider.SANDBOX,
                "gpt-4o-mini",
                null,
                null,
                0.2,
                2048,
                false,
                null,
                null
        );
    }

    /** Copy of this settings object with the plaintext key attached. */
    public AiSettings withApiKey(String plaintextKey) {
        return new AiSettings(provider, model, embeddingModel, baseUrl, temperature, maxTokens,
                hasApiKey, apiKeyMasked, plaintextKey);
    }
}