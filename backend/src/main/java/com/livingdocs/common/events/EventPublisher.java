package com.livingdocs.common.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Thin wrapper around {@link KafkaTemplate} that hides the topic
 * name lookup and the {@code send(...)} boilerplate. Every call is
 * fire-and-forget: the underlying template returns a
 * {@link CompletableFuture} that the caller can chain onto if it
 * needs a completion signal, but the standard path simply logs
 * success/failure and returns.
 *
 * <h2>Failure handling</h2>
 * If Kafka is down, the {@code send} future completes exceptionally
 * and we log the error. The caller is expected to handle this
 * gracefully (e.g. by writing the event to the
 * {@code doc_update_requests} table so it can be retried) — the
 * event bus is best-effort, not a transactional system.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String docGenerationRequestedTopic;
    private final String docGenerationCompletedTopic;
    private final String driftDetectionCompletedTopic;
    private final String notificationSendTopic;

    public EventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                           @Value("${app.kafka.topic.doc-generation-requested}") String docGenReq,
                           @Value("${app.kafka.topic.doc-generation-completed}") String docGenDone,
                           @Value("${app.kafka.topic.drift-detection-completed}") String driftDone,
                           @Value("${app.kafka.topic.notification-send}") String notif) {
        this.kafkaTemplate = kafkaTemplate;
        this.docGenerationRequestedTopic = docGenReq;
        this.docGenerationCompletedTopic = docGenDone;
        this.driftDetectionCompletedTopic = driftDone;
        this.notificationSendTopic = notif;
    }

    public void publishDocGenerationRequested(DocGenerationRequestedEvent event) {
        send(docGenerationRequestedTopic, event.getWorkspaceId().toString(), event);
    }

    public void publishDocGenerationCompleted(DocGenerationCompletedEvent event) {
        send(docGenerationCompletedTopic, event.getWorkspaceId().toString(), event);
    }

    public void publishDriftDetectionCompleted(DriftDetectionCompletedEvent event) {
        send(driftDetectionCompletedTopic, event.getWorkspaceId().toString(), event);
    }

    public void publishNotification(NotificationSendEvent event) {
        send(notificationSendTopic, event.getRecipientUserId().toString(), event);
    }

    private void send(String topic, String key, Object payload) {
        kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish event to topic {}: {}", topic, ex.getMessage(), ex);
            } else {
                log.debug("Published event to topic {} partition {} offset {}",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
