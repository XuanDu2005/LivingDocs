package com.livingdocs.modules.template.model;

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
 * A reusable blueprint the AI must conform to when generating documentation.
 *
 * <p>The {@code body} JSON describes ordered sections, headings and
 * placeholders that map to code entities (e.g. "class name", "endpoint
 * table"). The {@code version} counter is bumped on every clone/edit so
 * templates can be rolled back.
 */
@Entity
@Table(
        name = "doc_templates",
        uniqueConstraints = @UniqueConstraint(name = "uq_doc_templates_slug_global",
                columnNames = {"slug", "version"}),
        indexes = {
                @Index(name = "idx_doc_templates_workspace", columnList = "workspace_id"),
                @Index(name = "idx_doc_templates_doc_type", columnList = "doc_type")
        }
)
public class DocTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null when this template is the system default library entry. */
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "slug", nullable = false, length = 140)
    private String slug;

    @Column(name = "description", length = 500)
    private String description;

    /** E.g. "API_REFERENCE", "README", "MODULE_GUIDE", "ADR". */
    @Column(name = "doc_type", nullable = false, length = 40)
    private String docType;

    @Column(name = "version", nullable = false)
    private Integer version;

    /** JSON: ordered sections / placeholders. See {@code TemplateBody}. */
    @Column(name = "body", nullable = false, columnDefinition = "jsonb")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "output_format", nullable = false, length = 16)
    private OutputFormat outputFormat = OutputFormat.MARKDOWN;

    @Column(name = "auto_generate_on_commit", nullable = false)
    private boolean autoGenerateOnCommit;

    @Column(name = "auto_generate_on_pr", nullable = false)
    private boolean autoGenerateOnPr;

    @Column(name = "auto_generate_on_merge", nullable = false)
    private boolean autoGenerateOnMerge;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected DocTemplate() {
        // JPA
    }

    public DocTemplate(UUID workspaceId, String name, String slug, String description,
                       String docType, Integer version, String body,
                       boolean isDefault, UUID createdBy) {
        this.workspaceId = workspaceId;
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.docType = docType;
        this.version = version;
        this.body = body;
        this.isDefault = isDefault;
        this.createdBy = createdBy;
        this.outputFormat = OutputFormat.MARKDOWN;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
        if (this.version == null) this.version = 1;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public String getDocType() { return docType; }
    public Integer getVersion() { return version; }
    public String getBody() { return body; }
    public OutputFormat getOutputFormat() { return outputFormat; }
    public void setOutputFormat(OutputFormat v) { this.outputFormat = v; }
    public boolean isAutoGenerateOnCommit() { return autoGenerateOnCommit; }
    public void setAutoGenerateOnCommit(boolean v) { this.autoGenerateOnCommit = v; }
    public boolean isAutoGenerateOnPr() { return autoGenerateOnPr; }
    public void setAutoGenerateOnPr(boolean v) { this.autoGenerateOnPr = v; }
    public boolean isAutoGenerateOnMerge() { return autoGenerateOnMerge; }
    public void setAutoGenerateOnMerge(boolean v) { this.autoGenerateOnMerge = v; }
    public boolean isDefault() { return isDefault; }
    public UUID getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setBody(String body) { this.body = body; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }
}