package com.livingdocs.modules.version.model;

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
import com.livingdocs.modules.template.model.OutputFormat;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable snapshot of a document at a point in time. Every body change —
 * whether produced by the AI, a Staff reviewer, or a Manager — is stored as
 * a new row here. The acting role is recorded so any modification is
 * auditable and rollback is supported.
 */
@Entity
@Table(
        name = "document_versions",
        uniqueConstraints = @UniqueConstraint(name = "uq_document_versions",
                columnNames = {"document_id", "version_number"}),
        indexes = {
                @Index(name = "idx_document_versions_doc", columnList = "document_id"),
                @Index(name = "idx_document_versions_actor_role", columnList = "actor_role"),
                @Index(name = "idx_document_versions_created_at", columnList = "created_at DESC")
        }
)
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "body_markdown", nullable = false, columnDefinition = "text")
    private String bodyMarkdown;

    /**
     * The body rendered into the format the template asked for.
     *
     * <p>{@code body_markdown} stays the canonical representation —
     * the review workflow, the template compliance check and the diff
     * view all reason about markdown headings. This column carries the
     * converted content ({@link OutputFormat#HTML} today) so a consumer
     * that needs the published shape does not have to convert it again.
     */
    @Column(name = "rendered_body", columnDefinition = "text")
    private String renderedBody;

    /**
     * The format {@link #renderedBody} is in.
     *
     * <p>Stored on the version rather than looked up from the template
     * because templates are versioned: the template may be edited
     * after the version was generated, and the version has to keep the
     * shape it was produced with.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "output_format", nullable = false, length = 16)
    private OutputFormat outputFormat = OutputFormat.MARKDOWN;

    @Column(name = "change_summary", length = 500)
    private String changeSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 16)
    private ActorRole actorRole;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    /**
     * The user who pushed this version into the review workflow.
     *
     * <p>Different from {@link #actorUserId}: the author is whoever made
     * the change, the submitter is whoever asked for it to be reviewed.
     * They are usually the same person, but a developer may hand over a
     * draft that the AI produced, and a reviewer may submit a draft on
     * somebody else's behalf.
     *
     * <p>{@code null} while the version is still a plain draft — the
     * author has not asked for a review yet.
     */
    @Column(name = "submitted_by_user_id")
    private UUID submittedByUserId;

    /** When the version was submitted for review. */
    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "source_commit_sha", length = 80)
    private String sourceCommitSha;

    @Column(name = "source_pr_id")
    private UUID sourcePrId;

    /**
     * The ticket the change belongs to, e.g. {@code PROJ-123}.
     *
     * <p>Requirement #2 of the STAFF role asks the grounding panel to
     * show the ticket next to the source file, the code entity, the
     * commit and the diff. The reference is extracted from the commit
     * message or the pull-request title of the change that triggered
     * the generation, so it is best-effort and may be {@code null}.
     */
    @Column(name = "source_ticket_ref", length = 120)
    private String sourceTicketRef;

    @Column(name = "template_id")
    private UUID templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private VersionStatus status;

    @Column(name = "confidence_score")
    private Float confidenceScore;

    /**
     * Raw JSON list of {@link com.livingdocs.modules.ai.client.AiDtos.GroundingSource}
     * records returned by the AI service when this version was generated.
     * Persisted as JSONB so it can be queried and returned verbatim from
     * the standalone grounding endpoint.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grounding_json", columnDefinition = "jsonb")
    private String groundingJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected DocumentVersion() {
        // JPA
    }

    public DocumentVersion(UUID documentId, Integer versionNumber, String bodyMarkdown,
                           String changeSummary, ActorRole actorRole, UUID actorUserId,
                           String sourceCommitSha, UUID sourcePrId, UUID templateId,
                           VersionStatus status, Float confidenceScore) {
        this.documentId = documentId;
        this.versionNumber = versionNumber;
        this.bodyMarkdown = bodyMarkdown;
        this.changeSummary = changeSummary;
        this.actorRole = actorRole;
        this.actorUserId = actorUserId;
        this.sourceCommitSha = sourceCommitSha;
        this.sourcePrId = sourcePrId;
        this.templateId = templateId;
        this.status = status;
        this.confidenceScore = confidenceScore;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public Integer getVersionNumber() { return versionNumber; }
    public String getBodyMarkdown() { return bodyMarkdown; }
    public String getRenderedBody() { return renderedBody; }
    public OutputFormat getOutputFormat() { return outputFormat; }
    public void setRenderedBody(String renderedBody) { this.renderedBody = renderedBody; }
    public void setOutputFormat(OutputFormat outputFormat) { this.outputFormat = outputFormat; }
    public String getChangeSummary() { return changeSummary; }
    public ActorRole getActorRole() { return actorRole; }
    public UUID getActorUserId() { return actorUserId; }
    public UUID getSubmittedByUserId() { return submittedByUserId; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedByUserId(UUID submittedByUserId) { this.submittedByUserId = submittedByUserId; }
    public void setSubmittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; }
    public String getSourceCommitSha() { return sourceCommitSha; }
    public UUID getSourcePrId() { return sourcePrId; }
    public String getSourceTicketRef() { return sourceTicketRef; }
    public void setSourceTicketRef(String ref) { this.sourceTicketRef = ref; }
    public UUID getTemplateId() { return templateId; }
    public VersionStatus getStatus() { return status; }
    public Float getConfidenceScore() { return confidenceScore; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setStatus(VersionStatus status) { this.status = status; }
    public void setConfidenceScore(Float score) { this.confidenceScore = score; }
    public String getGroundingJson() { return groundingJson; }
    public void setGroundingJson(String groundingJson) { this.groundingJson = groundingJson; }
}