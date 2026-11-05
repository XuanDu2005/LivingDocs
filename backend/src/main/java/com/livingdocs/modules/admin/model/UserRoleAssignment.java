package com.livingdocs.modules.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Many-to-many assignment of a {@link com.livingdocs.modules.user.model.User}
 * to a {@link Role}. A user may hold multiple platform roles. Soft-deletion
 * via {@code revokedAt} preserves the audit trail of every role that was
 * ever granted.
 */
@Entity
@Table(name = "user_roles")
public class UserRoleAssignment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    /**
     * When the assignment was made.
     *
     * <p>Not {@code updatable = false}: a revoked assignment that is
     * granted again is re-activated, and that re-activation is a new
     * grant, so the timestamp has to move.
     */
    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected UserRoleAssignment() {
        // JPA
    }

    public UserRoleAssignment(UUID userId, UUID roleId, UUID assignedBy) {
        this.userId = userId;
        this.roleId = roleId;
        this.assignedBy = assignedBy;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (assignedAt == null) {
            assignedAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getRoleId() { return roleId; }
    public UUID getAssignedBy() { return assignedBy; }
    public OffsetDateTime getAssignedAt() { return assignedAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = OffsetDateTime.now();
        }
    }

    /**
     * Re-activate a previously revoked assignment.
     *
     * <p>The role is granted again rather than inserted as a second row:
     * the table is a soft-delete log of assignments, so one row per
     * (user, role) pair is the invariant. {@code assignedAt} is reset to
     * the moment of the new grant, because that is when the role became
     * effective again.
     */
    public void reactivate(UUID actorId) {
        this.revokedAt = null;
        this.assignedBy = actorId;
        this.assignedAt = OffsetDateTime.now();
    }
}