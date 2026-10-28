package com.livingdocs.common.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Published by the AI worker after a {@link DocGenerationRequestedEvent}
 * has been processed. Carries the resulting markdown body and the
 * confidence score so downstream consumers (notification service,
 * doc-update-request persistence, etc.) can react without re-running
 * the AI call.
 *
 * <p>{@code documentVersionId} is the freshly created row in
 * {@code document_versions} that holds the generated body; consumers
 * that want to expose the result in the UI should link to that
 * version rather than the document itself.
 */
public final class DocGenerationCompletedEvent extends AbstractEvent {

    public enum Outcome { SUCCESS, FAILED, SKIPPED }

    /**
     * One grounding reference: the code entity the generated prose is
     * grounded in. Persisted verbatim in
     * {@code document_versions.grounding_json} so the UI can show
     * "this sentence came from this function".
     */
    public record Grounding(
            @JsonProperty("qualified_name") String qualifiedName,
            String kind,
            String file,
            List<Integer> lines,
            String signature) {}

    private final UUID documentId;
    private final UUID documentVersionId;
    private final UUID requestedByUserId;
    private final Outcome outcome;
    private final float confidence;
    private final String markdown;
    private final List<Grounding> groundingSources;
    private final String errorMessage;
    private final int promptTokens;
    private final int completionTokens;

    @JsonCreator
    public DocGenerationCompletedEvent(@JsonProperty("eventId") UUID eventId,
                                       @JsonProperty("workspaceId") UUID workspaceId,
                                       @JsonProperty("correlationId") UUID correlationId,
                                       @JsonProperty("occurredAt") OffsetDateTime occurredAt,
                                       @JsonProperty("documentId") UUID documentId,
                                       @JsonProperty("documentVersionId") UUID documentVersionId,
                                       @JsonProperty("requestedByUserId") UUID requestedByUserId,
                                       @JsonProperty("outcome") Outcome outcome,
                                       @JsonProperty("confidence") float confidence,
                                       @JsonProperty("markdown") String markdown,
                                       @JsonProperty("groundingSources") List<Grounding> groundingSources,
                                       @JsonProperty("errorMessage") String errorMessage,
                                       @JsonProperty("promptTokens") int promptTokens,
                                       @JsonProperty("completionTokens") int completionTokens) {
        super(eventId, workspaceId, correlationId, occurredAt);
        this.documentId = documentId;
        this.documentVersionId = documentVersionId;
        this.requestedByUserId = requestedByUserId;
        this.outcome = outcome;
        this.confidence = confidence;
        this.markdown = markdown;
        this.groundingSources = groundingSources == null ? List.of() : groundingSources;
        this.errorMessage = errorMessage;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    public UUID getDocumentId() { return documentId; }
    public UUID getDocumentVersionId() { return documentVersionId; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public Outcome getOutcome() { return outcome; }
    public float getConfidence() { return confidence; }
    public String getMarkdown() { return markdown; }
    public List<Grounding> getGroundingSources() { return groundingSources; }
    public String getErrorMessage() { return errorMessage; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
}