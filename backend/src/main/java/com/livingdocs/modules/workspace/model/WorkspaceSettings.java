package com.livingdocs.modules.workspace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Per-workspace governance knobs. The {@code extra} JSONB column carries
 * optional feature-specific settings (AI settings, language configuration,
 * etc.) so this table does not need a new column every time a Phase 2
 * module is added.
 */
@Entity
@Table(name = "workspace_settings")
public class WorkspaceSettings {

    @Id
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private java.util.UUID workspaceId;

    @Column(name = "drift_severity_threshold", nullable = false, length = 16)
    private String driftSeverityThreshold = "MEDIUM";

    @Column(name = "auto_update_on_pr", nullable = false)
    private boolean autoUpdateOnPr = true;

    @Column(name = "auto_update_on_commit", nullable = false)
    private boolean autoUpdateOnCommit = false;

    @Column(name = "require_manager_approval", nullable = false)
    private boolean requireManagerApproval = true;

    @Column(name = "merge_policy_critical", nullable = false, length = 16)
    private String mergePolicyCritical = "WARN";

    @Column(name = "ai_confidence_threshold", nullable = false)
    private float aiConfidenceThreshold = 0.70f;

    @Column(name = "extra", nullable = false, columnDefinition = "jsonb")
    private String extra = "{}";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected WorkspaceSettings() {
        // JPA
    }

    public WorkspaceSettings(java.util.UUID workspaceId) {
        this.workspaceId = workspaceId;
        this.updatedAt = OffsetDateTime.now();
    }

    @jakarta.persistence.PrePersist
    @jakarta.persistence.PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public java.util.UUID getWorkspaceId() { return workspaceId; }
    public String getDriftSeverityThreshold() { return driftSeverityThreshold; }
    public void setDriftSeverityThreshold(String v) { this.driftSeverityThreshold = v; }
    public boolean isAutoUpdateOnPr() { return autoUpdateOnPr; }
    public void setAutoUpdateOnPr(boolean v) { this.autoUpdateOnPr = v; }
    public boolean isAutoUpdateOnCommit() { return autoUpdateOnCommit; }
    public void setAutoUpdateOnCommit(boolean v) { this.autoUpdateOnCommit = v; }
    public boolean isRequireManagerApproval() { return requireManagerApproval; }
    public void setRequireManagerApproval(boolean v) { this.requireManagerApproval = v; }
    public String getMergePolicyCritical() { return mergePolicyCritical; }
    public void setMergePolicyCritical(String v) { this.mergePolicyCritical = v; }
    public float getAiConfidenceThreshold() { return aiConfidenceThreshold; }
    public void setAiConfidenceThreshold(float v) { this.aiConfidenceThreshold = v; }
    public String getExtra() { return extra; }
    public void setExtra(String v) { this.extra = v == null || v.isBlank() ? "{}" : v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
