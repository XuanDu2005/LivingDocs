package com.livingdocs.common.events;

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

    private final UUID documentId;
    private final UUID documentVersionId;
    private final Outcome outcome;
    private final float confidence;
    private final String markdown;
    private final List<String> groundingSources;
    private final String errorMessage;

    public DocGenerationCompletedEvent(UUID eventId,
                                        UUID workspaceId,
                                        UUID correlationId,
                                        OffsetDateTime occurredAt,
                                        UUID documentId,
                                        UUID documentVersionId,
                                        Outcome outcome,
                                        float confidence,
                                        String markdown,
                                        List<String> groundingSources,
                                        String errorMessage) {
        super(eventId, workspaceId, correlationId, occurredAt);
        this.documentId = documentId;
        this.documentVersionId = documentVersionId;
        this.outcome = outcome;
        this.confidence = confidence;
        this.markdown = markdown;
        this.groundingSources = groundingSources;
        this.errorMessage = errorMessage;
    }

    public UUID getDocumentId() { return documentId; }
    public UUID getDocumentVersionId() { return documentVersionId; }
    public Outcome getOutcome() { return outcome; }
    public float getConfidence() { return confidence; }
    public String getMarkdown() { return markdown; }
    public List<String> getGroundingSources() { return groundingSources; }
    public String getErrorMessage() { return errorMessage; }
}
