package com.livingdocs.modules.integrations.model;

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
 * One inbound webhook delivery.
 *
 * <p>The full payload is not stored here; only a lightweight
 * {@code payload_summary} JSON projection that the admin UI uses to
 * render a human-readable row (e.g. {@code {"pr":42,"title":"..."}}).
 * Persisting every byte of every webhook would balloon the table; the
 * full body is logged by the application logger at INFO for forensics.
 *
 * <p>The unique index on {@code (provider, delivery_id)} makes the
 * ingest path idempotent.
 */
@Entity
@Table(name = "integration_events")
public class IntegrationEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "connection_id")
    private UUID connectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private IntegrationProvider provider;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "delivery_id", nullable = false, length = 160, updatable = false)
    private String deliveryId;

    @Column(name = "action", length = 40)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_summary", columnDefinition = "jsonb")
    private String payloadSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    public enum Status {
        PENDING, PROCESSED, FAILED, IGNORED
    }

    public IntegrationEvent() {
        // JPA
    }

    public static IntegrationEvent create(IntegrationProvider provider,
                                          UUID connectionId,
                                          String eventType,
                                          String deliveryId,
                                          String action,
                                          String payloadSummary) {
        IntegrationEvent e = new IntegrationEvent();
        e.id = UuidGenerator.newId();
        e.provider = provider;
        e.connectionId = connectionId;
        e.eventType = eventType;
        e.deliveryId = deliveryId;
        e.action = action;
        e.payloadSummary = payloadSummary;
        e.status = Status.PENDING;
        e.receivedAt = OffsetDateTime.now();
        return e;
    }

    public void markProcessed() {
        this.processedAt = OffsetDateTime.now();
        this.status = Status.PROCESSED;
        this.errorMessage = null;
    }

    public void markFailed(String error) {
        this.processedAt = OffsetDateTime.now();
        this.status = Status.FAILED;
        this.errorMessage = truncate(error);
    }

    public void markIgnored(String reason) {
        this.status = Status.IGNORED;
        this.errorMessage = truncate(reason);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    public UUID getId() { return id; }
    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }
    public IntegrationProvider getProvider() { return provider; }
    public String getEventType() { return eventType; }
    public String getDeliveryId() { return deliveryId; }
    public String getAction() { return action; }
    public String getPayloadSummary() { return payloadSummary; }
    public Status getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
}