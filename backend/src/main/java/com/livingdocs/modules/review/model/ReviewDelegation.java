package com.livingdocs.modules.review.model;

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
 * One delegation of review or approval work to somebody else.
 *
 * <p>Requirement #12 of the MANAGER role: a Manager may hand the review
 * or approval work of their workspace to another Manager. The delegation
 * is <em>per workspace</em> and carries the tier it applies to, because
 * "review the drafts" and "approve the reviewed drafts" are two
 * different acts with two different consequences.
 *
 * <p>The row is never deleted: a revoked delegation is stamped with
 * {@code revokedAt} / {@code revokedBy} and stays in the table, so the
 * trail can answer who handed what over to whom, and when the handover
 * ended.
 *
 * <p>{@code revokedAt == null} means the delegation is in force. The
 * database enforces "at most one active delegation per (workspace,
 * delegate, tier)" with a partial unique index.
 */
@Entity
@Table(
        name = "review_delegations",
        indexes = {
                @Index(name = "idx_review_delegations_workspace",
                        columnList = "workspace_id, revoked_at"),
                @Index(name = "idx_review_delegations_to",
                        columnList = "to_user_id, revoked_at")
        }
)
public class ReviewDelegation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /** The Manager who handed the work over. */
    @Column(name = "from_user_id", nullable = false)
    private UUID fromUserId;

    /** The person who now receives the work. */
    @Column(name = "to_user_id", nullable = false)
    private UUID toUserId;

    /**
     * The tier the delegation covers: {@code STAFF} (first-tier review)
     * or {@code MANAGER} (final approval).
     */
    @Column(name = "tier", nullable = false, length = 16)
    private String tier;

    /** Why the work was handed over — "on leave", "second pair of eyes", … */
    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** When the delegation ended. {@code null} while it is in force. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Who ended the delegation. */
    @Column(name = "revoked_by")
    private UUID revokedBy;

    protected ReviewDelegation() {
        // JPA
    }

    public ReviewDelegation(UUID workspaceId, UUID fromUserId, UUID toUserId,
                            String tier, String reason) {
        this.workspaceId = workspaceId;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.tier = tier;
        this.reason = reason;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getFromUserId() { return fromUserId; }
    public UUID getToUserId() { return toUserId; }
    public String getTier() { return tier; }
    public String getReason() { return reason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public UUID getRevokedBy() { return revokedBy; }

    /** Whether the delegation is still in force. */
    public boolean isActive() {
        return revokedAt == null;
    }

    /**
     * End the delegation.
     *
     * <p>Idempotent: a delegation that is already revoked keeps its
     * original stamp, so the trail shows when the handover actually
     * ended rather than when it was last touched.
     */
    public void revoke(UUID byUserId) {
        if (this.revokedAt == null) {
            this.revokedAt = OffsetDateTime.now();
            this.revokedBy = byUserId;
        }
    }
}
