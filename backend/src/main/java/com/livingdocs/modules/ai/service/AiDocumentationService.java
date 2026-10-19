package com.livingdocs.modules.ai.service;

import com.livingdocs.common.events.DocGenerationRequestedEvent;
import com.livingdocs.common.events.EventPublisher;
import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.client.AiServiceClient;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.document.service.DocumentService;
import com.livingdocs.modules.drift.model.DriftKind;
import com.livingdocs.modules.drift.model.DriftSeverity;
import com.livingdocs.modules.drift.service.DriftAlertService;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.review.service.DocumentReviewService;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.service.DocumentVersionService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import com.livingdocs.modules.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final CachedAiClient cachedAiClient;
    private final DocumentRepository documentRepository;
    private final DocumentVersionService versionService;
    private final DocumentReviewService reviewService;
    private final DriftAlertService driftAlertService;
    private final NotificationService notificationService;
    private final WorkspaceService workspaceService;
    private final EventPublisher eventPublisher;
    private final CodeEntityRepository codeEntityRepository;
    private final DocumentService documentService;
    private final RepositoryRepository repositoryRepository;
    private final GithubClient githubClient;
    private final GithubOAuthService githubOAuthService;

    public AiDocumentationService(AiServiceClient aiClient,
                                  CachedAiClient cachedAiClient,
                                  DocumentRepository documentRepository,
                                  DocumentVersionService versionService,
                                  DocumentReviewService reviewService,
                                  DriftAlertService driftAlertService,
                                  NotificationService notificationService,
                                  WorkspaceService workspaceService,
                                  EventPublisher eventPublisher,
                                  CodeEntityRepository codeEntityRepository,
                                  DocumentService documentService,
                                  RepositoryRepository repositoryRepository,
                                  GithubClient githubClient,
                                  GithubOAuthService githubOAuthService) {
        this.aiClient = aiClient;
        this.cachedAiClient = cachedAiClient;
        this.documentRepository = documentRepository;
        this.versionService = versionService;
        this.reviewService = reviewService;
        this.driftAlertService = driftAlertService;
        this.notificationService = notificationService;
        this.workspaceService = workspaceService;
        this.eventPublisher = eventPublisher;
        this.codeEntityRepository = codeEntityRepository;
        this.documentService = documentService;
        this.repositoryRepository = repositoryRepository;
        this.githubClient = githubClient;
        this.githubOAuthService = githubOAuthService;
    }

    /**
     * Generate documentation from source code and append it as a new
     * PENDING version of the target document.
     *
     * <p>The AI call is now asynchronous: this method returns 202-style
     * by creating a placeholder row and publishing a
     * {@link DocGenerationRequestedEvent} on Kafka. The actual AI call
     * happens in {@code DocGenerationEventListener}. This keeps the
     * HTTP request fast and lets AI workers scale independently.
     */
    @Transactional
    public DocumentVersion generateForDocument(UUID actorId, UUID documentId,
                                                List<AiDtos.SourceFile> sourceFiles,
                                                String template) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());

        // Translate the AI client DTOs into the event payload so the
        // listener doesn't have to depend on the AI client package.
        List<DocGenerationRequestedEvent.SourceFilePayload> payload = new ArrayList<>();
        for (AiDtos.SourceFile sf : sourceFiles) {
            payload.add(new DocGenerationRequestedEvent.SourceFilePayload(
                    sf.path(), sf.content()));
        }

        // Emit the request event. The listener will call the cached
        // AI client, persist a new DocumentVersion, and emit a
        // DocGenerationCompletedEvent for any downstream listeners
        // (notifications, UI updates, etc.).
        eventPublisher.publishDocGenerationRequested(new DocGenerationRequestedEvent(
                UUID.randomUUID(),
                document.getWorkspaceId(),
                null,           // correlationId — sync HTTP path doesn't carry one
                OffsetDateTime.now(),
                documentId,
                actorId,
                template,
                payload
        ));

        // Return null to signal "AI work is in flight; poll the
        // document's version list or subscribe to
        // DocGenerationCompletedEvent for the real result."
        log.info("Queued doc generation for document {} (template={}, files={})",
                documentId, template, sourceFiles.size());
        return null;
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

        // Cache drift results by (workspace, title, body, before, after)
        // tuple so re-runs with the same code change don't re-bill the LLM.
        AiDtos.DriftResponse resp = cachedAiClient.detectDriftCached(
                workspaceId, document.getTitle(), currentBody,
                filesBefore, filesAfter);
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

    /**
     * Run AI generation on a document and return the proposed body without
     * persisting a version. Used by the "Preview before submitting" UI flow.
     */
    @Transactional(readOnly = true)
    public AiDtos.GenerateResponse previewForDocument(UUID actorId, UUID documentId,
                                                       List<AiDtos.SourceFile> sourceFiles,
                                                       String template) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        workspaceService.requireMember(actorId, document.getWorkspaceId());
        // Use the Redis-cached wrapper so repeated "Preview" clicks with the
        // same source content don't re-bill the LLM.
        return cachedAiClient.generateCached(
                document.getWorkspaceId(), sourceFiles, template);
    }

    /**
     * Request AI to automatically rewrite or update a document.
     * (Regenerate on demand).
     */
    @Transactional
    public DocumentVersion regenerateOnDemand(UUID actorId, UUID workspaceId, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        workspaceService.requireMember(actorId, workspaceId);

        String currentBody = _currentBodyFor(documentId);

        List<AiDtos.SourceFile> sourceFiles = List.of(
                new AiDtos.SourceFile(document.getTitle() + ".md", currentBody)
        );

        AiDtos.GenerateResponse resp = aiClient.generate(
                workspaceId, sourceFiles, "MODULE_GUIDE", "Please refine, update and regenerate this documentation thoroughly.", null);

        if (resp == null) {
            throw new RuntimeException("AI service is currently unavailable");
        }

        DocumentVersion version = versionService.createAiVersion(
                actorId, documentId,
                resp.markdown(),
                "Manual AI Regeneration triggered by user",
                null, null,
                (float) Math.max(0.0, Math.min(1.0, resp.confidence())),
                _sourcesToJson(resp));

        reviewService.markPendingForStaffReview(documentId, version.getId());
        return version;
    }

    /**
     * Generate documentation for all coverage-gap code entities in a repository.
     * Creates new documents for undocumented entities.
     *
     * @return Result containing generated count, skipped count, errors, and created documents.
     */
    @Transactional
    public GenerateForRepositoryResult generateForRepository(UUID actorId, UUID workspaceId,
                                                            UUID repositoryId, String template) {
        workspaceService.requireMember(actorId, workspaceId);

        // Verify repository belongs to this workspace
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("Repository not found in this workspace");
        }

        // Get all code entities for the repository
        List<CodeEntity> entities = codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId);

        if (entities.isEmpty()) {
            return new GenerateForRepositoryResult(0, 0, List.of("No code entities found in repository"), List.of());
        }

        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> createdDocs = new ArrayList<>();
        int generated = 0;
        int skipped = 0;

        for (CodeEntity entity : entities) {
            try {
                // Check if this entity already has documentation
                // (has docstring or is linked to a document)
                boolean hasDoc = entity.getDocstring() != null && !entity.getDocstring().isBlank();

                if (hasDoc) {
                    skipped++;
                    continue;
                }

                // Fetch ACTUAL source code from GitHub for the file containing this entity
                String actualSourceCode = _fetchSourceCodeFromGithub(
                        repo, entity.getFilePath(), entity.getStartLine(), entity.getEndLine());
                
                List<AiDtos.SourceFile> sourceFiles = List.of(
                        new AiDtos.SourceFile(entity.getFilePath(), actualSourceCode)
                );

                // Call AI to generate documentation
                AiDtos.GenerateResponse resp = aiClient.generate(
                        workspaceId, sourceFiles, template,
                        "Generate documentation for this " + entity.getEntityType() + " '" + entity.getSimpleName() + "'. Include purpose, parameters, return value, and examples if applicable.",
                        entity.getLanguage()
                );

                if (resp == null) {
                    errors.add("AI service unavailable for entity: " + entity.getQualifiedName());
                    continue;
                }

                // Create a new document for this entity
                String docTitle = entity.getSimpleName() != null ? entity.getSimpleName() : entity.getQualifiedName();
                String slug = docTitle.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");

                // Check if document already exists
                if (documentRepository.findByWorkspaceIdAndSlug(workspaceId, slug).isPresent()) {
                    // Update existing document
                    var existingDoc = documentRepository.findByWorkspaceIdAndSlug(workspaceId, slug).get();
                    DocumentVersion version = versionService.createAiVersion(
                            actorId, existingDoc.getId(),
                            resp.markdown(),
                            "AI auto-generated for " + entity.getQualifiedName(),
                            null, null,
                            (float) Math.max(0.0, Math.min(1.0, resp.confidence())),
                            _sourcesToJson(resp)
                    );
                    reviewService.markPendingForStaffReview(existingDoc.getId(), version.getId());
                    generated++;
                    createdDocs.add(Map.of(
                            "documentId", existingDoc.getId().toString(),
                            "title", docTitle,
                            "entity", entity.getQualifiedName(),
                            "versionNumber", version.getVersionNumber()
                    ));
                } else {
                    // Create new document
                    var createReq = new com.livingdocs.modules.document.dto.CreateDocumentRequest(
                            repositoryId,
                            null, // templateId
                            docTitle,
                            slug,
                            "AUTO_GENERATED",
                            null, // summary
                            false, // autoUpdateEnabled
                            resp.markdown() // initialBody
                    );
                    var doc = documentService.create(actorId, workspaceId, createReq);

                    // Create initial version with AI content
                    DocumentVersion version = versionService.createAiVersion(
                            actorId, doc.id(),
                            resp.markdown(),
                            "AI auto-generated for " + entity.getQualifiedName(),
                            null, null,
                            (float) Math.max(0.0, Math.min(1.0, resp.confidence())),
                            _sourcesToJson(resp)
                    );
                    reviewService.markPendingForStaffReview(doc.id(), version.getId());
                    generated++;
                    createdDocs.add(Map.of(
                            "documentId", doc.id().toString(),
                            "title", docTitle,
                            "entity", entity.getQualifiedName(),
                            "versionNumber", version.getVersionNumber()
                    ));
                }

                log.info("Generated doc for entity: {} (type={})", entity.getQualifiedName(), entity.getEntityType());

            } catch (Exception e) {
                log.error("Failed to generate doc for entity {}: {}", entity.getQualifiedName(), e.getMessage());
                errors.add("Failed for " + entity.getQualifiedName() + ": " + e.getMessage());
            }
        }

        log.info("Generate All Docs: generated={}, skipped={}, errors={}", generated, skipped, errors.size());
        return new GenerateForRepositoryResult(generated, skipped, errors, createdDocs);
    }

    /**
     * Fetches the ACTUAL source code from GitHub for a given file.
     * This provides real code context for AI documentation generation.
     */
    private String _fetchSourceCodeFromGithub(Repository repo, String filePath, 
                                               Integer startLine, Integer endLine) {
        if (filePath == null || filePath.isBlank()) {
            return _buildSourceFromFallback(filePath);
        }
        
        try {
            // Get access token from GitHub OAuth connection
            var connectionOpt = githubOAuthService.findForUser(repo.getConnectedBy());
            if (connectionOpt.isEmpty()) {
                log.warn("No GitHub connection found for user: {}", repo.getConnectedBy());
                return _buildSourceFromFallback(filePath);
            }
            String accessToken = connectionOpt.get().getAccessToken();
            String branch = repo.getDefaultBranch();
            
            // Fetch the entire file from GitHub
            String fullContent = githubClient.getFileContent(
                    accessToken, repo.getOwner(), repo.getName(), filePath, branch);
            
            if (fullContent == null || fullContent.isBlank()) {
                log.warn("Empty content from GitHub for file: {}", filePath);
                return _buildSourceFromFallback(filePath);
            }
            
            // If we have line range, extract just that portion
            if (startLine != null && endLine != null && startLine > 0 && endLine >= startLine) {
                String[] lines = fullContent.split("\n");
                StringBuilder sb = new StringBuilder();
                int start = Math.max(0, startLine - 1); // Convert to 0-indexed
                int end = Math.min(lines.length, endLine);
                for (int i = start; i < end; i++) {
                    sb.append(lines[i]).append("\n");
                }
                return sb.toString();
            }
            
            return fullContent;
            
        } catch (Exception e) {
            log.error("Failed to fetch source from GitHub for {}: {}", filePath, e.getMessage());
            return _buildSourceFromFallback(filePath);
        }
    }

    /**
     * Fallback when we can't fetch actual source code.
     */
    private String _buildSourceFromFallback(String filePath) {
        return "// Source code not available for: " + filePath + "\n// Please check the repository manually.";
    }

    private String _buildSourceFromEntity(CodeEntity entity) {
        StringBuilder sb = new StringBuilder();
        sb.append("Language: ").append(entity.getLanguage()).append("\n");
        sb.append("Type: ").append(entity.getEntityType()).append("\n");
        sb.append("Name: ").append(entity.getQualifiedName()).append("\n");
        if (entity.getSignature() != null) {
            sb.append("Signature: ").append(entity.getSignature()).append("\n");
        }
        if (entity.getFilePath() != null) {
            sb.append("File: ").append(entity.getFilePath());
            if (entity.getStartLine() != null && entity.getEndLine() != null) {
                sb.append(":").append(entity.getStartLine()).append("-").append(entity.getEndLine());
            }
            sb.append("\n");
        }
        if (entity.getMetadata() != null) {
            sb.append("Metadata: ").append(entity.getMetadata()).append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String _currentBodyFor(UUID documentId) {
        return versionService.findLatestBody(documentId).orElse("");
    }

    private static String _sourcesToJson(AiDtos.GenerateResponse resp) {
        if (resp == null || resp.sources() == null || resp.sources().isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(resp.sources());
        } catch (Exception e) {
            return null;
        }
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

    /**
     * Result of generateForRepository operation.
     */
    public record GenerateForRepositoryResult(
            int generated,
            int skipped,
            List<String> errors,
            List<Map<String, Object>> documents
    ) {}
}