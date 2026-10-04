package com.livingdocs.modules.document.model;

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
 * A documentation entity (README, API reference, ADR, etc.) belonging to a
 * workspace, optionally tied to a specific GitHub repository.
 *
 * <p>{@code headVersionId} points to the currently canonical {@link DocumentVersion}
 * (the one readers see). It is set after every successful publish and
 * cleared when a document is archived.
 */
@Entity
@Table(
        name = "documents",
        uniqueConstraints = @UniqueConstraint(name = "uq_documents_workspace_slug",
                columnNames = {"workspace_id", "slug"}),
        indexes = {
                @Index(name = "idx_documents_workspace", columnList = "workspace_id"),
                @Index(name = "idx_documents_repository", columnList = "repository_id"),
                @Index(name = "idx_documents_status", columnList = "status"),
                @Index(name = "idx_documents_doc_type", columnList = "doc_type"),
                @Index(name = "idx_documents_owner", columnList = "owner_id")
        }
)
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id")
    private UUID repositoryId;

    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "slug", nullable = false, length = 280)
    private String slug;

    @Column(name = "doc_type", nullable = false, length = 40)
    private String docType;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DocumentStatus status;

    @Column(name = "auto_update_enabled", nullable = false)
    private boolean autoUpdateEnabled;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** Set to the currently published {@link DocumentVersion} id, may be null. */
    @Column(name = "head_version_id")
    private UUID headVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    protected Document() {
        // JPA
    }

    public Document(UUID workspaceId, UUID repositoryId, UUID templateId,
                    String title, String slug, String docType, String summary,
                    boolean autoUpdateEnabled, UUID ownerId) {
        this.workspaceId = workspaceId;
        this.repositoryId = repositoryId;
        this.templateId = templateId;
        this.title = title;
        this.slug = slug;
        this.docType = docType;
        this.summary = summary;
        this.status = DocumentStatus.DRAFT;
        this.autoUpdateEnabled = autoUpdateEnabled;
        this.ownerId = ownerId;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getRepositoryId() { return repositoryId; }
    public UUID getTemplateId() { return templateId; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public String getDocType() { return docType; }
    public String getSummary() { return summary; }
    public DocumentStatus getStatus() { return status; }
    public boolean isAutoUpdateEnabled() { return autoUpdateEnabled; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getHeadVersionId() { return headVersionId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }

    public void setTitle(String title) { this.title = title; }
    public void setSummary(String summary) { this.summary = summary; }
    public void setStatus(DocumentStatus status) { this.status = status; }
    public void setAutoUpdateEnabled(boolean autoUpdateEnabled) { this.autoUpdateEnabled = autoUpdateEnabled; }
    public void setHeadVersionId(UUID headVersionId) { this.headVersionId = headVersionId; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
    public void setRepositoryId(UUID repositoryId) { this.repositoryId = repositoryId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }
}