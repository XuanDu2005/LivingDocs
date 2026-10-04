package com.livingdocs.modules.github.model;

import com.livingdocs.common.persistence.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single delivery of a GitHub webhook.
 *
 * <p>The full payload is preserved as JSONB so we can replay, audit, and
 * analyse events independently of the current application version.
 * The {@code (delivery_id)} unique constraint makes ingestion
 * idempotent — re-delivering the same delivery is a no-op.
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repository_id")
    private UUID repositoryId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "delivery_id", nullable = false, length = 120, updatable = false)
    private String deliveryId;

    @Column(name = "action", length = 40)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 24)
    private Status processingStatus = Status.PENDING;

    @Column(name = "processing_error", length = 1000)
    private String processingError;

    public enum Status {
        PENDING, PROCESSED, FAILED, IGNORED
    }

    public static WebhookEvent create(String eventType, String deliveryId, String action,
                                      String payload, UUID repositoryId) {
        WebhookEvent e = new WebhookEvent();
        e.id = UuidGenerator.newId();
        e.eventType = eventType;
        e.deliveryId = deliveryId;
        e.action = action;
        e.payload = payload;
        e.repositoryId = repositoryId;
        e.receivedAt = OffsetDateTime.now();
        e.processingStatus = Status.PENDING;
        return e;
    }

    public void markProcessed() {
        this.processedAt = OffsetDateTime.now();
        this.processingStatus = Status.PROCESSED;
        this.processingError = null;
    }

    public void markFailed(String error) {
        this.processedAt = OffsetDateTime.now();
        this.processingStatus = Status.FAILED;
        this.processingError = error;
    }

    public void markIgnored(String reason) {
        this.processingStatus = Status.IGNORED;
        this.processingError = reason;
    }

    public UUID getId() { return id; }
    public UUID getRepositoryId() { return repositoryId; }
    public String getEventType() { return eventType; }
    public String getDeliveryId() { return deliveryId; }
    public String getAction() { return action; }
    public String getPayload() { return payload; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
    public Status getProcessingStatus() { return processingStatus; }
    public String getProcessingError() { return processingError; }
}