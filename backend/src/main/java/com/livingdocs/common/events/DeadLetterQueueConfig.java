package com.livingdocs.common.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Dead-letter handling for the LivingDocs event bus.
 *
 * <p>Without a dead-letter topic a message that keeps failing (a poison
 * message) is retried forever and blocks its partition, so every later
 * message on that partition is stuck behind it. The requirement is that
 * failed processing jobs are parked in a DLQ for later recovery.
 *
 * <h2>Retry policy</h2>
 * A message is retried three times with exponential backoff (1s, 2s, 4s).
 * After the final attempt it is published to the corresponding
 * {@code <topic>.DLT} topic together with the failure headers
 * ({@code KafkaHeaders.DLT_EXCEPTION_MESSAGE} and friends), so an operator
 * can inspect why it failed and replay it later.
 *
 * <h2>Topics</h2>
 * One DLT per source topic, declared with the same partition count so the
 * dead-lettered record keeps its partition affinity.
 */
@Configuration
public class DeadLetterQueueConfig {

    @Value("${app.kafka.topic.doc-generation-requested}")
    private String docGenerationRequestedTopic;

    @Value("${app.kafka.topic.doc-generation-completed}")
    private String docGenerationCompletedTopic;

    @Value("${app.kafka.topic.drift-detection-completed}")
    private String driftDetectionCompletedTopic;

    @Value("${app.kafka.topic.notification-send}")
    private String notificationSendTopic;

    /**
     * How many times a failing message is retried before it is dead-lettered.
     */
    public static final int MAX_RETRIES = 3;

    /**
     * Base backoff between retries. The effective delay grows
     * exponentially: 1s, 2s, 4s.
     */
    public static final long RETRY_BACKOFF_MS = 1000L;

    // -----------------------------------------------------------------
    // Dead-letter topics
    // -----------------------------------------------------------------

    @Bean
    public NewTopic docGenerationRequestedDltTopic() {
        return TopicBuilder.name(dltName(docGenerationRequestedTopic))
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic docGenerationCompletedDltTopic() {
        return TopicBuilder.name(dltName(docGenerationCompletedTopic))
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic driftDetectionCompletedDltTopic() {
        return TopicBuilder.name(dltName(driftDetectionCompletedTopic))
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationSendDltTopic() {
        return TopicBuilder.name(dltName(notificationSendTopic))
                .partitions(6)
                .replicas(1)
                .build();
    }

    // -----------------------------------------------------------------
    // Error handler
    // -----------------------------------------------------------------

    /**
     * Publishes failed records to the DLT instead of dropping them.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaOperations<Object, Object> kafkaTemplate) {
        return new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(
                        dltName(record.topic()), record.partition()));
    }

    /**
     * Retries a failing record a few times with exponential backoff, then
     * routes it to the DLT. The {@code commitRecovered} flag is left at
     * its default so the offset of a dead-lettered record is committed —
     * otherwise the consumer would keep re-reading it.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        // Spring Kafka 3.2's ExponentialBackOffWithMaxRetries takes the
        // retry count in its constructor; the interval and multiplier are
        // configured through the inherited BackOff setters. The elapsed
        // time must NOT be set explicitly — it is derived from maxRetries.
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(RETRY_BACKOFF_MS);
        backOff.setMultiplier(2.0);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setAckAfterHandle(true);
        return handler;
    }

    /**
     * Name of the dead-letter topic that mirrors the given source topic.
     */
    public static String dltName(String sourceTopic) {
        return sourceTopic + ".DLT";
    }
}
