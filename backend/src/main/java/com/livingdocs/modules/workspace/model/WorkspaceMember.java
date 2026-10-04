package com.livingdocs.modules.workspace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Association between a user and a workspace, tagged with a role.
 *
 * <p>The unique constraint on (workspace_id, user_id) makes "a user can
 * only have one role per workspace" an invariant enforced at the database.
 */
@Entity
@Table(
        name = "workspace_members",
        uniqueConstraints = @UniqueConstraint(name = "uq_workspace_member",
                columnNames = {"workspace_id", "user_id"}),
        indexes = {
                @Index(name = "idx_workspace_members_user", columnList = "user_id"),
                @Index(name = "idx_workspace_members_workspace", columnList = "workspace_id")
        }
)
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private WorkspaceRole role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    protected WorkspaceMember() {
        // JPA
    }

    public WorkspaceMember(UUID workspaceId, UUID userId, WorkspaceRole role) {
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.role = role;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getUserId() { return userId; }
    public WorkspaceRole getRole() { return role; }
    public void setRole(WorkspaceRole role) { this.role = role; }
    public OffsetDateTime getJoinedAt() { return joinedAt; }

    @jakarta.persistence.PrePersist
    void onCreate() {
        this.joinedAt = OffsetDateTime.now();
    }

    // ---- Test helpers ----
    public void setIdForTest(UUID id) {
        try {
            var f = WorkspaceMember.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(this, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}