package com.livingdocs.modules.ai.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload accepted by {@code PUT /workspaces/{id}/ai-settings}.
 *
 * <p>Fields are all optional because the frontend sends only what the
 * user changed. To clear the API key, the client sends
 * {@code clearApiKey=true}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateAiSettingsRequest(
        AiProvider provider,

        @Size(max = 120)
        String model,

        @Size(max = 120)
        String embeddingModel,

        @Size(max = 500)
        String baseUrl,

        @Min(0) @Max(2)
        Double temperature,

        @Min(1) @Max(32_000)
        Integer maxTokens,

        String apiKey,

        Boolean clearApiKey
) {
    public boolean wantsClear() {
        return Boolean.TRUE.equals(clearApiKey);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasEmbeddingModel() {
        return embeddingModel != null && !embeddingModel.isBlank();
    }
}
