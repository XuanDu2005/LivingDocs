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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerateRequest(
            List<SourceFile> files,
            String template,
            String focus,
            @JsonProperty("language_hint") String languageHint) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerateResponse(
            String markdown,
            double confidence,
            String model,
            List<GroundingSource> sources,
            List<String> notes) {}

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
            @JsonProperty("markdown_summary") String markdownSummary) {}

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