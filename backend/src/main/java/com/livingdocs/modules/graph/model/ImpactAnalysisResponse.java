package com.livingdocs.modules.graph.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The impact analysis of one code entity.
 *
 * <p>The graph answers "what does this documentation depend on?".
 * The impact analysis answers the question that actually matters for
 * a reviewer of a pull request: <em>"I am about to change this
 * method — what breaks?"</em>
 *
 * <p>It therefore answers four things at once:
 *
 * <ul>
 *   <li>which documents document this entity (directly or through a
 *       link of another kind),</li>
 *   <li>which other entities share its file or its module and would
 *       move with it,</li>
 *   <li>which drift alerts already touch the affected documents,</li>
 *   <li>which pull requests are currently open on the repository.</li>
 * </ul>
 *
 * @param entity          the entity the impact was computed for
 * @param documents       the documents that document the entity
 * @param relatedEntities the entities in the same file / module
 * @param driftAlerts     the open drift alerts of the affected documents
 * @param pullRequests    the pull requests currently open on the repository
 */
public record ImpactAnalysisResponse(
        ImpactedEntity entity,
        List<ImpactedDocument> documents,
        List<RelatedEntity> relatedEntities,
        List<ImpactedDriftAlert> driftAlerts,
        List<ImpactedPullRequest> pullRequests
) {
    /** The entity the analysis was requested for. */
    public record ImpactedEntity(
            UUID id,
            UUID repositoryId,
            String qualifiedName,
            String simpleName,
            String entityType,
            String filePath,
            Integer startLine,
            Integer endLine,
            /** The module (package) the entity lives in, when one is configured. */
            String modulePath,
            String language
    ) {
    }

    /** A document that documents the entity. */
    public record ImpactedDocument(
            UUID id,
            String title,
            String slug,
            String docType,
            String status,
            UUID repositoryId,
            /** How the document is linked to the entity. */
            String linkKind,
            /** The confidence of that link, when it has one. */
            Float confidence
    ) {
    }

    /**
     * An entity that shares its file or its module with the analysed
     * one — the "collateral damage" of a change.
     *
     * @param relation {@code SAME_FILE}, {@code SAME_MODULE} or
     *                 {@code SAME_REPOSITORY}
     */
    public record RelatedEntity(
            UUID id,
            String qualifiedName,
            String entityType,
            String filePath,
            Integer startLine,
            String relation
    ) {
    }

    /** A drift alert that touches one of the affected documents. */
    public record ImpactedDriftAlert(
            UUID id,
            UUID documentId,
            String severity,
            String driftKind,
            String title,
            String resolutionStatus,
            OffsetDateTime detectedAt
    ) {
    }

    /** A pull request currently open on the repository of the entity. */
    public record ImpactedPullRequest(
            UUID id,
            Long number,
            String title,
            String state,
            String authorLogin,
            String headBranch,
            String htmlUrl,
            OffsetDateTime updatedAt
    ) {
    }
}
