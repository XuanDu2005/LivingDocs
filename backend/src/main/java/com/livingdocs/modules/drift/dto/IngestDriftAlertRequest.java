package com.livingdocs.modules.drift.dto;

/**
 * Body used to manually ingest a drift report coming back from the AI service.
 *
 * <p>The CI/CD pipeline (or a developer) calls this endpoint to record the
 * results of an analysis. The endpoint validates that the actor has access
 * to the workspace and persists the alert.
 */
public record IngestDriftAlertRequest(
        java.util.UUID repositoryId,
        java.util.UUID pullRequestId,
        java.util.UUID documentId,
        com.livingdocs.modules.drift.model.DriftKind driftKind,
        com.livingdocs.modules.drift.model.DriftSeverity severity,
        String title,
        String description,
        String evidenceJson,
        String aiSuggestion,
        Float confidenceScore
) {}