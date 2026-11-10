package com.livingdocs.common.events;

import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.job.DocGenerationJob;
import com.livingdocs.modules.ai.job.DocGenerationJobService;
import com.livingdocs.modules.ai.service.AiDocumentationService;
import com.livingdocs.modules.ai.service.CachedAiClient;
import com.livingdocs.modules.ai.settings.AiRateLimitService;
import com.livingdocs.modules.ai.settings.GovernancePolicyService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.link.model.CodeDocumentLink;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.notification.service.NotificationService;
import com.livingdocs.modules.review.service.DocumentReviewService;
import com.livingdocs.modules.template.model.DocTemplate;
import com.livingdocs.modules.template.model.OutputFormat;
import com.livingdocs.modules.template.service.TemplateRenderer;
import com.livingdocs.modules.template.service.TemplateSchemaResolver;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.service.DocumentVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes {@link DocGenerationRequestedEvent}s from Kafka and
 * triggers the actual AI generation, then persists the result as a
 * new {@link DocumentVersion}.
 *
 * <p>This is the replacement for the inline {@code @Async}
 * generation call that used to live in
 * {@code AiDocumentationService.generateForDocument}. The benefit
 * is twofold:
 * <ul>
 *   <li>The HTTP request returns 202 immediately and doesn't block
 *       on the LLM call.</li>
 *   <li>Retries are automatic: if this listener crashes mid-call,
 *       Kafka redelivers the event after the consumer group's
 *       session timeout.</li>
 * </ul>
 *
 * <h2>Why persistence lives here</h2>
 * Previously the listener published a
 * {@link DocGenerationCompletedEvent} and nothing consumed it — the
 * version was never written, so the whole AI feature was inert. The
 * completion event is still published (other consumers may want it),
 * but the version is now written directly so a single consumer group
 * owns the whole request→version transition.
 */
@Component
public class DocGenerationEventListener {

    private static final Logger log = LoggerFactory.getLogger(DocGenerationEventListener.class);

    /** Rough USD price per 1k tokens, used only for the admin usage report. */
    private static final BigDecimal COST_PER_1K_TOKENS = new BigDecimal("0.0005");

    private final CachedAiClient cachedAiClient;
    private final AiDocumentationService aiDocumentationService;
    private final EventPublisher eventPublisher;
    private final DocumentVersionService versionService;
    private final DocumentReviewService reviewService;
    private final DocGenerationJobService jobService;
    private final NotificationService notificationService;
    private final AiRateLimitService rateLimitService;
    private final DocumentRepository documentRepository;
    private final GovernancePolicyService governancePolicyService;
    private final CodeEntityRepository codeEntityRepository;
    private final CodeDocumentLinkRepository codeDocumentLinkRepository;
    private final AuditLogService auditLogService;
    private final TemplateSchemaResolver templateResolver;
    private final TemplateRenderer templateRenderer;

    public DocGenerationEventListener(CachedAiClient cachedAiClient,
                                       AiDocumentationService aiDocumentationService,
                                       EventPublisher eventPublisher,
                                       DocumentVersionService versionService,
                                       DocumentReviewService reviewService,
                                       DocGenerationJobService jobService,
                                       NotificationService notificationService,
                                       AiRateLimitService rateLimitService,
                                       DocumentRepository documentRepository,
                                       GovernancePolicyService governancePolicyService,
                                       CodeEntityRepository codeEntityRepository,
                                       CodeDocumentLinkRepository codeDocumentLinkRepository,
                                       AuditLogService auditLogService,
                                       TemplateSchemaResolver templateResolver,
                                       TemplateRenderer templateRenderer) {
        this.cachedAiClient = cachedAiClient;
        this.aiDocumentationService = aiDocumentationService;
        this.eventPublisher = eventPublisher;
        this.versionService = versionService;
        this.reviewService = reviewService;
        this.jobService = jobService;
        this.notificationService = notificationService;
        this.rateLimitService = rateLimitService;
        this.documentRepository = documentRepository;
        this.governancePolicyService = governancePolicyService;
        this.codeEntityRepository = codeEntityRepository;
        this.codeDocumentLinkRepository = codeDocumentLinkRepository;
        this.auditLogService = auditLogService;
        this.templateResolver = templateResolver;
        this.templateRenderer = templateRenderer;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.doc-generation-requested}",
            groupId = "${spring.kafka.consumer.group-id}-doc-gen",
            concurrency = "2"
    )
    public void onRequested(DocGenerationRequestedEvent event) {
        log.info("Received DocGenerationRequestedEvent for document {} (workspace {})",
                event.getDocumentId(), event.getWorkspaceId());

        // Translate the event payload into the AI client DTOs and
        // hit the (cached) AI client.
        List<AiDtos.SourceFile> sourceFiles = new ArrayList<>();
        for (var sf : event.getSourceFiles()) {
            sourceFiles.add(new AiDtos.SourceFile(sf.path(), sf.content()));
        }

        DocGenerationCompletedEvent.Outcome outcomeEnum;
        String errMessage = null;
        String markdown = null;
        Float confidence = null;
        List<DocGenerationCompletedEvent.Grounding> grounding = List.of();
        int promptTokens = 0;
        int completionTokens = 0;

        try {
            // We use the cached client so re-runs of the same event
            // (Kafka redelivery on a transient failure) don't
            // re-bill the LLM.
            AiDtos.GenerateResponse resp = cachedAiClient.generateCached(
                    event.getWorkspaceId(), sourceFiles, event.getTemplate(),
                    event.getTemplateSchema(), event.getFocus(), event.getLanguageHint());
            if (resp == null) {
                outcomeEnum = DocGenerationCompletedEvent.Outcome.FAILED;
                errMessage = "AI service unavailable";
            } else {
                outcomeEnum = DocGenerationCompletedEvent.Outcome.SUCCESS;
                confidence = (float) Math.max(0.0, Math.min(1.0, resp.confidence()));
                markdown = resp.markdown();
                grounding = toGrounding(resp.sources());
                promptTokens = resp.promptTokens();
                completionTokens = resp.completionTokens();
            }
        } catch (Exception e) {
            log.error("Doc generation failed for document {}: {}",
                    event.getDocumentId(), e.getMessage(), e);
            outcomeEnum = DocGenerationCompletedEvent.Outcome.FAILED;
            errMessage = e.getMessage();
        }

        // ---- Persist the result as a new document version ----------------
        UUID versionId = null;
        if (outcomeEnum == DocGenerationCompletedEvent.Outcome.SUCCESS && markdown != null) {
            versionId = persistVersion(event, markdown, confidence, grounding);
        } else {
            jobService.fail(event.getCorrelationId(),
                    errMessage == null ? "AI service unavailable" : errMessage);
            notifyFailure(event, errMessage);
        }

        if (versionId != null) {
            jobService.succeed(event.getCorrelationId(), versionId, promptTokens, completionTokens);
            recordUsage(event.getWorkspaceId(), promptTokens, completionTokens);
            notifyAutoUpdate(event);
        }

        // Publish the completion event for any other consumer (UI push,
        // analytics, downstream integrations).
        DocGenerationCompletedEvent result = new DocGenerationCompletedEvent(
                UUID.randomUUID(),
                event.getWorkspaceId(),
                event.getCorrelationId(),
                OffsetDateTime.now(),
                event.getDocumentId(),
                versionId,
                event.getRequestedByUserId(),
                outcomeEnum,
                confidence == null ? 0f : confidence,
                markdown,
                grounding,
                errMessage,
                promptTokens,
                completionTokens);

        eventPublisher.publishDocGenerationCompleted(result);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /**
     * Write the generated markdown as a new PENDING version and put it
     * into the Staff review queue.
     *
     * @return the new version id, or {@code null} when the write failed
     */
    private UUID persistVersion(DocGenerationRequestedEvent event,
                                String markdown,
                                Float confidence,
                                List<DocGenerationCompletedEvent.Grounding> grounding) {
        try {
            // Idempotency guard: if the job already completed, this is a
            // Kafka redelivery. Creating a second version would leave the
            // document with two identical PENDING versions and confuse
            // the review queue.
            var existing = jobService.findByCorrelationId(event.getCorrelationId()).orElse(null);
            if (existing != null && existing.getStatus() != DocGenerationJob.Status.RUNNING) {
                log.info("Generation job {} already {} — skipping duplicate version creation",
                        event.getCorrelationId(), existing.getStatus());
                return existing.getDocumentVersionId();
            }

            // The template's output format decides what shape the
            // generated body is stored in. This is where the setting
            // stops being dead metadata: the template that applies to
            // the document says whether the AI output should stay
            // markdown or be converted (currently to HTML).
            OutputFormat outputFormat = resolveOutputFormat(event.getDocumentId());
            String renderedBody = templateRenderer.render(markdown, outputFormat);

            DocumentVersion version = versionService.createAiVersionInternal(
                    event.getRequestedByUserId(),
                    event.getDocumentId(),
                    markdown,
                    buildChangeSummary(event),
                    event.getSourceCommitSha(),
                    event.getSourcePrId(),
                    confidence,
                    groundingJson(grounding),
                    renderedBody,
                    templateRenderer.effectiveFormat(outputFormat));

            // The doc type drives the platform's per-type workflow policy.
            String documentDocType = documentRepository.findById(event.getDocumentId())
                    .map(d -> d.getDocType())
                    .orElse(null);

            // Governance gate 2 & 3: doc-type workflow and confidence
            // threshold decide whether this version is published
            // automatically or left as a draft.
            //
            //   - AUTO_APPLY policy + confidence at or above the
            //     workspace threshold → publish straight away.
            //   - anything else → stay a DRAFT. The version does NOT
            //     enter the review queue on its own; the author decides
            //     when to submit it. Pushing every generated version
            //     into the queue automatically meant reviewers were
            //     flooded with drafts nobody had looked at yet.
            //
            // A low-confidence result is never auto-applied, even when
            // the doc type is configured for auto-apply: the whole
            // point of the threshold is that a human reads the shaky
            // output.
            boolean autoApply = governancePolicyService.isAutoApply(
                    event.getWorkspaceId(), documentDocType);
            boolean confident = governancePolicyService.meetsConfidenceThreshold(
                    event.getWorkspaceId(), confidence);

            if (autoApply && confident) {
                versionService.publishAiVersionInternal(
                        event.getDocumentId(), version.getId(), event.getRequestedByUserId());
                log.info("AI version auto-applied (docType={}, confidence={}, threshold={}): "
                                + "document={} version={}",
                        documentDocType, confidence,
                        governancePolicyService.confidenceThreshold(event.getWorkspaceId()),
                        event.getDocumentId(), version.getVersionNumber());
            } else {
                // Leave the version as a draft. The author submits it
                // from the document page when they are happy with it.
                if (!confident) {
                    log.info("AI version kept as draft (confidence {} below threshold {})",
                            confidence,
                            governancePolicyService.confidenceThreshold(event.getWorkspaceId()));
                } else {
                    log.info("AI version kept as draft (docType={} requires review): "
                                    + "document={} version={}",
                            documentDocType, event.getDocumentId(), version.getVersionNumber());
                }
            }

            log.info("AI version persisted: document={} version={}",
                    event.getDocumentId(), version.getVersionNumber());

            // The version service already recorded the creation of the
            // version (it is the single place where a version comes into
            // existence, so the entry is written for every path that
            // creates one). Recording it again here would put two
            // entries in the trail for the same event. What the listener
            // adds is the *auto-update* context: this version was
            // produced by the webhook-driven pipeline rather than by a
            // person asking for it.
            recordAutoUpdateAudit(event, version, confidence, grounding);

            linkGroundingEntities(event.getDocumentId(), grounding);
            return version.getId();
        } catch (Exception e) {
            log.error("Failed to persist AI version for document {}: {}",
                    event.getDocumentId(), e.getMessage(), e);
            jobService.fail(event.getCorrelationId(), "Failed to persist version: " + e.getMessage());
            return null;
        }
    }

    /**
     * Persist document ↔ code-entity links for the entities the AI grounded
     * the document on.
     *
     * <p>Without this step the {@code code_document_links} table stays empty,
     * which breaks two downstream features: coverage-gap detection (every
     * entity looks undocumented) and the self-update loop (the webhook
     * handler resolves "which documents cover this file?" through this
     * table and finds nothing, so no document is ever auto-updated).
     *
     * <p>Matching is by file path first and by simple entity name as a
     * fallback, because the AI reports the qualified name it saw.
     */
    private void linkGroundingEntities(UUID documentId,
                                       List<DocGenerationCompletedEvent.Grounding> grounding) {
        if (grounding == null || grounding.isEmpty()) {
            return;
        }
        try {
            List<String> paths = grounding.stream()
                    .map(DocGenerationCompletedEvent.Grounding::file)
                    .filter(f -> f != null && !f.isBlank())
                    .distinct()
                    .toList();
            Map<String, List<CodeEntity>> byPath = new HashMap<>();
            if (!paths.isEmpty()) {
                for (CodeEntity e : codeEntityRepository.findByFilePathIn(paths)) {
                    byPath.computeIfAbsent(e.getFilePath(), k -> new ArrayList<>()).add(e);
                }
            }

            int linked = 0;
            for (DocGenerationCompletedEvent.Grounding g : grounding) {
                List<CodeEntity> candidates = byPath.get(g.file());
                if (candidates == null || candidates.isEmpty()) {
                    candidates = resolveBySimpleName(g.qualifiedName());
                }
                for (CodeEntity entity : candidates) {
                    if (codeDocumentLinkRepository
                            .findByDocumentIdAndCodeEntityId(documentId, entity.getId())
                            .isPresent()) {
                        continue;
                    }
                    codeDocumentLinkRepository.save(new CodeDocumentLink(
                            documentId, entity.getId(), "AI_GROUNDED", null));
                    linked++;
                }
            }
            if (linked > 0) {
                log.info("Linked {} grounding source(s) to document {}", linked, documentId);
            }
        } catch (Exception e) {
            // Linking is best-effort: a failure here must not fail the
            // generation itself, otherwise the document would be lost.
            log.warn("Failed to link grounding entities for document {}: {}",
                    documentId, e.getMessage());
        }
    }

    /**
     * Fallback matcher for grounding that names an entity without a known
     * file path. Matches on the trailing simple name of the qualified name.
     */
    private List<CodeEntity> resolveBySimpleName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return List.of();
        }
        String simpleName = qualifiedName;
        int lastDot = simpleName.lastIndexOf('.');
        if (lastDot >= 0) {
            simpleName = simpleName.substring(lastDot + 1);
        }
        if (simpleName.isBlank()) {
            return List.of();
        }
        return codeEntityRepository.findAllBySimpleNameIgnoreCase(simpleName);
    }

    private String buildChangeSummary(DocGenerationRequestedEvent event) {
        if (event.getSourceCommitSha() != null && !event.getSourceCommitSha().isBlank()) {
            String sha = event.getSourceCommitSha();
            String shortSha = sha.length() > 7 ? sha.substring(0, 7) : sha;
            return "AI auto-update triggered by commit " + shortSha;
        }
        if (event.getFocus() != null && !event.getFocus().isBlank()) {
            return "AI generation — " + event.getFocus();
        }
        return "AI-generated documentation";
    }

    private void notifyFailure(DocGenerationRequestedEvent event, String errMessage) {
        if (event.getRequestedByUserId() == null) return;
        try {
            notificationService.send(
                    event.getRequestedByUserId(),
                    "doc.generation.failed",
                    "AI generation failed",
                    errMessage == null ? "The AI service did not return a result." : errMessage,
                    "/workspaces/" + event.getWorkspaceId() + "/documents/" + event.getDocumentId());
        } catch (Exception e) {
            log.warn("Failed to notify user about generation failure: {}", e.getMessage());
        }
    }

    /**
     * Tell the document owner that the AI refreshed their document.
     *
     * <p>Only runs for code-triggered runs (a commit or a pull request).
     * A run the user asked for themselves does not need a "your
     * document was updated" notification — they are already looking
     * at it.
     *
     * <p>The recipient is the document owner rather than
     * {@code requestedByUserId}: for an auto-update there is no
     * requesting user, the pipeline ran on its own.
     */
    private void notifyAutoUpdate(DocGenerationRequestedEvent event) {
        boolean codeTriggered = (event.getSourceCommitSha() != null
                && !event.getSourceCommitSha().isBlank())
                || event.getSourcePrId() != null;
        if (!codeTriggered) {
            return;
        }
        try {
            UUID ownerId = documentRepository.findById(event.getDocumentId())
                    .map(d -> d.getOwnerId())
                    .orElse(null);
            if (ownerId == null) {
                return;
            }
            String title = "Document updated automatically";
            String body = "The AI refreshed this document after a code change.";
            String link = "/workspaces/" + event.getWorkspaceId()
                    + "/documents/" + event.getDocumentId();
            notificationService.send(ownerId, "doc.auto.updated", title, body, link);
        } catch (Exception e) {
            log.warn("Failed to notify document owner about auto-update: {}", e.getMessage());
        }
    }

    private void recordUsage(UUID workspaceId, int promptTokens, int completionTokens) {
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

    private List<DocGenerationCompletedEvent.Grounding> toGrounding(List<AiDtos.GroundingSource> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        List<DocGenerationCompletedEvent.Grounding> out = new ArrayList<>(sources.size());
        for (AiDtos.GroundingSource s : sources) {
            if (s == null) continue;
            out.add(new DocGenerationCompletedEvent.Grounding(
                    s.qualifiedName(), s.kind(), s.file(), s.lines(), s.signature()));
        }
        return out;
    }

    private String groundingJson(List<DocGenerationCompletedEvent.Grounding> grounding) {
        if (grounding == null || grounding.isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(grounding);
        } catch (Exception e) {
            log.warn("Failed to serialise grounding evidence: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The output format the template that applies to the document asks
     * for.
     *
     * <p>Resolved with the same precedence the AI generation uses — the
     * template pinned to the document, else the workspace default for
     * its doc type, else the global default. A document without a
     * template is markdown, which is the canonical representation of a
     * LivingDocs document.
     */
    private OutputFormat resolveOutputFormat(UUID documentId) {
        try {
            DocTemplate template = templateResolver.resolveTemplateForDocument(
                    documentRepository.findById(documentId).orElse(null));
            if (template == null || template.getOutputFormat() == null) {
                return OutputFormat.MARKDOWN;
            }
            return template.getOutputFormat();
        } catch (Exception e) {
            // A failure to resolve the template must not lose the
            // generated document — fall back to markdown.
            log.warn("Could not resolve the output format of document {}: {}",
                    documentId, e.getMessage());
            return OutputFormat.MARKDOWN;
        }
    }

    /**
     * Record the auto-update entry of the document's audit trail.
     *
     * <p>The requirement names "auto-updated" explicitly as one of the
     * three things the trail of a document has to show. The creation of
     * the version itself is already recorded by the version service as
     * {@code document.version.ai_created}; this entry adds what makes an
     * auto-update distinguishable from a generation a person asked
     * for: it was triggered by a code change, not by a user.
     *
     * <p>Only the automatic case is recorded here — a generation a
     * person asked for is already covered by the version service, and
     * writing a second entry for it would put two lines in the trail
     * for one event.
     *
     * <p>Best-effort — a failure here must not abort the pipeline.
     */
    private void recordAutoUpdateAudit(DocGenerationRequestedEvent event,
                                       DocumentVersion version,
                                       Float confidence,
                                       List<DocGenerationCompletedEvent.Grounding> grounding) {
        // A generation with no requesting user was started by the
        // webhook pipeline; one with a user was asked for by a person.
        // Only the former is an auto-update.
        if (event.getRequestedByUserId() != null) {
            return;
        }
        try {
            String versionId = version.getId() != null ? version.getId().toString() : "";
            int versionNumber = version.getVersionNumber() != null
                    ? version.getVersionNumber() : 0;
            auditLogService.record(
                    null,
                    "SYSTEM",
                    "document.auto_updated",
                    "document_version",
                    versionId,
                    event.getWorkspaceId(),
                    event.getDocumentId(),
                    Map.of(
                            "documentId", event.getDocumentId().toString(),
                            "versionId", versionId,
                            "versionNumber", versionNumber,
                            "confidence", confidence == null ? 0.0f : confidence,
                            "sourceCommitSha", event.getSourceCommitSha() == null
                                    ? "" : event.getSourceCommitSha(),
                            "sourcePrId", event.getSourcePrId() == null
                                    ? "" : event.getSourcePrId().toString(),
                            "groundingCount", grounding == null ? 0 : grounding.size()));
        } catch (Exception e) {
            log.warn("Failed to write audit log for AI generation on document {}: {}",
                    event.getDocumentId(), e.getMessage());
        }
    }
}
