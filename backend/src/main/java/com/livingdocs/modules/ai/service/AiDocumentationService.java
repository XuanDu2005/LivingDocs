package com.livingdocs.modules.ai.service;

import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.client.AiServiceClient;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.drift.model.DriftKind;
import com.livingdocs.modules.drift.model.DriftSeverity;
import com.livingdocs.modules.drift.service.DriftAlertService;
import com.livingdocs.modules.review.service.DocumentReviewService;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.service.DocumentVersionService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import com.livingdocs.modules.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * High-level facade that orchestrates AI-backed flows:
 * <ul>
 *     <li>Generating documentation from parsed source code.</li>
 *     <li>Detecting documentation drift against a code change.</li>
 *     <li>Auto-updating documentation when source code changes.</li>
 * </ul>
 *
 * <p>Every flow degrades gracefully — when the AI service is unavailable,
 * the rest of the platform keeps working with manual workflows.
 */
@Service
public class AiDocumentationService {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentationService.class);

    private final AiServiceClient aiClient;
    private final DocumentRepository documentRepository;
    private final DocumentVersionService versionService;
    private final DocumentReviewService reviewService;
    private final DriftAlertService driftAlertService;
    private final NotificationService notificationService;
    private final WorkspaceService workspaceService;

    public AiDocumentationService(AiServiceClient aiClient,
                                  DocumentRepository documentRepository,
                                  DocumentVersionService versionService,
                                  DocumentReviewService reviewService,
                                  DriftAlertService driftAlertService,
                                  NotificationService notificationService,
                                  WorkspaceService workspaceService) {
        this.aiClient = aiClient;
        this.documentRepository = documentRepository;
        this.versionService = versionService;
        this.reviewService = reviewService;
        this.driftAlertService = driftAlertService;
        this.notificationService = notificationService;
        this.workspaceService = workspaceService;
    }

    /**
     * Generate documentation from source code and append it as a new
     * PENDING version of the target document.
     */
    @Transactional
    public DocumentVersion generateForDocument(UUID actorId, UUID documentId,
                                                List<AiDtos.SourceFile> sourceFiles,
                                                String template) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());

        AiDtos.GenerateResponse resp = aiClient.generate(
                document.getWorkspaceId(), sourceFiles, template, null, null);
        if (resp == null) {
            log.warn("AI generation unavailable; aborting generateForDocument");
            return null;
        }
        DocumentVersion version = versionService.createAiVersion(
                actorId, documentId,
                resp.markdown(),
                "AI-generated from " + sourceFiles.size() + " source file(s)",
                null, null,
                (float) Math.max(0.0, Math.min(1.0, resp.confidence())));
        reviewService.markPendingForStaffReview(documentId, version.getId());
        return version;
    }

    /**
     * Run drift detection against a code change. For each finding, persist
     * a {@code drift_alert} row and notify the document owner.
     */
    @Transactional
    public int detectDriftForPullRequest(UUID actorId, UUID workspaceId, UUID repositoryId,
                                          UUID pullRequestId, UUID documentId,
                                          List<AiDtos.SourceFile> filesBefore,
                                          List<AiDtos.SourceFile> filesAfter) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        workspaceService.requireMember(actorId, workspaceId);

        String currentBody = _currentBodyFor(documentId);

        AiDtos.DriftResponse resp = aiClient.detectDrift(
                workspaceId, filesBefore, filesAfter, currentBody, document.getTitle());
        if (resp == null || resp.findings() == null || resp.findings().isEmpty()) {
            return 0;
        }

        int ingested = 0;
        for (AiDtos.DriftFinding f : resp.findings()) {
            Map<String, Object> evidence = f.evidence() == null ? Map.of() : f.evidence();
            driftAlertService.ingest(actorId, workspaceId,
                    new com.livingdocs.modules.drift.dto.IngestDriftAlertRequest(
                            repositoryId, pullRequestId, documentId,
                            _kindOf(f.driftKind()), _severityOf(f.severity()),
                            f.title(), f.description(),
                            _evidenceToJson(evidence), f.suggestion(),
                            (float) Math.max(0.0, Math.min(1.0, f.confidence()))));
            ingested++;
        }

        // Notify the document owner once per run.
        notificationService.send(document.getOwnerId(), "drift.detected",
                "Documentation drift detected on " + document.getTitle(),
                resp.markdownSummary(),
                "/workspaces/" + workspaceId + "/documents/" + documentId);

        return ingested;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String _currentBodyFor(UUID documentId) {
        return versionService.findLatestBody(documentId).orElse("");
    }

    private static String _evidenceToJson(Map<String, Object> evidence) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(evidence);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static DriftKind _kindOf(String raw) {
        if (raw == null) return DriftKind.REFERENTIAL;
        try {
            return DriftKind.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DriftKind.REFERENTIAL;
        }
    }

    private static DriftSeverity _severityOf(String raw) {
        if (raw == null) return DriftSeverity.MEDIUM;
        try {
            return DriftSeverity.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DriftSeverity.MEDIUM;
        }
    }
}