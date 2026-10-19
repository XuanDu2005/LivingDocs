package com.livingdocs.common.events;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Published by the drift-detection worker after it has finished
 * scanning a code change against one or more documents. Each
 * {@link Finding} is one issue the LLM surfaced (e.g. "method
 * signature changed but doc still says X"); the platform persists
 * these as {@code drift_alerts} rows and sends notifications to
 * the affected document owners.
 */
public final class DriftDetectionCompletedEvent extends AbstractEvent {

    public record Finding(
            String kind,        // REFERENTIAL / SIGNATURE / SEMANTIC
            String severity,    // LOW / MEDIUM / HIGH / CRITICAL
            String title,
            String description,
            String suggestion,
            float confidence
    ) {}

    private final UUID repositoryId;
    private final UUID pullRequestId;
    private final List<DocumentDrift> documents;

    public DriftDetectionCompletedEvent(UUID eventId,
                                         UUID workspaceId,
                                         UUID correlationId,
                                         OffsetDateTime occurredAt,
                                         UUID repositoryId,
                                         UUID pullRequestId,
                                         List<DocumentDrift> documents) {
        super(eventId, workspaceId, correlationId, occurredAt);
        this.repositoryId = repositoryId;
        this.pullRequestId = pullRequestId;
        this.documents = documents;
    }

    public UUID getRepositoryId() { return repositoryId; }
    public UUID getPullRequestId() { return pullRequestId; }
    public List<DocumentDrift> getDocuments() { return documents; }

    /**
     * One document's slice of the drift report. If
     * {@code documentId} is null the finding applies to repository-
     * level documentation (e.g. README) that has no row in the
     * {@code documents} table yet.
     */
    public record DocumentDrift(
            UUID documentId,
            String documentTitle,
            List<Finding> findings
    ) {}
}
