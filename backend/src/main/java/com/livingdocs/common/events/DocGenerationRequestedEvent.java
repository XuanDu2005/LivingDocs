package com.livingdocs.common.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Published when a caller (HTTP endpoint, scheduled job, GitHub
 * webhook) wants AI to generate or regenerate documentation for a
 * document. A consumer (typically a {@code @KafkaListener}-driven
 * worker) picks this up, calls the AI service, and emits a
 * {@link DocGenerationCompletedEvent} when done.
 *
 * <p>Decoupling the request from the AI call lets the HTTP path
 * stay fast (it returns 202 Accepted immediately) and lets us scale
 * AI workers independently from the API servers.
 *
 * <p>{@code templateSchema} carries the canonical JSON template body
 * (sections + placeholders) so the AI service can build its prompt
 * from the user's own template instead of a built-in preset.
 * {@code sourceCommitSha} / {@code sourcePrId} attribute the resulting
 * version to the code change that triggered it.
 */
public final class DocGenerationRequestedEvent extends AbstractEvent {

    private final UUID documentId;
    private final UUID requestedByUserId;
    private final String template;
    private final String templateSchema;
    private final String focus;
    private final String languageHint;
    private final String sourceCommitSha;
    private final UUID sourcePrId;
    private final List<SourceFilePayload> sourceFiles;

    @JsonCreator
    public DocGenerationRequestedEvent(@JsonProperty("eventId") UUID eventId,
                                       @JsonProperty("workspaceId") UUID workspaceId,
                                       @JsonProperty("correlationId") UUID correlationId,
                                       @JsonProperty("occurredAt") OffsetDateTime occurredAt,
                                       @JsonProperty("documentId") UUID documentId,
                                       @JsonProperty("requestedByUserId") UUID requestedByUserId,
                                       @JsonProperty("template") String template,
                                       @JsonProperty("templateSchema") String templateSchema,
                                       @JsonProperty("focus") String focus,
                                       @JsonProperty("languageHint") String languageHint,
                                       @JsonProperty("sourceCommitSha") String sourceCommitSha,
                                       @JsonProperty("sourcePrId") UUID sourcePrId,
                                       @JsonProperty("sourceFiles") List<SourceFilePayload> sourceFiles) {
        super(eventId, workspaceId, correlationId, occurredAt);
        this.documentId = documentId;
        this.requestedByUserId = requestedByUserId;
        this.template = template;
        this.templateSchema = templateSchema;
        this.focus = focus;
        this.languageHint = languageHint;
        this.sourceCommitSha = sourceCommitSha;
        this.sourcePrId = sourcePrId;
        this.sourceFiles = sourceFiles == null ? List.of() : sourceFiles;
    }

    /**
     * Backwards-compatible constructor for callers that do not need the
     * template schema / commit attribution.
     */
    public DocGenerationRequestedEvent(UUID eventId,
                                       UUID workspaceId,
                                       UUID correlationId,
                                       OffsetDateTime occurredAt,
                                       UUID documentId,
                                       UUID requestedByUserId,
                                       String template,
                                       List<SourceFilePayload> sourceFiles) {
        this(eventId, workspaceId, correlationId, occurredAt, documentId, requestedByUserId,
                template, null, null, null, null, null, sourceFiles);
    }

    public UUID getDocumentId() { return documentId; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public String getTemplate() { return template; }
    public String getTemplateSchema() { return templateSchema; }
    public String getFocus() { return focus; }
    public String getLanguageHint() { return languageHint; }
    public String getSourceCommitSha() { return sourceCommitSha; }
    public UUID getSourcePrId() { return sourcePrId; }
    public List<SourceFilePayload> getSourceFiles() { return sourceFiles; }

    /**
     * Plain-old payload version of {@code AiDtos.SourceFile}, kept
     * inside the event module so producers/consumers don't have to
     * take a dependency on the AI client DTOs.
     */
    public record SourceFilePayload(String path, String content) {}
}