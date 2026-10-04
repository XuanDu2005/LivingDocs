package com.livingdocs.modules.drift.dto;

import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.model.DriftKind;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.model.DriftSeverity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API projection of a drift alert. The {@code evidence} JSON string is
 * forwarded to the frontend unchanged; the UI parses it into a typed shape.
 */
public record DriftAlertResponse(
        UUID id,
        UUID workspaceId,
        UUID repositoryId,
        UUID pullRequestId,
        UUID documentId,
        DriftKind driftKind,
        DriftSeverity severity,
        String title,
        String description,
        String evidenceJson,
        DriftResolution resolutionStatus,
        String aiSuggestion,
        Float confidenceScore,
        OffsetDateTime detectedAt,
        OffsetDateTime resolvedAt,
        UUID resolvedBy
) {
    public static DriftAlertResponse from(DriftAlert a) {
        return new DriftAlertResponse(
                a.getId(),
                a.getWorkspaceId(),
                a.getRepositoryId(),
                a.getPullRequestId(),
                a.getDocumentId(),
                a.getDriftKind(),
                a.getSeverity(),
                a.getTitle(),
                a.getDescription(),
                a.getEvidence(),
                a.getResolutionStatus(),
                a.getAiSuggestion(),
                a.getConfidenceScore(),
                a.getDetectedAt(),
                a.getResolvedAt(),
                a.getResolvedBy()
        );
    }
}