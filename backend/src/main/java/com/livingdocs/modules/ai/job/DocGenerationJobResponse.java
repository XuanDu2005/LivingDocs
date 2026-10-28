package com.livingdocs.modules.ai.job;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API projection of a generation job. Used by the "generation status"
 * endpoint so the UI can render a spinner while the AI worker runs and
 * a result banner once it finishes.
 */
public record DocGenerationJobResponse(
        String id,
        String documentId,
        String status,
        String template,
        String documentVersionId,
        String errorMessage,
        int promptTokens,
        int completionTokens,
        String createdAt,
        String completedAt) {

    public static DocGenerationJobResponse from(DocGenerationJob job) {
        return new DocGenerationJobResponse(
                job.getId().toString(),
                job.getDocumentId().toString(),
                job.getStatus().name(),
                job.getTemplate(),
                job.getDocumentVersionId() == null ? null : job.getDocumentVersionId().toString(),
                job.getErrorMessage(),
                job.getPromptTokens(),
                job.getCompletionTokens(),
                job.getCreatedAt() == null ? null : job.getCreatedAt().toString(),
                job.getCompletedAt() == null ? null : job.getCompletedAt().toString());
    }

    /**
     * Flatten to a JSON object. {@code null} values are dropped so the
     * response never carries a bunch of {@code null} noise — the UI
     * treats a missing key and a null value the same way.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("documentId", documentId);
        map.put("status", status);
        map.put("template", template);
        map.put("documentVersionId", documentVersionId);
        map.put("errorMessage", errorMessage);
        map.put("promptTokens", promptTokens);
        map.put("completionTokens", completionTokens);
        map.put("createdAt", createdAt);
        map.put("completedAt", completedAt);
        map.values().removeIf(java.util.Objects::isNull);
        return map;
    }
}