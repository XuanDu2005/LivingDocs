package com.livingdocs.modules.notification.model;

import com.livingdocs.common.persistence.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-workspace / per-event-kind notification channel configuration.
 *
 * <p>Controls which delivery channels (in-app, email) are active for each
 * notification event. The existing {@link NotificationPolicy} controls
 * on/off; this table controls <i>how</i> the notification is delivered.
 */
@Entity
@Table(
        name = "notification_channels",
        indexes = {
                @Index(name = "idx_notification_channels_workspace",
                        columnList = "workspace_id")
        }
)
public class NotificationChannel {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "event_kind", nullable = false, length = 64)
    private String eventKind;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled = true;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = false;

    @Column(name = "email_recipients", length = 500)
    private String emailRecipients;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public NotificationChannel() {
        // JPA
    }

    public static NotificationChannel of(UUID workspaceId, String eventKind,
                                          boolean inAppEnabled, boolean emailEnabled,
                                          String emailRecipients, UUID updatedBy) {
        NotificationChannel c = new NotificationChannel();
        c.id = UuidGenerator.newId();
        c.workspaceId = workspaceId;
        c.eventKind = eventKind;
        c.inAppEnabled = inAppEnabled;
        c.emailEnabled = emailEnabled;
        c.emailRecipients = emailRecipients;
        c.updatedBy = updatedBy;
        c.updatedAt = OffsetDateTime.now();
        return c;
    }

    public void applyChange(boolean inAppEnabled, boolean emailEnabled,
                             String emailRecipients, UUID updatedBy) {
        this.inAppEnabled = inAppEnabled;
        this.emailEnabled = emailEnabled;
        this.emailRecipients = emailRecipients;
        this.updatedBy = updatedBy;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getEventKind() { return eventKind; }
    public boolean isInAppEnabled() { return inAppEnabled; }
    public void setInAppEnabled(boolean v) { this.inAppEnabled = v; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean v) { this.emailEnabled = v; }
    public String getEmailRecipients() { return emailRecipients; }
    public void setEmailRecipients(String v) { this.emailRecipients = v; }
    public UUID getUpdatedBy() { return updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
