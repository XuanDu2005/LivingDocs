package com.livingdocs.modules.audit.model;

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
 * Append-only audit record for governance / administrative actions.
 *
 * <p>{@code payload} is a JSON-encoded string capturing the context
 * relevant to the action (which document, which version, what the new
 * state is, etc.).
 */
@Entity
@Table(
        name = "audit_logs",
        indexes = {
                @Index(name = "idx_audit_logs_actor", columnList = "actor_user_id"),
                @Index(name = "idx_audit_logs_workspace", columnList = "workspace_id"),
                @Index(name = "idx_audit_logs_action", columnList = "action"),
                @Index(name = "idx_audit_logs_created", columnList = "created_at DESC")
        }
)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_role", length = 40)
    private String actorRole;

    @Column(name = "action", nullable = false, length = 80)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 80)
    private String resourceType;

    @Column(name = "resource_id", length = 120)
    private String resourceId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AuditLog() {
        // JPA
    }

    public AuditLog(UUID actorUserId, String actorRole, String action,
                    String resourceType, String resourceId, UUID workspaceId, String payload) {
        this.actorUserId = actorUserId;
        this.actorRole = actorRole;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.workspaceId = workspaceId;
        this.payload = payload == null || payload.isBlank() ? "{}" : payload;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getActorUserId() { return actorUserId; }
    public String getActorRole() { return actorRole; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getPayload() { return payload; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}