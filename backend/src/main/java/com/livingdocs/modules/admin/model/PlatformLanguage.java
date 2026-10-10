package com.livingdocs.modules.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Platform-wide supported programming language declaration.
 *
 * <p>Administrators maintain a single canonical list of languages that
 * every workspace starts with. A workspace can still customise the
 * prompt or disable the language on its own page; the row here is just
 * the default that the workspace pre-populates from.
 */
@Entity
@Table(
        name = "platform_languages",
        indexes = {
                @Index(name = "idx_platform_languages_sort",
                        columnList = "sort_order, language_code")
        }
)
public class PlatformLanguage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "language_code", nullable = false, unique = true, length = 40)
    private String languageCode;

    @Column(name = "language_name", nullable = false, length = 80)
    private String languageName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "default_prompt", columnDefinition = "text")
    private String defaultPrompt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PlatformLanguage() {
        // JPA
    }

    public PlatformLanguage(String languageCode, String languageName,
                             boolean enabled, String defaultPrompt, int sortOrder) {
        this.id = UUID.randomUUID();
        this.languageCode = languageCode;
        this.languageName = languageName;
        this.enabled = enabled;
        this.defaultPrompt = defaultPrompt;
        this.sortOrder = sortOrder;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getLanguageCode() { return languageCode; }
    public String getLanguageName() { return languageName; }
    public boolean isEnabled() { return enabled; }
    public String getDefaultPrompt() { return defaultPrompt; }
    public int getSortOrder() { return sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setLanguageName(String languageName) { this.languageName = languageName; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setDefaultPrompt(String defaultPrompt) { this.defaultPrompt = defaultPrompt; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
