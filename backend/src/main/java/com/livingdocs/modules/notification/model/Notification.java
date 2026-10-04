package com.livingdocs.modules.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A per-user notification (drift alert, review assignment, ...).
 */
@Entity
@Table(
        name = "notifications",
        indexes = {
                @Index(name = "idx_notifications_user_unread", columnList = "user_id, read_at"),
                @Index(name = "idx_notifications_created", columnList = "created_at DESC")
        }
)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "kind", nullable = false, length = 60)
    private String kind;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "link", length = 500)
    private String link;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Notification() {
        // JPA
    }

    public Notification(UUID userId, String kind, String title, String body, String link) {
        this.userId = userId;
        this.kind = kind;
        this.title = title;
        this.body = body;
        this.link = link;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getKind() { return kind; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getLink() { return link; }
    public OffsetDateTime getReadAt() { return readAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setReadAt(OffsetDateTime readAt) { this.readAt = readAt; }
}