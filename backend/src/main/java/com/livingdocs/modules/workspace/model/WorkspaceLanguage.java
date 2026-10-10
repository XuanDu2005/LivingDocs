package com.livingdocs.modules.workspace.model;

import com.livingdocs.common.persistence.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-workspace supported programming language for AI analysis.
 * Allows admins to enable/disable languages and override AI prompts.
 */
@Entity
@Table(
        name = "workspace_languages",
        indexes = {
                @Index(name = "idx_workspace_languages_ws",
                        columnList = "workspace_id")
        }
)
public class WorkspaceLanguage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "language_code", nullable = false, length = 40)
    private String languageCode;

    @Column(name = "language_name", nullable = false, length = 80)
    private String languageName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "custom_prompt", columnDefinition = "text")
    private String customPrompt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public WorkspaceLanguage() {
        // JPA
    }

    public static WorkspaceLanguage of(UUID workspaceId, String code, String name,
                                         boolean enabled, String customPrompt) {
        WorkspaceLanguage l = new WorkspaceLanguage();
        l.id = UuidGenerator.newId();
        l.workspaceId = workspaceId;
        l.languageCode = code;
        l.languageName = name;
        l.enabled = enabled;
        l.customPrompt = customPrompt;
        l.createdAt = OffsetDateTime.now();
        l.updatedAt = l.createdAt;
        return l;
    }

    public void applyChange(boolean enabled, String customPrompt) {
        this.enabled = enabled;
        this.customPrompt = customPrompt;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getLanguageCode() { return languageCode; }
    public String getLanguageName() { return languageName; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getCustomPrompt() { return customPrompt; }
    public void setCustomPrompt(String v) { this.customPrompt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
