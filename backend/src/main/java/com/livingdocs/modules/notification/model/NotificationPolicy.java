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
 * Per-scope enable / disable for a {@link Notification} kind.
 *
 * <p>The {@code workspaceId} column distinguishes platform-default rows
 * (NULL) from per-workspace overrides. Resolution order is:
 * <ol>
 *   <li>Exact match on {@code (workspaceId, eventKind)}.</li>
 *   <li>Platform default {@code (null, eventKind)}.</li>
 *   <li>Treat as <i>enabled</i> (legacy default).</li>
 * </ol>
 */
@Entity
@Table(
        name = "notification_policies",
        indexes = {
                @Index(name = "idx_notification_policies_workspace",
                        columnList = "workspace_id")
        }
)
public class NotificationPolicy {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "event_kind", nullable = false, length = 64)
    private String eventKind;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public NotificationPolicy() {
        // JPA
    }

    public static NotificationPolicy of(UUID workspaceId, String eventKind,
                                         boolean enabled, UUID updatedBy) {
        NotificationPolicy p = new NotificationPolicy();
        p.id = UuidGenerator.newId();
        p.workspaceId = workspaceId;
        p.eventKind = eventKind;
        p.enabled = enabled;
        p.updatedBy = updatedBy;
        p.updatedAt = OffsetDateTime.now();
        return p;
    }

    public void applyChange(boolean enabled, UUID updatedBy) {
        this.enabled = enabled;
        this.updatedBy = updatedBy;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getEventKind() { return eventKind; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public UUID getUpdatedBy() { return updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}