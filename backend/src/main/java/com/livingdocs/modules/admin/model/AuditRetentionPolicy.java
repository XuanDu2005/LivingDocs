package com.livingdocs.modules.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-entity-type retention policy that controls how long rows of a given
 * audit/admin tables are kept before the configured prune strategy is
 * applied. A policy is uniquely keyed by {@code entityType} so callers
 * can upsert on that key.
 */
@Entity
@Table(name = "audit_retention_policies")
public class AuditRetentionPolicy {

    public enum PruneStrategy {
        /** Physically delete the row. */
        HARD_DELETE,
        /** Move the row to a cold archive (out of scope of Phase 1 — flag only). */
        ARCHIVE,
        /** Replace PII fields with placeholders, keep the row for compliance. */
        ANONYMIZE
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "entity_type", nullable = false, unique = true, length = 80)
    private String entityType;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "prune_strategy", nullable = false, length = 24)
    private PruneStrategy pruneStrategy = PruneStrategy.HARD_DELETE;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_pruned_at")
    private OffsetDateTime lastPrunedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected AuditRetentionPolicy() {
        // JPA
    }

    public AuditRetentionPolicy(String entityType, int retentionDays) {
        this.entityType = entityType;
        this.retentionDays = retentionDays;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getEntityType() { return entityType; }
    public String getDescription() { return description; }
    public int getRetentionDays() { return retentionDays; }
    public PruneStrategy getPruneStrategy() { return pruneStrategy; }
    public boolean isEnabled() { return enabled; }
    public OffsetDateTime getLastPrunedAt() { return lastPrunedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setDescription(String description) { this.description = description; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public void setPruneStrategy(PruneStrategy pruneStrategy) { this.pruneStrategy = pruneStrategy; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void recordPruned() { this.lastPrunedAt = OffsetDateTime.now(); }
}