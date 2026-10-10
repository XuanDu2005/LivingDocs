package com.livingdocs.modules.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Singleton row that holds the platform-wide defaults for documentation
 * generation governance. New workspaces copy these into
 * {@code workspace_settings} on creation; existing workspaces keep
 * their own per-workspace values.
 */
@Entity
@Table(name = "platform_settings")
public class PlatformSettings {

    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private int id = SINGLETON_ID;

    @Column(name = "default_auto_update_on_commit", nullable = false)
    private boolean defaultAutoUpdateOnCommit = false;

    @Column(name = "default_auto_update_on_pr", nullable = false)
    private boolean defaultAutoUpdateOnPr = true;

    @Column(name = "default_auto_update_on_merge", nullable = false)
    private boolean defaultAutoUpdateOnMerge = false;

    @Column(name = "default_drift_severity_threshold", nullable = false, length = 16)
    private String defaultDriftSeverityThreshold = "MEDIUM";

    @Column(name = "default_require_manager_approval", nullable = false)
    private boolean defaultRequireManagerApproval = true;

    @Column(name = "default_merge_policy_critical", nullable = false, length = 16)
    private String defaultMergePolicyCritical = "WARN";

    @Column(name = "default_ai_confidence_threshold", nullable = false)
    private float defaultAiConfidenceThreshold = 0.70f;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public PlatformSettings() {
        // JPA
    }

    @PrePersist
    void onCreate() {
        if (id == 0) id = SINGLETON_ID;
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public int getId() { return id; }
    public boolean isDefaultAutoUpdateOnCommit() { return defaultAutoUpdateOnCommit; }
    public boolean isDefaultAutoUpdateOnPr() { return defaultAutoUpdateOnPr; }
    public boolean isDefaultAutoUpdateOnMerge() { return defaultAutoUpdateOnMerge; }
    public String getDefaultDriftSeverityThreshold() { return defaultDriftSeverityThreshold; }
    public boolean isDefaultRequireManagerApproval() { return defaultRequireManagerApproval; }
    public String getDefaultMergePolicyCritical() { return defaultMergePolicyCritical; }
    public float getDefaultAiConfidenceThreshold() { return defaultAiConfidenceThreshold; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setDefaultAutoUpdateOnCommit(boolean v) { this.defaultAutoUpdateOnCommit = v; }
    public void setDefaultAutoUpdateOnPr(boolean v) { this.defaultAutoUpdateOnPr = v; }
    public void setDefaultAutoUpdateOnMerge(boolean v) { this.defaultAutoUpdateOnMerge = v; }
    public void setDefaultDriftSeverityThreshold(String v) { this.defaultDriftSeverityThreshold = v; }
    public void setDefaultRequireManagerApproval(boolean v) { this.defaultRequireManagerApproval = v; }
    public void setDefaultMergePolicyCritical(String v) { this.defaultMergePolicyCritical = v; }
    public void setDefaultAiConfidenceThreshold(float v) { this.defaultAiConfidenceThreshold = v; }
}
