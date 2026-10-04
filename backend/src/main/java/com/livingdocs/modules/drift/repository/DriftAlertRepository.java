package com.livingdocs.modules.drift.repository;

import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.model.DriftKind;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.model.DriftSeverity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DriftAlertRepository extends JpaRepository<DriftAlert, UUID> {

    List<DriftAlert> findAllByWorkspaceIdOrderByDetectedAtDesc(UUID workspaceId);

    List<DriftAlert> findAllByDocumentIdOrderByDetectedAtDesc(UUID documentId);

    List<DriftAlert> findAllByRepositoryIdOrderByDetectedAtDesc(UUID repositoryId);

    List<DriftAlert> findAllByPullRequestIdOrderByDetectedAtDesc(UUID pullRequestId);

    List<DriftAlert> findAllByWorkspaceIdAndResolutionStatusOrderByDetectedAtDesc(
            UUID workspaceId, DriftResolution status);

    List<DriftAlert> findAllByWorkspaceIdAndSeverityOrderByDetectedAtDesc(
            UUID workspaceId, DriftSeverity severity);

    List<DriftAlert> findAllByWorkspaceIdAndDriftKindOrderByDetectedAtDesc(
            UUID workspaceId, DriftKind kind);

    long countByWorkspaceId(UUID workspaceId);
    long countByWorkspaceIdAndResolutionStatus(UUID workspaceId, DriftResolution status);
    long countByWorkspaceIdAndSeverity(UUID workspaceId, DriftSeverity severity);
}