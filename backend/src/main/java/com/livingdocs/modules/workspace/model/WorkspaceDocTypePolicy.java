package com.livingdocs.modules.workspace.model;

import com.livingdocs.modules.admin.model.PlatformDocTypePolicy.Workflow;
import com.livingdocs.common.persistence.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A workspace's own override of the workflow that applies to one
 * documentation type.
 *
 * <p>The platform holds a default policy per doc type in
 * {@code platform_doc_type_policies}. A workspace manager may narrow or
 * widen that policy for their own workspace — for example, requiring a
 * manager approval for {@code API_REFERENCE} while {@code README} stays
 * auto-applied — without asking the platform administrator to change a
 * rule that applies to every other workspace.
 *
 * <p>The row exists only when the workspace actually overrides
 * something: a workspace with no row for a doc type inherits the platform
 * policy, and a workspace with no row at all inherits the platform
 * default.
 *
 * <p>Reusing {@link PlatformDocTypePolicy.Workflow} for the enum keeps
 * the two tiers speaking the same language: the resolution chain in
 * {@code GovernancePolicyService} compares a workspace row against a
 * platform row without any conversion.
 */
@Entity
@Table(
        name = "workspace_doc_type_policies",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_workspace_doc_type_policies",
                columnNames = {"workspace_id", "doc_type"}),
        indexes = {
                @Index(name = "idx_workspace_doc_type_policies_ws",
                        columnList = "workspace_id")
        }
)
public class WorkspaceDocTypePolicy {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "doc_type", nullable = false, length = 40)
    private String docType;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow", nullable = false, length = 24)
    private Workflow workflow = Workflow.MANAGER_REVIEW;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected WorkspaceDocTypePolicy() {
        // JPA
    }

    public WorkspaceDocTypePolicy(UUID workspaceId, String docType, Workflow workflow) {
        this.id = UuidGenerator.newId();
        this.workspaceId = workspaceId;
        this.docType = docType;
        this.workflow = workflow;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Change the workflow this workspace applies to the doc type.
     */
    public void changeWorkflow(Workflow workflow) {
        this.workflow = workflow;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getDocType() { return docType; }
    public Workflow getWorkflow() { return workflow; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
