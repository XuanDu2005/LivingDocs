package com.livingdocs.common.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic provisioning for the LivingDocs event bus.
 *
 * <p>Each topic declared here is created automatically on startup
 * (the Kafka brokers in {@code docker-compose.yml} have
 * {@code auto.create.topics.enable=true}, but we declare them
 * explicitly so partitions and retention are predictable across
 * environments).
 *
 * <h2>Topic naming</h2>
 * Names are dot-separated ({@code doc.generation.requested} etc.)
 * to match the existing {@code app.kafka.topic.*} properties — that
 * way an operator can rename a topic for a canary deployment
 * without code changes.
 *
 * <h2>Partitions</h2>
 * Most topics use 3 partitions, which lets us run up to 3
 * consumer instances in parallel before rebalancing overhead kicks
 * in. Notifications use 6 partitions because they fan out to
 * many recipients and benefit from finer-grained parallelism.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${app.kafka.topic.doc-generation-requested}")
    private String docGenerationRequestedTopic;

    @Value("${app.kafka.topic.doc-generation-completed}")
    private String docGenerationCompletedTopic;

    @Value("${app.kafka.topic.drift-detection-completed}")
    private String driftDetectionCompletedTopic;

    @Value("${app.kafka.topic.notification-send}")
    private String notificationSendTopic;

    @Bean
    public NewTopic docGenerationRequestedTopic() {
        return TopicBuilder.name(docGenerationRequestedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic docGenerationCompletedTopic() {
        return TopicBuilder.name(docGenerationCompletedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic driftDetectionCompletedTopic() {
        return TopicBuilder.name(driftDetectionCompletedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationSendTopic() {
        return TopicBuilder.name(notificationSendTopic)
                .partitions(6)
                .replicas(1)
                .build();
    }
}
