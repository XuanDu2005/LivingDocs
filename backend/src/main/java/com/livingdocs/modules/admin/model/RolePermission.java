package com.livingdocs.modules.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A grant of a {@link Permission} to a {@link Role}. The (role_id,
 * permission_id) pair is unique at the database level. The grant is
 * never soft-deleted; revoking a permission removes the row so the
 * audit trail stays coherent.
 */
@Entity
@Table(name = "role_permissions")
public class RolePermission {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "permission_id", nullable = false)
    private UUID permissionId;

    @Column(name = "granted_by")
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private OffsetDateTime grantedAt;

    protected RolePermission() {
        // JPA
    }

    public RolePermission(UUID roleId, UUID permissionId, UUID grantedBy) {
        this.roleId = roleId;
        this.permissionId = permissionId;
        this.grantedBy = grantedBy;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (grantedAt == null) {
            grantedAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getRoleId() { return roleId; }
    public UUID getPermissionId() { return permissionId; }
    public UUID getGrantedBy() { return grantedBy; }
    public OffsetDateTime getGrantedAt() { return grantedAt; }
}
