package com.livingdocs.modules.ai.service;

import com.livingdocs.common.events.DocGenerationRequestedEvent;
import com.livingdocs.common.events.EventPublisher;
import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.client.AiServiceClient;
import com.livingdocs.modules.ai.job.DocGenerationJobService;
import com.livingdocs.modules.ai.settings.AiRateLimitService;
import com.livingdocs.modules.ai.settings.ConfidenceThresholdService;
import com.livingdocs.modules.ai.settings.GovernancePolicyService;
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
import com.livingdocs.modules.link.model.CodeDocumentLink;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.review.service.DocumentReviewService;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.service.DocumentVersionService;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import com.livingdocs.modules.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /** Rough USD price per 1k tokens, used only for the admin usage report. */
    private static final BigDecimal COST_PER_1K_TOKENS = new BigDecimal("0.0005");

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
    private final DocGenerationJobService jobService;
    private final CodeDocumentLinkRepository codeDocumentLinkRepository;
    private final ConfidenceThresholdService confidenceThresholdService;
    private final AiRateLimitService rateLimitService;
    private final GovernancePolicyService governancePolicyService;

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
                                  GithubOAuthService githubOAuthService,
                                  DocGenerationJobService jobService,
                                  CodeDocumentLinkRepository codeDocumentLinkRepository,
                                  ConfidenceThresholdService confidenceThresholdService,
                                  AiRateLimitService rateLimitService,
                                  GovernancePolicyService governancePolicyService) {
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
        this.jobService = jobService;
        this.codeDocumentLinkRepository = codeDocumentLinkRepository;
        this.confidenceThresholdService = confidenceThresholdService;
        this.rateLimitService = rateLimitService;
        this.governancePolicyService = governancePolicyService;
    }

    /**
     * Generate documentation from source code and append it as a new
     * PENDING version of the target document.
     *
     * <p>The AI call is asynchronous: this method returns immediately
     * after publishing a {@link DocGenerationRequestedEvent} on Kafka.
     * The actual AI call happens in {@code DocGenerationEventListener},
     * which then persists the resulting version. This keeps the HTTP
     * request fast and lets AI workers scale independently.
     *
     * <p>Returns the id of the {@code doc_generation_jobs} row that
     * tracks this run — the caller can poll the generation-status
     * endpoint to know when the version is ready.
     */
    @Transactional
    public UUID generateForDocument(UUID actorId, UUID documentId,
                                    List<AiDtos.SourceFile> sourceFiles,
                                    String template) {
        return generateForDocument(actorId, documentId, sourceFiles, template, null, null, null, null);
    }

    /**
     * Full form of {@link #generateForDocument(UUID, UUID, List, String)}
     * with the template schema, focus hint, commit attribution and the
     * PR that triggered the run.
     *
     * @return the correlation id of the generation job
     */
    @Transactional
    public UUID generateForDocument(UUID actorId, UUID documentId,
                                    List<AiDtos.SourceFile> sourceFiles,
                                    String template,
                                    String templateSchema,
                                    String focus,
                                    String sourceCommitSha,
                                    UUID sourcePrId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        // A {@code null} actor means "system-initiated" — e.g. the
        // webhook-driven auto-update path, which has no user session.
        // Membership is already enforced upstream (the caller verifies
        // the document belongs to the webhook's workspace), so the
        // membership check only applies to real user actors.
        if (actorId != null) {
            workspaceService.requireMember(actorId, document.getWorkspaceId());
        }

        // Governance gate 1: token budget. Refusing here costs nothing —
        // the user gets an immediate 429 instead of an expensive LLM call
        // that would blow the budget.
        governancePolicyService.requireBudget(document.getWorkspaceId());

        // Translate the AI client DTOs into the event payload so the
        // listener doesn't have to depend on the AI client package.
        // When the caller sent no files, resolve the source from the
        // document's linked code entities so the AI has real code to
        // describe instead of nothing.
        List<AiDtos.SourceFile> effectiveFiles = _resolveSourceFiles(document, sourceFiles);

        List<DocGenerationRequestedEvent.SourceFilePayload> payload = new ArrayList<>();
        for (AiDtos.SourceFile sf : effectiveFiles) {
            payload.add(new DocGenerationRequestedEvent.SourceFilePayload(
                    sf.path(), sf.content()));
        }

        // The job id doubles as the correlation id, so the whole chain
        // (request → Kafka → AI worker → persistence) is traceable and
        // a Kafka redelivery cannot create a second version.
        UUID correlationId = jobService.open(document.getWorkspaceId(), documentId,
                actorId, template, null);

        // Emit the request event *after* the transaction commits, so the
        // listener can see the job row we just inserted. The listener
        // will call the cached AI client, persist a new DocumentVersion,
        // and emit a DocGenerationCompletedEvent for any downstream
        // listeners (notifications, UI updates, etc.).
        eventPublisher.publishDocGenerationRequestedAfterCommit(new DocGenerationRequestedEvent(
                correlationId,
                document.getWorkspaceId(),
                correlationId,
                OffsetDateTime.now(),
                documentId,
                actorId,
                template,
                templateSchema,
                focus,
                null,
                sourceCommitSha,
                sourcePrId,
                payload
        ));

        log.info("Queued doc generation for document {} (template={}, files={}, correlation={})",
                documentId, template, effectiveFiles.size(), correlationId);
        return correlationId;
    }

    /**
     * Run drift detection against a code change. For each finding, persist
     * a {@code drift_alert} row and notify the document owner.
     *
     * <p>Findings below the workspace's confidence threshold are dropped:
     * a low-confidence drift alert is noise, and ingesting them would
     * bury the real issues in the review queue.
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

        float threshold = confidenceThresholdService.resolve(workspaceId);
        // The severity gate keeps the review queue focused: a LOW alert
        // is noise once the workspace has decided that MEDIUM is the
        // lowest severity worth surfacing.
        var severityGate = governancePolicyService.driftSeverityThreshold(workspaceId);
        int ingested = 0;
        int belowThreshold = 0;
        int belowSeverity = 0;
        for (AiDtos.DriftFinding f : resp.findings()) {
            float confidence = (float) Math.max(0.0, Math.min(1.0, f.confidence()));
            if (confidence < threshold) {
                belowThreshold++;
                continue;
            }
            var severity = _severityOf(f.severity());
            if (severity.ordinal() < severityGate.ordinal()) {
                belowSeverity++;
                continue;
            }
            Map<String, Object> evidence = f.evidence() == null ? Map.of() : f.evidence();
            driftAlertService.ingest(actorId, workspaceId,
                    new com.livingdocs.modules.drift.dto.IngestDriftAlertRequest(
                            repositoryId, pullRequestId, documentId,
                            _kindOf(f.driftKind()), severity,
                            f.title(), f.description(),
                            _evidenceToJson(evidence), f.suggestion(),
                            confidence));
            ingested++;
        }

        // Account for the tokens the semantic drift pass consumed.
        _recordUsage(workspaceId, resp.promptTokens(), resp.completionTokens());

        if (belowThreshold > 0) {
            log.info("Dropped {} drift finding(s) below the confidence threshold {} for workspace {}",
                    belowThreshold, threshold, workspaceId);
        }
        if (belowSeverity > 0) {
            log.info("Dropped {} drift finding(s) below the severity gate {} for workspace {}",
                    belowSeverity, severityGate, workspaceId);
        }

        if (ingested == 0) {
            return 0;
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
        // same source content don't re-bill the LLM. When the caller sent
        // no files, resolve the source from the document's linked code
        // entities — otherwise the preview would show the placeholder the
        // AI produces for an empty prompt.
        List<AiDtos.SourceFile> effectiveFiles = _resolveSourceFiles(document, sourceFiles);
        return cachedAiClient.generateCached(
                document.getWorkspaceId(), effectiveFiles, template);
    }

    /**
     * Collect the source files that ground a generation run.
     *
     * <p>When the caller supplies files, they are used as-is. When it
     * supplies none — which is what the UI does today — the source is
     * resolved from the document's linked code entities, pulling the
     * real implementation from GitHub. Without this the AI receives an
     * empty prompt and answers with a placeholder instead of real
     * documentation.
     *
     * <p>The document's current body is always included as a last
     * resort so a rewrite still has something to work from.
     */
    private List<AiDtos.SourceFile> _resolveSourceFiles(Document document,
                                                       List<AiDtos.SourceFile> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }

        List<AiDtos.SourceFile> resolved = new ArrayList<>();

        // 1. The code entities this document documents — the strongest
        //    grounding available.
        List<CodeDocumentLink> links = codeDocumentLinkRepository.findAllByDocumentId(document.getId());
        if (!links.isEmpty()) {
            List<UUID> entityIds = links.stream().map(CodeDocumentLink::getCodeEntityId).toList();
            List<CodeEntity> entities = codeEntityRepository.findAllById(entityIds);
            Repository repo = document.getRepositoryId() != null
                    ? repositoryRepository.findById(document.getRepositoryId()).orElse(null)
                    : null;

            for (CodeEntity entity : entities) {
                String source = repo != null
                        ? _fetchSourceCodeFromGithub(repo, entity.getFilePath(),
                            entity.getStartLine(), entity.getEndLine())
                        : null;
                // Fall back to the raw path when GitHub is unavailable so
                // the AI at least knows which file it is documenting.
                if (source == null || source.isBlank()) {
                    source = "# " + entity.getFilePath()
                            + " (source not available for this run)\n"
                            + "entity: " + entity.getQualifiedName()
                            + "\ntype: " + entity.getEntityType();
                }
                resolved.add(new AiDtos.SourceFile(entity.getFilePath(), source));
            }
        }

        // 2. The document's current body — lets the AI rewrite or extend
        //    the existing prose.
        String currentBody = _currentBodyFor(document.getId());
        if (currentBody != null && !currentBody.isBlank()) {
            resolved.add(new AiDtos.SourceFile(document.getTitle() + ".md", currentBody));
        }

        if (resolved.isEmpty()) {
            log.warn("No source available for document {} — the AI run will have nothing to ground on",
                    document.getId());
        }
        return resolved;
    }

    /**
     * Request AI to automatically rewrite or update a document.
     * (Regenerate on demand).
     *
     * <p>Like {@link #generateForDocument} this is asynchronous: the
     * request is queued on Kafka and the worker performs the actual
     * LLM call. Returns the generation job's correlation id.
     */
    @Transactional
    public UUID regenerateOnDemand(UUID actorId, UUID workspaceId, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        workspaceService.requireMember(actorId, workspaceId);

        // Resolve the source the same way a fresh generation does: the
        // linked code entities (real implementation from GitHub) plus the
        // current body. Passing only the body made the AI answer with a
        // placeholder, because a markdown document is not a parsable
        // source file.
        List<AiDtos.SourceFile> sourceFiles = _resolveSourceFiles(document, List.of());

        return generateForDocument(actorId, documentId, sourceFiles, "MODULE_GUIDE", null,
                "Please refine, update and regenerate this documentation thoroughly.",
                null, null);
    }

    /**
     * Generate documentation for a single code entity and persist it as a
     * document version that enters the staff review queue.
     *
     * <p>Extracted from {@link #generateForRepository} so the asynchronous
     * bulk worker can reuse exactly the same per-entity logic. Keeping one
     * implementation means the synchronous and the queued path can never
     * drift apart.
     *
     * @return the created document id and the version number, or a
     *         failure message when the entity could not be documented
     */
    public EntityGenerationOutcome generateDocumentForEntity(UUID actorId,
                                                             UUID workspaceId,
                                                             Repository repo,
                                                             CodeEntity entity,
                                                             String template) {
        // Fetch ACTUAL source code from GitHub for the file containing this
        // entity — the AI can only describe real behaviour when it sees
        // the real implementation.
        String actualSourceCode = _fetchSourceCodeFromGithub(
                repo, entity.getFilePath(), entity.getStartLine(), entity.getEndLine());

        List<AiDtos.SourceFile> sourceFiles = List.of(
                new AiDtos.SourceFile(entity.getFilePath(), actualSourceCode));

        AiDtos.GenerateResponse resp = aiClient.generate(
                workspaceId, sourceFiles, template,
                "Generate documentation for this " + entity.getEntityType() + " '"
                        + entity.getSimpleName() + "'. Include purpose, parameters, return value, and examples if applicable.",
                entity.getLanguage());

        if (resp == null) {
            return EntityGenerationOutcome.failure(
                    "AI service unavailable for entity: " + entity.getQualifiedName());
        }

        String docTitle = entity.getSimpleName() != null
                ? entity.getSimpleName() : entity.getQualifiedName();
        String slug = docTitle.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        float confidence = (float) Math.max(0.0, Math.min(1.0, resp.confidence()));

        var existingDoc = documentRepository.findByWorkspaceIdAndSlug(workspaceId, slug);
        if (existingDoc.isPresent()) {
            DocumentVersion version = versionService.createAiVersion(
                    actorId, existingDoc.get().getId(),
                    resp.markdown(),
                    "AI auto-generated for " + entity.getQualifiedName(),
                    null, null, confidence, _sourcesToJson(resp));
            _linkEntityToDocument(existingDoc.get().getId(), entity);
            return EntityGenerationOutcome.success(existingDoc.get().getId(),
                    version.getVersionNumber(), docTitle, entity.getQualifiedName());
        }

        var createReq = new com.livingdocs.modules.document.dto.CreateDocumentRequest(
                repo.getId(), null, docTitle, slug, "AUTO_GENERATED", null, false, resp.markdown());
        var doc = documentService.create(actorId, workspaceId, createReq);

        DocumentVersion version = versionService.createAiVersion(
                actorId, doc.id(),
                resp.markdown(),
                "AI auto-generated for " + entity.getQualifiedName(),
                null, null, confidence, _sourcesToJson(resp));
        _linkEntityToDocument(doc.id(), entity);

        return EntityGenerationOutcome.success(doc.id(), version.getVersionNumber(),
                docTitle, entity.getQualifiedName());
    }

    /**
     * Link a document to the code entity it documents.
     *
     * <p>Without this link the document has no code context, and the
     * next "Generate with AI" run on it would have nothing to ground
     * the AI on. It also breaks coverage-gap detection, which resolves
     * "is this entity documented?" through this table.
     */
    private void _linkEntityToDocument(UUID documentId, CodeEntity entity) {
        try {
            if (codeDocumentLinkRepository
                    .findByDocumentIdAndCodeEntityId(documentId, entity.getId())
                    .isPresent()) {
                return;
            }
            codeDocumentLinkRepository.save(new CodeDocumentLink(
                    documentId, entity.getId(), "AI_GENERATED", 1.0f));
        } catch (Exception e) {
            log.warn("Could not link entity {} to document {}: {}",
                    entity.getQualifiedName(), documentId, e.getMessage());
        }
    }

    /**
     * The outcome of documenting a single entity.
     */
    public record EntityGenerationOutcome(boolean success,
                                          UUID documentId,
                                          Integer versionNumber,
                                          String title,
                                          String qualifiedName,
                                          String errorMessage) {

        static EntityGenerationOutcome success(UUID documentId, int versionNumber,
                                               String title, String qualifiedName) {
            return new EntityGenerationOutcome(true, documentId, versionNumber,
                    title, qualifiedName, null);
        }

        static EntityGenerationOutcome failure(String errorMessage) {
            return new EntityGenerationOutcome(false, null, null,
                    null, null, errorMessage);
        }
    }

    /**
     * @deprecated Replaced by the asynchronous bulk generation flow in
     * {@code BulkGenerationService}. The synchronous version blocked the
     * HTTP request for as long as the LLM needed to document every
     * entity, which is what caused the client-side timeout.
     */
    @Transactional
    public GenerateForRepositoryResult generateForRepository(UUID actorId, UUID workspaceId,
                                                            UUID repositoryId, String template) {
        workspaceService.requireMember(actorId, workspaceId);

        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found"));
        if (!repo.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("Repository not found in this workspace");
        }

        List<CodeEntity> entities =
                codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId);
        if (entities.isEmpty()) {
            return new GenerateForRepositoryResult(0, 0,
                    List.of("No code entities found in repository"), List.of());
        }

        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> createdDocs = new ArrayList<>();
        int generated = 0;
        int skipped = 0;

        for (CodeEntity entity : entities) {
            if (entity.getDocstring() != null && !entity.getDocstring().isBlank()) {
                skipped++;
                continue;
            }
            try {
                var outcome = generateDocumentForEntity(actorId, workspaceId, repo, entity, template);
                if (outcome.success()) {
                    generated++;
                    createdDocs.add(Map.of(
                            "documentId", outcome.documentId().toString(),
                            "title", outcome.title(),
                            "entity", outcome.qualifiedName(),
                            "versionNumber", outcome.versionNumber()));
                } else {
                    errors.add(outcome.errorMessage());
                }
            } catch (Exception e) {
                log.error("Failed to generate doc for entity {}: {}",
                        entity.getQualifiedName(), e.getMessage());
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

    /**
     * Roll the token counts reported by the AI service into the
     * workspace's usage report so the admin dashboard and the budget
     * guard see the real spend.
     */
    private void _recordUsage(UUID workspaceId, int promptTokens, int completionTokens) {
        if (workspaceId == null) return;
        int total = promptTokens + completionTokens;
        if (total <= 0) return;
        try {
            BigDecimal cost = BigDecimal.valueOf(total)
                    .divide(BigDecimal.valueOf(1000), 6, java.math.RoundingMode.HALF_UP)
                    .multiply(COST_PER_1K_TOKENS);
            rateLimitService.recordUsage(workspaceId, total, cost);
        } catch (Exception e) {
            log.warn("Failed to record AI usage for workspace {}: {}", workspaceId, e.getMessage());
        }
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