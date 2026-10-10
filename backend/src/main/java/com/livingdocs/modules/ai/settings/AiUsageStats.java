package com.livingdocs.modules.ai.settings;

import com.livingdocs.common.persistence.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Aggregated daily AI usage stats per workspace for budget tracking.
 */
@Entity
@Table(
        name = "ai_usage_stats",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_ai_usage_stats",
                        columnNames = {"workspace_id", "date"})
        },
        indexes = {
                @Index(name = "idx_ai_usage_stats_workspace_date",
                        columnList = "workspace_id, date DESC")
        }
)
public class AiUsageStats {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens = 0L;

    @Column(name = "request_count", nullable = false)
    private int requestCount = 0;

    @Column(name = "estimated_cost", nullable = false)
    private BigDecimal estimatedCost = BigDecimal.ZERO;

    @Column(name = "last_request_at")
    private OffsetDateTime lastRequestAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public AiUsageStats() {
        // JPA
    }

    public static AiUsageStats of(UUID workspaceId, LocalDate date) {
        AiUsageStats s = new AiUsageStats();
        s.id = UuidGenerator.newId();
        s.workspaceId = workspaceId;
        s.date = date;
        s.updatedAt = OffsetDateTime.now();
        return s;
    }

    public void recordUsage(long tokens, BigDecimal cost) {
        this.totalTokens += tokens;
        this.requestCount++;
        this.estimatedCost = this.estimatedCost.add(cost);
        this.lastRequestAt = OffsetDateTime.now();
        this.updatedAt = this.lastRequestAt;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public LocalDate getDate() { return date; }
    public long getTotalTokens() { return totalTokens; }
    public int getRequestCount() { return requestCount; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public OffsetDateTime getLastRequestAt() { return lastRequestAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
