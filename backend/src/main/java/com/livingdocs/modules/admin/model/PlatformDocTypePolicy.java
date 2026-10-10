package com.livingdocs.modules.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-documentation-type policy that decides whether generated docs of
 * that type are published automatically or queued for manager review.
 */
@Entity
@Table(
        name = "platform_doc_type_policies",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_platform_doc_type_policies",
                columnNames = "doc_type")
)
public class PlatformDocTypePolicy {

    public enum Workflow {
        /** Generated docs are published without review. */
        AUTO_APPLY,
        /** Generated docs are queued for manager review. */
        MANAGER_REVIEW
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "doc_type", nullable = false, length = 40)
    private String docType;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow", nullable = false, length = 24)
    private Workflow workflow = Workflow.MANAGER_REVIEW;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PlatformDocTypePolicy() {
        // JPA
    }

    public PlatformDocTypePolicy(String docType, Workflow workflow) {
        this.id = UUID.randomUUID();
        this.docType = docType;
        this.workflow = workflow;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getDocType() { return docType; }
    public Workflow getWorkflow() { return workflow; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setWorkflow(Workflow workflow) { this.workflow = workflow; }
}
