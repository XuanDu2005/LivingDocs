package com.livingdocs.modules.ai.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Wire-format types for talking to the LivingDocs AI service.
 */
public final class AiDtos {

    private AiDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceFile(String path, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroundingSource(
            @JsonProperty("qualified_name") String qualifiedName,
            String kind,
            String file,
            List<Integer> lines,
            String signature) {}

    /**
     * Request body for {@code POST /api/v1/generate}.
     *
     * <p>{@code templateSchema} carries the canonical template JSON
     * (sections + placeholders). When present the AI service builds its
     * prompt from the user's own template instead of a built-in preset.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerateRequest(
            List<SourceFile> files,
            String template,
            String templateSchema,
            String focus,
            @JsonProperty("language_hint") String languageHint) {}

    /**
     * Response body for {@code POST /api/v1/generate}.
     *
     * <p>{@code promptTokens} / {@code completionTokens} are the token
     * counts reported by the LLM provider; the backend rolls them into
     * the workspace's AI usage report.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerateResponse(
            String markdown,
            double confidence,
            String model,
            List<GroundingSource> sources,
            List<String> notes,
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens) {
        /** Backwards-compatible constructor for callers that predate token accounting. */
        public GenerateResponse(String markdown, double confidence, String model,
                                 List<GroundingSource> sources, List<String> notes) {
            this(markdown, confidence, model, sources, notes, 0, 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriftFinding(
            @JsonProperty("drift_kind") String driftKind,
            String severity,
            String title,
            String description,
            double confidence,
            Map<String, Object> evidence,
            String suggestion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriftRequest(
            @JsonProperty("files_before") List<SourceFile> filesBefore,
            @JsonProperty("files_after") List<SourceFile> filesAfter,
            @JsonProperty("document_markdown") String documentMarkdown,
            @JsonProperty("document_title") String documentTitle) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriftResponse(
            List<DriftFinding> findings,
            @JsonProperty("markdown_summary") String markdownSummary,
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens) {
        public DriftResponse(List<DriftFinding> findings, String markdownSummary) {
            this(findings, markdownSummary, 0, 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexRequest(
            @JsonProperty("document_id") String documentId,
            String title,
            String body,
            @JsonProperty("doc_type") String docType,
            @JsonProperty("workspace_id") String workspaceId,
            Map<String, Object> metadata) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexResponse(
            @JsonProperty("chunks_indexed") int chunksIndexed) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchHit(
            @JsonProperty("doc_id") String docId,
            String text,
            double score,
            Map<String, Object> metadata) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchRequest(
            String query,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("top_k") Integer topK) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(List<SearchHit> hits) {}
}