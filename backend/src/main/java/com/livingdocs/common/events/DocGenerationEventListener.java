package com.livingdocs.common.events;

import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.job.DocGenerationJob;
import com.livingdocs.modules.ai.job.DocGenerationJobService;
import com.livingdocs.modules.ai.service.AiDocumentationService;
import com.livingdocs.modules.ai.service.CachedAiClient;
import com.livingdocs.modules.ai.settings.AiRateLimitService;
import com.livingdocs.modules.ai.settings.GovernancePolicyService;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.notification.service.NotificationService;
import com.livingdocs.modules.review.service.DocumentReviewService;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.service.DocumentVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

    public DocGenerationEventListener(CachedAiClient cachedAiClient,
                                       AiDocumentationService aiDocumentationService,
                                       EventPublisher eventPublisher,
                                       DocumentVersionService versionService,
                                       DocumentReviewService reviewService,
                                       DocGenerationJobService jobService,
                                       NotificationService notificationService,
                                       AiRateLimitService rateLimitService,
                                       DocumentRepository documentRepository,
                                       GovernancePolicyService governancePolicyService) {
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

            DocumentVersion version = versionService.createAiVersionInternal(
                    event.getRequestedByUserId(),
                    event.getDocumentId(),
                    markdown,
                    buildChangeSummary(event),
                    event.getSourceCommitSha(),
                    event.getSourcePrId(),
                    confidence,
                    groundingJson(grounding));

            // The doc type drives the platform's per-type workflow policy.
            String documentDocType = documentRepository.findById(event.getDocumentId())
                    .map(d -> d.getDocType())
                    .orElse(null);

            // Governance gate 2 & 3: doc-type workflow and confidence
            // threshold decide whether this version is published
            // automatically or queued for a human.
            //
            //   - AUTO_APPLY policy + confidence at or above the
            //     workspace threshold → publish straight away.
            //   - anything else → enter the Staff review queue.
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
                // Put the version into the Staff review queue. When the
                // workspace has disabled manager approval this still
                // works — the governance policy decides what happens
                // afterwards.
                reviewService.markPendingForStaffReview(event.getDocumentId(), version.getId());
                if (!confident) {
                    log.info("AI version routed to review (confidence {} below threshold {})",
                            confidence,
                            governancePolicyService.confidenceThreshold(event.getWorkspaceId()));
                }
            }

            log.info("AI version persisted: document={} version={}",
                    event.getDocumentId(), version.getVersionNumber());
            return version.getId();
        } catch (Exception e) {
            log.error("Failed to persist AI version for document {}: {}",
                    event.getDocumentId(), e.getMessage(), e);
            jobService.fail(event.getCorrelationId(), "Failed to persist version: " + e.getMessage());
            return null;
        }
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
}