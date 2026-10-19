package com.livingdocs.common.events;

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
 */
public final class DocGenerationRequestedEvent extends AbstractEvent {

    private final UUID documentId;
    private final UUID requestedByUserId;
    private final String template;
    private final List<SourceFilePayload> sourceFiles;

    public DocGenerationRequestedEvent(UUID eventId,
                                        UUID workspaceId,
                                        UUID correlationId,
                                        OffsetDateTime occurredAt,
                                        UUID documentId,
                                        UUID requestedByUserId,
                                        String template,
                                        List<SourceFilePayload> sourceFiles) {
        super(eventId, workspaceId, correlationId, occurredAt);
        this.documentId = documentId;
        this.requestedByUserId = requestedByUserId;
        this.template = template;
        this.sourceFiles = sourceFiles;
    }

    public UUID getDocumentId() { return documentId; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public String getTemplate() { return template; }
    public List<SourceFilePayload> getSourceFiles() { return sourceFiles; }

    /**
     * Plain-old payload version of {@code AiDtos.SourceFile}, kept
     * inside the event module so producers/consumers don't have to
     * take a dependency on the AI client DTOs.
     */
    public record SourceFilePayload(String path, String content) {}
}
