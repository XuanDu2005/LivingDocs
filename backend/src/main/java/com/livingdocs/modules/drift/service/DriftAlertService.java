package com.livingdocs.modules.drift.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.drift.dto.DriftAlertResponse;
import com.livingdocs.modules.drift.dto.IngestDriftAlertRequest;
import com.livingdocs.modules.drift.dto.ResolveDriftAlertRequest;
import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Manages documentation drift alerts.
 *
 * <p>Detection itself happens in the AI service; this service is the
 * persistent home for alerts and the gateway for human resolution actions.
 */
@Service
public class DriftAlertService {

    private static final Logger log = LoggerFactory.getLogger(DriftAlertService.class);

    private final DriftAlertRepository driftRepository;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public DriftAlertService(DriftAlertRepository driftRepository,
                             WorkspaceService workspaceService,
                             ObjectMapper objectMapper) {
        this.driftRepository = driftRepository;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DriftAlertResponse ingest(UUID actorId, UUID workspaceId, IngestDriftAlertRequest req) {
        workspaceService.requireMember(actorId, workspaceId);
        validateEvidenceJson(req.evidenceJson());

        DriftAlert alert = new DriftAlert(
                workspaceId,
                req.repositoryId(),
                req.pullRequestId(),
                req.documentId(),
                req.driftKind(),
                req.severity(),
                req.title(),
                req.description(),
                req.evidenceJson() == null || req.evidenceJson().isBlank() ? "{}" : req.evidenceJson(),
                req.aiSuggestion(),
                req.confidenceScore()
        );
        DriftAlert saved = driftRepository.save(alert);
        log.info("Drift alert ingested: workspace={} document={} severity={} kind={}",
                workspaceId, req.documentId(), req.severity(), req.driftKind());
        return DriftAlertResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DriftAlertResponse> listForWorkspace(UUID actorId, UUID workspaceId,
                                                     DriftResolution resolution,
                                                     com.livingdocs.modules.drift.model.DriftKind kind,
                                                     com.livingdocs.modules.drift.model.DriftSeverity severity) {
        workspaceService.requireMember(actorId, workspaceId);
        List<DriftAlert> alerts;
        if (resolution != null) {
            alerts = driftRepository.findAllByWorkspaceIdAndResolutionStatusOrderByDetectedAtDesc(
                    workspaceId, resolution);
        } else if (severity != null) {
            alerts = driftRepository.findAllByWorkspaceIdAndSeverityOrderByDetectedAtDesc(
                    workspaceId, severity);
        } else if (kind != null) {
            alerts = driftRepository.findAllByWorkspaceIdAndDriftKindOrderByDetectedAtDesc(
                    workspaceId, kind);
        } else {
            alerts = driftRepository.findAllByWorkspaceIdOrderByDetectedAtDesc(workspaceId);
        }
        return alerts.stream().map(DriftAlertResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DriftAlertResponse> listForDocument(UUID actorId, UUID documentId) {
        return driftRepository.findAllByDocumentIdOrderByDetectedAtDesc(documentId)
                .stream().map(DriftAlertResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<DriftAlertResponse> listForPullRequest(UUID actorId, UUID pullRequestId) {
        return driftRepository.findAllByPullRequestIdOrderByDetectedAtDesc(pullRequestId)
                .stream().map(DriftAlertResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public DriftAlertResponse get(UUID actorId, UUID alertId) {
        DriftAlert alert = loadAndAuthorize(actorId, alertId);
        return DriftAlertResponse.from(alert);
    }

    @Transactional
    public DriftAlertResponse resolve(UUID actorId, UUID alertId, ResolveDriftAlertRequest req) {
        DriftAlert alert = loadAndAuthorize(actorId, alertId);
        if (req.resolution() == DriftResolution.OPEN) {
            throw new BadRequestException("Resolution cannot be OPEN");
        }
        alert.setResolutionStatus(req.resolution());
        alert.setResolvedAt(OffsetDateTime.now());
        alert.setResolvedBy(actorId);
        log.info("Drift alert resolved: alert={} resolution={}", alertId, req.resolution());
        return DriftAlertResponse.from(alert);
    }

    /** Convenience used by the AI ingest path. */
    @Transactional(readOnly = true)
    public List<DriftAlertResponse> openAlertsForWorkspace(UUID workspaceId) {
        return driftRepository.findAllByWorkspaceIdAndResolutionStatusOrderByDetectedAtDesc(
                workspaceId, DriftResolution.OPEN)
                .stream().map(DriftAlertResponse::from).toList();
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private DriftAlert loadAndAuthorize(UUID actorId, UUID alertId) {
        DriftAlert alert = driftRepository.findById(alertId)
                .orElseThrow(() -> new NotFoundException("Drift alert not found"));
        workspaceService.requireMember(actorId, alert.getWorkspaceId());
        return alert;
    }

    private void validateEvidenceJson(String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("evidenceJson is not valid JSON: " + e.getMessage());
        }
    }
}