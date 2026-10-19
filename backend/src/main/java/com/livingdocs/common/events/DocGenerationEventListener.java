package com.livingdocs.common.events;

import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.ai.service.AiDocumentationService;
import com.livingdocs.modules.ai.service.CachedAiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Consumes {@link DocGenerationRequestedEvent}s from Kafka and
 * triggers the actual AI generation. Emits a
 * {@link DocGenerationCompletedEvent} when done so notification /
 * cache-invalidation listeners can react.
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
 */
@Component
public class DocGenerationEventListener {

    private static final Logger log = LoggerFactory.getLogger(DocGenerationEventListener.class);

    private final CachedAiClient cachedAiClient;
    private final AiDocumentationService aiDocumentationService;
    private final EventPublisher eventPublisher;

    public DocGenerationEventListener(CachedAiClient cachedAiClient,
                                       AiDocumentationService aiDocumentationService,
                                       EventPublisher eventPublisher) {
        this.cachedAiClient = cachedAiClient;
        this.aiDocumentationService = aiDocumentationService;
        this.eventPublisher = eventPublisher;
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
        List<String> groundingSources = List.of();
        try {
            // We use the cached client so re-runs of the same event
            // (Kafka redelivery on a transient failure) don't
            // re-bill the LLM.
            AiDtos.GenerateResponse resp = cachedAiClient.generateCached(
                    event.getWorkspaceId(), sourceFiles, event.getTemplate());
            if (resp == null) {
                outcomeEnum = DocGenerationCompletedEvent.Outcome.FAILED;
                errMessage = "AI service unavailable";
            } else {
                outcomeEnum = DocGenerationCompletedEvent.Outcome.SUCCESS;
                confidence = (float) Math.max(0.0, Math.min(1.0, resp.confidence()));
                markdown = resp.markdown();
                groundingSources = resp.sources() == null ? List.of()
                        : resp.sources().stream()
                                .map(AiDtos.GroundingSource::file)
                                .toList();
            }
        } catch (Exception e) {
            log.error("Doc generation failed for document {}: {}",
                    event.getDocumentId(), e.getMessage(), e);
            outcomeEnum = DocGenerationCompletedEvent.Outcome.FAILED;
            errMessage = e.getMessage();
        }

        DocGenerationCompletedEvent result = new DocGenerationCompletedEvent(
                UUID.randomUUID(),
                event.getWorkspaceId(),
                event.getCorrelationId(),
                OffsetDateTime.now(),
                event.getDocumentId(),
                null,                       // documentVersionId set by persistence listener
                outcomeEnum,
                confidence == null ? 0f : confidence,
                markdown,
                groundingSources,
                errMessage);

        eventPublisher.publishDocGenerationCompleted(result);
    }
}
