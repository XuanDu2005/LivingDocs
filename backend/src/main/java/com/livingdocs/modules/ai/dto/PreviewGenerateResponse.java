package com.livingdocs.modules.ai.dto;

import java.util.List;

/**
 * Response shape for {@code POST /workspaces/{id}/documents/{id}/preview-generate}.
 *
 * <p>Wraps the AI service's {@link com.livingdocs.modules.ai.client.AiDtos.GenerateResponse}
 * with a stable envelope that the UI can render without an extra round-trip.
 */
public record PreviewGenerateResponse(
        String markdown,
        Float confidenceScore,
        List<com.livingdocs.modules.ai.client.AiDtos.GroundingSource> sources,
        String template,
        String generatedAt) {
    public static PreviewGenerateResponse of(com.livingdocs.modules.ai.client.AiDtos.GenerateResponse r,
                                              String template) {
        if (r == null) {
            return new PreviewGenerateResponse(
                    "", 0.0f, List.of(), template, java.time.OffsetDateTime.now().toString());
        }
        float conf = (float) Math.max(0.0, Math.min(1.0, r.confidence()));
        return new PreviewGenerateResponse(
                r.markdown() == null ? "" : r.markdown(),
                conf,
                r.sources() == null ? List.of() : r.sources(),
                template,
                java.time.OffsetDateTime.now().toString());
    }
}
